package bayern.kickner.zoraxy.dyndns

import java.net.Inet4Address
import java.net.InetAddress

/**
 * A validated IP address or prefix with its host bits cleared, written as `address/prefix`.
 *
 * Reported addresses ([parse]) are public only: IPv4 /32, IPv6 /48 to /128. Fixed entries from config.json
 * ([parseStatic]) may be private. Parsing uses [InetAddress.ofLiteral], which never triggers a DNS lookup.
 *
 * @property prefix The prefix length in bits.
 */
class Cidr private constructor(private val bytes: ByteArray, val prefix: Int) {

    /** Whether this is an IPv4 address. IPv4-mapped IPv6 (`::ffff:1.2.3.4`) counts as IPv4. */
    val isV4: Boolean get() = bytes.size == 4

    private val address = format(bytes)

    /** Canonical `address/prefix`, computed once, used for [toString], [equals] and [hashCode]. */
    private val text = "$address/$prefix"

    /** `address/prefix`, the key of the entry in a Zoraxy whitelist. */
    override fun toString(): String = text

    /** Plain address for single hosts (dyndns2 answers `good 1.2.3.4`), CIDR for prefixes. */
    val display: String get() = if (prefix == bytes.size * 8) address else text

    /** Equal if address and prefix are the same. */
    override fun equals(other: Any?) = other is Cidr && other.text == text

    /** Consistent with [equals]. */
    override fun hashCode() = text.hashCode()

    companion object {
        /** Smallest IPv6 prefix a client may report, a /48 is what one site gets at most. */
        const val MIN_V6_PREFIX = 48

        private val dottedQuad = """\d{1,3}(\.\d{1,3}){3}""".toRegex()

        /** Non-public IPv4 ranges as (network bytes, prefix), parsed once. */
        private val blockedV4 = listOf(
            "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16",
            "172.16.0.0/12", "192.0.0.0/24", "192.0.2.0/24", "192.168.0.0/16", "198.18.0.0/15",
            "198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/4", "240.0.0.0/4",
        ).map(::network)

        /** The documentation range 2001:db8::/32, not routable. */
        private val documentationV6 = network("2001:db8::/32")

        private fun network(cidr: String) = InetAddress.ofLiteral(cidr.substringBefore('/')).address to cidr.substringAfter('/').toInt()

        /**
         * Address reported by a DynDNS client, e.g. `1.2.3.4`, `2003:db8::1` or `2003:d5:df4a:a500::/56`.
         *
         * @return the address, or a failure with a readable reason if it is no public IPv4 host or global IPv6
         *   host or prefix (/48 to /128).
         */
        fun parse(raw: String): Result<Cidr> = parse(raw, static = false)

        /**
         * Fixed entry from config.json (emergency IP, VPN, office): private ranges allowed, IPv4 /8 to /32, IPv6
         * /16 to /128. Nothing wider, that would practically disable the whitelist.
         *
         * @return the entry, or a failure with a readable reason.
         */
        fun parseStatic(raw: String): Result<Cidr> = parse(raw, static = true)

        private fun parse(raw: String, static: Boolean): Result<Cidr> = runCatching {
            val text = raw.trim()
            require(text.isNotEmpty()) { "empty address" }
            require('%' !in text) { "scoped addresses are not allowed" }
            val addrPart = text.substringBefore('/')
            val prefixPart = text.substringAfter('/', missingDelimiterValue = "")

            // ofLiteral also accepts legacy forms like "1.2.3" (= 1.2.0.3), only plain dotted quads are allowed
            require(':' in addrPart || dottedQuad.matches(addrPart)) { "not an IP literal: $addrPart" }
            val addr = runCatching { InetAddress.ofLiteral(addrPart) }.getOrNull()
                ?: throw IllegalArgumentException("not an IP literal: $addrPart")
            val v4 = addr is Inet4Address
            val prefix = if (prefixPart.isEmpty()) addr.address.size * 8
            else prefixPart.toIntOrNull() ?: throw IllegalArgumentException("invalid prefix length: $prefixPart")

            when {
                static && v4 -> require(prefix in 8..32) { "static IPv4 prefix must be /8 to /32" }
                static -> require(prefix in 16..128) { "static IPv6 prefix must be /16 to /128" }
                v4 -> require(prefix == 32) { "IPv4 must be a single address (/32)" }
                else -> require(prefix in MIN_V6_PREFIX..128) { "IPv6 prefix must be /$MIN_V6_PREFIX to /128" }
            }
            if (static.not()) require(isPublic(addr)) { "not a publicly routable address: $addrPart" }
            Cidr(mask(addr.address, prefix), prefix)
        }

        private fun mask(bytes: ByteArray, prefix: Int): ByteArray {
            val out = bytes.copyOf()
            for (i in out.indices) {
                val keep = (prefix - i * 8).coerceIn(0, 8)
                out[i] = (out[i].toInt() and (0xFF shl (8 - keep)) and 0xFF).toByte()
            }
            return out
        }

        private fun isPublic(addr: InetAddress): Boolean {
            val b = addr.address
            if (addr is Inet4Address) return blockedV4.none { inRange(b, it) }
            // Only global unicast 2000::/3, minus the documentation range
            return (b[0].toInt() and 0xE0) == 0x20 && inRange(b, documentationV6).not()
        }

        private fun inRange(b: ByteArray, network: Pair<ByteArray, Int>): Boolean =
            mask(b, network.second).contentEquals(network.first)

        /**
         * IPv4 dotted, IPv6 in the compressed form of RFC 5952 (the longest run of zero groups becomes `::`).
         * The JDK writes IPv6 uncompressed. One fixed spelling matters, the planner compares whitelist keys as text.
         */
        private fun format(b: ByteArray): String {
            if (b.size == 4) return b.joinToString(".") { (it.toInt() and 0xFF).toString() }
            val groups = IntArray(8) { ((b[it * 2].toInt() and 0xFF) shl 8) or (b[it * 2 + 1].toInt() and 0xFF) }
            var bestStart = -1
            var bestLen = 0
            var i = 0
            while (i < 8) {
                if (groups[i] != 0) {
                    i++
                    continue
                }
                var j = i
                while (j < 8 && groups[j] == 0) j++
                if (j - i > bestLen && j - i >= 2) {
                    bestStart = i
                    bestLen = j - i
                }
                i = j
            }
            val hex = groups.map { Integer.toHexString(it) }
            if (bestStart < 0) return hex.joinToString(":")
            return hex.subList(0, bestStart).joinToString(":") + "::" + hex.subList(bestStart + bestLen, 8).joinToString(":")
        }
    }
}

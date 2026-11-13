package bayern.kickner.zoraxy.dyndns

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CidrTest {

    private fun parsed(raw: String) = Cidr.parse(raw).getOrThrow().toString()
    private fun rejected(raw: String) = assertTrue(Cidr.parse(raw).isFailure, raw)
    private fun rejectedStatic(raw: String) = assertTrue(Cidr.parseStatic(raw).isFailure, raw)

    @Test
    fun publicIpv4IsAHost() = assertEquals("87.176.150.207/32", parsed("87.176.150.207"))

    @Test
    fun ipv4WithExplicitHostPrefix() = assertEquals("87.176.150.207/32", parsed("87.176.150.207/32"))

    @Test
    fun ipv4RangeIsRejected() = rejected("87.176.150.0/24")

    @Test
    fun nonPublicIpv4IsRejected() =
        listOf("192.168.178.1", "10.1.2.3", "100.64.1.1", "127.0.0.1", "192.0.2.1", "169.254.1.1", "224.0.0.1").forEach(::rejected)

    @Test
    fun legacyIpv4FormsAreRejected() = listOf("1.2.3", "16909060").forEach(::rejected)

    @Test
    fun hostnamesAndGarbageAreRejected() = listOf("example.com", "", "1.2.3.4/x", "fe80::1%eth0").forEach(::rejected)

    @Test
    fun ipv6PrefixHostBitsAreMasked() = assertEquals("2003:d5:df4a:a500::/56", parsed("2003:d5:df4a:a5ff:1234::/56"))

    @Test
    fun ipv6HostIsCompressed() =
        assertEquals("2003:d5:dfff:ceb:de39:6fff:fe60:4e1d/128", parsed("2003:00d5:dfff:0ceb:de39:6fff:fe60:4e1d"))

    @Test
    fun longestZeroRunIsCompressed() = assertEquals("2003:0:0:1::/64", parsed("2003:0:0:1:0:0:0:0/64"))

    @Test
    fun nonGlobalOrTooWideIpv6IsRejected() = listOf("2003::/16", "fe80::1", "fd00::1", "2001:db8::1", "::1").forEach(::rejected)

    @Test
    fun ipv4MappedIpv6IsTreatedAsIpv4() {
        val c = Cidr.parse("::ffff:87.176.150.207").getOrThrow()
        assertTrue(c.isV4)
        assertEquals("87.176.150.207", c.display)
    }

    @Test
    fun staticAllowsPrivate() {
        assertEquals("10.8.0.0/24", Cidr.parseStatic("10.8.0.0/24").getOrThrow().toString())
        assertEquals("192.168.178.20/32", Cidr.parseStatic("192.168.178.20").getOrThrow().toString())
        assertEquals("fd00::/64", Cidr.parseStatic("fd00::1/64").getOrThrow().toString())
    }

    @Test
    fun staticRejectsHugeRange() = listOf("0.0.0.0/0", "10.0.0.0/7", "2000::/8", "::/0").forEach(::rejectedStatic)

    @Test
    fun staticRejectsHostname() = listOf("vpn.example.com", "1.2.3", "").forEach(::rejectedStatic)

    @Test
    fun displayOmitsHostPrefix() {
        assertEquals("87.176.150.207", Cidr.parse("87.176.150.207").getOrThrow().display)
        assertEquals("2003:d5:df4a:a500::/56", Cidr.parse("2003:d5:df4a:a500::/56").getOrThrow().display)
    }
}

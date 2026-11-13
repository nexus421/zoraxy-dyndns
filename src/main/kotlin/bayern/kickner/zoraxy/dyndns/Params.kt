package bayern.kickner.zoraxy.dyndns

/** `myip` may hold IPv4 and IPv6, comma separated (dyndns2). */
private val anyFamily = listOf("myip")

/** Aliases for IPv4 used by common DynDNS clients and services. */
private val v4Only = listOf("myipv4", "ip")

/** Aliases for IPv6 used by common DynDNS clients and services. */
private val v6Only = listOf("myipv6", "myip6", "ipv6")

/**
 * Collects the reported addresses from all known query parameters.
 *
 * @return IPv4 first, then IPv6. `null` if no known parameter has a value, the caller then uses the source address.
 *   A failure for an invalid address, the wrong family for an alias or more than one address of a family.
 */
fun addressesFromQuery(query: Map<String, String>): Result<List<Cidr>?> = runCatching {
    fun values(names: List<String>) = names.flatMap { name ->
        query[name].orEmpty().split(',').map(String::trim).filter(String::isNotEmpty).map { name to Cidr.parse(it).getOrThrow() }
    }

    val found = values(anyFamily) + values(v4Only) + values(v6Only)
    if (found.isEmpty()) return@runCatching null
    found.forEach { (name, c) ->
        require(name !in v4Only || c.isV4) { "$name expects an IPv4 address" }
        require(name !in v6Only || c.isV4.not()) { "$name expects an IPv6 address" }
    }
    val addresses = found.map { it.second }
    require(addresses.count { it.isV4 } <= 1 && addresses.count { it.isV4.not() } <= 1) {
        "expected at most one IPv4 and one IPv6 address"
    }
    addresses.sortedBy { if (it.isV4) 0 else 1 }
}

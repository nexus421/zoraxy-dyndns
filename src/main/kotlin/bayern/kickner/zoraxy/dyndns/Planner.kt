package bayern.kickner.zoraxy.dyndns

/**
 * One access rule as Zoraxy reports it (`/plugin/api/access/list`).
 *
 * @property name The name shown in Zoraxy, the ID if the rule has none.
 * @property ips Whitelisted IPs and ranges, entry to comment.
 * @property countries Whitelisted country codes, code to comment.
 */
data class RuleSnapshot(
    val id: String,
    val name: String,
    val whitelistEnabled: Boolean,
    val allowLocal: Boolean,
    val ips: Map<String, String>,
    val countries: Map<String, String>,
)

/**
 * What has to change in one rule.
 *
 * @property add Entries to add or re-tag, entry to comment.
 * @property remove Owned entries to remove.
 * @property setEnabled New state of the whitelist switch, `null` leaves it alone.
 * @property warnings Problems to show and log.
 */
data class RulePlan(val add: Map<String, String>, val remove: Set<String>, val setEnabled: Boolean?, val warnings: List<String>) {
    /** Whether applying the plan changes anything in Zoraxy. */
    val changesSomething: Boolean get() = add.isNotEmpty() || remove.isNotEmpty() || setEnabled != null
}

private fun isOwned(comment: String) = comment.startsWith(OWNED_PREFIX)

/**
 * Desired plugin-owned entries per configured rule: static entries first, then the last report of every client of
 * that rule. The first owner of an address wins.
 */
fun desiredEntries(config: Config, state: State): Map<String, Map<String, String>> =
    config.ruleIds().associateWith { ruleId ->
        val entries = LinkedHashMap<String, String>()
        config.ruleConfig(ruleId).static.forEach { entries.putIfAbsent(it.toString(), Config.STATIC_TAG) }
        config.clients.filter { ruleId in it.rules }.forEach { client ->
            state.clients[client.name]?.addresses?.forEach { entries.putIfAbsent(it.toString(), client.tag) }
        }
        entries
    }

/**
 * Plans one rule. Pure, so every case is unit tested.
 *
 * Emptiness is judged on the result: no IP and no country entries, manual ones included.
 *
 * @param desired Owned entries the rule should have, see [desiredEntries].
 * @param configured `false` for a rule that was removed from config.json but is still remembered in state.json:
 *   only the plugin's own entries are removed, the switch stays as it is.
 */
fun planRule(desired: Map<String, String>, snap: RuleSnapshot, mode: Mode, configured: Boolean): RulePlan {
    val want = if (configured) desired else emptyMap()
    val warnings = mutableListOf<String>()

    val add = LinkedHashMap<String, String>()
    for ((ip, tag) in want) {
        val existing = snap.ips[ip]
        when {
            existing == null -> add[ip] = tag
            existing == tag -> Unit
            isOwned(existing) -> add[ip] = tag // re-tag (e.g. renamed client), same key, no gap
            else -> warnings += "$ip is already whitelisted by another entry ('$existing'), leaving it alone"
        }
    }
    val remove = snap.ips.filter { (ip, comment) -> isOwned(comment) && ip !in want }.keys

    if (configured.not()) return RulePlan(add, remove, null, emptyList())

    val empty = (snap.ips.keys - remove + add.keys).isEmpty() && snap.countries.isEmpty()
    val setEnabled = when (mode) {
        Mode.ENFORCE -> if (snap.whitelistEnabled.not()) true else null
        Mode.OPEN_WHEN_EMPTY -> when {
            empty && snap.whitelistEnabled -> false
            empty.not() && snap.whitelistEnabled.not() -> true
            else -> null
        }
        Mode.WARN -> null
    }
    val enabledAfter = setEnabled ?: snap.whitelistEnabled

    if (mode == Mode.WARN && enabledAfter.not()) warnings += "whitelist is OFF (mode warn), everyone can access"
    if (mode == Mode.OPEN_WHEN_EMPTY && enabledAfter.not()) warnings += "whitelist is OFF (open_when_empty, no entries), everyone can access"
    if (snap.allowLocal) warnings += "'allow local and loopback' is on, private networks bypass the whitelist"
    if (snap.countries.isNotEmpty()) warnings += "country entries (${snap.countries.keys.sorted().joinToString()}) allow whole countries"
    return RulePlan(add, remove, setEnabled, warnings)
}

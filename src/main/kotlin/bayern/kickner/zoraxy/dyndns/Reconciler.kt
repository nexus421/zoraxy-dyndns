package bayern.kickner.zoraxy.dyndns

import bayern.kickner.klogger.infoLog
import bayern.kickner.klogger.warnLog
import java.io.File

/**
 * State of one rule for the status page.
 *
 * @property enabled The whitelist switch, `null` if the rule is missing in Zoraxy.
 * @property entries Number of IP entries, manual ones included.
 */
data class RuleStatus(val mode: Mode, val enabled: Boolean?, val entries: Int, val problems: List<String>)

/**
 * The last dyndns2 answer to a client.
 *
 * @property at Unix milliseconds of the answer.
 */
data class ClientResult(val text: String, val at: Long)

/** Everything the status page shows, taken at one moment. */
data class Status(val clients: Map<String, ClientState>, val results: Map<String, ClientResult>, val rules: Map<String, RuleStatus>)

/**
 * Result of one reconcile run.
 *
 * @property changed Whether anything was changed in Zoraxy (or, after a report, the reported addresses).
 * @property failedRules Rules that are missing in Zoraxy or could not be updated.
 * @property knownRules Number of access rules Zoraxy has.
 */
data class ReconcileResult(val changed: Boolean, val failedRules: Set<String>, val knownRules: Int)

/**
 * Makes the whitelists match the desired state: config.json plus the last report per client (state.json).
 *
 * Runs at startup, after every report and every 60 seconds. Only rules from the config, or rules the plugin managed
 * before (state.json `managed_rules`), are ever touched. All public functions are synchronized, reports and the
 * periodic run never interleave.
 *
 * @param config The current config, read once at the start of every run. The UI changes it while the plugin runs.
 * @param clock Unix milliseconds, injectable for tests.
 */
class Reconciler(
    private val api: ZoraxyApi,
    private val config: () -> Config,
    private val stateFile: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var state = State.load(stateFile)
    private val ruleStatus = linkedMapOf<String, RuleStatus>()
    private val clientResults = mutableMapOf<String, ClientResult>()
    private val loggedProblems = mutableMapOf<String, List<String>>()

    /**
     * Records a report of [client], saves it and reconciles the client's rules.
     *
     * @return the result for the dyndns2 answer.
     */
    @Synchronized
    fun report(client: Client, addresses: List<Cidr>): ReconcileResult {
        val previous = state.clients[client.name]?.addresses
        state = state.copy(clients = state.clients + (client.name to ClientState(addresses, clock())))
        save()
        val result = reconcile(client.rules.toSet())
        return result.copy(changed = result.changed || previous != addresses)
    }

    /** Remembers the last dyndns2 answer to [client] for the status page. */
    @Synchronized
    fun recordResult(client: String, result: String) {
        clientResults[client] = ClientResult(result, clock())
    }

    /**
     * Brings the rules to the desired state: adds before it removes, so there is never a gap.
     *
     * @param onlyRules Restricts the run to these rules (after a report). `null` is a full run including the cleanup
     *   of removed clients and rules.
     * @throws Exception if Zoraxy cannot list its rules. Failures of single rules end up in [ReconcileResult.failedRules].
     */
    @Synchronized
    fun reconcile(onlyRules: Set<String>? = null): ReconcileResult {
        val config = config()
        val snapshots = api.listAccessRules()
        val configured = config.ruleIds()
        val desired = desiredEntries(config, state)
        val candidates = (configured + state.managedRules).filter { onlyRules == null || it in onlyRules }
        var changed = false
        val failed = mutableSetOf<String>()
        val stillManaged = configured.toMutableSet()

        for (ruleId in candidates) {
            val isConfigured = ruleId in configured
            val mode = config.ruleConfig(ruleId).mode
            val snap = snapshots[ruleId]
            if (snap == null) {
                // A rule deleted in Zoraxy that is no longer configured leaves nothing to clean
                if (isConfigured) {
                    failed += ruleId
                    logProblems(ruleId, listOf("config.json references unknown access rule '$ruleId'"))
                    ruleStatus[ruleId] = RuleStatus(mode, null, 0, listOf("rule not found in Zoraxy"))
                }
                continue
            }
            val plan = planRule(desired[ruleId].orEmpty(), snap, mode, isConfigured)
            val applied = runCatching { apply(ruleId, plan, mode) }
            val problems = applied.fold(
                onSuccess = {
                    changed = changed || plan.changesSomething
                    if (isConfigured.not()) infoLog { "[$ruleId] no longer in config.json, own entries removed, rule released" }
                    plan.warnings
                },
                onFailure = {
                    failed += ruleId
                    if (isConfigured.not()) stillManaged += ruleId // retry the cleanup in the next run
                    listOf("update failed: ${it.message}")
                },
            )
            logProblems(ruleId, problems)
            // The status page shows configured rules only
            if (isConfigured.not()) continue
            // After a failure the state in Zoraxy is unknown, the snapshot is the last one known
            ruleStatus[ruleId] = if (applied.isSuccess) {
                val entries = snap.ips.size + plan.add.keys.count { it !in snap.ips } - plan.remove.size
                RuleStatus(mode, plan.setEnabled ?: snap.whitelistEnabled, entries, problems)
            } else RuleStatus(mode, snap.whitelistEnabled, snap.ips.size, problems)
        }

        if (onlyRules == null) {
            // Rules released through the UI leave the status page and the problem log
            ruleStatus.keys.retainAll(configured)
            loggedProblems.keys.retainAll(stillManaged)
            // Forget clients that are gone from the config, their entries were removed above
            val names = config.clients.map { it.name }.toSet()
            val newState = State(state.clients.filterKeys { it in names }, stillManaged)
            if (newState != state) {
                state = newState
                save()
            }
        } else if ((candidates.toSet() - state.managedRules).isNotEmpty()) {
            // A report can come before any full run succeeded: remember its rules, so a later removal cleans them up
            state = state.copy(managedRules = state.managedRules + candidates)
            save()
        }
        return ReconcileResult(changed, failed, snapshots.size)
    }

    /** Copies everything the status page shows. */
    @Synchronized
    fun status(): Status = Status(state.clients.toMap(), clientResults.toMap(), ruleStatus.toMap())

    /** Writes state.json. */
    @Synchronized
    fun save() = state.save(stateFile)

    /** Applies [plan] to rule [ruleId]: adds first, then removes, then the switch. Throws on the first failure. */
    private fun apply(ruleId: String, plan: RulePlan, mode: Mode) {
        plan.add.forEach { (ip, tag) ->
            api.addIp(ruleId, ip, tag)
            infoLog { "[$ruleId] added $ip ($tag)" }
        }
        plan.remove.forEach { ip ->
            api.removeIp(ruleId, ip)
            infoLog { "[$ruleId] removed $ip" }
        }
        plan.setEnabled?.let {
            api.setWhitelistEnabled(ruleId, it)
            infoLog { "[$ruleId] whitelist switched ${if (it) "ON" else "OFF"} (mode ${mode.json})" }
        }
    }

    /** Logs the problems of a rule only when they change, so the 60 second loop does not flood the log. */
    private fun logProblems(ruleId: String, problems: List<String>) {
        if (loggedProblems[ruleId] == problems) return
        loggedProblems[ruleId] = problems
        problems.forEach { warnLog { "[$ruleId] $it" } }
    }
}

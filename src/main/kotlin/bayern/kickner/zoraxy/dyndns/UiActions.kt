package bayern.kickner.zoraxy.dyndns

import bayern.kickner.klogger.infoLog
import java.security.SecureRandom

/**
 * What an action leaves for the page: the message, a new password to show once, form input to show again.
 */
data class ActionResult(val notice: Notice, val secret: Secret? = null, val drafts: Drafts = Drafts())

/** 24 random bytes as hex, 48 characters: URL-safe for router settings and far beyond guessing. */
fun newPassword(random: SecureRandom): String = ByteArray(24).also(random::nextBytes).toHexString()

/**
 * The changes the status page can make. Every action validates the new config with the same rules as loading it,
 * writes config.json only when it is valid, reconciles at once and logs one line starting with `UI: `. Passwords
 * are never logged, only their hash is stored.
 *
 * @param zoraxyRules Zoraxy's access rules, a failure if Zoraxy cannot be asked. New rule IDs must be known there.
 */
class UiActions(
    private val store: ConfigStore,
    private val reconciler: Reconciler,
    private val zoraxyRules: () -> Result<Map<String, RuleSnapshot>>,
    private val random: SecureRandom = SecureRandom(),
) {

    companion object {
        /** Every action path below `/ui/`, the server answers 404 for anything else. */
        val ACTIONS = setOf(
            "client/add", "client/rules", "client/password", "client/delete",
            "rule/save", "rule/add", "rule/release", "settings",
        )
    }

    /**
     * Runs [action] (the path below `/ui/`, e.g. `client/add`) with the posted [form] fields.
     *
     * @return the result for the page, `null` if there is no such action.
     */
    fun handle(action: String, form: Map<String, List<String>>): ActionResult? {
        val run: (Call) -> ActionResult = when (action) {
            "client/add" -> ::addClient
            "client/rules" -> ::setRules
            "client/password" -> ::replacePassword
            "client/delete" -> ::deleteClient
            "rule/save" -> ::saveRule
            "rule/add" -> ::addRule
            "rule/release" -> ::releaseRule
            "settings" -> ::saveSettings
            else -> return null
        }
        return runCatching { run(Call(form, zoraxyRules)) }.getOrElse { ActionResult(Notice(false, it.message ?: "failed")) }
    }

    /**
     * One action: the posted fields and Zoraxy's access rules, asked at most once and only when needed. The messages
     * are built before [change] takes the config lock, so the question to Zoraxy happens outside it.
     */
    private class Call(val form: Map<String, List<String>>, ask: () -> Result<Map<String, RuleSnapshot>>) {
        val zoraxy: Result<Map<String, RuleSnapshot>> by lazy(ask)

        fun ruleName(id: String): String = zoraxy.getOrNull()?.get(id)?.name ?: id

        fun one(field: String): String = form[field]?.lastOrNull() ?: throw IllegalArgumentException("missing field $field")

        /** New rule IDs must exist in Zoraxy, IDs the config has already are always accepted. */
        fun checkRules(ids: List<String>, config: Config) {
            val unknown = ids.filter { it !in config.ruleIds() && it !in zoraxy.getOrNull().orEmpty() }
            if (unknown.isEmpty()) return
            zoraxy.exceptionOrNull()?.let { throw IllegalArgumentException("Cannot read Zoraxy's access rules: ${it.message}") }
            throw IllegalArgumentException("unknown access rule ${unknown.joinToString()}")
        }
    }

    private fun addClient(call: Call): ActionResult {
        val name = call.one("name").trim()
        val rules = call.form["rule"].orEmpty().distinct()
        val password = newPassword(random)
        return change(
            ok = "Client $name created. Enter the settings below in the router.",
            log = "client $name created (rules ${rules.joinToString { call.ruleName(it) }})",
            drafts = Drafts(newClientName = name, newClientRules = rules),
            secret = Secret(name, password),
        ) { config ->
            checkName(name, config)
            require(rules.isNotEmpty()) { "Pick at least one access rule." }
            call.checkRules(rules, config)
            config.copy(clients = config.clients + Client(name, sha256Hex(password), rules))
        }
    }

    private fun setRules(call: Call): ActionResult {
        val name = call.one("name")
        val rules = call.form["rule"].orEmpty().distinct()
        return change("Rules of $name saved.", "rules of $name set to ${rules.joinToString { call.ruleName(it) }}") { config ->
            val client = config.client(name)
            require(rules.isNotEmpty()) { "A client needs at least one access rule." }
            call.checkRules(rules.filter { it !in client.rules }, config)
            config.copy(clients = config.clients.map { if (it.name == name) it.copy(rules = rules) else it })
        }
    }

    private fun replacePassword(call: Call): ActionResult {
        val name = call.one("name")
        val password = newPassword(random)
        return change(
            ok = "New password for $name. Until the router has it, its updates are rejected.",
            log = "password of $name replaced",
            secret = Secret(name, password),
        ) { config ->
            config.client(name)
            config.copy(clients = config.clients.map { if (it.name == name) it.copy(passwordSha256 = sha256Hex(password)) else it })
        }
    }

    private fun deleteClient(call: Call): ActionResult {
        val name = call.one("name")
        return change("Client $name deleted, its whitelist entries are removed.", "client $name deleted") { config ->
            config.client(name)
            config.copy(clients = config.clients.filter { it.name != name })
        }
    }

    private fun saveRule(call: Call): ActionResult {
        val id = call.one("id")
        val modeText = call.one("mode")
        val staticText = call.one("static")
        val lines = staticText.split(Regex("\r?\n")).map(String::trim).filter(String::isNotEmpty)
        return change(
            ok = "Rule ${call.ruleName(id)} saved.",
            log = "rule ${call.ruleName(id)} set to $modeText with ${lines.size} fixed entr${if (lines.size == 1) "y" else "ies"}",
            drafts = Drafts(ruleId = id, ruleMode = modeText, ruleStatic = staticText),
        ) { config ->
            // A stale page must not bring back a released rule, and only rule/add may start managing one (in warn)
            require(id in config.ruleIds()) { "Rule ${call.ruleName(id)} is not managed." }
            val mode = Mode.parse(modeText)
            val static = lines.map { line ->
                Cidr.parseStatic(line).getOrElse { throw IllegalArgumentException("'$line' is not allowed as fixed entry: ${it.message}") }
            }.distinct()
            config.copy(rules = config.rules + (id to RuleConfig(mode, static)))
        }
    }

    private fun addRule(call: Call): ActionResult {
        val id = call.one("id")
        return change("Rule ${call.ruleName(id)} is now managed in mode warn, which leaves its switch alone. Choose a mode, add fixed entries and save.", "rule ${call.ruleName(id)} added") { config ->
            require(id !in config.ruleIds()) { "Rule ${call.ruleName(id)} is managed already." }
            val zoraxy = call.zoraxy.getOrElse { throw IllegalArgumentException("Cannot read Zoraxy's access rules: ${it.message}") }
            require(id in zoraxy) { "unknown access rule $id" }
            // warn first: adding a rule must never switch on an empty whitelist and block a service
            config.copy(rules = config.rules + (id to RuleConfig(Mode.WARN, emptyList())))
        }
    }

    private fun releaseRule(call: Call): ActionResult {
        val id = call.one("id")
        return change("Rule ${call.ruleName(id)} released, its own entries are removed.", "rule ${call.ruleName(id)} released") { config ->
            val owners = config.clients.filter { id in it.rules }.map { it.name }
            require(owners.isEmpty()) { "Rule ${call.ruleName(id)} is used by ${owners.joinToString()}, change their rules first." }
            require(id in config.rules) { "Rule ${call.ruleName(id)} is not managed." }
            config.copy(rules = config.rules - id)
        }
    }

    private fun saveSettings(call: Call): ActionResult {
        val host = call.one("dyndns_host").trim().ifEmpty { null }
        return change(
            ok = if (host == null) "DynDNS host cleared." else "DynDNS host saved.",
            log = if (host == null) "DynDNS host cleared" else "DynDNS host set to $host",
        ) { it.copy(dyndnsHost = host) }
    }

    /**
     * Stores the config [transform] returns and reconciles. A rejected change leaves memory and file as they were
     * and shows [drafts] again. A stored change whose reconcile fails stays stored, the loop retries every minute.
     */
    private fun change(
        ok: String,
        log: String,
        drafts: Drafts = Drafts(),
        secret: Secret? = null,
        transform: (Config) -> Config,
    ): ActionResult = store.update(transform).fold(
        onSuccess = {
            infoLog { "UI: $log" }
            runCatching { reconciler.reconcile() }.fold(
                onSuccess = { ActionResult(Notice(true, ok), secret) },
                onFailure = { e -> ActionResult(Notice(false, "saved, applying failed: ${e.message}, retried every minute"), secret) },
            )
        },
        onFailure = { ActionResult(Notice(false, it.message ?: "invalid input"), drafts = drafts) },
    )

    /** UI wording for the name rules of [Config.validated]. */
    private fun checkName(name: String, config: Config) {
        require(Config.namePattern.matches(name)) { "Name: letters, digits, dot, dash and underscore only, at most 64 characters." }
        require(name != "static") { "The name static is reserved." }
        require(config.clients.none { it.name == name }) { "A client named $name exists already." }
    }

    private fun Config.client(name: String): Client =
        clients.firstOrNull { it.name == name } ?: throw IllegalArgumentException("unknown client $name")
}

package bayern.kickner.zoraxy.dyndns

import bayern.kickner.klogger.KLogger
import bayern.kickner.klogger.staticLog
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest

private const val TAG = "Config"

/** Placeholder in the config.json template that v0.1.0 wrote on its first start. */
private const val V01_TEMPLATE_MARKER = "<output of: ./zoraxy-dyndns -hash>"

/** Comment prefix of every whitelist entry the plugin owns. Entries without it are never touched. */
const val OWNED_PREFIX = "dyndns:"

/** How the plugin handles the whitelist switch of a rule. */
enum class Mode(val json: String) {
    /** The plugin keeps the whitelist on. An empty whitelist blocks everything. The default. */
    ENFORCE("enforce"),

    /** The plugin never touches the switch, it only warns while it is off. */
    WARN("warn"),

    /** Whitelist on while it has entries, off (everything allowed) while it is empty. Dangerous, explicit only. */
    OPEN_WHEN_EMPTY("open_when_empty");

    companion object {
        /** The mode named [s] in config.json. @throws IllegalArgumentException for an unknown name. */
        fun parse(s: String): Mode = entries.firstOrNull { it.json == s }
            ?: throw IllegalArgumentException("unknown mode '$s' (use ${entries.joinToString { it.json }})")
    }
}

/**
 * One DynDNS client (e.g. a FRITZ!Box). It authenticates with Basic Auth and owns the whitelist entries tagged
 * [tag] in the access rules [rules].
 */
data class Client(val name: String, val passwordSha256: String, val rules: List<String>) {
    /** Comment that marks whitelist entries owned by this client. */
    val tag: String get() = "$OWNED_PREFIX$name"

    /** Compares the SHA-256 of [password] with [passwordSha256] in constant time. */
    fun checkPassword(password: String): Boolean =
        MessageDigest.isEqual(sha256Hex(password).toByteArray(), passwordSha256.lowercase().toByteArray())
}

/** Settings of one access rule: the switch [mode] and the fixed entries [static]. */
data class RuleConfig(val mode: Mode, val static: List<Cidr>) {
    companion object {
        /** For a rule that is not listed under `rules`. */
        val DEFAULT = RuleConfig(Mode.ENFORCE, emptyList())
    }
}

/**
 * The validated `config.json`: the clients, the per-rule settings and the DynDNS host shown in the update URL.
 *
 * @property dyndnsHost Host name (optionally with `:port`) of the Zoraxy host that receives the updates, `null` if
 *   not set. Only used to show a ready update URL.
 */
data class Config(val clients: List<Client>, val rules: Map<String, RuleConfig>, val dyndnsHost: String? = null) {

    /** All rules the plugin manages: those of the clients plus those listed under `rules`. */
    fun ruleIds(): Set<String> = clients.flatMap { it.rules }.toSet() + rules.keys

    /** Settings of rule [id], [RuleConfig.DEFAULT] if it is not listed. */
    fun ruleConfig(id: String): RuleConfig = rules[id] ?: RuleConfig.DEFAULT

    /**
     * Checks everything that parses fine but must not be stored: client names, reserved names, hashes, rules,
     * duplicates and the DynDNS host. Fixed entries are [Cidr]s and therefore already valid.
     *
     * @return this config, or a failure with a readable reason (the first problem found).
     */
    fun validated(): Result<Config> = runCatching {
        clients.forEachIndexed { i, c ->
            require(namePattern.matches(c.name)) { "clients[$i].name must match ${namePattern.pattern}" }
            require(c.name != "static") { "clients[$i].name 'static' is reserved" }
            require(sha256Pattern.matches(c.passwordSha256)) { "clients[$i].password_sha256 must be 64 hex characters" }
            require(c.rules.isNotEmpty()) { "clients[$i].rules must not be empty" }
        }
        require(clients.map { it.name }.toSet().size == clients.size) { "duplicate client names" }
        if (dyndnsHost != null) {
            val port = dyndnsHost.substringAfter(':', "1").toIntOrNull()
            require(hostPattern.matches(dyndnsHost) && port in 1..65535) { "dyndns_host must be a host name, optionally with :port, without https:// and path" }
        }
        this
    }

    /** The JSON written to config.json, the format [parse] reads. A missing DynDNS host is left out. */
    fun toJson(): String = fileJson.encodeToString(
        ConfigFile(
            clients = clients.map { ConfigClient(it.name, it.passwordSha256, it.rules) },
            rules = rules.mapValues { (_, r) -> ConfigRule(r.mode.json, r.static.map { it.toString() }) },
            dyndnsHost = dyndnsHost,
        )
    ) + "\n"

    companion object {
        /** Next to the binary, Zoraxy starts the plugin in its folder. */
        const val FILE_NAME = "config.json"

        /** Comment of the fixed entries from `rules.<id>.static`. */
        const val STATIC_TAG = "${OWNED_PREFIX}static"

        /** Allowed client names, they are the Basic Auth user name. */
        val namePattern = "[A-Za-z0-9._-]{1,64}".toRegex()

        private val sha256Pattern = "[0-9a-fA-F]{64}".toRegex()

        private val label = "[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
        private val hostPattern = "$label(\\.$label)*(:\\d{1,5})?".toRegex()

        /**
         * Loads [file]. Without a file the plugin starts with an empty config, the UI writes the file on the first
         * change. The unedited template of v0.1.0 counts as no file, so an upgrade never locks the page out.
         *
         * @return the config, or a failure with a readable reason.
         */
        fun load(file: File = File(FILE_NAME)): Result<Config> {
            if (file.exists().not()) return Result.success(Config(emptyList(), emptyMap()))
            return runCatching { file.readText() }.mapCatching { text ->
                if (V01_TEMPLATE_MARKER !in text) return@mapCatching parse(text).getOrThrow()
                staticLog(KLogger.Level.WARN, TAG) { "${file.name} is the unedited template of version 0.1.0, starting empty. Set the plugin up on its page." }
                Config(emptyList(), emptyMap())
            }
        }

        /**
         * Parses and validates the text of config.json. Unknown keys are errors.
         *
         * @return the config, or a failure with a readable reason (the first problem found).
         */
        fun parse(text: String): Result<Config> = runCatching {
            val file = fileJson.decodeFromString<ConfigFile>(text)
            val rules = file.rules.mapValues { (id, r) ->
                val static = r.static.map { s ->
                    Cidr.parseStatic(s).getOrElse { throw IllegalArgumentException("rules.$id.static: ${it.message}") }
                }
                RuleConfig(Mode.parse(r.mode), static)
            }
            Config(file.clients.map { Client(it.name, it.passwordSha256, it.rules) }, rules, file.dyndnsHost).validated().getOrThrow()
        }
    }
}

/** `config.json` as written, validated into [Config] by [Config.parse]. */
@Serializable
private data class ConfigFile(
    val clients: List<ConfigClient>,
    val rules: Map<String, ConfigRule> = emptyMap(),
    @SerialName("dyndns_host") val dyndnsHost: String? = null,
)

/** One entry of `clients`. */
@Serializable
private data class ConfigClient(
    val name: String,
    @SerialName("password_sha256") val passwordSha256: String,
    val rules: List<String>,
)

/** One entry of `rules`. */
@Serializable
private data class ConfigRule(val mode: String = Mode.ENFORCE.json, val static: List<String> = emptyList())

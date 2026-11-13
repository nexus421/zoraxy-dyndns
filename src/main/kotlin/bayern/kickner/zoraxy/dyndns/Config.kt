package bayern.kickner.zoraxy.dyndns

import bayern.kickner.klogger.KLogger
import bayern.kickner.klogger.staticLog
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest

private const val TAG = "Config"

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

/** The validated `config.json`: the clients and the per-rule settings. */
data class Config(val clients: List<Client>, val rules: Map<String, RuleConfig>) {

    /** All rules the plugin manages: those of the clients plus those listed under `rules`. */
    fun ruleIds(): Set<String> = clients.flatMap { it.rules }.toSet() + rules.keys

    /** Settings of rule [id], [RuleConfig.DEFAULT] if it is not listed. */
    fun ruleConfig(id: String): RuleConfig = rules[id] ?: RuleConfig.DEFAULT

    companion object {
        /** Next to the binary, Zoraxy starts the plugin in its folder. */
        const val FILE_NAME = "config.json"

        /** Comment of the fixed entries from `rules.<id>.static`. */
        const val STATIC_TAG = "${OWNED_PREFIX}static"

        private val name = "[A-Za-z0-9._-]{1,64}".toRegex()
        private val sha256 = "[0-9a-fA-F]{64}".toRegex()

        private val template = """
            {
              "clients": [
                {
                  "name": "fritzbox",
                  "password_sha256": "<output of: ./zoraxy-dyndns -hash>",
                  "rules": ["<access rule id from Zoraxy>"]
                }
              ],
              "rules": {
                "<access rule id from Zoraxy>": { "mode": "enforce", "static": [] }
              }
            }
        """.trimIndent() + "\n"

        /**
         * Loads [file]. If it does not exist, writes a template (owner only) and returns an empty config, so the
         * plugin runs and the log tells what to do.
         *
         * @return the config, or a failure with a readable reason.
         */
        fun load(file: File = File(FILE_NAME)): Result<Config> {
            if (file.exists()) return runCatching { file.readText() }.mapCatching { parse(it).getOrThrow() }
            return runCatching {
                file.writeText(template)
                restrictToOwner(file)
                staticLog(KLogger.Level.WARN, TAG) { "No ${file.name} found, wrote a template to ${file.absolutePath}. Edit it and restart the plugin." }
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
            val clients = file.clients.mapIndexed { i, c ->
                require(name.matches(c.name)) { "clients[$i].name must match ${name.pattern}" }
                require(c.name != "static") { "clients[$i].name 'static' is reserved" }
                require(sha256.matches(c.passwordSha256)) { "clients[$i].password_sha256 must be 64 hex characters (use -hash)" }
                require(c.rules.isNotEmpty()) { "clients[$i].rules must not be empty" }
                Client(c.name, c.passwordSha256, c.rules)
            }
            require(clients.map { it.name }.toSet().size == clients.size) { "duplicate client names" }

            val rules = file.rules.mapValues { (id, r) ->
                val static = r.static.map { s ->
                    Cidr.parseStatic(s).getOrElse { throw IllegalArgumentException("rules.$id.static: ${it.message}") }
                }
                RuleConfig(Mode.parse(r.mode), static)
            }
            Config(clients, rules)
        }
    }
}

/** `config.json` as written, validated into [Config] by [Config.parse]. */
@Serializable
private data class ConfigFile(val clients: List<ConfigClient>, val rules: Map<String, ConfigRule> = emptyMap())

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

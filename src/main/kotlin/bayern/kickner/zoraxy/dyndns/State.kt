package bayern.kickner.zoraxy.dyndns

import bayern.kickner.klogger.KLogger
import bayern.kickner.klogger.staticLog
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.time.Instant

private const val TAG = "State"

/**
 * Last report of one client.
 *
 * @property updatedAt Unix milliseconds of the report.
 */
data class ClientState(val addresses: List<Cidr>, val updatedAt: Long)

/**
 * What the plugin remembers between restarts (`state.json`, written only by the plugin): the last report per client
 * and every rule it has ever managed, so that rules removed from config.json can still be cleaned up.
 */
data class State(val clients: Map<String, ClientState>, val managedRules: Set<String>) {

    /** Writes [file] atomically: a temporary file (owner only) in the same directory, then a rename. */
    fun save(file: File) {
        val content = StateFile(
            clients = clients.toSortedMap().mapValues { (_, c) ->
                StateClient(c.addresses.map { it.toString() }, Instant.ofEpochMilli(c.updatedAt).toString())
            },
            managedRules = managedRules.sorted(),
        )
        writeOwnerOnly(file, fileJson.encodeToString(content) + "\n")
    }

    companion object {
        /** Next to config.json. */
        const val FILE_NAME = "state.json"

        /** Nothing reported, nothing managed yet. */
        val EMPTY = State(emptyMap(), emptySet())

        /**
         * Reads [file]. Never fails: a missing file is [EMPTY], an unreadable one is logged and [EMPTY], an invalid
         * client entry is logged and dropped on its own.
         */
        fun load(file: File): State {
            if (file.exists().not()) return EMPTY
            val content = runCatching { fileJson.decodeFromString<StateFile>(file.readText()) }.getOrElse {
                staticLog(KLogger.Level.WARN, TAG) { "${file.name} is unreadable (${it.message}), starting with an empty state" }
                return EMPTY
            }
            val clients = content.clients.mapNotNull { (name, c) ->
                runCatching {
                    name to ClientState(c.addresses.map { Cidr.parse(it).getOrThrow() }, Instant.parse(c.updatedAt).toEpochMilli())
                }.onFailure {
                    staticLog(KLogger.Level.WARN, TAG) { "${file.name}: dropping the invalid entry of client '$name' (${it.message})" }
                }.getOrNull()
            }.toMap()
            return State(clients, content.managedRules.toSet())
        }
    }
}

/** `state.json` as written. */
@Serializable
private data class StateFile(
    val clients: Map<String, StateClient> = emptyMap(),
    @SerialName("managed_rules") val managedRules: List<String> = emptyList(),
)

/** One client in `state.json`. */
@Serializable
private data class StateClient(val addresses: List<String>, @SerialName("updated_at") val updatedAt: String)

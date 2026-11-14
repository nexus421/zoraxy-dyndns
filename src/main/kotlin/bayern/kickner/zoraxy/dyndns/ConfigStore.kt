package bayern.kickner.zoraxy.dyndns

import java.io.File

/**
 * Holds the current config and writes every change to config.json. The UI changes the config through [update], the
 * server and the reconciler read it through [current], so a change applies without a restart.
 *
 * @param file config.json, written owner only and atomically.
 * @param initial The config loaded at startup.
 */
class ConfigStore(private val file: File, initial: Config) {
    // Volatile instead of a lock: reading the current config never waits for a change in progress
    @Volatile
    private var config = initial

    /** The config as it is now. */
    fun current(): Config = config

    /**
     * Applies [transform] to the current config, validates the result and writes it. Only a written config replaces
     * the current one, on any failure memory and file stay as they were.
     *
     * @return the new config, or a failure with a readable reason.
     */
    @Synchronized
    fun update(transform: (Config) -> Config): Result<Config> = runCatching {
        val next = transform(config).validated().getOrThrow()
        writeOwnerOnly(file, next.toJson())
        config = next
        next
    }
}

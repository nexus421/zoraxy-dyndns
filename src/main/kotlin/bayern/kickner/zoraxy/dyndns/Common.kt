package bayern.kickner.zoraxy.dyndns

import bayern.kickner.klogger.KLogger
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

/**
 * Format of every timestamp this plugin shows, e.g. `15.10.2026 19:40:12 CEST`. Pinned to [Locale.ENGLISH] so the
 * zone abbreviation stays the same on every host.
 */
val timestampFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss z", Locale.ENGLISH)

/** Formats Unix milliseconds with [timestampFormat] in the host's time zone. */
fun formatTimestamp(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(timestampFormat)

/** Version and build time for the start banner. */
val appVersion: String = "${BuildConfig.VERSION} (built ${formatTimestamp(BuildConfig.BUILD_TIME)})"

/** Strict JSON for the files of the plugin: an unknown key is an error, so a typo never falls back to a default. */
val fileJson = Json {
    ignoreUnknownKeys = false
    prettyPrint = true
}

/** JSON from Zoraxy: its structures carry more fields than the plugin reads. */
val zoraxyJson = Json { ignoreUnknownKeys = true }

/** SHA-256 of the UTF-8 bytes of [s] as lower case hex, the form of `password_sha256` in config.json. */
fun sha256Hex(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).toHexString()

/** Control characters such as line breaks, they become a space so they cannot forge log lines. */
private val controlChar = "\\p{Cc}".toRegex()

/** Invisible formatting characters such as U+202E, which turns the text after it around on screen. */
private val formatChar = "\\p{Cf}".toRegex()

/**
 * Untrusted text as it may be logged, answered or shown: no control or formatting characters, at most [max]
 * characters.
 */
fun cleanText(raw: String, max: Int = 200): String = raw.replace(controlChar, " ").replace(formatChar, "").take(max)

/** Longest message of one log line, a longer one is cut. */
const val MAX_LOG_MESSAGE = 1000

/**
 * One log line as Zoraxy shows it (Zoraxy adds the timestamp). Every message goes through [cleanText], so text
 * from a request, from Zoraxy or from an exception can never break a line or forge another one. The plugin never
 * logs stack traces, they would lose their line breaks here.
 */
fun logLine(level: KLogger.Level, tag: String, message: String): String = "${level.name}/$tag: ${cleanText(message, MAX_LOG_MESSAGE)}"

/**
 * Writes [text] to [file] atomically and owner only (`0600`): a temporary file in the same directory, then a rename.
 * NIO creates the temporary file as `0600`, `java.io.File.createTempFile` would follow the umask.
 */
fun writeOwnerOnly(file: File, text: String) {
    val tmp = Files.createTempFile(file.absoluteFile.parentFile.toPath(), ".${file.name}", ".tmp")
    runCatching {
        Files.writeString(tmp, text)
        Files.move(tmp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }.also { Files.deleteIfExists(tmp) }.getOrThrow()
}

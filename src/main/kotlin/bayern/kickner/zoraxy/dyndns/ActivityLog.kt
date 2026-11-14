package bayern.kickner.zoraxy.dyndns

import bayern.kickner.klogger.KLogger

/**
 * One line of the activity card.
 *
 * @property at Unix milliseconds of the log call.
 * @property line The line as [logLine] formats it.
 */
data class ActivityEntry(val at: Long, val line: String)

/**
 * The last [capacity] log lines in memory, for the "Recent activity" card on the status page. Klogger feeds it as a
 * second destination, so every log line ends up here without extra calls. Nothing is written to disk, a restart
 * starts empty.
 *
 * @param clock Unix milliseconds, injectable for tests.
 */
class ActivityLog(private val capacity: Int = 100, private val clock: () -> Long = System::currentTimeMillis) {
    private val entries = ArrayDeque<ActivityEntry>()

    /** Stores one log line, dropping the oldest once [capacity] is reached. Same signature as a Klogger destination. */
    @Synchronized
    fun add(level: KLogger.Level, tag: String, message: String) {
        entries.addFirst(ActivityEntry(clock(), logLine(level, tag, message)))
        while (entries.size > capacity) entries.removeLast()
    }

    /** All stored lines, the newest first. */
    @Synchronized
    fun entries(): List<ActivityEntry> = entries.toList()
}

package bayern.kickner.zoraxy.dyndns

import bayern.kickner.klogger.KLogger
import kotlin.test.Test
import kotlin.test.assertEquals

class ActivityLogTest {

    @Test
    fun keepsTheNewestFirst() {
        val log = ActivityLog(capacity = 3)
        (1..5).forEach { log.add(KLogger.Level.INFO, "T", "m$it") }
        assertEquals(listOf("INFO/T: m5", "INFO/T: m4", "INFO/T: m3"), log.entries().map { it.line })
    }

    @Test
    fun linesAreCleaned() {
        val log = ActivityLog()
        log.add(KLogger.Level.WARN, "T", "a\nb")
        assertEquals("WARN/T: a b", log.entries().single().line)
    }

    @Test
    fun timeComesFromTheClock() {
        val log = ActivityLog(clock = { 42L })
        log.add(KLogger.Level.INFO, "T", "x")
        assertEquals(42L, log.entries().single().at)
    }
}

package bayern.kickner.zoraxy.dyndns

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlannerTest {

    private val home = "87.176.150.207/32"
    private fun snap(enabled: Boolean = true, local: Boolean = false, ips: Map<String, String> = emptyMap(), countries: Map<String, String> = emptyMap()) =
        RuleSnapshot("r1", "r1", enabled, local, ips, countries)

    // --- desiredEntries -----------------------------------------------------------------------------

    @Test
    fun desiredCombinesClientsAndStatic() {
        val config = Config(
            listOf(Client("fritz", "a".repeat(64), listOf("r1", "r2"))),
            mapOf("r1" to RuleConfig(Mode.ENFORCE, listOf(Cidr.parseStatic("10.8.0.0/24").getOrThrow()))),
        )
        val state = State(mapOf("fritz" to ClientState(listOf(Cidr.parse("87.176.150.207").getOrThrow()), 0L)), emptySet())
        assertEquals(
            mapOf(
                "r1" to mapOf("10.8.0.0/24" to "dyndns:static", home to "dyndns:fritz"),
                "r2" to mapOf(home to "dyndns:fritz"),
            ),
            desiredEntries(config, state),
        )
    }

    @Test
    fun clientWithoutReportHasNoEntries() {
        val config = Config(listOf(Client("fritz", "a".repeat(64), listOf("r1"))), emptyMap())
        assertEquals(mapOf("r1" to emptyMap()), desiredEntries(config, State.EMPTY))
    }

    // --- entries ------------------------------------------------------------------------------------

    @Test
    fun missingEntryIsAdded() {
        val p = planRule(mapOf(home to "dyndns:fritz"), snap(), Mode.ENFORCE, configured = true)
        assertEquals(mapOf(home to "dyndns:fritz"), p.add)
        assertEquals(emptySet(), p.remove)
    }

    @Test
    fun staleOwnedEntryIsRemoved() {
        val p = planRule(mapOf(home to "dyndns:fritz"), snap(ips = mapOf("1.2.3.4/32" to "dyndns:fritz", home to "dyndns:fritz")), Mode.ENFORCE, true)
        assertEquals(emptyMap(), p.add)
        assertEquals(setOf("1.2.3.4/32"), p.remove)
    }

    @Test
    fun manualEntriesUntouched() {
        val p = planRule(emptyMap(), snap(ips = mapOf("1.1.1.1" to "office", "2.2.2.2" to "")), Mode.ENFORCE, true)
        assertEquals(emptyMap(), p.add)
        assertEquals(emptySet(), p.remove)
    }

    @Test
    fun foreignKeyIsNotTaken() {
        val p = planRule(mapOf(home to "dyndns:fritz"), snap(ips = mapOf(home to "office")), Mode.ENFORCE, true)
        assertEquals(emptyMap(), p.add)
        assertEquals(emptySet(), p.remove)
        assertTrue(p.warnings.any { "already whitelisted" in it }, p.warnings.toString())
    }

    @Test
    fun renamedClientRetagsEntry() {
        val p = planRule(mapOf(home to "dyndns:new"), snap(ips = mapOf(home to "dyndns:old")), Mode.ENFORCE, true)
        assertEquals(mapOf(home to "dyndns:new"), p.add)
        assertEquals(emptySet(), p.remove)
    }

    @Test
    fun unconfiguredRuleOnlyRemovesOwned() {
        val p = planRule(emptyMap(), snap(enabled = true, ips = mapOf(home to "dyndns:fritz", "1.1.1.1" to "office")), Mode.ENFORCE, configured = false)
        assertEquals(setOf(home), p.remove)
        assertNull(p.setEnabled)
        assertEquals(emptyList(), p.warnings)
    }

    // --- switch -------------------------------------------------------------------------------------

    @Test
    fun enforceEnablesSwitch() = assertEquals(true, planRule(emptyMap(), snap(enabled = false), Mode.ENFORCE, true).setEnabled)

    @Test
    fun enforceEmptyStaysOn() = assertNull(planRule(emptyMap(), snap(enabled = true), Mode.ENFORCE, true).setEnabled)

    @Test
    fun warnNeverTouchesSwitch() {
        val p = planRule(emptyMap(), snap(enabled = false), Mode.WARN, true)
        assertNull(p.setEnabled)
        assertTrue(p.warnings.any { "OFF" in it }, p.warnings.toString())
    }

    @Test
    fun openWhenEmptyOpens() {
        val p = planRule(emptyMap(), snap(enabled = true), Mode.OPEN_WHEN_EMPTY, true)
        assertEquals(false, p.setEnabled)
        assertTrue(p.warnings.any { "everyone" in it }, p.warnings.toString())
    }

    @Test
    fun openWhenEmptyClosesWhenEntryArrives() =
        assertEquals(true, planRule(mapOf(home to "dyndns:fritz"), snap(enabled = false), Mode.OPEN_WHEN_EMPTY, true).setEnabled)

    @Test
    fun openWhenEmptyClosesWhenLastEntryIsReplaced() =
        assertNull(planRule(mapOf(home to "dyndns:fritz"), snap(enabled = true, ips = mapOf("1.2.3.4/32" to "dyndns:fritz")), Mode.OPEN_WHEN_EMPTY, true).setEnabled)

    @Test
    fun staticEntryKeepsOpenWhenEmptyClosed() =
        assertNull(planRule(mapOf("10.8.0.0/24" to "dyndns:static"), snap(enabled = true), Mode.OPEN_WHEN_EMPTY, true).setEnabled)

    @Test
    fun manualEntryKeepsOpenWhenEmptyClosed() =
        assertNull(planRule(emptyMap(), snap(enabled = true, ips = mapOf("1.1.1.1" to "office")), Mode.OPEN_WHEN_EMPTY, true).setEnabled)

    @Test
    fun countryEntryCountsAsNonEmpty() =
        assertNull(planRule(emptyMap(), snap(enabled = true, countries = mapOf("de" to "")), Mode.OPEN_WHEN_EMPTY, true).setEnabled)

    @Test
    fun warnsOnLocalBypassAndCountries() {
        val p = planRule(emptyMap(), snap(local = true, countries = mapOf("de" to "")), Mode.ENFORCE, true)
        assertTrue(p.warnings.any { "loopback" in it }, p.warnings.toString())
        assertTrue(p.warnings.any { "countr" in it }, p.warnings.toString())
    }
}

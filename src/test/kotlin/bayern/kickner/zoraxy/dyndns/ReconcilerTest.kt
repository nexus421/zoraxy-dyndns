package bayern.kickner.zoraxy.dyndns

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReconcilerTest {

    private val home = Cidr.parse("87.176.150.207").getOrThrow()
    private val newHome = Cidr.parse("93.184.215.14").getOrThrow()
    private val fritz = Client("fritz", sha256Hex("pw"), listOf("r1", "r2"))
    private val stateFile = File(Files.createTempDirectory("reconciler-test").toFile(), State.FILE_NAME)
    private val zoraxy = FakeZoraxy(
        linkedMapOf(
            "r1" to FakeRule(enabled = false, ips = linkedMapOf("1.1.1.1" to "office")),
            "r2" to FakeRule(enabled = true),
        )
    )

    private fun reconciler(config: Config = Config(listOf(fritz), mapOf("r2" to RuleConfig(Mode.OPEN_WHEN_EMPTY, emptyList())))) =
        Reconciler(zoraxy.api, { config }, stateFile, clock = { 1_000L })

    @AfterTest
    fun stop() = zoraxy.stop()

    @Test
    fun startupAppliesModesAndKeepsManualEntries() {
        val result = reconciler().reconcile()
        assertEquals(2, result.knownRules)
        assertTrue(zoraxy.rules.getValue("r1").enabled, "enforce switches on")
        assertTrue(zoraxy.rules.getValue("r2").enabled.not(), "open_when_empty opens while empty")
        assertEquals(mapOf("1.1.1.1" to "office"), zoraxy.rules.getValue("r1").ips)
    }

    @Test
    fun reportAddsBeforeItRemoves() {
        val r = reconciler()
        r.report(fritz, listOf(home))
        zoraxy.calls.clear()
        val result = r.report(fritz, listOf(newHome))
        assertTrue(result.changed)
        assertEquals(
            listOf("add r1 93.184.215.14/32", "remove r1 87.176.150.207/32", "add r2 93.184.215.14/32", "remove r2 87.176.150.207/32"),
            zoraxy.calls,
        )
        assertEquals(mapOf("1.1.1.1" to "office", "93.184.215.14/32" to "dyndns:fritz"), zoraxy.rules.getValue("r1").ips)
    }

    @Test
    fun sameReportChangesNothing() {
        val r = reconciler()
        r.report(fritz, listOf(home))
        zoraxy.calls.clear()
        assertTrue(r.report(fritz, listOf(home)).changed.not())
        assertEquals(emptyList(), zoraxy.calls)
    }

    @Test
    fun reportSurvivesARestart() {
        reconciler().report(fritz, listOf(home))
        val status = reconciler().status()
        assertEquals(ClientState(listOf(home), 1_000L), status.clients["fritz"])
    }

    @Test
    fun removedRuleAndClientAreCleanedUp() {
        zoraxy.rules["r3"] = FakeRule(enabled = true, ips = linkedMapOf("5.5.5.5/32" to "dyndns:gone", "2.2.2.2" to "manual"))
        State(mapOf("gone" to ClientState(listOf(Cidr.parse("5.5.5.5").getOrThrow()), 0L)), setOf("r1", "r3")).save(stateFile)

        reconciler().reconcile()

        assertEquals(mapOf("2.2.2.2" to "manual"), zoraxy.rules.getValue("r3").ips)
        assertTrue(zoraxy.rules.getValue("r3").enabled, "the switch of a released rule stays")
        val state = State.load(stateFile)
        assertEquals(emptySet(), state.clients.keys)
        assertEquals(setOf("r1", "r2"), state.managedRules)
    }

    @Test
    fun unknownRuleFails() {
        val r = reconciler(Config(listOf(fritz.copy(rules = listOf("r1", "nope"))), emptyMap()))
        assertEquals(setOf("nope"), r.reconcile().failedRules)
        assertEquals(RuleStatus(Mode.ENFORCE, null, 0, listOf("rule not found in Zoraxy")), r.status().rules["nope"])
    }

    @Test
    fun failedUpdateShowsTheLastKnownState() {
        val r = reconciler()
        r.reconcile()
        zoraxy.failAdd = true
        val result = r.report(fritz, listOf(home))
        assertEquals(setOf("r1", "r2"), result.failedRules)
        val status = r.status().rules.getValue("r2")
        assertEquals(false, status.enabled)
        assertEquals(0, status.entries)
        assertTrue(status.problems.single().startsWith("update failed"), status.problems.toString())
    }

    @Test
    fun releasedRuleLeavesTheStatus() {
        var config = Config(listOf(fritz), mapOf("r3" to RuleConfig(Mode.ENFORCE, emptyList())))
        zoraxy.rules["r3"] = FakeRule(enabled = true)
        val r = Reconciler(zoraxy.api, { config }, stateFile, clock = { 1_000L })
        r.reconcile()
        assertTrue("r3" in r.status().rules)
        config = Config(listOf(fritz), emptyMap())
        r.reconcile()
        assertTrue("r3" !in r.status().rules)
    }

    @Test
    fun resultCarriesTheTime() {
        val r = reconciler()
        r.recordResult("fritz", "good 1.2.3.4")
        assertEquals(ClientResult("good 1.2.3.4", 1_000L), r.status().results["fritz"])
    }

    @Test
    fun ruleOfAReportIsCleanedEvenWithoutAFullRunBefore() {
        var config = Config(listOf(fritz), emptyMap())
        val r = Reconciler(zoraxy.api, { config }, stateFile, clock = { 1_000L })
        r.report(fritz, listOf(home))
        assertTrue("87.176.150.207/32" in zoraxy.rules.getValue("r1").ips)
        config = Config(emptyList(), emptyMap())
        r.reconcile()
        assertTrue("87.176.150.207/32" !in zoraxy.rules.getValue("r1").ips)
    }
}

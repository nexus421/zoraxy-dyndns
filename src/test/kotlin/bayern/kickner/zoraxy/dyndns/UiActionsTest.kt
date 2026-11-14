package bayern.kickner.zoraxy.dyndns

import bayern.kickner.klogger.KLogger
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiActionsTest {

    companion object {
        /** Every log message of all tests, Klogger is global. */
        val logged = CopyOnWriteArrayList<String>()

        init {
            KLogger.configure { logToCustom { _, _, message -> logged += message } }
        }
    }

    private val dir = Files.createTempDirectory("ui-actions-test").toFile()
    private val configFile = File(dir, Config.FILE_NAME)
    private val zoraxy = FakeZoraxy(linkedMapOf("r1" to FakeRule(enabled = true, name = "home-only"), "r2" to FakeRule(enabled = true, name = "nas")))
    private val home = Cidr.parse("87.176.150.207").getOrThrow()

    private var zoraxyCalls = 0
    private var zoraxyRules: () -> Result<Map<String, RuleSnapshot>> = { zoraxyCalls++; runCatching { zoraxy.api.listAccessRules() } }
    private val store = ConfigStore(configFile, Config(emptyList(), emptyMap())).also { it.update { c -> c }.getOrThrow() }
    private val reconciler = Reconciler(zoraxy.api, store::current, File(dir, State.FILE_NAME))
    private val actions = UiActions(store, reconciler, { zoraxyRules() })

    private fun run(action: String, vararg fields: Pair<String, String>): ActionResult =
        actions.handle(action, fields.groupBy({ it.first }, { it.second }))!!

    private fun addFritz(vararg rules: String = arrayOf("r1")): String =
        run("client/add", "name" to "fritz", *rules.map { "rule" to it }.toTypedArray()).secret!!.password

    @AfterTest
    fun stop() = zoraxy.stop()

    @Test
    fun addClientCreatesHashAndShowsPasswordOnce() {
        val result = run("client/add", "name" to "fritz", "rule" to "r1", "rule" to "r2")
        val password = result.secret!!.password
        assertTrue(result.notice.ok, result.notice.text)
        assertEquals(48, password.length)
        val client = store.current().clients.single()
        assertEquals(listOf("r1", "r2"), client.rules)
        assertTrue(client.checkPassword(password))
        assertTrue(logged.any { "fritz" in it && it.startsWith("UI: ") })
        assertTrue(logged.none { password in it })
    }

    @Test
    fun addClientRejectsBadNameAndEchoesIt() {
        val before = configFile.readBytes()
        val result = run("client/add", "name" to "<b>x</b>", "rule" to "r1")
        assertTrue(result.notice.ok.not())
        assertNull(result.secret)
        assertEquals("<b>x</b>", result.drafts.newClientName)
        assertEquals(listOf("r1"), result.drafts.newClientRules)
        assertTrue(before.contentEquals(configFile.readBytes()))
    }

    @Test
    fun addClientRejectsDuplicateAndMissingRules() {
        addFritz()
        assertTrue(run("client/add", "name" to "fritz", "rule" to "r1").notice.ok.not())
        assertTrue(run("client/add", "name" to "other").notice.ok.not())
    }

    @Test
    fun addClientRejectsUnknownRule() {
        val before = configFile.readBytes()
        val result = run("client/add", "name" to "fritz", "rule" to "nope")
        assertTrue(result.notice.ok.not())
        assertTrue("nope" in result.notice.text, result.notice.text)
        assertTrue(before.contentEquals(configFile.readBytes()))
    }

    @Test
    fun rulesOfClientReplacedAndWhitelistFollows() {
        addFritz("r1", "r2")
        reconciler.report(store.current().clients.single(), listOf(home))
        assertTrue("87.176.150.207/32" in zoraxy.rules.getValue("r2").ips)
        assertTrue(run("client/rules", "name" to "fritz", "rule" to "r1").notice.ok)
        assertEquals(listOf("r1"), store.current().clients.single().rules)
        assertTrue("87.176.150.207/32" !in zoraxy.rules.getValue("r2").ips)
        assertTrue("87.176.150.207/32" in zoraxy.rules.getValue("r1").ips)
    }

    @Test
    fun newPasswordReplacesHash() {
        val old = addFritz()
        val new = run("client/password", "name" to "fritz").secret!!.password
        val client = store.current().clients.single()
        assertTrue(client.checkPassword(new))
        assertTrue(client.checkPassword(old).not())
    }

    @Test
    fun deleteRemovesClientAndEntries() {
        addFritz()
        reconciler.report(store.current().clients.single(), listOf(home))
        assertTrue(run("client/delete", "name" to "fritz").notice.ok)
        assertEquals(emptyList(), store.current().clients)
        assertTrue(zoraxy.rules.getValue("r1").ips.isEmpty())
    }

    @Test
    fun staleClientGivesUnknownClient() {
        val before = configFile.readBytes()
        listOf("client/delete", "client/password").forEach {
            assertEquals(Notice(false, "unknown client ghost"), run(it, "name" to "ghost").notice)
        }
        assertEquals(Notice(false, "unknown client ghost"), run("client/rules", "name" to "ghost", "rule" to "r1").notice)
        assertTrue(before.contentEquals(configFile.readBytes()))
    }

    @Test
    fun ruleSaveNormalizesStatic() {
        run("rule/add", "id" to "r1")
        val result = run("rule/save", "id" to "r1", "mode" to "open_when_empty", "static" to " 10.8.0.0/24\r\n\r\n10.8.0.1/24\n203.0.113.10 ")
        assertTrue(result.notice.ok, result.notice.text)
        val rule = store.current().ruleConfig("r1")
        assertEquals(Mode.OPEN_WHEN_EMPTY, rule.mode)
        assertEquals(listOf("10.8.0.0/24", "203.0.113.10/32"), rule.static.map { it.toString() })
        assertEquals("dyndns:static", zoraxy.rules.getValue("r1").ips["10.8.0.0/24"])
    }

    @Test
    fun ruleSaveRejectsHugeRangeAndUnknownMode() {
        run("rule/add", "id" to "r1")
        val before = configFile.readBytes()
        val result = run("rule/save", "id" to "r1", "mode" to "enforce", "static" to "0.0.0.0/0")
        assertTrue(result.notice.ok.not())
        assertEquals("r1", result.drafts.ruleId)
        assertEquals("0.0.0.0/0", result.drafts.ruleStatic)
        assertTrue(run("rule/save", "id" to "r1", "mode" to "open", "static" to "").notice.ok.not())
        assertTrue(before.contentEquals(configFile.readBytes()))
    }

    @Test
    fun releaseOfUsedRuleRejected() {
        addFritz()
        assertTrue(run("rule/release", "id" to "r1").notice.ok.not())
        assertEquals(setOf("r1"), store.current().ruleIds())
    }

    @Test
    fun releaseOfStaticOnlyRuleRemovesOwnEntries() {
        zoraxy.rules.getValue("r2").ips["2.2.2.2"] = "manual"
        assertTrue(run("rule/add", "id" to "r2").notice.ok)
        assertTrue(run("rule/save", "id" to "r2", "mode" to "enforce", "static" to "10.8.0.0/24").notice.ok)
        assertTrue("10.8.0.0/24" in zoraxy.rules.getValue("r2").ips)
        assertTrue(run("rule/release", "id" to "r2").notice.ok)
        assertEquals(emptySet(), store.current().ruleIds())
        assertEquals(mapOf("2.2.2.2" to "manual"), zoraxy.rules.getValue("r2").ips)
    }

    @Test
    fun ruleAddUnknownRejected() = assertTrue(run("rule/add", "id" to "nope").notice.ok.not())

    @Test
    fun settingsSetsAndClearsHost() {
        assertTrue(run("settings", "dyndns_host" to "dyndns.example.com").notice.ok)
        assertEquals("dyndns.example.com", store.current().dyndnsHost)
        assertTrue(run("settings", "dyndns_host" to "https://x").notice.ok.not())
        assertTrue(run("settings", "dyndns_host" to " ").notice.ok)
        assertNull(store.current().dyndnsHost)
    }

    @Test
    fun applyFailureKeepsConfig() {
        zoraxy.stop()
        val result = run("settings", "dyndns_host" to "a.example")
        assertTrue(result.notice.text.startsWith("saved, applying failed"), result.notice.text)
        assertEquals("a.example", Config.parse(configFile.readText()).getOrThrow().dyndnsHost)
    }

    @Test
    fun unknownActionGivesNull() = assertNull(actions.handle("client/rename", emptyMap()))

    @Test
    fun missingFieldGivesNotice() = assertEquals(Notice(false, "missing field name"), run("client/delete").notice)

    @Test
    fun zoraxyDownRejectsUnknownRuleWithReason() {
        addFritz()
        zoraxyRules = { Result.failure(IllegalStateException("down")) }
        val rejected = run("client/add", "name" to "other", "rule" to "r2")
        assertTrue("Cannot read Zoraxy's access rules: down" in rejected.notice.text, rejected.notice.text)
        assertTrue(run("client/add", "name" to "other", "rule" to "r1").notice.ok)
    }

    @Test
    fun ruleAddStartsInWarnAndLeavesTheSwitch() {
        zoraxy.rules.getValue("r2").enabled = false
        assertTrue(run("rule/add", "id" to "r2").notice.ok)
        assertEquals(Mode.WARN, store.current().ruleConfig("r2").mode)
        assertTrue(zoraxy.rules.getValue("r2").enabled.not(), "adding a rule must not switch its whitelist on")
    }

    @Test
    fun staleSaveDoesNotBringBackAReleasedRule() {
        val before = configFile.readBytes()
        val result = run("rule/save", "id" to "r2", "mode" to "enforce", "static" to "")
        assertTrue(result.notice.ok.not())
        assertTrue("not managed" in result.notice.text, result.notice.text)
        assertTrue(before.contentEquals(configFile.readBytes()))
    }

    @Test
    fun missingStaticGivesNoticeAndKeepsEntries() {
        run("rule/add", "id" to "r1")
        run("rule/save", "id" to "r1", "mode" to "enforce", "static" to "10.8.0.0/24")
        assertEquals(Notice(false, "missing field static"), run("rule/save", "id" to "r1", "mode" to "enforce").notice)
        assertEquals(listOf("10.8.0.0/24"), store.current().ruleConfig("r1").static.map { it.toString() })
    }

    @Test
    fun zoraxyIsAskedAtMostOncePerAction() {
        val actions = listOf(
            arrayOf("client/add", "name" to "fritz", "rule" to "r1", "rule" to "r2"),
            arrayOf("client/rules", "name" to "fritz", "rule" to "r1"),
            arrayOf("rule/save", "id" to "r1", "mode" to "warn", "static" to ""),
            arrayOf("rule/add", "id" to "r2"),
            arrayOf("rule/release", "id" to "r2"),
            arrayOf("client/delete", "name" to "fritz"),
        )
        actions.forEach { a ->
            zoraxyCalls = 0
            @Suppress("UNCHECKED_CAST")
            val result = run(a[0] as String, *(a.drop(1) as List<Pair<String, String>>).toTypedArray())
            assertTrue(result.notice.ok, "${a[0]}: ${result.notice.text}")
            assertTrue(zoraxyCalls <= 1, "${a[0]} asked Zoraxy $zoraxyCalls times")
        }
    }

    @Test
    fun everyListedActionIsHandled() = UiActions.ACTIONS.forEach { assertTrue(actions.handle(it, emptyMap()) != null, it) }
}

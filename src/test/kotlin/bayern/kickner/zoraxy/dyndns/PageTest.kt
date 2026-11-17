package bayern.kickner.zoraxy.dyndns

import bayern.kickner.klogger.KLogger
import kotlin.test.Test
import kotlin.test.assertTrue

class PageTest {

    private val fritz = Client("fritz", "a".repeat(64), listOf("r1", "gone"))
    private val config = Config(listOf(fritz), mapOf("r2" to RuleConfig(Mode.OPEN_WHEN_EMPTY, emptyList())))
    private val zoraxyRules = mapOf(
        "r1" to RuleSnapshot("r1", "home-only", true, false, emptyMap(), emptyMap()),
        "r2" to RuleSnapshot("r2", "office", true, false, emptyMap(), emptyMap()),
    )
    private val status = Status(
        clients = mapOf("fritz" to ClientState(listOf(Cidr.parse("87.176.150.207").getOrThrow()), 0L)),
        results = mapOf("fritz" to ClientResult("good 87.176.150.207", 0L)),
        rules = mapOf("r1" to RuleStatus(Mode.ENFORCE, true, 2, listOf("1.1.1.1/32 is already whitelisted by another entry ('<b>x</b>'), leaving it alone"))),
    )
    private val activity = ActivityLog().apply { add(KLogger.Level.INFO, "Server", "fritz: good <script>alert(1)</script>") }

    private fun render(
        config: Config = this.config,
        rules: Result<Map<String, RuleSnapshot>> = Result.success(zoraxyRules),
        secret: Secret? = null,
        status: Status = this.status,
    ) = renderPage(PageView(config, status, rules, activity.entries(), "t0k3n", secret = secret))

    @Test
    fun rendersClientsRulesAndActivity() {
        val html = render()
        assertTrue("fritz" in html)
        assertTrue("home-only" in html)
        assertTrue("fritz: good" in html)
        assertTrue("""content="t0k3n"""" in html)
    }

    @Test
    fun answersShowInWordsWithTheDyndns2AnswerAsTooltip() {
        val labels = mapOf("good" to "updated", "nochg" to "unchanged", "badauth" to "wrong password", "badrequest" to "invalid address", "911" to "Zoraxy error")
        labels.forEach { (answer, label) ->
            val html = render(status = status.copy(results = mapOf("fritz" to ClientResult("$answer 87.176.150.207", 0L))))
            assertTrue(">$label</span>" in html, label)
            assertTrue("title=\"$answer 87.176.150.207\"" in html, answer)
        }
    }

    @Test
    fun aClientWithAReportShowsAsReportedAfterARestart() {
        val html = render(status = status.copy(results = emptyMap()))
        assertTrue(">reported</span>" in html)
        assertTrue("never reported" !in html)
    }

    @Test
    fun aClientWithoutAReportShowsAsNeverReported() =
        assertTrue(">never reported</span>" in render(status = status.copy(clients = emptyMap(), results = emptyMap())))

    @Test
    fun activitySaysWhereOlderLinesAre() = assertTrue("Since the plugin last started. Older lines are in Zoraxy" in render())

    @Test
    fun escapesEverything() {
        val html = render()
        assertTrue("&lt;b&gt;x&lt;/b&gt;" in html, html)
        assertTrue("<b>x" !in html)
        assertTrue("<script>alert" !in html)
    }

    @Test
    fun noInlineStyleOrScript() {
        val html = render(secret = Secret("fritz", "f".repeat(48)))
        assertTrue("style=" !in html)
        assertTrue("<style" !in html)
        assertTrue(Regex("<script").findAll(html).count() == 1, "exactly one script tag")
        assertTrue("""<script src="app.js"""" in html)
    }

    @Test
    fun secretOnlyWhenGiven() {
        val password = "0123456789abcdef".repeat(3)
        val html = render(secret = Secret("fritz", password))
        assertTrue("""id="secret-password">$password<""" in html, html)
        assertTrue("https://&lt;username&gt;:&lt;pass&gt;@" in html)
        assertTrue("secret-password" !in render())
    }

    @Test
    fun placeholderWithoutHost() {
        val secret = Secret("fritz", "f".repeat(48))
        assertTrue("@&lt;zoraxy-host&gt;/nic/update" in render(secret = secret))
        assertTrue("@d.example/nic/update" in render(config.copy(dyndnsHost = "d.example"), secret = secret))
    }

    @Test
    fun zoraxyDownStillRenders() {
        val html = render(rules = Result.failure(IllegalStateException("down")))
        assertTrue("down" in html)
        assertTrue("r2" in html)
        assertTrue("fritz" in html)
    }

    @Test
    fun unknownRuleOfClientIsOfferedForRemoval() {
        val html = render()
        assertTrue("""value="gone" checked""" in html || """checked="checked" value="gone"""" in html || Regex("""value="gone"[^>]*checked""").containsMatchIn(html), html)
    }

    @Test
    fun formsAreRelativeAndIconAbsolute() {
        val html = render()
        assertTrue("""action="client/add"""" in html)
        assertTrue("""src="/api/plugins/icon?plugin_id=bayern.kickner.zoraxy.dyndns"""" in html)
        assertTrue("""href="app.css"""" in html)
    }

    @Test
    fun zoraxyDownOffersConfiguredRulesAndDisablesAdding() {
        val html = render(rules = Result.failure(IllegalStateException("down")))
        val addForm = html.substringAfter("""id="add-client"""").substringBefore("</form>")
        assertTrue("""value="r1"""" in addForm && """value="r2"""" in addForm, addForm)
        assertTrue("not in Zoraxy" !in html)
        val addRule = html.substringAfter("""action="rule/add"""").substringBefore("</form>")
        assertTrue("Zoraxy is not reachable" in addRule && "disabled" in addRule, addRule)
    }

    @Test
    fun noFreeRuleDisablesManageButton() {
        val all = config.copy(rules = config.rules + ("r1" to RuleConfig.DEFAULT))
        val addRule = render(all).substringAfter("""action="rule/add"""").substringBefore("</form>")
        assertTrue("All rules are managed" in addRule && Regex("<button[^>]*disabled").containsMatchIn(addRule), addRule)
    }

    @Test
    fun domainnameDropsThePortUrlKeepsIt() {
        val html = render(config.copy(dyndnsHost = "d.example:8443"), secret = Secret("fritz", "f".repeat(48)))
        assertTrue("""id="secret-domain">d.example<""" in html, html)
        assertTrue("@d.example:8443/nic/update" in html)
    }

    @Test
    fun footerShowsTheUpdateUrlPattern() {
        val footer = render().substringAfter("<footer").substringBefore("</footer>")
        assertTrue("/nic/update?myip=&lt;ipaddr&gt;" in footer, footer)
    }

    @Test
    fun setupGuideIsOpenUntilTheFirstClient() {
        val empty = render(Config(emptyList(), emptyMap())).substringAfter("""id="setup"""").substringBefore("</details>")
        assertTrue("Setup in Zoraxy" in empty, empty)
        assertTrue("127.0.0.1:1" in empty && "dyndns" in empty && "/nic/update" in empty, empty)
        val tag = Regex("""<details[^>]*id="setup"[^>]*>""")
        val emptyTag = tag.find(render(Config(emptyList(), emptyMap())))!!.value
        assertTrue("open" in emptyTag, emptyTag)
        val withClient = tag.find(render())!!.value
        assertTrue("open" !in withClient, withClient)
    }

    @Test
    fun setupGuideUsesTheDyndnsHost() {
        val guide = render(config.copy(dyndnsHost = "d.example")).substringAfter("""id="setup"""").substringBefore("</details>")
        assertTrue("https://d.example/nic/update" in guide, guide)
    }
}

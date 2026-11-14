package bayern.kickner.zoraxy.dyndns

import java.io.File
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.nio.file.Files
import java.util.*
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerTest {

    private val password = "0123456789abcdef0123456789abcdef"
    private val zoraxy = FakeZoraxy(linkedMapOf("r1" to FakeRule(enabled = true, ips = linkedMapOf("93.184.215.14/32" to "<b>office</b>"))))
    private val config = Config(
        listOf(Client("fritz", sha256Hex(password), listOf("r1")), Client("lost", sha256Hex(password), listOf("nope"))),
        emptyMap(),
    )
    private val dir = Files.createTempDirectory("server-test").toFile()
    private val store = ConfigStore(File(dir, Config.FILE_NAME), config)
    private val reconciler = Reconciler(zoraxy.api, store::current, File(dir, State.FILE_NAME))
    private var zoraxyCalls = 0
    @Volatile
    private var exits = 0
    private val zoraxyRules = { zoraxyCalls++; runCatching { zoraxy.api.listAccessRules() } }
    private val port = freePort().also {
        Server(it, store::current, reconciler, UiActions(store, reconciler, zoraxyRules), ActivityLog(), zoraxyRules, exit = { exits++ }).start()
    }

    private val csp = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'self'"

    /** A form POST to the plugin like `app.js` sends it. */
    @Suppress("DEPRECATION")
    private fun post(path: String, body: String): Answer {
        val conn = URL("http://127.0.0.1:$port$path").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        val text = (if (code < 400) conn.inputStream else conn.errorStream).bufferedReader().use { it.readText() }
        return Answer(code, text, conn)
    }

    /** Status code, body and the connection to read headers from (case-insensitive). */
    private data class Answer(val code: Int, val body: String, val conn: HttpURLConnection)

    /** A request to the plugin. [URL] instead of [java.net.URI], so a broken escape like `%zz` reaches the server. */
    @Suppress("DEPRECATION")
    private fun get(path: String, user: String? = "fritz", pw: String = password, forwardedFor: String? = null): Answer {
        val conn = URL("http://127.0.0.1:$port$path").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        if (user != null) conn.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString("$user:$pw".toByteArray()))
        if (forwardedFor != null) conn.setRequestProperty("X-Forwarded-For", forwardedFor)
        val code = conn.responseCode
        val body = (if (code < 400) conn.inputStream else conn.errorStream).bufferedReader().use { it.readText() }
        return Answer(code, body, conn)
    }

    private fun update(query: String, user: String? = "fritz", pw: String = password) = get("/s_capture$query", user, pw).let { "${it.body} ${it.code}" }

    @AfterTest
    fun stop() = zoraxy.stop()

    @Test
    fun withoutCredentialsIsBadauthWithChallenge() {
        val (code, body, conn) = get("/s_capture?myip=87.176.150.207", user = null)
        assertEquals(401, code)
        assertEquals("badauth", body)
        assertEquals("Basic realm=\"dyndns\"", conn.getHeaderField("WWW-Authenticate"))
    }

    @Test
    fun wrongPasswordOrUserIsBadauth() {
        assertEquals("badauth 401", update("?myip=87.176.150.207", pw = "wrong"))
        assertEquals("badauth 401", update("?myip=87.176.150.207", user = "mallory"))
    }

    @Test
    fun updateCycle() {
        assertEquals("good 87.176.150.207 200", update("?myip=87.176.150.207"))
        assertEquals("nochg 87.176.150.207 200", update("?myip=87.176.150.207"))
        assertEquals("dyndns:fritz", zoraxy.rules.getValue("r1").ips["87.176.150.207/32"])
    }

    @Test
    fun invalidInputIsBadrequestAndChangesNothing() {
        assertEquals("badrequest not a publicly routable address: 192.168.1.1 400", update("?myip=192.168.1.1"))
        assertEquals(400, get("/s_capture?myip=%zz").code, "the JDK server rejects invalid percent-encoding itself")
        assertEquals(mapOf("93.184.215.14/32" to "<b>office</b>"), zoraxy.rules.getValue("r1").ips)
    }

    @Test
    fun withoutAddressTheSourceAddressIsUsed() {
        val (code, body, _) = get("/s_capture", forwardedFor = "10.0.0.1, 87.176.150.207")
        assertEquals("good 87.176.150.207 200", "$body $code")
    }

    @Test
    fun unknownRuleIs911() = assertEquals("911 500", update("?myip=87.176.150.207", user = "lost"))

    @Test
    fun statusPageEscapesAndForbidsScripts() {
        update("?myip=93.184.215.14") // collides with the manual entry, the warning quotes its comment
        val (code, body, conn) = get("/ui/", user = null)
        assertEquals(200, code)
        assertTrue("&lt;b&gt;office&lt;/b&gt;" in body, body)
        assertTrue("<b>office" !in body, body)
        assertEquals(csp, conn.getHeaderField("Content-Security-Policy"))
    }

    @Test
    fun unknownPathIs404WithNosniff() {
        val (code, _, conn) = get("/other", user = null)
        assertEquals(404, code)
        assertEquals("nosniff", conn.getHeaderField("X-Content-Type-Options"))
    }

    @Test
    fun wrongPasswordOfKnownClientIsRecorded() {
        assertEquals("badauth 401", update("?myip=87.176.150.207", pw = "wrong"))
        assertEquals("badauth", reconciler.status().results["fritz"]?.text)
        assertEquals("badauth 401", update("?myip=87.176.150.207", user = "mallory"))
        assertTrue("mallory" !in reconciler.status().results)
    }

    @Test
    fun postAddClientShowsPasswordOnceThenNever() {
        val answer = post("/ui/client/add", "name=router&rule=r1")
        assertEquals(200, answer.code)
        assertTrue("""id="secret-password"""" in answer.body, answer.body)
        assertTrue("secret-password" !in get("/ui/", user = null).body)
        assertTrue(store.current().clients.any { it.name == "router" })
    }

    @Test
    fun postDeleteRemovesWhitelistEntry() {
        assertEquals("good 87.176.150.207 200", update("?myip=87.176.150.207"))
        assertTrue("87.176.150.207/32" in zoraxy.rules.getValue("r1").ips)
        assertEquals(200, post("/ui/client/delete", "name=fritz").code)
        assertTrue("87.176.150.207/32" !in zoraxy.rules.getValue("r1").ips)
    }

    @Test
    fun pageCarriesCspAndCsrfMeta() {
        @Suppress("DEPRECATION")
        val conn = URL("http://127.0.0.1:$port/ui/").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        conn.setRequestProperty("X-Zoraxy-Csrf", "t0k3n")
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        assertEquals(csp, conn.getHeaderField("Content-Security-Policy"))
        assertTrue("""content="t0k3n"""" in body)
    }

    @Test
    fun assetsAreServed() {
        val css = get("/ui/app.css", user = null)
        val js = get("/ui/app.js", user = null)
        assertEquals("text/css; charset=utf-8", css.conn.getHeaderField("Content-Type"))
        assertEquals("text/javascript; charset=utf-8", js.conn.getHeaderField("Content-Type"))
        assertTrue(css.body.isNotBlank() && js.body.isNotBlank())
    }

    @Test
    fun getOnActionIs405() = assertEquals(405, get("/ui/client/add", user = null).code)

    @Test
    fun unknownActionIs404() = assertEquals(404, post("/ui/client/rename", "name=fritz").code)

    @Test
    fun oversizedBodyIs413() {
        assertEquals(413, post("/ui/settings", "dyndns_host=" + "a".repeat(64 * 1024)).code)
        assertEquals(null, store.current().dyndnsHost)
    }

    @Test
    fun htmlNameIsEscapedInResponse() {
        val body = post("/ui/client/add", "name=%3Cb%3Ex%3C%2Fb%3E&rule=r1").body
        assertTrue("&lt;b&gt;x&lt;/b&gt;" in body, body)
        assertTrue("<b>x</b>" !in body)
    }

    @Test
    fun parseFormKeepsRepeatedValues() {
        assertEquals(listOf("a", "b"), parseForm("rule=a&rule=b&x=1")["rule"])
        assertEquals(listOf("1"), parseForm("rule=a&rule=b&x=1")["x"])
        assertEquals(emptyMap(), parseForm(null))
    }

    @Test
    fun unknownUiPathIs404ForEveryMethod() {
        assertEquals(404, get("/ui/foo", user = null).code)
        assertEquals(404, post("/ui/foo", "x=1").code)
    }

    @Test
    fun assetsAreGetOnly() = assertEquals(405, post("/ui/app.css", "x=1").code)

    @Test
    fun pageIsGetOnly() = assertEquals(405, post("/ui/", "x=1").code)

    @Test
    fun termThroughZoraxysAdminProxyIsRefused() {
        @Suppress("DEPRECATION")
        val conn = URL("http://127.0.0.1:$port/ui//term").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        conn.setRequestProperty("X-Zoraxy-Csrf", "t0k3n")
        assertEquals(403, conn.responseCode)
        assertEquals(0, exits)
        // Still running
        assertEquals(200, get("/ui/", user = null).code)
    }

    @Test
    fun anActionAsksZoraxyOnceIncludingTheAnswerPage() {
        zoraxyCalls = 0
        assertEquals(200, post("/ui/client/add", "name=router&rule=r1").code)
        assertEquals(1, zoraxyCalls)
    }

    @Test
    fun withoutAddressAndSourceTheAnswerSaysSo() =
        assertEquals("badrequest no address parameter and no source address 400", update(""))

    @Test
    fun termFromZoraxyItselfStopsThePlugin() {
        assertEquals(200, get("/ui//term", user = null).code)
        Thread.sleep(200)
        assertEquals(1, exits)
    }
}

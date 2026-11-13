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
    private val port = freePort().also {
        val stateFile = File(Files.createTempDirectory("server-test").toFile(), State.FILE_NAME)
        Server(it, config, Reconciler(zoraxy.api, config, stateFile)).start()
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
        assertEquals("default-src 'none'; style-src 'unsafe-inline'", conn.getHeaderField("Content-Security-Policy"))
    }

    @Test
    fun unknownPathIs404WithNosniff() {
        val (code, _, conn) = get("/other", user = null)
        assertEquals(404, code)
        assertEquals("nosniff", conn.getHeaderField("X-Content-Type-Options"))
    }
}

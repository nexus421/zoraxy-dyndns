package bayern.kickner.zoraxy.dyndns

import bayern.kickner.klogger.infoLog
import bayern.kickner.klogger.warnLog
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.*
import kotlin.system.exitProcess

/**
 * Local HTTP server (127.0.0.1 only) that Zoraxy forwards captured requests and the status page to.
 *
 * Speaks the dyndns2 protocol, so any client that supports it works (FRITZ!Box, ddclient, inadyn, OPNsense):
 * `GET /nic/update?myip=<ipv4>[,<ipv6 or ipv6 prefix>]` with Basic Auth.
 *
 * Under `/ui/` it serves the status and settings page, its stylesheet and script, and the POST actions of
 * [UiActions]. Zoraxy only forwards `/plugin.ui/<id>/...` after its login and its CSRF check.
 *
 * It runs on the single dispatcher thread of the JDK server, requests are handled one after another.
 *
 * @param port The port Zoraxy assigned in the configure spec.
 * @param config The current config, see [ConfigStore].
 * @param zoraxyRules Zoraxy's access rules for the page, a failure if Zoraxy cannot be asked.
 */
class Server(
    private val port: Int,
    private val config: () -> Config,
    private val reconciler: Reconciler,
    private val actions: UiActions,
    private val activity: ActivityLog,
    private val zoraxyRules: () -> Result<Map<String, RuleSnapshot>>,
) {

    @Volatile
    private var warnedPlainHttp = false

    /** Binds the port and starts serving. */
    fun start() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 16)
        server.createContext(Introspect.STATIC_INGRESS) { ex -> safely(ex) { handleUpdate(ex) } }
        server.createContext(Introspect.UI_PATH) { ex ->
            // Zoraxy calls ui_path + "/term", i.e. "/ui//term" for ui_path "/ui/"
            if (ex.requestURI.path.trimEnd('/').endsWith("/term")) handleTerm(ex) else safely(ex) { handleUi(ex) }
        }
        server.createContext("/") { ex -> safely(ex) { reply(ex, 404, "not found") } }
        server.start()
        infoLog { "Listening on 127.0.0.1:$port with ${config().clients.size} client(s)" }
    }

    private fun handleUpdate(ex: HttpExchange) {
        if (ex.requestMethod != "GET" && ex.requestMethod != "POST") return reply(ex, 405, "badrequest")

        // With TLS enabled, Zoraxy never routes plain HTTP to plugins. "http" here means Zoraxy itself runs without
        // TLS, fine behind a TLS-terminating proxy, a plaintext password otherwise. Warned once instead of rejected,
        // so setups with TLS in front of Zoraxy keep working.
        val plainHttp = ex.requestHeaders.getFirst("X-Forwarded-Proto").equals("http", ignoreCase = true)
        if (plainHttp && warnedPlainHttp.not()) {
            warnedPlainHttp = true
            warnLog {
                "A request reached Zoraxy without TLS. Make sure TLS is terminated in front of Zoraxy, " +
                    "otherwise DynDNS passwords travel in plain text"
            }
        }

        val client = authenticate(ex)
        if (client == null) {
            // No delay here: requests are handled one by one, a delay would let anyone stall real updates.
            // Zoraxy's rate limit, which runs before the plugin sees the request, stops password guessing.
            ex.responseHeaders.add("WWW-Authenticate", "Basic realm=\"dyndns\"")
            return reply(ex, 401, "badauth")
        }

        val query = parseForm(ex.requestURI.rawQuery).mapValues { it.value.last() }
        val addresses = addressesFromQuery(query).mapCatching { reported ->
            // dyndns2: without an address parameter the source address of the request is used
            reported ?: listOf(Cidr.parse(sourceIp(ex) ?: "").getOrThrow())
        }.getOrElse {
            // Parameter names only: a misconfigured client may put its password into the query
            infoLog { "${client.name}: rejected update (parameters: ${query.keys.sorted().joinToString()}): ${it.message}" }
            return finish(ex, client, 400, "badrequest ${cleanText(it.message.orEmpty())}")
        }

        val shown = addresses.joinToString(",") { it.display }
        runCatching { reconciler.report(client, addresses) }
            .onSuccess { result ->
                if (result.failedRules.isNotEmpty()) warnLog { "${client.name}: could not update rule(s) ${result.failedRules.joinToString()}" }
                val (code, answer) = when {
                    result.failedRules.isNotEmpty() -> 500 to "911"
                    result.changed -> 200 to "good $shown"
                    else -> 200 to "nochg $shown"
                }
                finish(ex, client, code, answer)
            }
            .onFailure {
                warnLog { "${client.name}: update failed: ${it.message}" }
                finish(ex, client, 500, "911")
            }
    }

    /** Zoraxy stops a plugin by calling `<ui_path>/term` (then SIGTERM): answer, then exit through the shutdown hook. */
    private fun handleTerm(ex: HttpExchange) {
        runCatching { reply(ex, 200, "OK") }
        ex.close()
        Thread { exitProcess(0) }.start()
    }

    private fun finish(ex: HttpExchange, client: Client, code: Int, body: String) {
        reconciler.recordResult(client.name, body)
        infoLog { "${client.name}: $body" }
        reply(ex, code, body)
    }

    /** The client named in the Basic Auth header, `null` if the header is missing or name or password is wrong. */
    private fun authenticate(ex: HttpExchange): Client? {
        val header = ex.requestHeaders.getFirst("Authorization") ?: return null
        if (header.startsWith("Basic ", ignoreCase = true).not()) return null
        val decoded = runCatching { String(Base64.getDecoder().decode(header.substring(6).trim())) }.getOrNull() ?: return null
        val name = decoded.substringBefore(':')
        val password = decoded.substringAfter(':', "")
        val client = config().clients.firstOrNull { it.name == name }
        // Always hash once, so a wrong name costs the same as a wrong password
        val ok = client?.checkPassword(password) ?: run { sha256Hex(password); false }
        if (ok.not() && client != null) reconciler.recordResult(client.name, "badauth")
        if (ok.not()) infoLog { "Authentication failed for client '${name.take(64)}' from ${sourceIp(ex)?.take(64)}" }
        return if (ok) client else null
    }

    /** Zoraxy appends the address it saw as the last `X-Forwarded-For` entry. */
    private fun sourceIp(ex: HttpExchange): String? =
        ex.requestHeaders.getFirst("X-Forwarded-For")?.split(',')?.lastOrNull()?.trim()?.ifEmpty { null }

    private fun handleUi(ex: HttpExchange) {
        val path = ex.requestURI.path.removePrefix(Introspect.UI_PATH).trim('/')
        val get = ex.requestMethod == "GET"
        when {
            path.isEmpty() && get -> page(ex, PageView(config(), reconciler.status(), zoraxyRules(), activity.entries(), csrf(ex)))
            path == "app.css" && get -> asset(ex, "text/css; charset=utf-8", APP_CSS)
            path == "app.js" && get -> asset(ex, "text/javascript; charset=utf-8", APP_JS)
            path in UiActions.ACTIONS && ex.requestMethod == "POST" -> handleAction(ex, path)
            path.isEmpty() || path == "app.css" || path == "app.js" || path in UiActions.ACTIONS -> reply(ex, 405, "method not allowed")
            else -> reply(ex, 404, "not found")
        }
    }

    private fun handleAction(ex: HttpExchange, action: String) {
        val body = ex.requestBody.readNBytes(MAX_FORM_BYTES + 1)
        if (body.size > MAX_FORM_BYTES) return reply(ex, 413, "form too large")
        val form = runCatching { parseForm(body.decodeToString()) }.getOrElse { emptyMap() }
        val result = actions.handle(action, form) ?: return reply(ex, 404, "not found")
        val view = PageView(config(), reconciler.status(), zoraxyRules(), activity.entries(), csrf(ex), result.notice, result.secret, result.drafts)
        page(ex, view)
    }

    /** Zoraxy's CSRF token, which it adds to every request it forwards to the plugin UI. */
    private fun csrf(ex: HttpExchange): String = ex.requestHeaders.getFirst("X-Zoraxy-Csrf").orEmpty()

    private fun page(ex: HttpExchange, view: PageView) {
        ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        ex.responseHeaders.add("Content-Security-Policy", PAGE_CSP)
        reply(ex, 200, renderPage(view))
    }

    private fun asset(ex: HttpExchange, type: String, content: String) {
        ex.responseHeaders.add("Content-Type", type)
        reply(ex, 200, content)
    }

    /** Answers a failure with 500 `911` instead of dropping the connection, and always closes the exchange. */
    private fun safely(ex: HttpExchange, block: () -> Unit) {
        runCatching(block).onFailure {
            warnLog { "Internal error: ${it.message}" }
            runCatching { reply(ex, 500, "911") }
        }
        ex.close()
    }

    private fun reply(ex: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray()
        if (ex.responseHeaders.containsKey("Content-Type").not()) ex.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
        ex.responseHeaders.add("X-Content-Type-Options", "nosniff")
        ex.responseHeaders.add("Cache-Control", "no-store")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

}

/** Largest form body an action accepts. Every real form is far below. */
private const val MAX_FORM_BYTES = 64 * 1024

/**
 * The page loads nothing but its own stylesheet, its own script and Zoraxy's icon, no inline code. Only Zoraxy's own
 * origin may frame it, so no other site can lay the delete buttons under its own page.
 */
private const val PAGE_CSP =
    "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'self'"

/**
 * Decodes `application/x-www-form-urlencoded` text (a query or a form body). A name may repeat, like the rule
 * checkboxes, so every name maps to all its values in order. The JDK server already rejects a URI with broken
 * percent-encoding, a broken body reads as empty and the action reports the missing field.
 */
internal fun parseForm(raw: String?): Map<String, List<String>> = raw.orEmpty().split('&').filter(String::isNotEmpty)
    .groupBy({ URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) }, { URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8) })

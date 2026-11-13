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
 * It runs on the single dispatcher thread of the JDK server, requests are handled one after another.
 *
 * @param port The port Zoraxy assigned in the configure spec.
 */
class Server(private val port: Int, private val config: Config, private val reconciler: Reconciler) {

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
        infoLog { "Listening on 127.0.0.1:$port with ${config.clients.size} client(s)" }
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

        val query = parseQuery(ex.requestURI.rawQuery)
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
        val client = config.clients.firstOrNull { it.name == name }
        // Always hash once, so a wrong name costs the same as a wrong password
        val ok = client?.checkPassword(password) ?: run { sha256Hex(password); false }
        if (ok.not()) infoLog { "Authentication failed for client '${name.take(64)}' from ${sourceIp(ex)?.take(64)}" }
        return if (ok) client else null
    }

    /** Zoraxy appends the address it saw as the last `X-Forwarded-For` entry. */
    private fun sourceIp(ex: HttpExchange): String? =
        ex.requestHeaders.getFirst("X-Forwarded-For")?.split(',')?.lastOrNull()?.trim()?.ifEmpty { null }

    private fun handleUi(ex: HttpExchange) {
        val status = reconciler.status()
        val clientRows = config.clients.joinToString("") { c ->
            val s = status.clients[c.name]
            row(
                c.name, s?.addresses?.joinToString(", ") { it.display } ?: "none",
                s?.let { formatTimestamp(it.updatedAt) } ?: "never", status.results[c.name] ?: "none", c.rules.joinToString(", "),
            )
        }
        val ruleRows = status.rules.entries.joinToString("") { (id, r) ->
            val switch = when (r.enabled) {
                true -> "on"
                false -> "OFF"
                null -> "unknown"
            }
            row(id, r.mode.json, switch, r.entries.toString(), r.problems.joinToString(" | ").ifEmpty { "none" })
        }
        val html = """
            <!DOCTYPE html><html><head><meta charset="utf-8"><title>DynDNS Whitelist</title>
            <style>body{font-family:sans-serif;margin:1.5rem}table{border-collapse:collapse;margin-bottom:1.5rem}td,th{padding:.3rem .8rem;text-align:left;border-bottom:1px solid #ddd;vertical-align:top}</style>
            </head><body><h2>DynDNS Whitelist</h2>
            <h3>Clients</h3>
            <table><tr><th>Client</th><th>Last addresses</th><th>Last update</th><th>Last result</th><th>Rules</th></tr>$clientRows</table>
            <h3>Access rules</h3>
            <table><tr><th>Rule</th><th>Mode</th><th>Whitelist</th><th>Entries</th><th>Warnings</th></tr>$ruleRows</table>
            <p>Update URL: <code>https://&lt;client&gt;:&lt;password&gt;@&lt;host&gt;${Introspect.CAPTURE_PATH}?myip=&lt;ipaddr&gt;</code></p>
            </body></html>
        """.trimIndent()
        ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        // No scripts, no external resources, only the inline style above
        ex.responseHeaders.add("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'")
        reply(ex, 200, html)
    }

    /** One table row, every cell escaped. */
    private fun row(vararg cells: String) = cells.joinToString("", "<tr>", "</tr>") { "<td>${esc(it)}</td>" }

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

    /**
     * The decoded query, the last value wins for a repeated name. The JDK server already answers 400 to a request
     * whose URI has invalid percent-encoding, so decoding cannot fail here.
     */
    private fun parseQuery(raw: String?): Map<String, String> = raw.orEmpty().split('&').filter(String::isNotEmpty).associate {
        URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8)
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}

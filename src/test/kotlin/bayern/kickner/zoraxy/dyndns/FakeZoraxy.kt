package bayern.kickner.zoraxy.dyndns

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.addJsonObject
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URLDecoder

/** One access rule of [FakeZoraxy]. [name] `null` leaves the `Name` field out, like a rule without a name. */
class FakeRule(
    var enabled: Boolean = false,
    val allowLocal: Boolean = false,
    val ips: MutableMap<String, String> = linkedMapOf(),
    val name: String? = null,
)

/**
 * Zoraxy's plugin API in memory, on a free port of 127.0.0.1: the four endpoints of [Introspect] with the same
 * answers as Zoraxy. Records every change as `add <rule> <ip>`, `remove <rule> <ip>` or `enable <rule> <state>`.
 */
class FakeZoraxy(val rules: MutableMap<String, FakeRule>) {
    /** The key [api] sends. */
    val apiKey = "test-key"

    /** Every change in the order it arrived. */
    val calls = mutableListOf<String>()

    /** While true, adding an IP answers HTTP 500. */
    @Volatile
    var failAdd = false

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 16).apply {
        createContext("/plugin/api/") { ex -> handle(ex) }
        start()
    }

    /** A client for this fake. */
    val api = ZoraxyApi(server.address.port, apiKey)

    /** Stops the server. */
    fun stop() = server.stop(0)

    @Synchronized
    private fun handle(ex: HttpExchange): Unit = ex.use {
        if (ex.requestHeaders.getFirst("Authorization") != "Bearer $apiKey") return reply(ex, 401, """{"error":"invalid api key"}""")
        val form = ex.requestBody.readBytes().decodeToString().split('&').filter(String::isNotEmpty).associate {
            URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
        }
        val path = ex.requestURI.path
        if (path == "/plugin/api/access/list") return reply(ex, 200, accessList())
        val rule = rules[form["id"]] ?: return reply(ex, 200, """{"error":"rule not found"}""")
        when (path) {
            "/plugin/api/whitelist/ip/add" -> {
                if (failAdd) return reply(ex, 500, """{"error":"disk full"}""")
                rule.ips[form.getValue("ip")] = form["comment"].orEmpty()
                calls += "add ${form["id"]} ${form["ip"]}"
            }
            "/plugin/api/whitelist/ip/remove" -> {
                rule.ips.remove(form.getValue("ip"))
                calls += "remove ${form["id"]} ${form["ip"]}"
            }
            "/plugin/api/whitelist/enable" -> {
                rule.enabled = form["enable"] == "true"
                calls += "enable ${form["id"]} ${rule.enabled}"
            }
            else -> return reply(ex, 404, """{"error":"not found"}""")
        }
        reply(ex, 200, "\"OK\"")
    }

    private fun accessList() = buildJsonArray {
        rules.forEach { (id, r) ->
            addJsonObject {
                put("ID", id)
                r.name?.let { put("Name", it) }
                put("WhitelistEnabled", r.enabled)
                put("WhitelistAllowLocalAndLoopback", r.allowLocal)
                putJsonObject("WhiteListIP") { r.ips.forEach { (ip, comment) -> put(ip, comment) } }
            }
        }
    }.toString()

    private fun reply(ex: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray()
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
}

/** A port that was free a moment ago. */
fun freePort(): Int = ServerSocket(0).use { it.localPort }

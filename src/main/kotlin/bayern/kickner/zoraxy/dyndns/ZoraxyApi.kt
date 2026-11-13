package bayern.kickner.zoraxy.dyndns

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.net.URLEncoder

/**
 * Client for Zoraxy's plugin API (`/plugin/api/...` on 127.0.0.1, Bearer API key). Only the endpoints declared in
 * [Introspect] are reachable with the key. Every call throws on a failure, the [Reconciler] catches per rule.
 *
 * @param port Zoraxy's own port, from the configure spec.
 * @param apiKey The key Zoraxy generated for this plugin.
 */
class ZoraxyApi(private val port: Int, private val apiKey: String) {

    /** All access rules with switch state and whitelist entries, by rule ID. */
    fun listAccessRules(): Map<String, RuleSnapshot> = parseAccessRules(request("GET", "/plugin/api/access/list"))

    /** Adds [ip] with [comment] to the whitelist of rule [ruleId]. */
    fun addIp(ruleId: String, ip: String, comment: String) =
        expectOk(request("POST", "/plugin/api/whitelist/ip/add", form("id" to ruleId, "ip" to ip, "comment" to comment)))

    /** Removes [ip] from the whitelist of rule [ruleId]. */
    fun removeIp(ruleId: String, ip: String) =
        expectOk(request("POST", "/plugin/api/whitelist/ip/remove", form("id" to ruleId, "ip" to ip)))

    /** Switches the whitelist of rule [ruleId] on or off. */
    fun setWhitelistEnabled(ruleId: String, enabled: Boolean) =
        expectOk(request("POST", "/plugin/api/whitelist/enable", form("id" to ruleId, "enable" to enabled.toString())))

    private fun expectOk(body: String) {
        val answer = zoraxyJson.parseToJsonElement(body)
        if (answer == JsonPrimitive("OK")) return
        val reason = (answer as? JsonObject)?.get("error")?.jsonPrimitive?.content ?: body
        error("Zoraxy: ${cleanText(reason)}")
    }

    private fun request(method: String, path: String, formBody: String? = null): String {
        // NO_PROXY: never send the API key through a configured system proxy
        val conn = URI("http://127.0.0.1:$port$path").toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 5_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            if (formBody != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.outputStream.use { it.write(formBody.toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code != 200) error("Zoraxy API $method $path answered HTTP $code: ${cleanText(text.trim())}")
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun form(vararg pairs: Pair<String, String>) = pairs.joinToString("&") { "${enc(it.first)}=${enc(it.second)}" }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
}

/**
 * Parses the answer of `/plugin/api/access/list`. Zoraxy answers an error as object `{"error": "..."}`.
 *
 * @throws IllegalStateException for an error answer.
 * @throws kotlinx.serialization.SerializationException for anything that is no list of rules.
 */
fun parseAccessRules(body: String): Map<String, RuleSnapshot> {
    val answer = zoraxyJson.parseToJsonElement(body)
    if (answer is JsonObject) error("Zoraxy: ${cleanText(answer["error"]?.jsonPrimitive?.content ?: body)}")
    return zoraxyJson.decodeFromJsonElement<List<AccessRule>>(answer).associate {
        it.id to RuleSnapshot(it.id, it.whitelistEnabled, it.allowLocal, it.ips.orEmpty(), it.countries.orEmpty())
    }
}

/** The fields of a Zoraxy access rule the plugin reads. The whitelist maps are `null` in rules that never had one. */
@Serializable
private data class AccessRule(
    @SerialName("ID") val id: String,
    @SerialName("WhitelistEnabled") val whitelistEnabled: Boolean = false,
    @SerialName("WhitelistAllowLocalAndLoopback") val allowLocal: Boolean = false,
    @SerialName("WhiteListIP") val ips: Map<String, String>? = null,
    @SerialName("WhiteListCountryCode") val countries: Map<String, String>? = null,
)

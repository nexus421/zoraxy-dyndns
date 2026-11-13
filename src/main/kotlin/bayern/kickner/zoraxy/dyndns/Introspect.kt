package bayern.kickner.zoraxy.dyndns

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The plugin's self-description, printed on `-introspect`. Zoraxy reads it before it starts the plugin, the plugin
 * store reads the same JSON from `.introspect` in the repository root. CI checks that both are identical.
 */
object Introspect {
    /** Public path on the tagged host that Zoraxy hands to the plugin, the dyndns2 standard path. */
    const val CAPTURE_PATH = "/nic/update"

    /** Local path Zoraxy forwards captured requests to. */
    const val STATIC_INGRESS = "/s_capture"

    /** Local path of the status page, Zoraxy shows it in its plugin UI and stops the plugin through `<ui_path>/term`. */
    const val UI_PATH = "/ui/"

    private val version = BuildConfig.VERSION.split('.').map(String::toInt)

    private val spec = Spec(
        id = "bayern.kickner.zoraxy.dyndns",
        name = "DynDNS Whitelist",
        author = "nexus421",
        authorContact = "https://github.com/nexus421",
        description = "Turns Zoraxy into a DynDNS (dyndns2) endpoint: a router such as a FRITZ!Box pushes its current IP " +
            "and the plugin keeps it in the whitelist of the configured access rules.",
        url = "https://github.com/nexus421/zoraxy-dyndns",
        type = 0, // router plugin
        versionMajor = version[0],
        versionMinor = version[1],
        versionPatch = version[2],
        staticCapturePaths = listOf(CapturePath(CAPTURE_PATH)),
        staticCaptureIngress = STATIC_INGRESS,
        uiPath = UI_PATH,
        permittedApiEndpoints = listOf(
            Endpoint("GET", "/plugin/api/access/list", "Read access rules: whitelist entries, switch state and bypass settings"),
            Endpoint("POST", "/plugin/api/whitelist/ip/add", "Whitelist the address reported by a DynDNS client or a static entry"),
            Endpoint("POST", "/plugin/api/whitelist/ip/remove", "Remove addresses that are no longer reported or configured"),
            Endpoint("POST", "/plugin/api/whitelist/enable", "Keep the whitelist switch as configured (modes enforce and open_when_empty)"),
        ),
    )

    private val json = Json { prettyPrint = true }

    /** The introspect JSON. */
    fun json(): String = json.encodeToString(spec)
}

/** Introspect payload as defined by Zoraxy's `zoraxy_plugin` package. */
@Serializable
private data class Spec(
    val id: String,
    val name: String,
    val author: String,
    @SerialName("author_contact") val authorContact: String,
    val description: String,
    val url: String,
    val type: Int,
    @SerialName("version_major") val versionMajor: Int,
    @SerialName("version_minor") val versionMinor: Int,
    @SerialName("version_patch") val versionPatch: Int,
    @SerialName("static_capture_paths") val staticCapturePaths: List<CapturePath>,
    @SerialName("static_capture_ingress") val staticCaptureIngress: String,
    @SerialName("ui_path") val uiPath: String,
    @SerialName("permitted_api_endpoints") val permittedApiEndpoints: List<Endpoint>,
)

/** One path on the tagged host that Zoraxy forwards to [Introspect.STATIC_INGRESS]. */
@Serializable
private data class CapturePath(@SerialName("capture_path") val capturePath: String)

/** One Zoraxy API endpoint the plugin's key may call. */
@Serializable
private data class Endpoint(val method: String, val endpoint: String, val reason: String)

package bayern.kickner.zoraxy.dyndns

import bayern.kickner.klogger.KLogger
import bayern.kickner.klogger.staticLog
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

private const val TAG = "Main"

/** Interval of the reconcile loop: brings the whitelists back to the desired state after manual changes. */
private const val RECONCILE_SECONDS = 60L

/** Zoraxy starts plugins before its own API listens, the first run is retried this often, every 2 seconds. */
private const val STARTUP_ATTEMPTS = 30

/** Failed runs in a row are logged once every this many, so an outage does not flood the log. */
private const val LOG_EVERY_NTH_FAILURE = 30

/**
 * Entry point.
 *
 * - `-configure=<json>` is how Zoraxy starts the plugin, with the port to listen on and the API key.
 * - `-introspect` prints the self-description for Zoraxy, see [Introspect].
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "-introspect") return println(Introspect.json())
    val activity = ActivityLog()
    configureLogging(activity)

    val spec = args.firstOrNull { it.startsWith("-configure=") }
        ?.let { runCatching { zoraxyJson.decodeFromString<ConfigureSpec>(it.removePrefix("-configure=")) }.getOrNull() }
        ?: fail("Zoraxy starts this plugin with -configure=<json>. Tool: -introspect")
    if (spec.apiKey.isEmpty()) fail("No API key from Zoraxy, are permitted_api_endpoints set?")

    staticLog(KLogger.Level.INFO, TAG) { "zoraxy-dyndns $appVersion" }
    val store = ConfigStore(File(Config.FILE_NAME), Config.load().getOrElse { fail("Invalid ${Config.FILE_NAME}: ${it.message}") })
    val api = ZoraxyApi(spec.zoraxyPort, spec.apiKey)
    val zoraxyRules = { runCatching { api.listAccessRules() } }
    val reconciler = Reconciler(api, store::current, File(State.FILE_NAME))
    Server(spec.port, store::current, reconciler, UiActions(store, reconciler, zoraxyRules), activity, zoraxyRules).start()
    // Runs on /term, on SIGTERM (Zoraxy sends it after /term) and on a normal exit
    Runtime.getRuntime().addShutdownHook(Thread {
        staticLog(KLogger.Level.INFO, TAG) { "Shutting down" }
        runCatching { reconciler.save() }
    })
    startReconcileLoop(reconciler)
}

/**
 * The fields of Zoraxy's configure spec the plugin reads.
 *
 * @property port Where the plugin listens.
 * @property apiKey Key for Zoraxy's plugin API, empty if the plugin declares no endpoints.
 * @property zoraxyPort Where Zoraxy's plugin API listens.
 */
@Serializable
private data class ConfigureSpec(
    val port: Int,
    @SerialName("api_key") val apiKey: String = "",
    @SerialName("zoraxy_port") val zoraxyPort: Int = 0,
)

/**
 * The console, which Zoraxy shows in its own log with its own timestamp, and [activity] for the status page. Every
 * line goes through [logLine]. Secrets (passwords, the API key) are never logged.
 */
private fun configureLogging(activity: ActivityLog) {
    KLogger.configure {
        logToConsole()
        logToCustom(KLogger.Level.INFO, activity::add)
        minLevel = KLogger.Level.INFO
        format(::logLine)
    }
}

/**
 * Retries the first run every 2 seconds until it succeeds, then reconciles every [RECONCILE_SECONDS].
 *
 * Failures while Zoraxy's API is still starting are expected and only logged once the startup attempts are used up.
 * Once the config was validated, the first failure is logged at once. After that, one in [LOG_EVERY_NTH_FAILURE]
 * failures is logged.
 */
private fun startReconcileLoop(reconciler: Reconciler) {
    val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "reconcile").apply { isDaemon = true } }
    var validated = false
    var failures = 0
    val run = Runnable {
        runCatching { reconciler.reconcile() }
            .onSuccess {
                if (validated.not()) staticLog(KLogger.Level.INFO, TAG) { "Config validated against ${it.knownRules} access rule(s)" }
                validated = true
                failures = 0
            }
            .onFailure {
                failures++
                val logIt = if (validated) failures % LOG_EVERY_NTH_FAILURE == 1 else failures % LOG_EVERY_NTH_FAILURE == 0
                if (logIt) staticLog(KLogger.Level.WARN, TAG) { "Reconcile failed ($failures in a row): ${it.message}" }
            }
    }
    scheduler.execute {
        while (validated.not() && failures < STARTUP_ATTEMPTS) {
            run.run()
            if (validated.not()) Thread.sleep(2_000)
        }
        scheduler.scheduleWithFixedDelay(run, RECONCILE_SECONDS, RECONCILE_SECONDS, TimeUnit.SECONDS)
    }
}

private fun fail(msg: String): Nothing {
    staticLog(KLogger.Level.ERROR, TAG) { msg }
    exitProcess(1)
}

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

/** Shortest password `-hash` accepts. `openssl rand -hex 24` gives 48 characters. */
private const val MIN_PASSWORD_LENGTH = 16

/**
 * Entry point.
 *
 * - `-configure=<json>` is how Zoraxy starts the plugin, with the port to listen on and the API key.
 * - `-introspect` prints the self-description for Zoraxy, see [Introspect].
 * - `-hash` reads a password from stdin and prints its SHA-256 for config.json.
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "-introspect") return println(Introspect.json())
    configureLogging()
    if (args.firstOrNull() == "-hash") return hashPassword()

    val spec = args.firstOrNull { it.startsWith("-configure=") }
        ?.let { runCatching { zoraxyJson.decodeFromString<ConfigureSpec>(it.removePrefix("-configure=")) }.getOrNull() }
        ?: fail("Zoraxy starts this plugin with -configure=<json>. Tools: -introspect, -hash")
    if (spec.apiKey.isEmpty()) fail("No API key from Zoraxy, are permitted_api_endpoints set?")

    staticLog(KLogger.Level.INFO, TAG) { "zoraxy-dyndns $appVersion" }
    val config = Config.load().getOrElse { fail("Invalid ${Config.FILE_NAME}: ${it.message}") }
    val reconciler = Reconciler(ZoraxyApi(spec.zoraxyPort, spec.apiKey), config, File(State.FILE_NAME))
    Server(spec.port, config, reconciler).start()
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
 * Console only: Zoraxy shows every line the plugin prints in its own log, with its own timestamp. Every line goes
 * through [logLine]. Secrets (passwords, the API key) are never logged.
 */
private fun configureLogging() {
    KLogger.configure {
        logToConsole()
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

/** Reads a password from the terminal or stdin and prints its SHA-256 for config.json. */
private fun hashPassword() {
    val password = System.console()?.readPassword("Password: ")?.let(::String) ?: readlnOrNull().orEmpty()
    val tooShort = password.length < MIN_PASSWORD_LENGTH
    if (tooShort) fail("Use at least $MIN_PASSWORD_LENGTH characters, e.g. from: openssl rand -hex 24 (hex is URL-safe)")
    println(sha256Hex(password))
}

private fun fail(msg: String): Nothing {
    staticLog(KLogger.Level.ERROR, TAG) { msg }
    exitProcess(1)
}

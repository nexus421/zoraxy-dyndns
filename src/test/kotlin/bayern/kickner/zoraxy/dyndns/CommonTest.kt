package bayern.kickner.zoraxy.dyndns

import bayern.kickner.klogger.KLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class CommonTest {

    @Test
    fun cleanTextRemovesLineBreaksAndBidiControls() =
        assertEquals("evil  INFO/Main: forged txt.exe", cleanText("evil\n\rINFO/Main: forged ‮txt.exe"))

    @Test
    fun cleanTextLimitsLength() = assertEquals("abc", cleanText("abcdef", 3))

    @Test
    fun logLineIsOneCleanLine() {
        assertEquals("WARN/Server: a b", logLine(KLogger.Level.WARN, "Server", "a\nb"))
        assertEquals("INFO/T: " + "x".repeat(MAX_LOG_MESSAGE), logLine(KLogger.Level.INFO, "T", "x".repeat(MAX_LOG_MESSAGE + 5)))
    }

    @Test
    fun sha256HexIsLowerCaseHex() =
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", sha256Hex("hello"))

    @Test
    fun introspectCarriesIdVersionAndEndpoints() {
        val spec = Json.parseToJsonElement(Introspect.json()).jsonObject
        assertEquals("bayern.kickner.zoraxy.dyndns", spec["id"]?.jsonPrimitive?.content)
        val version = listOf("version_major", "version_minor", "version_patch").map { spec[it]!!.jsonPrimitive.int }
        assertEquals(BuildConfig.VERSION, version.joinToString("."))
        assertEquals(Introspect.CAPTURE_PATH, spec["static_capture_paths"]!!.jsonArray.single().jsonObject["capture_path"]?.jsonPrimitive?.content)
        assertEquals(4, spec["permitted_api_endpoints"]!!.jsonArray.size)
    }
}

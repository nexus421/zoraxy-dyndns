package bayern.kickner.zoraxy.dyndns

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ZoraxyApiTest {

    @Test
    fun accessListIsParsed() {
        val body = """[
            { "ID": "r1", "Name": "home-only", "WhitelistEnabled": true, "WhitelistAllowLocalAndLoopback": false,
              "WhiteListIP": { "87.176.150.207/32": "dyndns:fritzbox", "1.1.1.1": "office" },
              "WhiteListCountryCode": { "de": "" }, "BlacklistEnabled": false },
            { "ID": "default", "WhiteListIP": null, "WhiteListCountryCode": null }
        ]"""
        val rules = parseAccessRules(body)
        assertEquals(
            RuleSnapshot("r1", "home-only", true, false, mapOf("87.176.150.207/32" to "dyndns:fritzbox", "1.1.1.1" to "office"), mapOf("de" to "")),
            rules["r1"],
        )
        assertEquals(RuleSnapshot("default", "default", false, false, emptyMap(), emptyMap()), rules["default"])
    }

    @Test
    fun errorAnswerFails() {
        val failure = runCatching { parseAccessRules("""{ "error": "invalid api key" }""") }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("invalid api key"), failure.toString())
    }

    @Test
    fun garbageFails() = assertTrue(runCatching { parseAccessRules("<html>") }.isFailure)
}

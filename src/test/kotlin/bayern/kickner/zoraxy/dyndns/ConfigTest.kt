package bayern.kickner.zoraxy.dyndns

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigTest {

    private val hash = "a".repeat(64)

    private fun client(name: String = "fritzbox", h: String = hash, rules: String = """["r1"]""") =
        """{ "name": "$name", "password_sha256": "$h", "rules": $rules }"""

    private fun parsed(text: String) = Config.parse(text).getOrThrow()
    private fun rejected(text: String) = assertTrue(Config.parse(text).isFailure, text)

    @Test
    fun minimalConfigParses() {
        val c = parsed("""{ "clients": [ ${client()} ] }""")
        assertEquals(listOf(Client("fritzbox", hash, listOf("r1"))), c.clients)
        assertEquals("dyndns:fritzbox", c.clients.single().tag)
        assertEquals(setOf("r1"), c.ruleIds())
    }

    @Test
    fun emptyConfigIsAllowed() = assertEquals(emptyList(), parsed("""{ "clients": [] }""").clients)

    @Test
    fun defaultModeIsEnforceWithoutStatic() =
        assertEquals(RuleConfig(Mode.ENFORCE, emptyList()), parsed("""{ "clients": [ ${client()} ] }""").ruleConfig("r1"))

    @Test
    fun modesAreParsed() {
        val c = parsed("""{ "clients": [], "rules": {
            "a": { "mode": "enforce" }, "b": { "mode": "warn" }, "c": { "mode": "open_when_empty" } } }""")
        assertEquals(listOf(Mode.ENFORCE, Mode.WARN, Mode.OPEN_WHEN_EMPTY), listOf("a", "b", "c").map { c.ruleConfig(it).mode })
    }

    @Test
    fun unknownModeIsRejected() = rejected("""{ "clients": [], "rules": { "a": { "mode": "open" } } }""")

    @Test
    fun unknownKeyIsRejected() {
        rejected("""{ "clients": [], "rules": { "a": { "statics": ["10.8.0.1"] } } }""")
        rejected("""{ "clients": [ { "name": "x", "password_sha256": "$hash", "rules": ["r"], "password": "secret" } ] }""")
    }

    @Test
    fun invalidJsonIsRejected() {
        rejected("""{ "clients": [ """)
        rejected("""[]""")
        rejected("""{ "rules": {} }""")
    }

    @Test
    fun staticEntriesAreParsed() {
        val c = parsed("""{ "clients": [], "rules": { "a": { "static": ["10.8.0.0/24", "203.0.113.10"] } } }""")
        assertEquals(listOf("10.8.0.0/24", "203.0.113.10/32"), c.ruleConfig("a").static.map { it.toString() })
    }

    @Test
    fun invalidStaticEntryIsRejected() = rejected("""{ "clients": [], "rules": { "a": { "static": ["0.0.0.0/0"] } } }""")

    @Test
    fun ruleWithOnlyStaticEntriesIsManaged() =
        assertEquals(setOf("r1", "r2"), parsed("""{ "clients": [ ${client()} ], "rules": { "r2": { "static": ["10.8.0.1"] } } }""").ruleIds())

    @Test
    fun reservedNameStaticIsRejected() = rejected("""{ "clients": [ ${client(name = "static")} ] }""")

    @Test
    fun invalidNameIsRejected() = rejected("""{ "clients": [ ${client(name = "a b")} ] }""")

    @Test
    fun duplicateNamesAreRejected() = rejected("""{ "clients": [ ${client()}, ${client()} ] }""")

    @Test
    fun badHashIsRejected() = rejected("""{ "clients": [ ${client(h = "abc")} ] }""")

    @Test
    fun clientWithoutRulesIsRejected() = rejected("""{ "clients": [ ${client(rules = "[]")} ] }""")

    @Test
    fun passwordCheckUsesSha256() {
        val c = Client("x", sha256Hex("secret-password-123"), listOf("r"))
        assertTrue(c.checkPassword("secret-password-123"))
        assertTrue(c.checkPassword("wrong").not())
        assertTrue(Client("x", sha256Hex("pw").uppercase(), listOf("r")).checkPassword("pw"))
    }

    @Test
    fun missingFileGivesEmptyConfigAndWritesNothing() {
        val file = File(Files.createTempDirectory("config-test").toFile(), Config.FILE_NAME)
        assertEquals(emptyList(), Config.load(file).getOrThrow().clients)
        assertTrue(file.exists().not())
    }

    @Test
    fun dyndnsHostIsOptionalAndValidated() {
        assertEquals(null, parsed("""{ "clients": [] }""").dyndnsHost)
        assertEquals("dyndns.example.com:8443", parsed("""{ "clients": [], "dyndns_host": "dyndns.example.com:8443" }""").dyndnsHost)
        listOf("https://x.example", "x.example/path", "a b", "").forEach { rejected("""{ "clients": [], "dyndns_host": "$it" }""") }
    }

    @Test
    fun toJsonRoundTrips() {
        val c = Config(
            listOf(Client("fritz", hash, listOf("r1", "r2")), Client("office", "b".repeat(64), listOf("r1"))),
            mapOf("r2" to RuleConfig(Mode.OPEN_WHEN_EMPTY, listOf(Cidr.parseStatic("10.8.0.0/24").getOrThrow()))),
            dyndnsHost = "dyndns.example.com",
        )
        assertEquals(c, parsed(c.toJson()))
    }

    @Test
    fun toJsonLeavesOutAMissingHost() = assertTrue("dyndns_host" !in Config(emptyList(), emptyMap()).toJson())

    @Test
    fun unfinishedTemplateOfVersion01StartsEmpty() {
        val file = File(Files.createTempDirectory("config-test").toFile(), Config.FILE_NAME)
        file.writeText(
            """
            {
              "clients": [
                {
                  "name": "fritzbox",
                  "password_sha256": "<output of: ./zoraxy-dyndns -hash>",
                  "rules": ["<access rule id from Zoraxy>"]
                }
              ],
              "rules": {
                "<access rule id from Zoraxy>": { "mode": "enforce", "static": [] }
              }
            }
            """.trimIndent() + "\n"
        )
        assertEquals(Config(emptyList(), emptyMap()), Config.load(file).getOrThrow())
    }

    @Test
    fun dyndnsHostPortMustBeAPort() {
        assertEquals("d.example:65535", parsed("""{ "clients": [], "dyndns_host": "d.example:65535" }""").dyndnsHost)
        listOf("d.example:99999", "d.example:0").forEach { rejected("""{ "clients": [], "dyndns_host": "$it" }""") }
    }
}

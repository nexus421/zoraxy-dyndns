package bayern.kickner.zoraxy.dyndns

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
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
    fun missingFileWritesAnOwnerOnlyTemplateThatDoesNotParse() {
        val file = File(Files.createTempDirectory("config-test").toFile(), Config.FILE_NAME)
        assertEquals(emptyList(), Config.load(file).getOrThrow().clients)
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath())))
        // An unedited template must stop the plugin, not run it with placeholder rules
        assertTrue(Config.load(file).isFailure)
    }
}

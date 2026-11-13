package bayern.kickner.zoraxy.dyndns

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals

class StateTest {

    private fun tempFile(): File = Files.createTempDirectory("state-test").toFile().resolve(State.FILE_NAME)

    private val sample = State(
        clients = mapOf(
            "fritzbox" to ClientState(
                listOf(Cidr.parse("87.176.150.207").getOrThrow(), Cidr.parse("2003:d5:df4a:a500::/56").getOrThrow()),
                1_794_477_600_000,
            ),
        ),
        managedRules = setOf("r1", "r2"),
    )

    @Test
    fun roundTrip() {
        val f = tempFile()
        sample.save(f)
        assertEquals(sample, State.load(f))
    }

    @Test
    fun fileIsOwnerOnly() {
        val f = tempFile()
        sample.save(f)
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(f.toPath())))
    }

    @Test
    fun missingFileGivesEmptyState() = assertEquals(State.EMPTY, State.load(tempFile()))

    @Test
    fun corruptFileGivesEmptyState() {
        val f = tempFile()
        f.writeText("{ not json")
        assertEquals(State.EMPTY, State.load(f))
    }

    @Test
    fun invalidEntryDropsOnlyThatClient() {
        val f = tempFile()
        f.writeText(
            """{ "clients": {
            "good": { "addresses": ["87.176.150.207/32"], "updated_at": "2026-11-12T10:00:00Z" },
            "bad":  { "addresses": ["192.168.1.1/32"],  "updated_at": "2026-11-12T10:00:00Z" },
            "late": { "addresses": ["87.176.150.208/32"], "updated_at": "yesterday" } },
            "managed_rules": ["r1"] }"""
        )
        val s = State.load(f)
        assertEquals(setOf("good"), s.clients.keys)
        assertEquals(setOf("r1"), s.managedRules)
    }

    @Test
    fun saveLeavesNoTempFile() {
        val f = tempFile()
        sample.save(f)
        sample.save(f)
        assertEquals(listOf(State.FILE_NAME), f.parentFile.list()!!.toList())
    }
}

package bayern.kickner.zoraxy.dyndns

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigStoreTest {

    private val file = File(Files.createTempDirectory("store-test").toFile(), Config.FILE_NAME)
    private val start = Config(listOf(Client("fritz", "a".repeat(64), listOf("r1"))), emptyMap())

    @Test
    fun updateWritesOwnerOnlyAndSwaps() {
        val store = ConfigStore(file, start)
        assertTrue(store.update { it.copy(dyndnsHost = "a.example") }.isSuccess)
        assertEquals("a.example", store.current().dyndnsHost)
        assertEquals("a.example", Config.parse(file.readText()).getOrThrow().dyndnsHost)
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath())))
    }

    @Test
    fun invalidUpdateChangesNothing() {
        val store = ConfigStore(file, start)
        store.update { it }.getOrThrow()
        val before = file.readBytes()
        val result = store.update { it.copy(clients = it.clients + Client("a b", "x", listOf("r"))) }
        assertTrue(result.isFailure)
        assertTrue(before.contentEquals(file.readBytes()))
        assertEquals(start, store.current())
    }
}

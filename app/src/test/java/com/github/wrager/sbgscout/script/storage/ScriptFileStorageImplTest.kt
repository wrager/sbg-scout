package com.github.wrager.sbgscout.script.storage

import com.github.wrager.sbgscout.script.model.ScriptIdentifier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Unit-тесты [ScriptFileStorageImpl] — покрывают обе ветки `file.exists()` в
 * [readContent] и [deleteContent], санитизацию имени файла в [fileFor], и
 * init-блок, создающий директорию, если её ещё нет.
 */
class ScriptFileStorageImplTest {

    private lateinit var tempDir: File
    private lateinit var storage: ScriptFileStorageImpl

    @Before
    fun setUp() {
        tempDir = createTempDirectory("script-file-storage-test")
        storage = ScriptFileStorageImpl(tempDir)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `readContent returns content after writeContent`() {
        val identifier = ScriptIdentifier("test/script")
        storage.writeContent(identifier, "script body")

        assertEquals("script body", storage.readContent(identifier))
    }

    @Test
    fun `readContent returns null when file does not exist`() {
        // Покрывает ветку `if (file.exists())` = false в readContent.
        assertNull(storage.readContent(ScriptIdentifier("missing/script")))
    }

    @Test
    fun `deleteContent removes file when it exists`() {
        val identifier = ScriptIdentifier("test/delete")
        storage.writeContent(identifier, "to delete")
        assertEquals("to delete", storage.readContent(identifier))

        storage.deleteContent(identifier)

        assertNull(storage.readContent(identifier))
    }

    @Test
    fun `deleteContent does nothing when file does not exist`() {
        // Покрывает ветку `if (file.exists())` = false в deleteContent.
        // Не должно бросать исключение.
        storage.deleteContent(ScriptIdentifier("non/existent"))
        // Падения нет — тест проходит.
        assertTrue(tempDir.exists())
    }

    @Test
    fun `sanitizes identifier with slashes and special chars in filename`() {
        val identifier = ScriptIdentifier("github.com/owner/repo/Name With Spaces")
        storage.writeContent(identifier, "x")

        val files = tempDir.listFiles().orEmpty()
        assertEquals(1, files.size)
        // Точки, буквы, цифры, дефис, подчёркивание остаются — всё прочее
        // заменяется на `_`.
        assertTrue(
            "file='${files[0].name}' должен содержать sanitized identifier",
            files[0].name == "github.com_owner_repo_Name_With_Spaces.user.js",
        )
    }

    @Test
    fun `init creates scripts directory when missing`() {
        // Покрывает ветку `if (!scriptsDirectory.exists())` = true в init.
        val subDir = File(tempDir, "nested/dir")
        assertFalse(subDir.exists())
        ScriptFileStorageImpl(subDir)
        assertTrue("директория должна быть создана", subDir.exists())
    }

    private fun createTempDirectory(prefix: String): File {
        val dir = File.createTempFile(prefix, null)
        dir.delete()
        dir.mkdirs()
        return dir
    }
}

package app.rustmfa.android.vault

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import app.rustmfa.vault.BackupFormatException
import java.io.File
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import org.junit.Test

class AndroidBackupFilesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val files = AndroidBackupFiles(context.contentResolver)
    private val dir = File(context.cacheDir, "backup-test").apply { mkdirs() }

    @AfterTest fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test fun writeThenReadRoundTrips() = runBlocking {
        val uri = Uri.fromFile(File(dir, "round.json"))
        val bytes = "{\"app\":\"rostdav-mfa\"}".toByteArray()
        files.write(uri, bytes)
        assertContentEquals(bytes, files.read(uri, 1024))
    }

    @Test fun aSecondWriteReplacesTheFileInsteadOfLeavingATail() = runBlocking {
        val uri = Uri.fromFile(File(dir, "twice.json"))
        files.write(uri, ByteArray(100) { 'a'.code.toByte() })
        files.write(uri, "short".toByteArray())
        assertEquals("short", String(files.read(uri, 1024)))
    }

    @Test fun deleteRemovesAWrittenFile() = runBlocking {
        val file = File(dir, "partial.json")
        val uri = Uri.fromFile(file)
        files.write(uri, "half".toByteArray())
        files.delete(uri)
        kotlin.test.assertFalse(file.exists(), "the partial backup was left behind")
    }

    @Test fun deleteOfAMissingOrForeignUriDoesNotThrow() = runBlocking {
        files.delete(Uri.fromFile(File(dir, "never.json")))
        files.delete(Uri.parse("content://app.rustmfa.nothing/document/1"))
    }

    @Test fun aFileOverTheCapThrowsBackupFormatException() {
        val file = File(dir, "big.json").apply { writeBytes(ByteArray(2048)) }
        assertFailsWith<BackupFormatException> { runBlocking { files.read(Uri.fromFile(file), 1024) } }
    }

    @Test fun aFileOfExactlyTheCapIsRead() = runBlocking {
        val file = File(dir, "exact.json").apply { writeBytes(ByteArray(1024) { 7 }) }
        assertEquals(1024, files.read(Uri.fromFile(file), 1024).size)
    }

    @Test fun readingAMissingFileThrowsIOException() {
        val uri = Uri.fromFile(File(dir, "missing.json"))
        assertFailsWith<IOException> { runBlocking { files.read(uri, 1024) } }
    }
}

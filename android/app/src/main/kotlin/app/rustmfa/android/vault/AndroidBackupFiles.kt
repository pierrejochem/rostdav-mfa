package app.rustmfa.android.vault

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import app.rustmfa.vault.BackupFormatException
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Backup files behind the system file picker (`content:` URIs; `file:` URIs work too). A read checks the size the
 * provider declares first and then caps the read itself, since a provider may not know or may misreport the size.
 * Everything that is not a too-large file comes out as an [IOException] (a revoked grant is a SecurityException).
 */
class AndroidBackupFiles(
    private val resolver: ContentResolver,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : BackupFiles {
    override suspend fun read(uri: Uri, maxBytes: Int): ByteArray =
        withContext(io) {
            fileErrors {
                val declared = declaredSize(uri)
                if (declared != null && declared > maxBytes) throw BackupFormatException(FILE_TOO_LARGE)
                val input = resolver.openInputStream(uri) ?: throw IOException("the file cannot be opened")
                input.use { readCapped(it, maxBytes) }
            }
        }

    override suspend fun write(uri: Uri, bytes: ByteArray) =
        withContext(io) {
            fileErrors {
                // "wt": an existing file is truncated, so a shorter backup leaves no tail of an older one
                val output = resolver.openOutputStream(uri, "wt") ?: throw IOException("the file cannot be opened")
                output.use {
                    it.write(bytes)
                    it.flush()
                }
            }
        }

    /** The provider's size, or the file descriptor's; null when neither knows. */
    private fun declaredSize(uri: Uri): Long? {
        val fromQuery = try {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
        } catch (e: Exception) {
            null
        }
        if (fromQuery != null) return fromQuery
        return try {
            resolver.openAssetFileDescriptor(uri, "r")?.use { it.length.takeIf { length -> length >= 0 } }
        } catch (e: Exception) {
            null // the open below reports the real problem
        }
    }

    private inline fun <T> fileErrors(block: () -> T): T =
        try {
            block()
        } catch (e: SecurityException) {
            throw IOException("no access to the file", e)
        } catch (e: IllegalArgumentException) {
            throw IOException("not a usable file", e)
        } catch (e: IllegalStateException) {
            throw IOException("the file provider failed", e)
        }
}

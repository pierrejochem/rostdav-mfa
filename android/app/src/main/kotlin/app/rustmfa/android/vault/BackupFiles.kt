package app.rustmfa.android.vault

import android.net.Uri
import app.rustmfa.vault.BackupFormatException
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** The [BackupFormatException] message for a file over the read cap. */
const val FILE_TOO_LARGE = "file too large"

interface BackupFiles {
    /**
     * Reads the file, failing with [BackupFormatException] ([FILE_TOO_LARGE]) when it is larger than [maxBytes]
     * and with an `IOException` when it cannot be read.
     */
    suspend fun read(uri: Uri, maxBytes: Int): ByteArray

    /** Writes [bytes] as the whole file and flushes; an `IOException` when it cannot. */
    suspend fun write(uri: Uri, bytes: ByteArray)

    /** Deletes the file (a backup that could not be written); best effort, the caller ignores a failure. */
    suspend fun delete(uri: Uri)
}

/** Reads [input] to the end, but stops with [FILE_TOO_LARGE] as soon as more than [maxBytes] came in. */
fun readCapped(input: InputStream, maxBytes: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var total = 0L
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        total += n
        if (total > maxBytes) throw BackupFormatException(FILE_TOO_LARGE)
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

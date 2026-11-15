package app.rustmfa.android.vault

import android.net.Uri

interface BackupFiles {
    /** Reads the file, failing when it is larger than [maxBytes]. */
    suspend fun read(uri: Uri, maxBytes: Int): ByteArray

    suspend fun write(uri: Uri, bytes: ByteArray)
}

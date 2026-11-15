package app.rustmfa.android.vault

interface ClipboardWriter {
    /** Copies [text] as a sensitive clip (hidden from the clipboard preview). */
    fun copySensitive(text: String)

    /** Clears the clipboard if it still holds [text]; best effort. */
    fun clearIfStillOurs(text: String)
}

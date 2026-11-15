package app.rustmfa.android.vault

import android.net.Uri
import app.rustmfa.android.R
import app.rustmfa.vault.BackupDecryptException
import app.rustmfa.vault.BackupFormatException
import app.rustmfa.vault.ImportResult
import app.rustmfa.vault.OtpAccount
import app.rustmfa.vault.OtpType
import app.rustmfa.vault.VaultAuthRequiredException
import app.rustmfa.vault.VaultBackup
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class BackupViewModelTest {
    private val main = UnconfinedTestDispatcher()

    /** The `io` dispatcher on its own scheduler: nothing runs there until the test advances it. */
    private val ioScheduler = TestCoroutineScheduler()
    private val io = StandardTestDispatcher(ioScheduler)

    private val now = 1_700_000_000_000L
    private val gateway = FakeVaultGateway { now }
    private val files = FakeFiles()
    private val prefs = FakePrefs()

    private val github = OtpAccount("", OtpType.TOTP, "GitHub", "alice", "12345678901234567890".toByteArray())
    private val mail = OtpAccount("", OtpType.HOTP, "Mail", "bob", "abcdefghijabcdefghij".toByteArray(), counter = 3)

    private var exports = 0
    private var decrypts = 0

    @BeforeTest fun setUp() = Dispatchers.setMain(main)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakePrefs : VaultPrefs {
        override var lastBackupAt: Long? = null
        override var nudgeHiddenUntil: Long = 0
    }

    /** The file behind the picker: [content] (or a generated stream of [generatedSize] bytes) is read through the real cap. */
    private class FakeFiles : BackupFiles {
        var written: ByteArray? = null
        var content: ByteArray = ByteArray(0)
        var generatedSize: Long? = null
        var bytesRead = 0L
        var reads = 0
        var failRead: IOException? = null

        override suspend fun read(uri: Uri, maxBytes: Int): ByteArray {
            reads++
            failRead?.let { throw it }
            val size = generatedSize
            val source: InputStream = if (size == null) content.inputStream() else GeneratedStream(size)
            val counting = object : InputStream() {
                override fun read(): Int = source.read().also { if (it >= 0) bytesRead++ }

                override fun read(b: ByteArray, off: Int, len: Int): Int = source.read(b, off, len).also { if (it > 0) bytesRead += it }
            }
            return readCapped(counting, maxBytes)
        }

        override suspend fun write(uri: Uri, bytes: ByteArray) {
            written = bytes.copyOf()
        }
    }

    private class GeneratedStream(private var left: Long) : InputStream() {
        override fun read(): Int = if (left-- > 0) 'x'.code else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = minOf(len.toLong(), left).toInt()
            b.fill('x'.code.toByte(), off, off + n)
            left -= n
            return n
        }
    }

    private fun viewModel() =
        BackupViewModel(
            gateway, files, prefs, { now }, io,
            exporter = { a, p -> exports++; VaultBackup.export(a, p) },
            decrypter = { b, p -> decrypts++; VaultBackup.import(b, p) },
        )

    /** Runs the work queued on `io`, then whatever resumes on main. */
    private fun TestScope.drain() {
        repeat(4) {
            ioScheduler.advanceUntilIdle()
            runCurrent()
        }
    }

    private suspend fun openVault(vararg accounts: OtpAccount) {
        gateway.stored = emptyList()
        gateway.session.open()
        if (accounts.isNotEmpty()) gateway.session.import(accounts.map { it.copy(secret = it.secret.copyOf()) })
        gateway.calls.clear()
    }

    private fun CharArray.isZeroed() = all { it == '\u0000' }

    @Test fun `a passphrase of seven characters fails, writes nothing and never derives`() = runTest(main) {
        openVault(github)
        val vm = viewModel()
        val pass = "abcdefg".toCharArray()
        vm.create(pass, "abcdefg".toCharArray(), fakeUri())
        drain()
        assertEquals(BackupState.Failed(R.string.backup_error_short), vm.state.value)
        assertNull(files.written)
        assertEquals(0, exports)
        assertTrue(gateway.calls.isEmpty(), "nothing touched the vault: ${gateway.calls}")
        assertTrue(pass.isZeroed())
    }

    @Test fun `a mismatch fails, writes nothing and never derives`() = runTest(main) {
        openVault(github)
        val vm = viewModel()
        val repeat = "correct horse!".toCharArray()
        vm.create("correct horse".toCharArray(), repeat, fakeUri())
        drain()
        assertEquals(BackupState.Failed(R.string.backup_error_mismatch), vm.state.value)
        assertNull(files.written)
        assertEquals(0, exports)
        assertTrue(repeat.isZeroed())
    }

    @Test fun `a valid create exports on io, writes the exported bytes, records the date and ends Saved`() = runTest(main) {
        openVault(github, mail)
        val vm = viewModel()
        val pass = "correct horse".toCharArray()
        val repeat = "correct horse".toCharArray()
        vm.create(pass, repeat, fakeUri())
        runCurrent()
        // the main dispatcher has done all it can: the export waits for io
        assertEquals(BackupState.Working, vm.state.value)
        assertEquals(0, exports, "the export ran on the main dispatcher")
        assertNull(files.written)
        drain()
        assertEquals(1, exports)
        assertEquals(BackupState.Saved, vm.state.value)
        val back = VaultBackup.import(assertNotNull(files.written), "correct horse".toCharArray())
        assertEquals(listOf("GitHub" to "alice", "Mail" to "bob"), back.map { it.issuer to it.label })
        assertContentEquals(github.secret, back[0].secret)
        assertContentEquals(mail.secret, back[1].secret)
        assertEquals(now, prefs.lastBackupAt)
        assertTrue(pass.isZeroed() && repeat.isZeroed(), "the passphrase arrays were not zeroed")
    }

    @Test fun `create while locked unlocks first, and a cancelled prompt ends Idle with no file`() = runTest(main) {
        gateway.stored = emptyList()
        gateway.unlockFailures += VaultAuthRequiredException()
        val vm = viewModel()
        val pass = "correct horse".toCharArray()
        vm.create(pass, "correct horse".toCharArray(), fakeUri())
        drain()
        assertEquals(listOf("unlock"), gateway.calls)
        assertEquals(BackupState.Idle, vm.state.value)
        assertNull(files.written)
        assertNull(prefs.lastBackupAt)
        assertEquals(0, exports)
        assertTrue(pass.isZeroed())
    }

    @Test fun `create while locked unlocks and then saves`() = runTest(main) {
        gateway.stored = emptyList()
        val vm = viewModel()
        vm.create("correct horse".toCharArray(), "correct horse".toCharArray(), fakeUri())
        drain()
        assertEquals("unlock", gateway.calls.first())
        assertEquals(BackupState.Saved, vm.state.value)
        assertNotNull(files.written)
    }

    @Test fun `a write failure gives the file message and records no date`() = runTest(main) {
        openVault(github)
        val failing = object : BackupFiles {
            override suspend fun read(uri: Uri, maxBytes: Int): ByteArray = error("unused")

            override suspend fun write(uri: Uri, bytes: ByteArray) = throw IOException("disk full")
        }
        val vm = BackupViewModel(gateway, failing, prefs, { now }, io)
        vm.create("correct horse".toCharArray(), "correct horse".toCharArray(), fakeUri())
        drain()
        assertEquals(BackupState.Failed(R.string.backup_error_file), vm.state.value)
        assertNull(prefs.lastBackupAt)
    }

    @Test fun `restore of a valid file imports and reports added and skipped`() = runTest(main) {
        openVault(github)
        files.content = backupOfGithubAndMail
        val vm = viewModel()
        val pass = "correct horse".toCharArray()
        vm.restore(fakeUri(), pass)
        runCurrent()
        assertEquals(BackupState.Working, vm.state.value)
        assertEquals(0, decrypts, "the decryption ran on the main dispatcher")
        drain()
        assertEquals(BackupState.Restored(ImportResult(added = 1, skipped = 1)), vm.state.value)
        assertEquals(listOf("GitHub", "Mail"), gateway.session.accounts().map { it.issuer })
        assertTrue(pass.isZeroed())
    }

    @Test fun `a wrong passphrase gives the decrypt message and imports nothing`() = runTest(main) {
        openVault(github)
        files.content = backupOfGithubAndMail
        val vm = viewModel()
        vm.restore(fakeUri(), "wrong horse".toCharArray())
        drain()
        assertEquals(BackupState.Failed(R.string.backup_error_decrypt), vm.state.value)
        assertFalse("import" in gateway.calls)
    }

    @Test fun `a file of 6 MiB is rejected before all of it is read`() = runTest(main) {
        openVault(github)
        files.generatedSize = 6L * 1024 * 1024
        val vm = viewModel()
        vm.restore(fakeUri(), "correct horse".toCharArray())
        drain()
        assertEquals(BackupState.Failed(R.string.backup_error_format), vm.state.value)
        assertTrue(files.bytesRead <= MAX_BACKUP_BYTES + 64 * 1024, "read ${files.bytesRead} bytes")
        assertEquals(0, decrypts)
        assertFalse("import" in gateway.calls)
    }

    @Test fun `restore while locked with a cancelled prompt ends Idle and reads nothing`() = runTest(main) {
        gateway.stored = emptyList()
        gateway.unlockFailures += VaultAuthRequiredException()
        files.content = backupOfGithubAndMail
        val vm = viewModel()
        vm.restore(fakeUri(), "correct horse".toCharArray())
        drain()
        assertEquals(BackupState.Idle, vm.state.value)
        assertEquals(0, files.reads)
    }

    @Test fun `a read failure gives the file message`() = runTest(main) {
        openVault(github)
        files.failRead = FileNotFoundException("gone")
        val vm = viewModel()
        vm.restore(fakeUri(), "correct horse".toCharArray())
        drain()
        assertEquals(BackupState.Failed(R.string.backup_error_file), vm.state.value)
    }

    @Test fun `the file name is the UTC date`() {
        assertEquals("rostdav-mfa-backup-2023-11-14.json", backupFileName(1_700_000_000_000))
        // 23:59:59.999 UTC on 2023-11-14 is still that day
        assertEquals("rostdav-mfa-backup-2023-11-14.json", backupFileName(1_700_006_399_999))
        assertEquals("rostdav-mfa-backup-2023-11-15.json", backupFileName(1_700_006_400_000))
    }

    @Test fun `backupMessage maps each exception class`() {
        assertEquals(R.string.backup_error_memory, backupMessage(BackupFormatException(VaultBackup.KDF_OUT_OF_MEMORY)))
        assertEquals(R.string.backup_error_format, backupMessage(BackupFormatException("not a rostdav-mfa backup")))
        assertEquals(R.string.backup_error_format, backupMessage(BackupFormatException("file too large")))
        assertEquals(R.string.backup_error_decrypt, backupMessage(BackupDecryptException()))
        assertEquals(R.string.backup_error_file, backupMessage(IOException("x")))
        assertEquals(R.string.backup_error_file, backupMessage(FileNotFoundException("x")))
        assertEquals(NO_MESSAGE, backupMessage(VaultAuthRequiredException()))
        assertEquals(R.string.vault_error_generic, backupMessage(IllegalStateException("x")))
    }

    companion object {
        /** One real export (the key derivation takes a moment), shared by the restore tests. */
        private val backupOfGithubAndMail: ByteArray by lazy {
            VaultBackup.export(
                listOf(
                    OtpAccount("", OtpType.TOTP, "GitHub", "alice", "12345678901234567890".toByteArray()),
                    OtpAccount("", OtpType.HOTP, "Mail", "bob", "abcdefghijabcdefghij".toByteArray(), counter = 3),
                ),
                "correct horse".toCharArray(),
            )
        }

        /**
         * A [Uri] for the fakes, which never look at it. The JVM's android.jar stub cannot build one (Uri is abstract
         * and its constructor private), so this defines an empty subclass with no constructor at all and allocates it
         * without running one. Calling any method on it would fail; nothing does.
         */
        fun fakeUri(): Uri = fakeUriClass.let { unsafeAllocate(it) as Uri }

        private val fakeUriClass: Class<*> by lazy {
            fun utf8(s: String): ByteArray = byteArrayOf(1, (s.length shr 8).toByte(), s.length.toByte()) + s.toByteArray()
            val bytes = java.io.ByteArrayOutputStream().apply {
                val d = java.io.DataOutputStream(this)
                d.writeInt(0xCAFEBABE.toInt())
                d.writeShort(0) // minor
                d.writeShort(52) // Java 8 class file
                d.writeShort(5) // constant pool count + 1
                d.write(byteArrayOf(7, 0, 2)) // #1 Class #2
                d.write(utf8("app/rustmfa/android/vault/FakeUri")) // #2
                d.write(byteArrayOf(7, 0, 4)) // #3 Class #4
                d.write(utf8("android/net/Uri")) // #4
                d.writeShort(0x0021) // public super
                d.writeShort(1) // this
                d.writeShort(3) // super
                repeat(4) { d.writeShort(0) } // interfaces, fields, methods, attributes
            }.toByteArray()
            object : ClassLoader(Uri::class.java.classLoader) {
                fun define(): Class<*> = defineClass("app.rustmfa.android.vault.FakeUri", bytes, 0, bytes.size)
            }.define()
        }

        private fun unsafeAllocate(c: Class<*>): Any {
            val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }
            val unsafe = field.get(null)
            return unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, c)
        }
    }
}

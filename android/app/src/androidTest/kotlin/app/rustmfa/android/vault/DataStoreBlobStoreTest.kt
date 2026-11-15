package app.rustmfa.android.vault

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import app.rustmfa.vault.VaultCorruptException
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DataStoreBlobStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val a = DataStoreBlobStore(context, "test_blob_a")
    private val b = DataStoreBlobStore(context, "test_blob_b")

    @BeforeTest fun clean() = runBlocking { a.clear(); b.clear() }
    @AfterTest fun cleanUp() = runBlocking { a.clear(); b.clear() }

    @Test
    fun readReturnsNullAtFirst() = runBlocking {
        assertNull(a.read())
    }

    @Test
    fun writeThenReadReturnsTheSameBytes() = runBlocking {
        val bytes = ByteArray(300) { it.toByte() }
        a.write(bytes)
        assertContentEquals(bytes, a.read())
    }

    @Test
    fun clearThenReadReturnsNull() = runBlocking {
        a.write(byteArrayOf(1, 2, 3))
        a.clear()
        assertNull(a.read())
    }

    @Test
    fun storesWithDifferentNamesDoNotShareData() = runBlocking {
        a.write(byteArrayOf(1, 2, 3))
        assertNull(b.read())
        b.write(byteArrayOf(9))
        assertContentEquals(byteArrayOf(1, 2, 3), a.read())
        assertContentEquals(byteArrayOf(9), b.read())
    }

    @Test
    fun aSecondInstanceWithTheSameNameSeesTheSameData() = runBlocking {
        a.write(byteArrayOf(4, 5))
        assertContentEquals(byteArrayOf(4, 5), DataStoreBlobStore(context, "test_blob_a").read())
    }

    /**
     * A store whose file holds [content] before the process first opens it. DataStore reads the file once per
     * process, so each call uses a new name; the file is deleted after the test.
     */
    private fun storeOverFile(content: ByteArray): DataStoreBlobStore {
        val name = "test_blob_damaged_${System.nanoTime()}"
        val file = context.preferencesDataStoreFile(name)
        file.parentFile!!.mkdirs()
        file.writeBytes(content)
        damagedFiles += file
        return DataStoreBlobStore(context, name)
    }

    private val damagedFiles = mutableListOf<java.io.File>()

    @AfterTest fun deleteDamaged() { damagedFiles.forEach { it.delete() } }

    @Test
    fun aDamagedFileIsCorruptAndClearRecoversIt() = runBlocking {
        val store = storeOverFile(byteArrayOf(0x7F, 0x13, 0x00, 0x55, 0xAA.toByte(), 0x01))
        assertFailsWith<VaultCorruptException> { store.read() }
        assertFailsWith<VaultCorruptException> { store.read() } // stays corrupt, never an empty vault
        store.clear()
        assertNull(store.read())
        store.write(byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(1, 2, 3), store.read())
    }

    @Test
    fun writeOverADamagedFileReplacesIt() = runBlocking {
        val store = storeOverFile(ByteArray(40) { 0x5A })
        store.write(byteArrayOf(8, 9))
        assertContentEquals(byteArrayOf(8, 9), store.read())
    }

    @Test
    fun aValueThatIsNotBase64IsCorrupt() = runBlocking {
        val name = "test_blob_badb64_${System.nanoTime()}"
        damagedFiles += context.preferencesDataStoreFile(name)
        val raw = PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(name) }
        raw.updateData { preferencesOf(stringPreferencesKey("blob") to "not base64 !!") }
        val store = DataStoreBlobStore(raw)
        assertFailsWith<VaultCorruptException> { store.read() }
        store.clear()
        assertNull(store.read())
    }
}

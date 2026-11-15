package app.rustmfa.android.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
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
}

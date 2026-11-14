package app.rustmfa.android.enroll

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.rustmfa.android.net.PushChannel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DataStoreDeviceStoreInstrumentedTest {
    private val store = DataStoreDeviceStore(InstrumentationRegistry.getInstrumentation().targetContext)

    @BeforeTest fun clean() = runBlocking { store.clear() }

    @AfterTest fun cleanUp() = runBlocking { store.clear() }

    @Test
    fun nothingIsStoredAtFirst() = runBlocking {
        assertNull(store.load())
    }

    @Test
    fun saveThenLoadGivesTheSameEnrollment() = runBlocking {
        val e = Enrollment("https://rd.example/", "dev_1", PushChannel.None, "")
        store.save(e)
        assertEquals(e, store.load())
    }

    @Test
    fun clearForgetsTheEnrollment() = runBlocking {
        store.save(Enrollment("https://rd.example/", "dev_1", PushChannel.Fcm, "tok"))
        store.clear()
        assertNull(store.load())
    }
}

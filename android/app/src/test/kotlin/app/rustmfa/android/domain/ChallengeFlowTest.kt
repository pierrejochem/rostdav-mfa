package app.rustmfa.android.domain

import app.rustmfa.android.crypto.ApprovalSigner
import app.rustmfa.android.crypto.FakeDeviceKey
import app.rustmfa.android.crypto.KeyInvalidatedException
import app.rustmfa.android.net.ApiError
import app.rustmfa.android.net.ChallengeDetails
import app.rustmfa.android.net.ChallengeStatus
import app.rustmfa.android.net.Clock
import app.rustmfa.android.net.ErrorCode
import app.rustmfa.android.net.StubApi
import app.rustmfa.contract.Base64Url
import app.rustmfa.contract.Canonical
import app.rustmfa.contract.ChallengeFields
import app.rustmfa.contract.Signature
import java.io.IOException
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class ChallengeFlowTest {
    private val expires = "2026-11-14T12:00:30Z"
    private val expiresMs = Instant.parse(expires).toEpochMilli()

    private class FakeClock(var now: Long) : Clock {
        override fun nowMillis() = now
    }

    /** Time only moves when the flow waits: the delay advances the clock. */
    private class FakeDelayer(val clock: FakeClock) : Delayer {
        val waits = mutableListOf<Long>()
        override suspend fun delay(ms: Long) {
            waits += ms
            clock.now += ms
        }
    }

    private class FakeApi(val details: ChallengeDetails, val clock: FakeClock) : StubApi() {
        var getResult: () -> ChallengeDetails = { details }
        var approveResult: (Int) -> Unit = {}
        var denyResult: () -> Unit = {}
        val approves = mutableListOf<Triple<String, Int, String>>()
        val approveTimes = mutableListOf<Long>()
        val denies = mutableListOf<Pair<String, String>>()
        override suspend fun getChallenge(id: String) = getResult()
        override suspend fun approve(id: String, selectedNumber: Int, signature: String) {
            approves += Triple(id, selectedNumber, signature)
            approveTimes += clock.now
            approveResult(approves.size)
        }
        override suspend fun deny(id: String, signature: String) {
            denies += id to signature
            denyResult()
        }
    }

    private val key = FakeDeviceKey().also { it.createKeys("tok") }
    private val clock = FakeClock(Instant.parse("2026-11-14T12:00:00Z").toEpochMilli())
    private val delayer = FakeDelayer(clock)
    private val details = ChallengeDetails(
        challengeId = "ch_1", serviceName = "Rostdav", loginDevice = "Firefox", createdAt = "2026-11-14T11:59:30Z",
        expiresAt = expires, status = ChallengeStatus.Pending, candidateNumbers = listOf(12, 47, 83),
        nonce = "n0nce", loginId = "login_1", audience = "rostdav", approximateLocation = null,
    )
    private val api = FakeApi(details, clock)

    private fun flow() = ChallengeFlow(api, key, "dev_1", clock, delayer)

    private val auth: suspend (ApprovalSigner, ByteArray) -> ByteArray = { signer, msg -> signer.finish(signer.cryptoObject(), msg) }

    private fun canonical() = Canonical.message(ChallengeFields("ch_1", "n0nce", "login_1", "dev_1", expires, "rostdav"))

    private fun assertSigned(sigB64: String) {
        Signature.verifyEs256Raw(Base64.getDecoder().decode(key.approvalPublicKeyB64()), canonical().toByteArray(), Base64Url.decode(sigB64))
    }

    @Test
    fun approveSendsThePickedNumberAndTheSignatureOfTheCanonicalMessage() = runBlocking {
        val f = flow()
        f.load("ch_1")
        assertIs<ChallengeState.Pending>(f.state.value)
        f.approve(47, auth)
        assertEquals(ChallengeState.Approved, f.state.value)
        val (id, n, sig) = api.approves.single()
        assertEquals("ch_1", id)
        assertEquals(47, n)
        assertSigned(sig)
    }

    @Test
    fun denySendsNoNumberButTheSameSignedMessage() = runBlocking {
        val f = flow()
        f.load("ch_1")
        f.deny(auth)
        assertEquals(ChallengeState.Denied, f.state.value)
        assertEquals(0, api.approves.size)
        assertSigned(api.denies.single().second)
    }

    @Test
    fun aNumberOtherThanTheServersEndsAsMismatch() = runBlocking {
        api.approveResult = { throw ApiError(ErrorCode.CHALLENGE_NUMBER_MISMATCH, 403, "x") }
        val f = flow(); f.load("ch_1"); f.approve(12, auth)
        assertEquals(ChallengeState.NumberMismatch, f.state.value)
    }

    @Test
    fun expiredCodeEndsAsExpired() = runBlocking {
        api.approveResult = { throw ApiError(ErrorCode.CHALLENGE_EXPIRED, 410, "x") }
        val f = flow(); f.load("ch_1"); f.approve(12, auth)
        assertEquals(ChallengeState.Expired, f.state.value)
    }

    @Test
    fun alreadyConsumedIsFinalAndNothingIsRetried() = runBlocking {
        api.approveResult = { throw ApiError(ErrorCode.CHALLENGE_ALREADY_CONSUMED, 409, "x") }
        val f = flow(); f.load("ch_1"); f.approve(12, auth)
        assertEquals(ChallengeState.AlreadyHandled, f.state.value)
        assertEquals(1, api.approves.size)
        assertEquals(emptyList(), delayer.waits)
    }

    @Test
    fun aSecondApproveWhileTheFirstIsRunningSendsNothing() = runBlocking {
        val f = flow(); f.load("ch_1")
        val gate = CompletableDeferred<Unit>()
        val slow: suspend (ApprovalSigner, ByteArray) -> ByteArray = { s, m -> gate.await(); auth(s, m) }
        val first = launch(start = CoroutineStart.UNDISPATCHED) { f.approve(12, slow) }
        f.approve(12, auth) // double tap
        f.approve(47, auth)
        gate.complete(Unit)
        first.join()
        assertEquals(1, api.approves.size)
        assertEquals(12, api.approves.single().second)
    }

    @Test
    fun aRevokedDeviceIsReported() = runBlocking {
        api.approveResult = { throw ApiError(ErrorCode.DEVICE_REVOKED, 403, "x") }
        val f = flow(); f.load("ch_1"); f.approve(12, auth)
        assertEquals(ChallengeState.DeviceRevoked, f.state.value)
    }

    @Test
    fun anInvalidatedKeyBeforeSigningSendsNothing() = runBlocking {
        val f = flow(); f.load("ch_1")
        key.invalidateApprovalKey()
        f.approve(12, auth)
        assertEquals(ChallengeState.KeyInvalidated, f.state.value)
        assertEquals(0, api.approves.size)
    }

    @Test
    fun aKeyInvalidatedDuringTheBiometricPromptSendsNothing() = runBlocking {
        val f = flow(); f.load("ch_1")
        f.approve(12) { _, _ -> throw KeyInvalidatedException() }
        assertEquals(ChallengeState.KeyInvalidated, f.state.value)
        assertEquals(0, api.approves.size)
    }

    @Test
    fun cancellingThePromptGoesBackToPendingWithoutARequest() = runBlocking {
        val f = flow(); f.load("ch_1")
        f.approve(12) { _, _ -> throw AuthCancelledException() }
        assertIs<ChallengeState.Pending>(f.state.value)
        assertEquals(0, api.approves.size)
        f.approve(12, auth) // and the user can try again
        assertEquals(ChallengeState.Approved, f.state.value)
    }

    @Test
    fun aNetworkErrorKeepsTheSignedAnswerAndRetriesWithBackOff() = runBlocking {
        api.approveResult = { n -> if (n < 3) throw IOException("down") }
        val f = flow(); f.load("ch_1")
        val seen = mutableListOf<ChallengeState>()
        val watcher = launch(kotlinx.coroutines.Dispatchers.Unconfined) { f.state.collect { seen += it } }
        f.approve(47, auth)
        watcher.cancel()
        assertEquals(ChallengeState.Approved, f.state.value)
        assertEquals(3, api.approves.size)
        assertEquals(listOf(1000L, 2000L), delayer.waits)
        assertTrue(seen.any { it is ChallengeState.Offline }, "Offline was shown")
        // the same signature each time: signed once, resent as is
        assertEquals(1, api.approves.map { it.third }.toSet().size)
    }

    @Test
    fun retriesStopAtExpiryAndNothingIsSentAfterIt() = runBlocking {
        api.approveResult = { throw IOException("down") }
        val f = flow(); f.load("ch_1"); f.approve(47, auth)
        assertEquals(ChallengeState.Expired, f.state.value)
        assertTrue(api.approves.size > 1)
        assertTrue(api.approveTimes.all { it < expiresMs }, "sent after expires_at: ${api.approveTimes}")
    }

    @Test
    fun theCountdownEndsAsExpiredAtExpiresAtByTheCorrectedClock() = runBlocking {
        val f = flow(); f.load("ch_1")
        val left = mutableListOf<Long>()
        val w = launch(kotlinx.coroutines.Dispatchers.Unconfined) {
            f.state.collect { if (it is ChallengeState.Pending) left += it.secondsLeft }
        }
        f.countdown()
        w.cancel()
        assertEquals(ChallengeState.Expired, f.state.value)
        assertEquals(30L, left.first())
        assertEquals(1L, left.last())
        assertTrue(clock.now >= expiresMs)
        assertEquals(30, delayer.waits.size)
    }

    @Test
    fun aPhoneClockAheadOfTheServerExpiresEarlierButWithTheCorrectedClockIsFine() = runBlocking {
        // the clock handed to the flow is the corrected one; here it says 20 s before expiry
        clock.now = expiresMs - 20_000
        val f = flow(); f.load("ch_1")
        assertEquals(20L, (f.state.value as ChallengeState.Pending).secondsLeft)
    }

    @Test
    fun anExpiredChallengeIsNotSignedAtAll() = runBlocking {
        val f = flow(); f.load("ch_1")
        clock.now = expiresMs + 1
        var asked = false
        f.approve(12) { s, m -> asked = true; auth(s, m) }
        assertEquals(ChallengeState.Expired, f.state.value)
        assertFalse(asked)
        assertEquals(0, api.approves.size)
    }

    @Test
    fun loadMapsTheServersView() = runBlocking {
        for ((st, want) in listOf(
            ChallengeStatus.Approved to ChallengeState.AlreadyHandled,
            ChallengeStatus.Denied to ChallengeState.AlreadyHandled,
            ChallengeStatus.Expired to ChallengeState.Expired,
        )) {
            api.getResult = { details.copy(status = st) }
            val f = flow(); f.load("ch_1")
            assertEquals(want, f.state.value, st.name)
        }
        api.getResult = { throw ApiError(ErrorCode.DEVICE_REVOKED, 403, "x") }
        val f = flow(); f.load("ch_1")
        assertEquals(ChallengeState.DeviceRevoked, f.state.value)
        api.getResult = { throw ApiError(ErrorCode.RESOURCE_NOT_FOUND, 404, "x") }
        val g = flow(); g.load("ch_1")
        assertEquals(ChallengeState.Failed(ErrorCode.RESOURCE_NOT_FOUND), g.state.value)
        api.getResult = { throw IOException("down") }
        val h = flow(); h.load("ch_1")
        assertEquals(ChallengeState.Failed(null), h.state.value)
    }

    @Test
    fun aChallengeWithOddFieldsIsRefusedBeforeSigning() = runBlocking {
        api.getResult = { details.copy(expiresAt = "2026-11-14T12:00:30.123Z") }
        val f = flow(); f.load("ch_1"); f.approve(12, auth)
        assertEquals(ChallengeState.Failed(ErrorCode.INVALID_REQUEST), f.state.value)
        assertEquals(0, api.approves.size)
    }

    @Test
    fun theSignedAnswerOnlyLivesInMemory() {
        // no field, constructor parameter or call to a storage API in the flow, its lambdas or its state classes
        val banned = listOf("DeviceStore", "DataStore", "SharedPreferences", "java/io/File", "java/io/FileOutputStream", "java/nio/file", "android/content/Context", "SQLite")
        val url = checkNotNull(ChallengeFlow::class.java.getResource("ChallengeFlow.class")).toString()
        val texts = mutableMapOf<String, String>()
        if (url.startsWith("jar:")) {
            java.util.zip.ZipFile(java.io.File(java.net.URI(url.substringBefore("!").removePrefix("jar:")))).use { z ->
                for (e in z.entries()) {
                    val n = e.name.substringAfterLast('/')
                    if (e.name.startsWith("app/rustmfa/android/domain/") && n.endsWith(".class") &&
                        listOf("ChallengeFlow", "ChallengeState", "PendingAnswer").any { n.startsWith(it) }
                    ) texts[n] = String(z.getInputStream(e).readBytes(), Charsets.ISO_8859_1)
                }
            }
        } else {
            val dir = java.io.File(java.net.URI(url)).parentFile
            for (f in dir.listFiles()!!) if (listOf("ChallengeFlow", "ChallengeState", "PendingAnswer").any { f.name.startsWith(it) }) texts[f.name] = String(f.readBytes(), Charsets.ISO_8859_1)
        }
        assertTrue(texts.size >= 3, "found ${texts.keys}")
        for ((name, text) in texts) {
            for (b in banned) assertFalse(text.contains(b), "$name mentions $b")
        }
    }
}

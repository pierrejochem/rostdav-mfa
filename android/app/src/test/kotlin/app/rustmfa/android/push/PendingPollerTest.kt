package app.rustmfa.android.push

import app.rustmfa.android.domain.Delayer
import app.rustmfa.android.net.ApiError
import app.rustmfa.android.net.ChallengeDetails
import app.rustmfa.android.net.ChallengeStatus
import app.rustmfa.android.net.Clock
import app.rustmfa.android.net.ErrorCode
import app.rustmfa.android.net.StubApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class PendingPollerTest {
    private fun ch(id: String, status: ChallengeStatus = ChallengeStatus.Pending, expires: String = "2099-01-01T00:00:00Z") =
        ChallengeDetails(id, "Rostdav", "Firefox", "2026-11-14T11:59:30Z", expires, status, listOf(1, 2, 3), "n", "l", "rostdav")

    private class Api(var answer: () -> List<ChallengeDetails>) : StubApi() {
        var calls = 0
        override suspend fun listChallenges(): List<ChallengeDetails> {
            calls++
            return answer()
        }
    }

    private val clock = Clock { java.time.Instant.parse("2026-11-14T12:00:00Z").toEpochMilli() }
    private val delays = mutableListOf<Long>()
    private val delayer = Delayer { delays += it; delay(it) }

    private fun poller(api: Api) = PendingPoller(api, clock, delayer, idleMs = 15_000, awaitedMs = 3_000, maxBackoffMs = 60_000)

    @Test fun pollsInTheForegroundAndPublishesTheList() = runTest {
        val api = Api { listOf(ch("a")) }
        val p = poller(api)
        backgroundScope.launch { p.run() }
        p.setForeground(true)
        runCurrent()
        assertEquals(1, api.calls)
        assertEquals(listOf("a"), p.pending.value.map { it.challengeId })
        advanceTimeBy(15_001)
        assertEquals(2, api.calls)
    }

    @Test fun pollsFasterWhileALoginIsAwaited() = runTest {
        val api = Api { emptyList() }
        val p = poller(api)
        backgroundScope.launch { p.run() }
        p.setForeground(true)
        p.setAwaiting(true)
        runCurrent()
        advanceTimeBy(9_001)
        assertTrue(api.calls >= 4, "calls=${api.calls}")
        assertTrue(delays.all { it == 3_000L }, "$delays")
    }

    @Test fun stopsInTheBackground() = runTest {
        val api = Api { emptyList() }
        val p = poller(api)
        backgroundScope.launch { p.run() }
        p.setForeground(true)
        runCurrent()
        p.setForeground(false)
        runCurrent()
        val n = api.calls
        advanceTimeBy(120_000)
        assertEquals(n, api.calls)
        p.setForeground(true)
        runCurrent()
        assertEquals(n + 1, api.calls)
    }

    @Test fun backsOffOnRateLimitThenRecovers() = runTest {
        var fail = true
        val api = Api { if (fail) throw ApiError(ErrorCode.RATE_LIMITED, 429, "slow") else emptyList() }
        val p = poller(api)
        backgroundScope.launch { p.run() }
        p.setForeground(true)
        runCurrent()
        advanceTimeBy(200_000)
        assertEquals(listOf(30_000L, 60_000L, 60_000L), delays.take(3), "$delays")
        fail = false
        advanceTimeBy(61_000)
        advanceTimeBy(16_000)
        assertEquals(15_000L, delays.last())
    }

    @Test fun networkErrorsAlsoBackOffAndTheLoopSurvives() = runTest {
        val api = Api { throw java.io.IOException("down") }
        val p = poller(api)
        backgroundScope.launch { p.run() }
        p.setForeground(true)
        runCurrent()
        advanceTimeBy(100_000)
        assertTrue(api.calls >= 2)
    }

    @Test fun dropsExpiredAndAnsweredChallenges() = runTest {
        val api = Api {
            listOf(
                ch("open"),
                ch("late", expires = "2026-11-14T11:59:59Z"),
                ch("done", status = ChallengeStatus.Approved),
                ch("gone", status = ChallengeStatus.Expired),
            )
        }
        val p = poller(api)
        backgroundScope.launch { p.run() }
        p.setForeground(true)
        runCurrent()
        assertEquals(listOf("open"), p.pending.value.map { it.challengeId })
    }
}

package app.rustmfa.android.push

import app.rustmfa.android.net.ApiError
import app.rustmfa.android.net.ChallengeDetails
import app.rustmfa.android.net.ChallengeStatus
import app.rustmfa.android.net.Clock
import app.rustmfa.android.net.ErrorCode
import app.rustmfa.android.net.StubApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest

class ChallengeNotifierTest {
    private class Sink : NotificationSink {
        val shown = mutableListOf<NotificationSpec>()
        val cancelled = mutableListOf<String>()
        override fun show(spec: NotificationSpec) { shown += spec }
        override fun cancel(challengeId: String) { cancelled += challengeId }
    }

    private fun ch(id: String, status: ChallengeStatus = ChallengeStatus.Pending, expires: String = "2099-01-01T00:00:00Z") =
        ChallengeDetails(id, "Rostdav", "Firefox", "2026-11-14T11:59:30Z", expires, status, listOf(4711, 4712, 4713), "n", "l", "rostdav")

    private class Api(val byId: MutableMap<String, ChallengeDetails>) : StubApi() {
        override suspend fun getChallenge(id: String) =
            byId[id] ?: throw ApiError(ErrorCode.RESOURCE_NOT_FOUND, 404, "no")
    }

    private val clock = Clock { java.time.Instant.parse("2026-11-14T12:00:00Z").toEpochMilli() }
    private val sink = Sink()
    private fun notifier(api: Api = Api(mutableMapOf())) = ChallengeNotifier(api, sink, clock)

    @Test fun oneNotificationPerChallenge() = runTest {
        val n = notifier()
        n.sync(listOf(ch("a"), ch("b")))
        n.sync(listOf(ch("a"), ch("b")))
        assertEquals(listOf("a", "b"), sink.shown.map { it.challengeId })
    }

    @Test fun removedWhenNoLongerOpen() = runTest {
        val n = notifier()
        n.sync(listOf(ch("a"), ch("b")))
        n.sync(listOf(ch("b")))
        assertEquals(listOf("a"), sink.cancelled)
        n.answered("b")
        assertEquals(listOf("a", "b"), sink.cancelled)
    }

    @Test fun pushFetchesDetailsThenNotifies() = runTest {
        val n = notifier(Api(mutableMapOf("a" to ch("a"))))
        n.onPush("a")
        assertEquals(listOf("a"), sink.shown.map { it.challengeId })
        n.onPush("a")
        assertEquals(1, sink.shown.size)
    }

    @Test fun noNotificationForUnknownExpiredOrAnsweredIds() = runTest {
        val api = Api(mutableMapOf(
            "late" to ch("late", expires = "2026-11-14T11:59:59Z"),
            "done" to ch("done", status = ChallengeStatus.Denied),
        ))
        val n = notifier(api)
        n.onPush("unknown")
        n.onPush("late")
        n.onPush("done")
        assertEquals(emptyList(), sink.shown)
        assertEquals(listOf("unknown", "late", "done"), sink.cancelled)
    }

    @Test fun noNumberInAnyNotificationText() = runTest {
        val n = notifier(Api(mutableMapOf("a" to ch("a"))))
        n.onPush("a")
        n.sync(listOf(ch("b")))
        for (s in sink.shown) {
            for (num in listOf("4711", "4712", "4713")) assertFalse(s.toString().contains(num), s.toString())
        }
    }
}

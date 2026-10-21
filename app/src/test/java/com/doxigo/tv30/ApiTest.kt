package com.doxigo.tv30

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.coroutines.EmptyCoroutineContext

class ApiTest {
    private fun words(msg: String?, language: String = "fa", loggedIn: Boolean = true) =
        Api.refusalText(msg, language, loggedIn) { id ->
            when (id) {
                R.string.error_session -> "session"
                R.string.error_sign_in -> "sign in"
                R.string.error_server -> "server"
                else -> error("unexpected string $id")
            }
        }

    @Test
    fun refusalsNeverShowCodesOrActions() {
        assertEquals("server", words(null))
        assertEquals("server", words("CODE_NOT_USED"))
        assertEquals("server", words("qrlogin_code failed"))
        assertEquals("session", words("Login Required"))
        assertEquals("sign in", words("login required", loggedIn = false))
        assertEquals("اشتراک شما به پایان رسیده است.", words("اشتراک شما به پایان رسیده است."))
        assertEquals("server", words("اشتراک شما به پایان رسیده است.", language = "en")) // Persian on an English screen
    }

    @Test
    fun readOnlyGuardCoversEveryAccountWrite() {
        fun write(action: String, vararg params: Pair<String, Any?>) = Api.isWrite(action, params)
        assertTrue(write("observer"))
        assertTrue(write("continue_watching_dismiss", "id" to 1))
        assertTrue(write("logout"))
        assertTrue(write("user_list", "action" to "add", "group" to "favorite", "post_id" to 1))
        assertTrue(write("user_list", "action" to "remove", "group" to "favorite", "post_id" to 1))
        assertTrue(write("user_list", "group" to "favorite")) // no action: assume the worst
        assertFalse(write("user_list", "action" to "list", "group" to "favorite"))
        assertFalse(write("home_stream"))
        assertFalse(write("qrlogin_code"))
    }

    @Test
    fun progressGoesOutOnTheMarks() {
        assertEquals(0L, Api.progressMark(-1)) // nothing reported yet
        assertEquals(0L, Api.progressMark(14)) // not sent: the old first report, which never landed
        assertEquals(15L, Api.progressMark(15))
        assertEquals(15L, Api.progressMark(29))
        assertEquals(2010L, Api.progressMark(2024)) // a pause between marks
    }

    @Test
    fun rateLimitsAreWaitedOutAndNothingElseIs() {
        fun failure(code: Int, text: String, retryAfter: String? = null) = Api.notJson(code, retryAfter, text, "home_stream")
        val page = "<html><title>Access denied</title><span class=\"cf-error-code\">1015</span><h2>You are being rate limited</h2></html>"

        val tooMany = failure(429, "<html>Too Many Requests</html>", retryAfter = "4")
        assertTrue(tooMany is Api.Throttled)
        assertEquals(4_000L, (tooMany as Api.Throttled).wait)
        assertEquals(10_000L, (failure(429, "", retryAfter = "120") as Api.Throttled).wait) // capped
        assertEquals(0L, (failure(429, "", retryAfter = "Wed, 21 Oct 2026 07:28:00 GMT") as Api.Throttled).wait)
        assertTrue(failure(429, "{\"success\":false}") is Api.Throttled)
        assertTrue(failure(403, "error code: 1015") is Api.Throttled) // the plain page API clients get
        assertTrue(failure(200, page) is Api.Throttled)

        val challenge = failure(403, "<html><title>Just a moment...</title></html>")
        val badGateway = failure(502, "<html>502 Bad Gateway</html>")
        val down = failure(503, "")
        for (e in listOf(challenge, badGateway, down)) {
            assertFalse(e is Api.Throttled)
            assertTrue(Api.movesHost(e))
        }
        assertTrue(Api.movesHost(SocketTimeoutException()))
        assertTrue(Api.movesHost(IOException("Cloudflare challenged host")))
        assertFalse(Api.movesHost(tooMany))
        assertFalse(Api.movesHost(Api.ApiError("login required")))
    }

    @Test
    fun writesAndSignInsGoOutOnce() {
        fun once(action: String, vararg params: Pair<String, Any?>) = Api.sendsOnce(action, params)
        assertTrue(once("observer"))
        assertTrue(once("logout"))
        assertTrue(once("qrlogin_login", "auth_code" to "x"))
        assertTrue(once("user_list", "action" to "add", "group" to "favorite", "post_id" to 1))
        assertFalse(once("user_list", "action" to "list", "group" to "favorite"))
        assertFalse(once("home_stream"))
        assertFalse(once("search", "query" to "x"))
    }

    @Test
    fun screenCallsGoFirstTwoAtATimeAndSpacedOut() = runTest {
        val gate = Gate(slots = 2, backgroundSlots = 2, spacing = 100, now = { testScheduler.currentTime }, context = EmptyCoroutineContext)
        val started = mutableListOf<Pair<String, Long>>()
        fun call(name: String, background: Boolean) = launch {
            gate.pass(background) { started += name to currentTime; delay(1_000) }
        }
        call("search 1", background = true)
        call("search 2", background = true)
        call("search 3", background = true)
        call("single", background = false)
        call("stream", background = false)
        advanceUntilIdle()
        assertEquals(
            listOf("search 1" to 0L, "search 2" to 100L, "single" to 1_000L, "stream" to 1_100L, "search 3" to 2_000L),
            started,
        )
    }

    @Test
    fun backgroundCallsKeepToTwoAndGiveWayToAScreen() = runTest {
        val gate = Gate(slots = 4, backgroundSlots = 2, spacing = 100, now = { testScheduler.currentTime }, context = EmptyCoroutineContext)
        val started = mutableListOf<Pair<String, Long>>()
        fun call(name: String, background: Boolean) = launch {
            gate.pass(background) { started += name to currentTime; delay(1_000) }
        }
        call("search 1", background = true)
        call("search 2", background = true)
        call("search 3", background = true)
        call("single", background = false)
        call("stream", background = false)
        advanceUntilIdle()
        // The third search waits until fewer than two calls of any kind are in flight.
        assertEquals(
            listOf("search 1" to 0L, "search 2" to 100L, "single" to 200L, "stream" to 300L, "search 3" to 1_200L),
            started,
        )
    }

    @Test
    fun aRateLimitHoldsEveryStart() = runTest {
        val gate = Gate(slots = 2, backgroundSlots = 2, spacing = 100, now = { testScheduler.currentTime }, context = EmptyCoroutineContext)
        val started = mutableListOf<Long>()
        gate.pause(3_000)
        repeat(2) { launch { gate.pass(background = false) { started += currentTime } } }
        advanceUntilIdle()
        assertEquals(listOf(3_000L, 3_100L), started)
    }

    @Test
    fun callsGivenUpInLineLeaveNoSlotTaken() = runTest {
        val gate = Gate(slots = 1, backgroundSlots = 1, spacing = 0, now = { testScheduler.currentTime }, context = EmptyCoroutineContext)
        val ran = mutableListOf<String>()
        lateinit var handed: Job
        launch {
            gate.pass(background = false) { delay(1_000) }
            handed.cancel() // given the slot just now, gone before it could use it
        }
        handed = launch { gate.pass(background = false) { ran += "handed" } }
        launch { gate.pass(background = false) { ran += "queued" } }
        val dropped = launch { gate.pass(background = true) { ran += "dropped" } }
        advanceTimeBy(500)
        dropped.cancel() // still in line
        launch { gate.pass(background = true) { ran += "last" } }
        advanceUntilIdle()
        assertEquals(listOf("queued", "last"), ran)
    }
}

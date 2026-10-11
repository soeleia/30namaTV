package com.doxigo.tv30

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}

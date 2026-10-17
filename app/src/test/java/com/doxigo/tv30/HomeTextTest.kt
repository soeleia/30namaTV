package com.doxigo.tv30

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeTextTest {
    @Test
    fun grouped() {
        // A category tile's count: Persian digits with Persian's own thousands separator, never a Latin comma.
        assertEquals("۸۴٬۸۰۶", 84806.grouped(persian = true))
        assertEquals("۱٬۲۰۹", 1209.grouped(persian = true))
        assertEquals("۹۹۹", 999.grouped(persian = true))
        assertEquals("84,806", 84806.grouped(persian = false))
    }

    @Test
    fun trailed() {
        // The server's own cut-off dots read as one ellipsis; a sentence's full stop stays.
        assertEquals("دبیرستان دولتی، درگ…", "دبیرستان دولتی، درگ..".trailed())
        assertEquals("and then…", "and then ...".trailed())
        assertEquals("و همه چیز…", "و همه چیز….".trailed())
        assertEquals("به هم می زند.", "به هم می زند.".trailed())
        assertEquals("a.. b", "a.. b".trailed())
    }

    @Test
    fun englishNameWithoutYear() {
        fun title(english: String, year: Int?) = org.json.JSONObject()
            .put("title", org.json.JSONObject().put("english", english))
            .put("info", org.json.JSONObject().put("year", year ?: org.json.JSONObject.NULL))
        assertEquals("Cruella", title("Cruella 2021", 2021).englishName)
        // 30nama sends the film 1917 year first; trimming the end left «2019» under its poster.
        assertEquals("1917", title("2019 1917", 2019).englishName)
        assertEquals("1917", title("1917 2019", 2019).englishName)
        assertEquals("The Forsytes", title("The Forsytes 2026", 2025).englishName) // the name's year off by one
        assertEquals("Ted Lasso", title("Ted Lasso 2020", null).englishName) // a stream's info: no year
    }
}

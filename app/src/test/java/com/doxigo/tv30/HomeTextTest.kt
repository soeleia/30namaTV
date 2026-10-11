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
}

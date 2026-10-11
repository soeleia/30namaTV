package com.doxigo.tv30

import org.junit.Assert.assertEquals
import org.junit.Test

class CastTest {
    @Test
    fun initials() {
        assertEquals("GA", initials("Gillian Anderson"))
        assertEquals("AT", initials(" Aaron  Taylor-Johnson ")) // stray spaces
        assertEquals("BW", initials("Bradley James Whitford")) // first and last only
        assertEquals("Z", initials("Zendaya"))
        assertEquals("", initials(""))
        assertEquals("ا‌ف", initials("اصغر فرهادی")) // kept apart, not joined into «اف»
    }
}

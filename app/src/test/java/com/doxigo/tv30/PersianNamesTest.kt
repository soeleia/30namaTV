package com.doxigo.tv30

import org.junit.Assert.assertEquals
import org.junit.Test

class PersianNamesTest {
    @Test
    fun persian() {
        val p = PersianNames
        assertEquals("اسب‌های کیهانی ۲", p.persian(" اسب‌هاي كيهانی 2 ", "Space Horses 2", "en", "Space Horses 2")) // ي ك 2, ZWNJ kept
        assertEquals("سقوط ۲", p.persian("سقوط ٢", "Fall 2", "en", "Fall 2")) // Arabic-Indic digit, as TMDB has it
        assertEquals(null, p.persian("Below", "Below", "en", "Below")) // untranslated: TMDB's English fallback
        assertEquals(null, p.persian("الرسالة", "الرسالة", "ar", "The Message")) // Arabic original, untranslated
        assertEquals("محمد رسول‌الله", p.persian("محمد رسول‌الله", "الرسالة", "ar", "The Message")) // Arabic original, translated
        assertEquals("جدایی نادر از سیمین", p.persian("جدایی نادر از سیمین", "جدایی نادر از سیمین", "fa", "A Separation")) // Iranian
        assertEquals(null, p.persian("خاتون", "خاتون", "fa", "خاتون")) // no more than the English line says
    }
}

package com.doxigo.tv30

import androidx.media3.common.text.Cue
import androidx.media3.extractor.text.CuesWithTiming
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class TranslatorTest {
    private val about = Translator.About("Night Shift", "شیفت شب", 2024, "season 1, episode 3", "A mother finds out her son owes money to a gangster.")

    @Test
    fun reply() {
        val reply = """
            ```
            #1
            سلام.

            #2: خوبی؟
            #۳
            - کجایی؟
            - خونه.
            #4
            #5
            باشه.
            ```
        """.trimIndent()
        // Persian digits in a marker still count; an empty cue (#4) is no answer, so it is asked again.
        assertEquals(mapOf(1 to "سلام.", 2 to "خوبی؟", 3 to "- کجایی؟\n- خونه.", 5 to "باشه."), Translator.parse(reply))
    }

    @Test
    fun prompt() {
        val lines = (1..20).map { Translator.Line(it * 1000L, it * 1000L + 900, "line $it") }
        val prompt = Translator.prompt(lines, listOf(9, 10, 11), about)
        assertTrue(prompt.startsWith("Title: Night Shift («شیفت شب»), 2024, season 1, episode 3\nStory: A mother"))
        // Six lines of scene before, three after, and only the wanted ones numbered.
        assertTrue("\nBefore:\nline 4\nline 5\nline 6\nline 7\nline 8\nline 9\n" in prompt)
        assertTrue("\nTranslate:\n#10\nline 10\n#11\nline 11\n#12\nline 12\n" in prompt)
        assertTrue(prompt.endsWith("\nAfter:\nline 13\nline 14\nline 15\n"))
        assertFalse("#9" in prompt)
        // The first cue has no scene before it, the last none after.
        val edges = Translator.prompt(lines, listOf(0, 19), Translator.About("X", null, null, null, null))
        assertEquals("Title: X\n\nTranslate:\n#1\nline 1\n#20\nline 20\n", edges)
    }

    @Test
    fun choice() {
        val claude = { provider: Translator.Provider -> provider === Translator.CLAUDE }
        assertEquals("claude-opus-5-5", Translator.choose("", claude)?.id) // nothing picked: the keyed provider's best
        assertEquals("claude-sonnet-5-5", Translator.choose("claude-sonnet-5-5", claude)?.id)
        assertEquals("claude-opus-5-5", Translator.choose("gemini-3.8-flash", claude)?.id) // picked, but no Gemini key
        assertEquals("gpt-6.1-sol", Translator.choose("", { it === Translator.OPENAI || it === Translator.GEMINI })?.id) // OpenAI ahead of Gemini
        assertEquals(null, Translator.choose("claude-opus-5-5") { false })
    }

    @Test
    fun polish() {
        assertEquals("چی شد؟ باشه، بریم؛ ۱,۰۰۰ تومن.", Translator.polish("چی شد? باشه, بریم; ۱,۰۰۰ تومن."))
        assertEquals("یک کتاب", Translator.polish("يک كتاب"))
    }

    @Test
    fun srt() {
        val lines = listOf(Translator.Line(1_000, 2_500, "Hi."), Translator.Line(3_723_456, 3_725_000, "- Who?\n- Me."))
        assertEquals(
            "1\n00:00:01,000 --> 00:00:02,500\nسلام.\n\n2\n01:02:03,456 --> 01:02:05,000\n- کی؟\n- من.\n\n",
            Translator.srt(lines, listOf("سلام.", "- کی؟\n- من.")),
        )
    }

    @Test
    fun cuts() {
        fun at(start: Long, end: Long, vararg texts: String) =
            CuesWithTiming(texts.map { Cue.Builder().setText(it).build() }, start * 1000, (end - start) * 1000)
        // A VTT line held across a cut comes back whole; an empty or text-less stretch is no cue.
        val lines = Translator.lines(listOf(at(4_000, 5_000, "Next."), at(1_000, 2_000, "Hold on."), at(2_000, 3_000, "Hold on."), at(3_000, 3_500)))
        assertEquals(listOf("Hold on." to (1_000L to 3_000L), "Next." to (4_000L to 5_000L)), lines.map { it.text to (it.start to it.end) })
    }

    /**
     * The real thing, for a person to read: the sample scene through every model of each provider whose
     * key is in the environment (ANTHROPIC_KEY, OPENAI_KEY, GEMINI_KEY), each written to
     * build/translator-live/<model>.srt. Skipped without one, so no build spends anyone's credit.
     */
    @Test
    fun live() = runBlocking {
        val lines = sample(javaClass.getResource("/translator/sample.srt")!!.readText())
        val keys = listOf(Translator.CLAUDE to "ANTHROPIC_KEY", Translator.OPENAI to "OPENAI_KEY", Translator.GEMINI to "GEMINI_KEY")
            .mapNotNull { (provider, name) -> System.getenv(name)?.takeIf(String::isNotBlank)?.let { provider to it } }.toMap()
        assumeTrue("no translator key in the environment", keys.isNotEmpty())
        for (model in Translator.MODELS) {
            val key = keys[model.provider] ?: continue
            val started = System.currentTimeMillis()
            val persian = Translator.translate(model, lines, about, key)
            File("build/translator-live/${model.id}.srt").apply { parentFile!!.mkdirs() }.writeText(Translator.srt(lines, persian))
            println("${model.name}: ${lines.size} cues in ${(System.currentTimeMillis() - started) / 1000} s")
            persian.forEachIndexed { i, text ->
                assertTrue("${model.name} #${i + 1} still English: $text", text.any { it in 'آ'..'ی' })
                assertFalse("${model.name} #${i + 1} Arabic letters: $text", text.any { it == 'ي' || it == 'ك' })
            }
        }
    }

    /** SubRip as the player reads it: each cue's times and its text, tags gone. */
    private fun sample(srt: String) = srt.trim().split(Regex("\\n\\s*\\n")).map { block ->
        val rows = block.lines()
        val (start, end) = rows[1].split(" --> ").map { time ->
            val (h, m, s, ms) = time.trim().split(':', ',').map(String::toLong)
            ((h * 60 + m) * 60 + s) * 1000 + ms
        }
        Translator.Line(start, end, rows.drop(2).joinToString("\n").replace(Regex("<[^>]+>"), ""))
    }
}

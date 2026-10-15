package com.doxigo.tv30

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Persian subtitles written by a language model from an English one, for a title nobody has
 * subtitled in Persian: the model picked in Settings, else the best of Claude, OpenAI or Gemini,
 * whichever the viewer brought a key to first in that order, else (the developer's builds only) `anthropic.key`, `openai.key`, `gemini.key` in
 * local.properties. API keys only; the viewer pays the provider, or Gemini's free tier does.
 *
 * The film goes in parts of [PART] cues, [PARALLEL] at a time, each with the lines around it for the
 * scene and the title and plot for who is speaking. Each part answers cue by cue ("#n", then its
 * Persian), the one format all three read and write alike; a cue the answer leaves out is asked
 * again once, then keeps its English rather than leave a hole in the film.
 *
 * The shapes, as checked in October 2026:
 * - Gemini: `POST generativelanguage.googleapis.com/v1beta/models/{model}:generateContent` with the
 *   key in x-goog-api-key, {systemInstruction, contents, generationConfig {thinkingConfig
 *   {thinkingLevel}, maxOutputTokens}, safetySettings}; the text is candidates[0].content.parts[]
 *   (thought parts aside). A blocked prompt has promptFeedback.blockReason and no candidates. A bad
 *   key answers 400 API_KEY_INVALID; from Iran, 400 "User location is not supported".
 * - Claude: `POST api.anthropic.com/v1/messages` with x-api-key and anthropic-version 2023-06-01,
 *   {model, max_tokens, system, messages, output_config {effort}}; text blocks in content[]. The 5.5
 *   models always think: effort is the only control. `fallbacks: "default"` (beta
 *   server-side-fallback-2026-07-01) re-runs a request Opus or Sonnet's safety classifiers decline on
 *   the model Anthropic picks, rather than stop with stop_reason "refusal".
 * - OpenAI: `POST api.openai.com/v1/responses` with a Bearer key, {model, instructions, input,
 *   reasoning {effort}, max_output_tokens}; text in output[] messages' output_text parts, a refusal
 *   as a "refusal" part. 429 insufficient_quota is billing, not a rate.
 * All three: 401 a bad key, 429 too fast (Retry-After, or Gemini's retryDelay), 5xx and Anthropic's
 * 529 busy, and `GET …/models` the free call that tries a key.
 */
object Translator {
    /** A provider by the name Settings keeps its key under, and where the key is made. */
    class Provider(val id: String, val link: String, private val built: String) {
        val key get() = Settings.pickKey(Settings.savedKey(id), built)
    }

    /**
     * A model to translate with: its provider, the id the API takes, the name Settings shows, and
     * what a two-hour film costs on it (about 30k tokens in and 40k out), in US cents; [free] within
     * the provider's free tier.
     */
    class Model(val provider: Provider, val id: String, val name: String, val cents: Int, val free: Boolean = false)

    val CLAUDE = Provider("Claude", "https://platform.claude.com/settings/keys", BuildConfig.ANTHROPIC_KEY)
    val OPENAI = Provider("OpenAI", "https://platform.openai.com/api-keys", BuildConfig.OPENAI_KEY)
    val GEMINI = Provider("Gemini", "https://aistudio.google.com/apikey", BuildConfig.GEMINI_KEY)

    // Best Persian first, and each provider's best ahead of its cheaper one. On the sample scene
    // (TranslatorTest, October 2026) Opus 5.5 kept every meaning, register and idiom; Sonnet 5.5 came
    // close, dropping a clause and a شما; Gemini 3.8 Flash read as well but added the odd clause, so
    // it comes last, for being free. The small tier is left out: Haiku 5.5 ran cues into one long line
    // and turned meanings, and Gemini 3.5 Flash-Lite got whole idioms wrong; GPT-6 Luna is that tier.
    // Prices per million in and out: Opus 5.5 $4/$20, Sonnet 5.5 $2/$10, GPT-6.1 Sol $2/$10, GPT-6
    // Astra $10/$50, Gemini 3.8 Flash $0.75/$3.75.
    val MODELS = listOf(
        Model(CLAUDE, "claude-opus-5-5", "Claude Opus 5.5", 92),
        Model(CLAUDE, "claude-sonnet-5-5", "Claude Sonnet 5.5", 46),
        Model(OPENAI, "gpt-6.1-sol", "GPT-6.1 Sol", 46),
        Model(OPENAI, "gpt-6-astra", "GPT-6 Astra", 230),
        Model(GEMINI, "gemini-3.8-flash", "Gemini 3.8 Flash", 17, free = true),
    )

    /** The model picked in Settings, keyed or not; null until one is. */
    val picked get() = MODELS.firstOrNull { it.id == Settings.translatorModel }

    /** The one the player translates with ([choose]); null with no key at all. */
    val model get() = choose(Settings.translatorModel) { it.key.isNotEmpty() }

    /** [picked] while its provider has a key, else the best model of the first provider that has one. */
    internal fun choose(picked: String, keyed: (Provider) -> Boolean): Model? =
        MODELS.firstOrNull { it.id == picked && keyed(it.provider) } ?: MODELS.firstOrNull { keyed(it.provider) }

    /** Cues a part: enough of the scene to keep voices consistent, small enough to answer in a minute. */
    private const val PART = 100
    private const val PARALLEL = 4
    /** Lines shown around a part, untranslated, for the scene it starts and ends in. */
    private const val BEFORE = 6
    private const val AFTER = 3
    /** A part's Persian is some 5k tokens; room for that, the thinking, and a long-winded answer. */
    private const val MAX_OUT = 16_000

    /** A cue: when it shows and hides (ms), and what it says. */
    class Line(val start: Long, val end: Long, val text: String)

    /** What plays, so the model knows who speaks and how: the names, the year, the episode, the story. */
    class About(val title: String, val persianTitle: String?, val year: Int?, val episode: String?, val plot: String?)

    /** Why a provider said no, for the player to say what to do. */
    enum class Why { KEY, QUOTA, REGION, BLOCKED, DOWN }

    class Refused(val provider: String, val why: Why) : IOException("$provider refused: $why")

    /** Nothing in English to translate from: none in the file, none found on the subtitle sites. */
    class NoSource : IOException("no English subtitle")

    /** Too fast or too busy: worth a wait ([after] ms, when the provider says), else [why] in the end. */
    private class Busy(val after: Long?, val why: Why) : IOException("busy")

    /** Long: a part takes the model up to a minute or two to write. TranslateChecks answers in the providers' place. */
    internal var client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(150, TimeUnit.SECONDS).callTimeout(200, TimeUnit.SECONDS).build()
    private val JSON_TYPE = "application/json".toMediaType()

    /**
     * Cues as the player parsed them, one [Line] each. Media3 hands over a VTT or SSA file cut where
     * any cue starts or ends, so a line held across a cut comes back once, whole; styling (italics,
     * positions) is already gone from the text.
     */
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    fun lines(cues: List<CuesWithTiming>): List<Line> {
        val out = ArrayList<Line>()
        for (c in cues.sortedBy { it.startTimeUs }) {
            val text = c.cues.mapNotNull { it.text?.toString()?.trim()?.takeIf(String::isNotEmpty) }.joinToString("\n")
            if (text.isEmpty() || c.startTimeUs == C.TIME_UNSET) continue
            val start = c.startTimeUs / 1000
            val end = if (c.durationUs == C.TIME_UNSET) start + 3_000 else c.endTimeUs / 1000
            val last = out.lastOrNull()
            if (last != null && last.text == text && start <= last.end) out[out.lastIndex] = Line(last.start, maxOf(last.end, end), text)
            else out += Line(start, end, text)
        }
        return out
    }

    /** [lines] in Persian, in order; [progress] hears the share done (0 to 1) as each part lands. */
    suspend fun translate(model: Model, lines: List<Line>, about: About, key: String = model.provider.key, progress: (Float) -> Unit = {}): List<String> = coroutineScope {
        val gate = Semaphore(PARALLEL)
        val done = AtomicInteger()
        lines.indices.chunked(PART).map { part ->
            async {
                gate.withPermit { part(model, key, lines, part, about) }.also { progress(done.addAndGet(part.size).toFloat() / lines.size) }
            }
        }.awaitAll().flatten()
    }

    private suspend fun part(model: Model, key: String, lines: List<Line>, part: List<Int>, about: About): List<String> {
        val got = HashMap<Int, String>()
        repeat(2) {
            val wanted = part.filter { it !in got }
            if (wanted.isNotEmpty()) got += parse(ask(model, key, prompt(lines, wanted, about))).mapKeys { it.key - 1 }.filterKeys { it in wanted }
        }
        return part.map { got[it]?.let(::polish) ?: lines[it].text }
    }

    /** The cues [wanted] (indexes into [lines]) as "#n" blocks, between the lines around them and after what plays. */
    internal fun prompt(lines: List<Line>, wanted: List<Int>, about: About): String = buildString {
        append("Title: ").append(about.title)
        about.persianTitle?.let { append(" («").append(it).append("»)") }
        about.year?.let { append(", ").append(it) }
        about.episode?.let { append(", ").append(it) }
        append('\n')
        about.plot?.let { append("Story: ").append(it).append('\n') }
        val before = lines.subList((wanted.first() - BEFORE).coerceAtLeast(0), wanted.first())
        if (before.isNotEmpty()) append("\nBefore:\n").append(before.joinToString("\n") { it.text }).append('\n')
        append("\nTranslate:\n")
        for (i in wanted) append('#').append(i + 1).append('\n').append(lines[i].text).append('\n')
        val after = lines.subList((wanted.last() + 1).coerceAtMost(lines.size), (wanted.last() + 1 + AFTER).coerceAtMost(lines.size))
        if (after.isNotEmpty()) append("\nAfter:\n").append(after.joinToString("\n") { it.text }).append('\n')
    }

    /**
     * The answer's cues by number: each "#n" line, or "#n: text" on one, and the lines under it up to
     * the next. Fences and blank lines go; a number the model wrote in Persian digits still counts.
     */
    internal fun parse(reply: String): Map<Int, String> {
        val out = HashMap<Int, String>()
        var id: Int? = null
        val text = ArrayList<String>()
        fun flush() {
            id?.let { n -> text.joinToString("\n").trim().takeIf { it.isNotEmpty() }?.let { out[n] = it } }
            text.clear()
        }
        for (raw in reply.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("```")) continue
            val marker = MARKER.matchEntire(line)
            if (marker != null) {
                flush()
                id = marker.groupValues[1].map { if (it in '۰'..'۹') '0' + (it - '۰') else it }.joinToString("").toInt()
                marker.groupValues[2].takeIf { it.isNotEmpty() }?.let(text::add)
            } else if (id != null) text += line
        }
        flush()
        return out
    }
    private val MARKER = Regex("#\\s*([0-9۰-۹]+)\\s*[:.)\\-]?\\s*(.*)")

    /** Persian letters and marks where a model slips into Arabic or Latin ones: ي ك ? , ; (not a number's comma). */
    internal fun polish(text: String) = text.replace('ي', 'ی').replace('ى', 'ی').replace('ك', 'ک')
        .replace('?', '؟').replace(';', '؛').replace(Regex("(?<![0-9۰-۹]),|,(?![0-9۰-۹])"), "،")

    /** [lines] with [texts] in their place, as SubRip. */
    fun srt(lines: List<Line>, texts: List<String>): String = buildString {
        lines.forEachIndexed { i, line ->
            append(i + 1).append('\n').append(time(line.start)).append(" --> ").append(time(line.end)).append('\n')
            append(texts[i]).append("\n\n")
        }
    }

    private fun time(ms: Long) = String.format(Locale.US, "%02d:%02d:%02d,%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)

    /** One part's answer, waiting out a provider that is too busy or too fast for us (three times, up to a minute). */
    private suspend fun ask(model: Model, key: String, prompt: String): String {
        var wait = 5_000L
        repeat(3) {
            try {
                return call(model, key, prompt)
            } catch (e: Busy) {
                delay((e.after ?: wait).coerceAtMost(60_000))
            } catch (e: Refused) {
                throw e
            } catch (e: IOException) {
                delay(wait) // a dropped connection or a timeout, as from Iran
            }
            wait *= 3
        }
        return try {
            call(model, key, prompt)
        } catch (e: Busy) {
            throw Refused(model.provider.id, e.why)
        }
    }

    private suspend fun call(model: Model, key: String, prompt: String): String = when (model.provider) {
        GEMINI -> gemini(model, key, prompt)
        CLAUDE -> claude(model, key, prompt)
        else -> openAi(model, key, prompt)
    }

    private suspend fun gemini(model: Model, key: String, prompt: String): String {
        fun text(value: String) = JSONObject().put("parts", JSONArray().put(JSONObject().put("text", value)))
        val body = JSONObject()
            .put("systemInstruction", text(SYSTEM))
            .put("contents", JSONArray().put(text(prompt).put("role", "user")))
            .put("generationConfig", JSONObject().put("maxOutputTokens", MAX_OUT).put("thinkingConfig", JSONObject().put("thinkingLevel", "low")))
            // Films have violence, sex and slurs in them; a translation must carry them, not stop at them.
            .put("safetySettings", JSONArray(listOf("HARASSMENT", "HATE_SPEECH", "SEXUALLY_EXPLICIT", "DANGEROUS_CONTENT").map {
                JSONObject().put("category", "HARM_CATEGORY_$it").put("threshold", "BLOCK_NONE")
            }))
        val reply = post(model.provider, "https://generativelanguage.googleapis.com/v1beta/models/${model.id}:generateContent", body, "x-goog-api-key" to key)
        if (reply.optJSONObject("promptFeedback")?.str("blockReason") != null) throw Refused(model.provider.id, Why.BLOCKED)
        val candidate = reply.optJSONArray("candidates")?.optJSONObject(0) ?: throw Refused(model.provider.id, Why.DOWN)
        val text = candidate.optJSONObject("content")?.optJSONArray("parts").objects().filter { !it.optBoolean("thought") }.joinToString("") { it.optString("text") }
        if (text.isBlank() && candidate.str("finishReason") in setOf("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "RECITATION")) throw Refused(model.provider.id, Why.BLOCKED)
        return text
    }

    private suspend fun claude(model: Model, key: String, prompt: String): String {
        val body = JSONObject()
            .put("model", model.id)
            .put("max_tokens", MAX_OUT)
            .put("system", SYSTEM)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            .put("output_config", JSONObject().put("effort", "low"))
            .put("fallbacks", "default")
        val reply = post(model.provider, "https://api.anthropic.com/v1/messages", body,
            "x-api-key" to key, "anthropic-version" to "2023-06-01", "anthropic-beta" to "server-side-fallback-2026-07-01")
        if (reply.str("stop_reason") == "refusal") throw Refused(model.provider.id, Why.BLOCKED)
        return reply.optJSONArray("content").objects().filter { it.str("type") == "text" }.joinToString("") { it.optString("text") }
    }

    private suspend fun openAi(model: Model, key: String, prompt: String): String {
        val body = JSONObject()
            .put("model", model.id)
            .put("instructions", SYSTEM)
            .put("input", prompt)
            .put("reasoning", JSONObject().put("effort", "low"))
            .put("max_output_tokens", MAX_OUT)
        val reply = post(model.provider, "https://api.openai.com/v1/responses", body, "Authorization" to "Bearer $key")
        val parts = reply.optJSONArray("output").objects().filter { it.str("type") == "message" }.flatMap { it.optJSONArray("content").objects() }
        val text = parts.filter { it.str("type") == "output_text" }.joinToString("") { it.optString("text") }
        if (text.isBlank() && parts.any { it.str("type") == "refusal" }) throw Refused(model.provider.id, Why.BLOCKED)
        return text
    }

    /** The answer, or why not: [Refused] for what waiting won't change, [Busy] for what it may. */
    private suspend fun post(provider: Provider, url: String, body: JSONObject, vararg headers: Pair<String, String>): JSONObject {
        val request = Request.Builder().url(url).post(body.toString().toRequestBody(JSON_TYPE))
            .apply { for ((name, value) in headers) header(name, value) }
            .header("User-Agent", "30namaTV v${BuildConfig.VERSION_NAME}")
            .build()
        return Api.run { client.newCall(request).await() }.use { response ->
            val text = withContext(Dispatchers.IO) { response.body?.string().orEmpty() }
            val code = response.code
            when {
                response.isSuccessful -> runCatching { JSONObject(text) }.getOrElse { throw Refused(provider.id, Why.DOWN) }
                REGION.containsMatchIn(text) -> throw Refused(provider.id, Why.REGION)
                code == 401 || code == 403 || "API_KEY_INVALID" in text -> throw Refused(provider.id, Why.KEY)
                code == 402 || "insufficient_quota" in text || "credit balance" in text -> throw Refused(provider.id, Why.QUOTA)
                code == 429 -> throw Busy(retryAfter(response.header("Retry-After"), text), Why.QUOTA)
                code >= 500 -> throw Busy(retryAfter(response.header("Retry-After"), text), Why.DOWN)
                else -> throw Refused(provider.id, Why.DOWN)
            }
        }
    }

    /** Seen from Iran: Gemini's "User location is not supported", OpenAI's unsupported_country_region_territory. */
    private val REGION = Regex("(?i)location is not supported|unsupported_country|not available in your (country|region)")

    private fun retryAfter(header: String?, body: String): Long? =
        (header?.toDoubleOrNull() ?: Regex("\"retryDelay\"\\s*:\\s*\"([0-9.]+)s\"").find(body)?.groupValues?.get(1)?.toDoubleOrNull())?.let { (it * 1000).toLong() }

    suspend fun checkGemini(key: String) = Subtitles.check(
        Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1").header("x-goog-api-key", key).build(), 400, 401)

    suspend fun checkClaude(key: String) = Subtitles.check(
        Request.Builder().url("https://api.anthropic.com/v1/models?limit=1").header("x-api-key", key).header("anthropic-version", "2023-06-01").build(), 401)

    suspend fun checkOpenAi(key: String) = Subtitles.check(
        Request.Builder().url("https://api.openai.com/v1/models").header("Authorization", "Bearer $key").build(), 401)

    /**
     * How the three are told to write: as the best Persian subtitles read, speech in spoken Persian
     * spelt properly. Written once in English for all three; the examples carry the Persian.
     */
    private val SYSTEM = """
        You are an experienced Iranian subtitle translator. You turn English film and TV subtitles into Persian subtitles that read as if a skilled Iranian translator wrote them for viewers in Iran.

        Each cue arrives as a line "#n" followed by its English text. Translate every cue under "Translate:". The lines under "Before:" and "After:" are the neighbouring dialogue, there only so you can follow the scene: do not translate them.

        Reply with every cue under "Translate:", in the same order, each as its "#n" line followed by its Persian text, and nothing else: no notes, no English, no cue left out, no two cues merged into one.

        How the Persian reads:
        - Dialogue in natural spoken Persian, written the way good Persian subtitles write speech (محاوره): «نمی‌دونم کجا رفت.»، «اون رو بده به من.»، «داری چی کار می‌کنی؟». Never stiff, bookish or word for word: no «آیا» questions, no «می‌باشد», no «او» where people say «اون». Narration, letters, signs, on-screen text, news and formal speeches stay in written Persian.
        - Translate what is meant, not the words, and exactly what is meant: add nothing, leave out nothing that matters, never turn a line's meaning around. Idioms, jokes and slang become what an Iranian would say in that moment, never a word-for-word copy ("break a leg" is «موفق باشی», "sleep on it" is «روش فکر کن»). Keep each speaker's tone: rude stays rude, tender stays tender, curt stays curt. Swearing gets a natural Persian equivalent of similar weight (لعنتی، عوضی، آشغال، خفه شو), never stars or dots.
        - تو between friends, family, lovers, children and people fighting; شما to strangers, superiors, elders and in formal settings. Settle it for each pair of characters from how they first speak, and keep it both ways for the rest of the scene unless their relationship changes on screen.
        - Subtitles are read in passing: at most two lines of no more than 42 characters each, broken where a speaker would pause; a short line stays one line. When the Persian runs long, say it more briefly. Drop filler ("well", "you know", "I mean") before dropping meaning. Each cue keeps to its own words: when a sentence runs across cues, split the Persian at a natural point so each cue matches what is being said while it shows.
        - Names of people and places in their usual Persian spelling (Michael مایکل، Sarah سارا، New York نیویورک), spelt the same way every time. Well-known brands, titles and acronyms as Persian media write them.
        - A cue whose lines start with "-" holds two speakers: keep one line each, each starting with "-".
        - Sounds and speaker labels such as [door slams] or (SIGHS) become short spoken Persian in the same brackets: [در به هم می‌خوره]. Song lines marked ♪ are translated simply, ♪ kept.
        - Keep "..." where the English trails off or is cut short.

        Persian spelling, which viewers notice at once:
        - A half-space (ZWNJ) wherever Persian takes one: می‌خوام، نمی‌دونی، کتاب‌ها، بزرگ‌تر، خونه‌ات.
        - Persian ی and ک, never Arabic ي or ك. Numbers in Persian digits: ۱۲، ۲۰۲۴.
        - Persian punctuation: ، ؛ ؟ and «» for quotes, with no space before them. No dash except a speaker's "-", no emoji.
        - Never write the ezafe as ـه: «کتابِ من» or «کتاب من», not «کتابه من». A final ـه stands only for است, as in «خوبه».
    """.trimIndent()
}

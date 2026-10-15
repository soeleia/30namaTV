package com.doxigo.tv30

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Make Persian subtitles with no network: the stream and its English subtitle from a fixture, and
 * the provider the player picks (a fixture Gemini key, or the developer's own built in ahead of it)
 * answered cue for cue by a fake in that provider's shape, one part a second so the page counts up;
 * nothing reaches a provider. Then a refused key, which must say so on the page and offer a retry.
 */
object TranslateChecks {
    private const val CUES = 250

    fun run(instrumentation: Instrumentation) = with(instrumentation) {
        val result = Bundle()
        var resultCode = Activity.RESULT_OK
        val originalClient = Api.http
        val originalUser = Api.user
        val originalLanguage = Api.language
        val originalLocale = Locale.getDefault()
        val originalTranslator = Translator.client
        val originalKey = Settings.savedKey(Translator.GEMINI.id)
        val originalModel = Settings.translatorModel
        val prefs = targetContext.getSharedPreferences("player", android.content.Context.MODE_PRIVATE)
        val originalSubtitle = prefs.getString("subtitle", null)
        val clientField = Api::class.java.getDeclaredField("http").apply { isAccessible = true }
        val english = (1..CUES).joinToString("") { "$it\n${time(it * 2_000L)} --> ${time(it * 2_000L + 1_500)}\nLine $it.\n\n" }
        val stream = """{"success":true,"result":{"info":{"title":{"english":"Night Shift 2024"}},"hls":[{"info":{"file_id":"translate"},"file":{"subtitle":{"en":"https://fixture.test/en.srt"}}}]}}"""
        val api = OkHttpClient.Builder().addInterceptor { chain ->
            val body = if (chain.request().url.pathSegments.last() == "stream") stream else english
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
        }.build()
        var refuse = false
        val models = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val sent = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
            val prompt = when (request.url.host) {
                "generativelanguage.googleapis.com" -> sent.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text")
                "api.anthropic.com" -> sent.getJSONArray("messages").getJSONObject(0).getString("content")
                else -> sent.getString("input")
            }
            val ids = Regex("(?m)^#(\\d+)$").findAll(prompt.substringAfter("Translate:").substringBefore("\nAfter:")).map { it.groupValues[1].toInt() }.toList()
            SystemClock.sleep(1_000L + ids.first() * 10L) // part by part: 40%, 80%, 100%
            val text = JSONObject().put("text", ids.joinToString("\n") { "#$it\nسطر $it?" })
            val (code, body) = if (refuse) 401 to """{"error":{"message":"invalid key"}}""" else 200 to when (request.url.host) {
                "generativelanguage.googleapis.com" -> JSONObject().put("candidates", org.json.JSONArray().put(JSONObject().put("content", JSONObject().put("parts", org.json.JSONArray().put(text)))))
                "api.anthropic.com" -> JSONObject().put("stop_reason", "end_turn").put("content", org.json.JSONArray().put(text.put("type", "text")))
                else -> JSONObject().put("output", org.json.JSONArray().put(JSONObject().put("type", "message").put("content", org.json.JSONArray().put(text.put("type", "output_text")))))
            }.toString()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val made = File(targetContext.cacheDir, "subtitles")
        lateinit var provider: Translator.Provider
        fun forget() = made.listFiles().orEmpty().filter { it.name.startsWith("${provider.id}-") }.forEach(File::delete)
        try {
            for (language in listOf("fa", "en")) {
                refuse = false
                runOnMainSync {
                    clientField.set(Api, api)
                    Translator.client = models
                    Settings.saveKey(Translator.GEMINI.id, "fixture-key")
                    Settings.translatorModel = ""
                    provider = checkNotNull(Translator.model).provider
                    Api.user = null
                    Api.language = language
                    prefs.edit().putString("subtitle", "fa").apply()
                }
                forget()
                var fragment = open(language)
                val label = fragment.getString(R.string.subtitle_translate)
                runOnMainSync { invoke(fragment, "openPanel", field(fragment, "subtitlesPage")) }
                await("Make Persian subtitles was not offered") { row(fragment, label) != null }
                settle()
                capture("translate-offer-$language")
                runOnMainSync { checkNotNull(row(fragment, label)).performClick() }
                val forty = if (language == "fa") "۴۰٪" else "40%"
                val watch = fragment.getString(R.string.subtitle_translate_watch)
                await("The page did not count up") { text(fragment)?.contains(forty) == true && row(fragment, watch)?.hasFocus() == true }
                settle()
                capture("translate-progress-$language")
                val found = fragment.getString(R.string.subtitle_found, Locale.forLanguageTag("fa").getDisplayLanguage(Locale.getDefault()), provider.id)
                await("The Persian was not added and checked") { row(fragment, found)?.isSelected == true && row(fragment, label) == null }
                settle()
                capture("translate-done-$language")
                runOnMainSync {
                    val srt = made.listFiles().orEmpty().single { it.name.startsWith("${provider.id}-") && it.extension == "srt" }.readText()
                    check(srt.startsWith("1\n00:00:02,000 --> 00:00:03,500\nسطر 1؟\n\n")) { "Made SubRip: ${srt.take(80)}" }
                    check("\n$CUES\n${time(CUES * 2_000L)} --> ${time(CUES * 2_000L + 1_500)}\nسطر $CUES؟\n" in srt) { "The last part is missing" }
                    activity?.finish()
                }
                await("Player did not close") { activity?.isDestroyed != false }

                forget()
                refuse = true
                fragment = open(language)
                runOnMainSync { invoke(fragment, "openPanel", field(fragment, "subtitlesPage")) }
                await("Make Persian subtitles was not offered again") { row(fragment, label) != null }
                runOnMainSync { checkNotNull(row(fragment, label)).performClick() }
                val rejected = fragment.getString(R.string.subtitle_translate_key, provider.id)
                await("A refused key did not say so") { text(fragment)?.contains(rejected) == true && row(fragment, fragment.getString(R.string.subtitle_translate_again)) != null }
                settle()
                capture("translate-refused-$language")
                runOnMainSync { activity?.finish() }
                await("Player did not close") { activity?.isDestroyed != false }
            }
            result.putString("stream", "PASS: offered with a key and English, counted up part by part, added checked as SubRip, refused key said so with a retry\n")
        } catch (error: Throwable) {
            result.putString("stream", error.stackTraceToString())
            resultCode = Activity.RESULT_CANCELED
        } finally {
            runOnMainSync { activity?.finish() }
            await("Player did not close") { activity?.isDestroyed != false }
            runCatching { forget() }
            runOnMainSync {
                prefs.edit().putString("subtitle", originalSubtitle).apply()
                Settings.saveKey(Translator.GEMINI.id, originalKey)
                Settings.translatorModel = originalModel
                Translator.client = originalTranslator
                Api.user = originalUser
                Api.language = originalLanguage
                Locale.setDefault(originalLocale)
                clientField.set(Api, originalClient)
            }
            api.dispatcher.cancelAll()
            models.dispatcher.cancelAll()
        }
        finish(resultCode, result)
    }

    /** The player on the fixture, its controls up. */
    private fun Instrumentation.open(language: String): PlayerFragment {
        val host = startActivitySync(PlayerActivity.intent(targetContext, 1, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlayerActivity
        activity = host
        var fragment: PlayerFragment? = null
        await("Player did not load ($language)") {
            fragment = host.supportFragmentManager.findFragmentById(android.R.id.content) as? PlayerFragment
            (fragment?.view as? ViewGroup)?.findViewById<View>(androidx.leanback.R.id.transport_row)?.height?.let { it > 0 } == true
        }
        return checkNotNull(fragment)
    }

    /** The player open now; null once it is gone. */
    private var activity: PlayerActivity? = null
        get() = field?.takeIf { !it.isDestroyed }

    /** The panel's row labelled [label]: the clickable parent of that text. */
    private fun row(fragment: PlayerFragment, label: String): View? {
        var view: View? = texts(fragment.view).firstOrNull { it.isShown && it.text.toString() == label } ?: return null
        while (view != null && !view.isClickable) view = view.parent as? View
        return view
    }

    /** All the panel says now, the page's note included. */
    private fun text(fragment: PlayerFragment) = texts(fragment.view).filter { it.isShown }.joinToString("\n") { it.text }

    private fun texts(view: View?): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }

    /** The panel slides and cross-fades its pages: a screenshot waits for it to come to rest. */
    private fun Instrumentation.settle() {
        waitForIdleSync()
        SystemClock.sleep(800)
    }

    private fun time(ms: Long) = String.format(Locale.US, "%02d:%02d:%02d,%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)

    private fun field(instance: Any, name: String): Any? =
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance)

    private fun invoke(instance: Any, name: String, vararg args: Any?) =
        instance.javaClass.declaredMethods.single { it.name == name && it.parameterCount == args.size }.apply { isAccessible = true }.invoke(instance, *args)

    private fun Instrumentation.await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        do {
            var passed = false
            runOnMainSync { passed = condition() }
            if (passed) return
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        error(message)
    }
}

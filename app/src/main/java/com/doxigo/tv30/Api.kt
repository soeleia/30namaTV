package com.doxigo.tv30

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.webkit.WebSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/**
 * The protocol the official 30tv app speaks (its bundled "SDK"): every call is
 * `POST {host}/action/{name}` with a form body and a handful of `c-*` headers, answered with
 * `{success, result}`. Hosts are not fixed: a JSON list on two CDNs names them and the app races
 * a `test` call against each, keeping whichever answers first.
 */
object Api {
    // The official app ships one key per platform; this is the one it sends as ANDROIDTV.
    private const val API_KEY = "VdapnkLG9EHsSmF8yeKcb4253RAuN6YX"
    private const val SDK_VERSION = "1.0.0"
    private val HOST_LISTS = listOf(
        "https://pub-ac69cf865ce145f8b4c1ed87dc2a9fb7.r2.dev/sdk-endpoint.json",
        "https://30update.s3.ir-thr-at1.arvanstorage.ir/sdk-endpoint.json",
    )
    private val FALLBACK_HOSTS = listOf("https://world.hana-api.com/api/v2", "https://ir.kazem-api.com/api/v2")

    lateinit var http: OkHttpClient
        private set
    private lateinit var prefs: SharedPreferences
    private lateinit var appContext: Context
    private val discovery = Mutex()

    /** For fire-and-forget calls that must outlive the screen that made them (progress reports). */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastDiscovery = 0L

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences("api", Context.MODE_PRIVATE)
        http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    var endpoint: String
        get() = prefs.getString("endpoint", null) ?: FALLBACK_HOSTS[0]
        private set(v) = prefs.edit().putString("endpoint", v).apply()

    var token: String?
        get() = prefs.getString("token", null)
        set(v) = prefs.edit().putString("token", v).apply()

    /** "fa" or "en": picks the language of titles and genres the server returns, and of the UI. */
    var language: String
        get() = prefs.getString("language", null) ?: "fa"
        set(v) = prefs.edit().putString("language", v).apply()

    /** Last `user` result, so the UI knows who is signed in before the network answers. */
    var user: JSONObject?
        get() = prefs.getString("user", null)?.let(::JSONObject)
        set(v) = prefs.edit().putString("user", v?.toString()).apply()

    val loggedIn get() = !token.isNullOrEmpty()

    // The official app sends the device's WebView user agent. Computing it loads WebView, which is
    // slow on these TVs, so it is done once and kept.
    val userAgent: String by lazy {
        prefs.getString("ua", null) ?: (runCatching { WebSettings.getDefaultUserAgent(appContext) }
            .getOrNull() ?: System.getProperty("http.agent") ?: "Mozilla/5.0").also {
            prefs.edit().putString("ua", it).apply()
        }
    }

    /** The server said no. Its message is always words for the screen, in the app's language. */
    class ApiError(message: String) : IOException(message)

    /** Runs [action] and returns its `result`, or throws [ApiError] worded by [refusal]. */
    suspend fun result(action: String, vararg params: Pair<String, Any?>): Any? {
        val json = call(action, *params)
        if (!json.optBoolean("success")) throw refusal(json.message())
        return json.opt("result")
    }

    /**
     * What a refusal says on screen. The server's `msg` is often a code ("CODE_NOT_USED", "login
     * required"), so it is shown only as a Persian sentence on a Persian screen, which is 30nama's
     * own copy; anything else gets the app's words. Never an action name.
     */
    private fun refusal(msg: String?) = ApiError(refusalText(msg, language, loggedIn, ::localized))

    internal fun refusalText(msg: String?, language: String, loggedIn: Boolean, text: (Int) -> String): String = when {
        msg != null && msg.contains("login required", ignoreCase = true) ->
            text(if (loggedIn) R.string.error_session else R.string.error_sign_in)
        msg != null && language == "fa" && !msg.isLatin -> msg
        else -> text(R.string.error_server)
    }

    /** The application context speaks the system's language; errors speak the app's. */
    private fun localized(id: Int): String = appContext.createConfigurationContext(
        Configuration(appContext.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) },
    ).getString(id)

    /** Actions that change the account: progress sync, Continue Watching, sign-out (lists: see [isWrite]). */
    private val WRITES = setOf("observer", "continue_watching_dismiss", "logout")

    /** A user_list call is a write (add, remove, whatever comes next) unless it says it only lists. */
    internal fun isWrite(action: String, params: Array<out Pair<String, Any?>>) =
        action in WRITES || (action == "user_list" && params.none { it.first == "action" && it.second == "list" })

    private fun readOnly(): Boolean = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, "debug.lily.readonly") == "1"
    }.getOrDefault(false)

    suspend fun call(action: String, vararg params: Pair<String, Any?>): JSONObject {
        // Automated emulator checks press keys blind, and a stray OK on a poster once played an
        // episode and moved real progress. `adb shell setprop debug.lily.readonly 1` makes a debug
        // build pretend every account write succeeded; release builds always write.
        if (isWrite(action, params) && BuildConfig.DEBUG && readOnly()) return JSONObject().put("success", true)
        val host = endpoint
        return try {
            post(host, action, params, retries = 2)
        } catch (e: IOException) {
            // What the official app does when a request gives up: find a host that answers.
            if (e !is ApiError && rediscover(host)) post(endpoint, action, params, retries = 1) else throw e
        }
    }

    private suspend fun post(host: String, action: String, params: Array<out Pair<String, Any?>>, retries: Int): JSONObject {
        var attempt = 1
        while (true) {
            try {
                return postOnce(host, action, params)
            } catch (e: IOException) {
                if (e is ApiError || attempt >= retries) throw e
                attempt++
                delay(1000)
            }
        }
    }

    private suspend fun postOnce(host: String, action: String, params: Array<out Pair<String, Any?>>): JSONObject {
        val body = FormBody.Builder().apply {
            for ((k, v) in params) if (v != null) add(k, v.toString())
        }.build()
        val request = Request.Builder()
            .url("$host/action/$action")
            .post(body)
            .header("c-api-key", API_KEY)
            .header("c-version-number", SDK_VERSION)
            .header("c-language", language)
            .header("user-agent", userAgent)
            .apply { token?.takeIf { it.isNotEmpty() }?.let { header("c-token", it) } }
            .build()
        return http.newCall(request).await().use { response ->
            if (response.header("cf-mitigated") != null) throw IOException("Cloudflare challenged $host")
            // home_stream alone is ~300 KB of JSON: parse it off the main thread.
            withContext(Dispatchers.IO) {
                val text = response.body?.string().orEmpty()
                if (BuildConfig.DEBUG) dump(action, text)
                // `observer` answers 200 with no body at all.
                if (text.isBlank() && response.isSuccessful) return@withContext JSONObject().put("success", true)
                runCatching { JSONObject(text) }.getOrNull() ?: throw IOException("HTTP ${response.code}: not JSON from $action")
            }
        }
    }

    /**
     * Endpoint discovery, as the official app does it at startup: keep the saved host if it
     * answers `test`, otherwise race every host from the published lists and keep the winner.
     */
    suspend fun initialize(): Boolean {
        if (ping(endpoint)) return true
        return rediscover(endpoint)
    }

    private suspend fun rediscover(failedHost: String): Boolean = discovery.withLock {
        if (endpoint != failedHost) return true // someone else already moved us
        if (System.currentTimeMillis() - lastDiscovery < 30_000) return false
        lastDiscovery = System.currentTimeMillis()
        val hosts = firstOf(HOST_LISTS) { hostList(it) }.orEmpty().ifEmpty { FALLBACK_HOSTS }
        val best = firstOf(hosts) { host -> host.takeIf { ping(it) } } ?: return false
        endpoint = best
        true
    }

    // The official SDK gives `test` 15 s; a host that slow is no use to a TV anyway.
    private suspend fun ping(host: String) = runCatching {
        withTimeout(10_000) { postOnce(host, "test", emptyArray()).optBoolean("success") }
    }.getOrDefault(false)

    private suspend fun hostList(url: String): List<String>? = runCatching {
        val text = http.newCall(Request.Builder().url("$url?t=${System.currentTimeMillis()}").build()).await()
            .use { withContext(Dispatchers.IO) { it.body!!.string() } }
        val arr = JSONArray(text)
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("host")?.takeIf(String::isNotEmpty) }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** Cancelling the coroutine cancels the request, so a lost race does not keep a socket open. */
    suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
            override fun onResponse(call: Call, response: Response) = cont.resume(response) { _, r, _ -> r.close() }
        })
    }

    /** Runs [f] on every item at once and returns the first non-null answer. */
    private suspend fun <T, R : Any> firstOf(items: List<T>, f: suspend (T) -> R?): R? = coroutineScope {
        val answers = Channel<R?>(items.size)
        val jobs = items.map { launch { answers.send(runCatching { f(it) }.getOrNull()) } }
        repeat(items.size) {
            answers.receive()?.let { winner ->
                jobs.forEach { it.cancel() }
                return@coroutineScope winner
            }
        }
        null
    }

    // 30nama answers with `msg` (often JSON null, which optString would read as "null").
    private fun JSONObject.message(): String? = str("msg") ?: str("message") ?: str("result", "message")

    private fun dump(action: String, text: String) = runCatching {
        val dir = appContext.filesDir.resolve("dump").apply { mkdirs() }
        dir.resolve("$action.json").writeText(text)
    }

    // The actions the official TV app uses, named and shaped exactly as it sends them.
    suspend fun user() = call("user")
    suspend fun logout() = call("logout")
    suspend fun qrCode() = result("qrlogin_code")
    suspend fun qrLogin(code: String) = call("qrlogin_login", "auth_code" to code)
    suspend fun home() = result("home_stream") as JSONObject
    suspend fun single(id: Int) = result("single", "post_id" to id) as JSONObject
    suspend fun menu() = result("menu") as JSONObject
    suspend fun search(query: String, page: Int) =
        result("search", "query" to query, "streamonly" to true, "page" to page) as JSONObject
    suspend fun archive(slugs: String, page: Int, orderBy: String) =
        result("archive", "slugs" to slugs, "streamonly" to true, "page" to page, "orderby" to orderBy, "order" to "DESC") as JSONObject
    /** [params] as the official SDK sends them, e.g. {"actor": "nm1172478", "stream": "true"}: people by IMDb id, as names match loosely. */
    suspend fun advancedSearch(params: JSONObject, page: Int, orderBy: String) =
        result("advanced_search", "parameters" to params.toString(), "page" to page, "orderby" to orderBy, "order" to "DESC") as JSONObject
    suspend fun continueWatching() = result("continue_watching")
    suspend fun dismissContinueWatching(id: Int) = call("continue_watching_dismiss", "id" to id)
    suspend fun stream(id: Int, free: Boolean) =
        result("stream", "post_id" to id, "free_stream" to free) as JSONObject
    /**
     * Where [seconds] of playback is reported: its 15 s mark. The official player reports only on
     * the marks (floor(time) % 15 == 0), and 30nama keeps nothing sent between them: reports at
     * 14, 29, 44 s… never put a title started from the beginning in Continue Watching.
     */
    fun progressMark(seconds: Long) = seconds / 15 * 15
    suspend fun observer(postId: Int, fileId: Any?, userId: Any?, time: Long, duration: Long, token: Any?) =
        call("observer", "post_id" to postId, "file_id" to fileId, "user_id" to userId, "time" to time, "duration" to duration, "token" to token)
    suspend fun myLists() = result("user_mylist") as JSONObject
    suspend fun userList(group: String, page: Int, orderBy: String) =
        result("user_list", "action" to "list", "group" to group, "page" to page, "orderby" to orderBy, "order" to "DESC") as JSONObject
    suspend fun toggleList(group: String, postId: Int) =
        call("user_list", "action" to "add", "group" to group, "post_id" to postId)
}

/** Which titles sit in the user's three lists; the details page shows and flips membership. */
object Library {
    val GROUPS = listOf("favorite", "watchlist", "notify")
    private val ids = HashMap<String, MutableSet<Int>>()

    suspend fun load() {
        val lists = Api.myLists()
        for (group in GROUPS) {
            val posts = lists.optJSONObject(group)?.optJSONArray("post")
            ids[group] = (0 until (posts?.length() ?: 0)).mapTo(HashSet()) { posts!!.optInt(it) }
        }
    }

    fun has(group: String, id: Int) = ids[group]?.contains(id) == true

    /**
     * Toggles the server has taken, so home refetches its My List row only after one. Counted once
     * the server answers: a row fetched before then would come back without the change.
     */
    var changes = 0
        private set

    /** Flips locally first, as the official app does, then tells the server. */
    suspend fun toggle(group: String, id: Int) {
        val set = ids.getOrPut(group) { HashSet() }
        if (!set.remove(id)) set.add(id)
        Api.toggleList(group, id)
        changes++
    }

    fun clear() = ids.clear()
}

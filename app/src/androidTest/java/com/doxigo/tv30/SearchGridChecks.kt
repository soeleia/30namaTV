package com.doxigo.tv30

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.leanback.widget.HorizontalGridView
import androidx.leanback.widget.VerticalGridView
import androidx.leanback.widget.ItemBridgeAdapter
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.ObjectAdapter
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.VerticalGridPresenter
import android.widget.FrameLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object SearchGridChecks {
    fun run(instrumentation: Instrumentation) = with(instrumentation) {
        val result = Bundle()
        var resultCode = Activity.RESULT_OK
        var activity: Activity? = null
        val originalClient = Api.http
        val originalLanguage = Api.language
        val originalLocale = Locale.getDefault()
        val originalRecents = Recents.read(instrumentation.targetContext)
        val clientField = Api::class.java.getDeclaredField("http").apply { isAccessible = true }
        val requests = Collections.synchronizedList(mutableListOf<Pair<String, Int>>())
        val slowStarted = CountDownLatch(1)
        val releaseSlow = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val body = chain.request().body as? FormBody
            if (body == null) return@addInterceptor Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(JSONArray().put(JSONObject().put("host", Api.endpoint)).toString().toResponseBody()).build()
            val params = (0 until body.size).associate { body.name(it) to body.value(it) }
            val query = params["query"] ?: "suggested"
            val page = params["page"]?.toInt() ?: 1
            requests += query to page
            if (query == "slow") {
                slowStarted.countDown()
                check(releaseSlow.await(15, TimeUnit.SECONDS))
            }
            val total = when (query) { "none" -> 0; "suggested", "beta" -> 48; else -> 12 }
            val posts = JSONArray()
            for (i in (page - 1) * 24 until minOf(page * 24, total)) posts.put(JSONObject()
                .put("id", i + 1).put("title", JSONObject().put("english", "$query $i"))
                .put("options", JSONObject().put("title_type", "movie")))
            val payload = JSONObject().put("success", query != "error")
                .put("result", JSONObject().put("posts", posts).put("page", page).put("pages", maxOf(1, (total + 23) / 24)).put("total", total))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(payload.toString().toResponseBody()).build()
        }.build()
        try {
            cachedPagePublishesAfterLayout()
            for (language in listOf("fa", "en")) {
                runOnMainSync {
                    Api.language = language
                    clientField.set(Api, client)
                    // Opens on suggestions alone; the recent searches have their own pass below.
                    Recents.clear(targetContext)
                }
                activity = startActivitySync(Intent(targetContext, SearchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                val host = checkNotNull(activity)
                lateinit var fragment: SearchFragment
                lateinit var grid: VerticalGridView
                lateinit var editor: TextView
                await("Search suggestions did not load") {
                    val found = (host as SearchActivity).supportFragmentManager.findFragmentById(R.id.browse_host) as? SearchFragment
                    val current = found?.view?.findViewById<VerticalGridView>(androidx.leanback.R.id.browse_grid)
                    if (current?.isFocusable != true) false else {
                        fragment = checkNotNull(found)
                        grid = current
                        editor = checkNotNull(found.view).findViewById(androidx.leanback.R.id.lb_search_text_editor)
                        true
                    }
                }
                runOnMainSync {
                    check(editor.hasFocus()) { "Search stopped opening on the keyboard" }
                    check(grid.verticalSpacing == (POSTER_GRID_ROW_SPACING_DP * host.resources.displayMetrics.density).toInt())
                }
                await("Search keyboard did not appear") { keyboardVisible(host) }
                press(KeyEvent.KEYCODE_BACK)
                await("Keyboard dismissal did not reach search results", detail = {
                    "ime=${keyboardVisible(host)}, windowFocus=${host.hasWindowFocus()}, grid.hasFocus=${grid.hasFocus()}, selectedPosition=${grid.selectedPosition}, focus=${host.currentFocus?.javaClass?.simpleName}, focusId=${host.currentFocus?.id}"
                }) { !keyboardVisible(host) && grid.hasFocus() && grid.selectedPosition >= 0 }
                var landing = 0
                runOnMainSync { landing = grid.selectedPosition }
                press(KeyEvent.KEYCODE_DPAD_DOWN)
                await("Search results are not a six-column vertical grid", detail = {
                    "landing=$landing, selectedPosition=${grid.selectedPosition}, focusId=${host.currentFocus?.id}"
                }) { grid.selectedPosition == landing + 6 }
                await("Search grid did not paginate") { (field(fragment, "pager") as Pager).adapter.size() == 48 }
                runOnMainSync { rowGap(grid) }
                press(KeyEvent.KEYCODE_DPAD_UP)
                await("Up did not return to the previous row") { grid.selectedPosition == landing }
                runOnMainSync { grid.selectedPosition = 0 }
                await("Search could not select the first column") { grid.selectedPosition == 0 && grid.hasFocus() && grid.scrollState == 0 }
                val intoRail = if (language == "fa") KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
                press(intoRail)
                await("Search first column did not enter the rail", detail = {
                    "grid.hasFocus=${grid.hasFocus()}, selectedPosition=${grid.selectedPosition}, scrollState=${grid.scrollState}, focus=${host.currentFocus?.javaClass?.simpleName}, focusId=${host.currentFocus?.id}"
                }) { host.findViewById<View>(R.id.rail).hasFocus() }
                press(if (language == "fa") KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
                await("Rail did not return to the search card") { grid.hasFocus() && grid.selectedPosition == 0 }
                press(KeyEvent.KEYCODE_DPAD_UP)
                await("Up from search first row did not return to the editor") { editor.hasFocus() }
                await("Search keyboard did not reopen") { keyboardVisible(host) }
                runOnMainSync {
                    host.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(editor.windowToken, 0)
                }
                await("Search keyboard did not hide before Down") { !keyboardVisible(host) }
                var dismissedIntoResults = false
                runOnMainSync { dismissedIntoResults = grid.hasFocus() }
                if (!dismissedIntoResults) press(KeyEvent.KEYCODE_DPAD_DOWN)
                await("Down after keyboard dismissal did not reach search results", detail = {
                    "ime=${keyboardVisible(host)}, windowFocus=${host.hasWindowFocus()}, touchMode=${editor.isInTouchMode}, grid.hasFocus=${grid.hasFocus()}, focus=${host.currentFocus?.javaClass?.simpleName}"
                }) { grid.hasFocus() }
                press(KeyEvent.KEYCODE_DPAD_UP)
                await("Up did not return to typing") { editor.hasFocus() }

                runOnMainSync {
                    editor.requestFocus()
                    editor.text = "alpha"
                    editor.text = "beta"
                }
                await("Debounced search did not load") { first(fragment)?.title == "beta 0" }
                check(requests.none { it.first == "alpha" }) { "Search fetched a replaced query before typing settled" }
                runOnMainSync {
                    check(editor.hasFocus()) { "Typing moved focus into results" }
                    fragment.onSearchQuerySubmit("beta")
                }
                await("Search submit did not focus results") { grid.hasFocus() }
                SystemClock.sleep(300)
                capture("search-grid-$language")
                var clicked = false
                var clickPosition = 0
                runOnMainSync {
                    val items = (field(fragment, "pager") as Pager).adapter
                    clickPosition = grid.selectedPosition
                    items.replace(clickPosition, Card("Click check") { clicked = true })
                }
                await("Click fixture did not bind to its focused poster") {
                    val holder = grid.findViewHolderForAdapterPosition(clickPosition) as? ItemBridgeAdapter.ViewHolder
                    (holder?.item as? Card)?.title == "Click check" && holder?.itemView?.hasFocus() == true && grid.selectedPosition == clickPosition && grid.scrollState == 0
                }
                press(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Grid lost poster click callbacks") { clicked }

                if (language == "fa") {
                    text(editor, "slow")
                    runOnMainSync { fragment.onSearchQuerySubmit("slow") }
                    check(slowStarted.await(5, TimeUnit.SECONDS))
                    text(editor, "fresh")
                    runOnMainSync { fragment.onSearchQuerySubmit("fresh") }
                    releaseSlow.countDown()
                    await("Cancelled search replaced current results") { first(fragment)?.title == "fresh 0" }
                }
                text(editor, "none")
                runOnMainSync { fragment.onSearchQuerySubmit("none") }
                await("Empty search stayed on a skeleton") {
                    (field(fragment, "pager") as Pager).adapter.size() == 0 &&
                        (field(fragment, "heading") as TextView).text.toString() == host.getString(R.string.no_results) && !grid.isFocusable
                }
                text(editor, "error")
                runOnMainSync { fragment.onSearchQuerySubmit("error") }
                await("Search error stayed on a skeleton") { (field(fragment, "pager") as Pager).adapter.size() == 0 && !grid.isFocusable }
                runOnMainSync {
                    fragment.onActivityResult(1, Activity.RESULT_OK, Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf("voice")))
                }
                await("Voice result did not populate and focus the grid") { first(fragment)?.title == "voice 0" && grid.hasFocus() }
                runOnMainSync { check(editor.text.toString() == "voice") }

                recentSearches(host, fragment, grid, editor, language)
                persianKeyboard(host, fragment, grid, editor, language, requests)
                runOnMainSync { host.finish() }
                await("Search activity did not close") { host.isDestroyed }
                activity = null
            }
            activity = startActivitySync(GridActivity.archive(targetContext, "movies", "Grid check").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val host = checkNotNull(activity)
            await("Category grid did not load") {
                val grid = host.findViewById<VerticalGridView>(androidx.leanback.R.id.browse_grid)
                if (grid?.findViewHolderForAdapterPosition(6) == null) false else {
                    rowGap(grid)
                    check(grid.verticalSpacing == (POSTER_GRID_ROW_SPACING_DP * host.resources.displayMetrics.density).toInt())
                    true
                }
            }
            result.putString("stream", "PASS: cached pagination after layout, six-column RTL/LTR search, D-pad/editor/rail/clicks, row spacing, pagination, debounce/cancel, empty/error and voice results, recent searches, in-app Persian keyboard\n")
        } catch (error: Throwable) {
            capture("search-failure")
            result.putString("stream", error.stackTraceToString())
            resultCode = Activity.RESULT_CANCELED
        } finally {
            releaseSlow.countDown()
            runOnMainSync { activity?.finish() }
            await("Grid activity did not close") { activity?.isDestroyed != false }
            runOnMainSync {
                Api.language = originalLanguage
                Locale.setDefault(originalLocale)
                clientField.set(Api, originalClient)
                Recents.clear(targetContext)
                originalRecents.asReversed().forEach { Recents.add(targetContext, it) }
            }
            client.dispatcher.cancelAll()
        }
        finish(resultCode, result)
    }

    private fun Instrumentation.cachedPagePublishesAfterLayout() {
        val host = startActivitySync(Intent(targetContext, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val scope = CoroutineScope(Job() + Dispatchers.Main.immediate)
        val items = ArrayObjectAdapter(PosterPresenter(112, 168))
        val presenter = VerticalGridPresenter(FocusHighlight.ZOOM_FACTOR_NONE, false).apply {
            numberOfColumns = 6
            shadowEnabled = false
        }
        var holder: VerticalGridPresenter.ViewHolder? = null
        var pager: Pager? = null
        lateinit var grid: VerticalGridView
        var selectedDuringLayout = false
        var fetchedDuringLayout = false
        var insertedDuringLayout = false
        val requests = mutableListOf<Int>()
        val errors = mutableListOf<Throwable>()
        try {
            runOnMainSync {
                val views = presenter.onCreateViewHolder(FrameLayout(host))
                holder = views
                grid = views.gridView
                grid.selectedPosition = 0
                presenter.setOnItemViewSelectedListener(OnItemViewSelectedListener { _, item, _, _ ->
                    if (!selectedDuringLayout && grid.isComputingLayout && item is Card) {
                        selectedDuringLayout = true
                        pager?.onSelected(item)
                    }
                })
                presenter.onBindViewHolder(views, items)
                items.registerObserver(object : ObjectAdapter.DataObserver() {
                    override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                        insertedDuringLayout = insertedDuringLayout || grid.isComputingLayout
                    }
                })
                pager = Pager(scope, items, { Card("Cached ${it.getInt("id")}") {} }, onError = { errors += it }) { page ->
                    requests += page
                    if (page == 2) fetchedDuringLayout = grid.isComputingLayout
                    JSONObject().put("page", page).put("pages", 2)
                        .put("posts", JSONArray().put(JSONObject().put("id", page)))
                }
                host.setContentView(views.view)
                pager?.reset()
                grid.requestFocus()
            }
            await("Immediate cached pagination did not finish outside layout", detail = {
                "selectedDuringLayout=$selectedDuringLayout, requests=$requests, cards=${items.size()}, errors=$errors"
            }) { selectedDuringLayout && requests == listOf(1, 2) && items.size() == 2 }
            var failure: Throwable? = null
            runOnMainSync {
                if (fetchedDuringLayout || insertedDuringLayout || errors.isNotEmpty()) {
                    failure = IllegalStateException("Cached fetch/publication ran during layout: fetch=$fetchedDuringLayout, insert=$insertedDuringLayout, errors=$errors")
                }
            }
            failure?.let { throw it }
        } finally {
            runOnMainSync {
                pager?.cancel()
                scope.cancel()
                holder?.let(presenter::onUnbindViewHolder)
                host.finish()
            }
            await("Cached pagination fixture did not close") { host.isDestroyed }
        }
    }

    /**
     * The empty box's recent searches, from the voice results the caller left: opening one keeps its
     * query; clearing the box brings it back over the suggestions; Down goes box, recents, grid and
     * Up back; OK on a recent runs it again and hands the remote to its results; Clear forgets them.
     */
    private fun Instrumentation.recentSearches(host: Activity, fragment: SearchFragment, grid: VerticalGridView, editor: TextView, language: String) {
        val recents = field(fragment, "recents") as View
        val strip = field(fragment, "strip") as HorizontalGridView
        // The title page is held back: only the click's own bookkeeping is under test.
        val details = addMonitor(DetailsActivity::class.java.name, null, true)
        try {
            runOnMainSync { checkNotNull(first(fragment)).onClick() }
            check(details.hits == 1) { "Opening a result did not open its title" }
        } finally {
            removeMonitor(details)
        }
        check(Recents.read(targetContext) == listOf("voice")) { "Opening a result did not keep its query: ${Recents.read(targetContext)}" }

        /**
         * Back to the box, emptied, once the results have taken the remote and the keyboard has gone
         * down with it: a person's pace, where the test's was 0.13 s. On Android 16 the phone
         * keyboard of this landscape emulator goes full screen for the results' non-text focus and
         * hides itself there (HIDE_SOFT_INPUT_EXTRACT_INPUT_CHANGED); the box's show, asked for
         * before the keyboard had caught up, was cancelled by that hide and never came. A TV's
         * keyboard is never full screen, and nobody is back in the box that fast.
         */
        fun backToBox() {
            await("The keyboard did not go down with the remote") { !keyboardVisible(host) }
            SystemClock.sleep(600)
            text(editor, "")
        }

        fun downFromBox() {
            await("Search keyboard did not open with the editor") { keyboardVisible(host) }
            runOnMainSync { host.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(editor.windowToken, 0) }
            await("Search keyboard did not hide") { !keyboardVisible(host) }
            var there = false
            runOnMainSync { there = strip.hasFocus() }
            if (!there) press(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Down from the box did not reach the recent searches", detail = {
                "recents=${recents.visibility}, focus=${host.currentFocus?.javaClass?.simpleName}"
            }) { strip.hasFocus() }
        }

        backToBox()
        await("Clearing the box did not bring back the recent searches") {
            recents.visibility == View.VISIBLE && strip.adapter?.itemCount == 2 && grid.isFocusable
        }
        downFromBox()
        await("Recent searches did not open on the newest") { strip.selectedPosition == 0 }
        SystemClock.sleep(300)
        capture("search-recents-$language")
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        await("Down from the recent searches did not reach the suggestions") { grid.hasFocus() }
        runOnMainSync { grid.selectedPosition = 0 }
        await("Suggestions could not select the first poster") { grid.selectedPosition == 0 && grid.hasFocus() && grid.scrollState == 0 }
        press(KeyEvent.KEYCODE_DPAD_UP)
        await("Up from the suggestions did not return to the recent searches") { strip.hasFocus() }
        press(KeyEvent.KEYCODE_DPAD_UP)
        await("Up from the recent searches did not return to the editor") { editor.hasFocus() }
        downFromBox()

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        await("A recent search did not run again into focused results", detail = {
            "text=${editor.text}, first=${first(fragment)?.title}, grid.hasFocus=${grid.hasFocus()}, recents=${recents.visibility}, focus=${host.currentFocus?.javaClass?.simpleName}"
        }) { editor.text.toString() == "voice" && first(fragment)?.title == "voice 0" && grid.hasFocus() && recents.visibility == View.GONE }

        backToBox()
        await("Recent searches did not come back") { recents.visibility == View.VISIBLE && grid.isFocusable }
        downFromBox()
        press(if (language == "fa") KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
        await("Clear did not follow the last recent search") { strip.selectedPosition == 1 && strip.hasFocus() }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        await("Clear did not forget the recent searches and hand the remote to the suggestions") {
            Recents.read(targetContext).isEmpty() && recents.visibility == View.GONE && grid.hasFocus()
        }
    }

    /**
     * The box's own Persian keyboard, from the suggestions [recentSearches] left with the remote:
     * Up from under its button reaches the button without going through the field; OK brings the
     * keys out on «ا»; the D-pad alone types «کیک‌ها ۲» (ک and ی in their Persian forms, a half
     * space, a space, a digit, and a slip taken back with delete), which reaches the search as
     * typing does while the system keyboard stays down; the remote crosses into the results and back,
     * and up to the button and down again without passing the field; Back puts the keys away onto
     * the results.
     */
    private fun Instrumentation.persianKeyboard(host: Activity, fragment: SearchFragment, grid: VerticalGridView, editor: TextView, language: String, requests: List<Pair<String, Int>>) {
        val keys = field(fragment, "keys") as ViewGroup
        val toggle = field(fragment, "toggle") as View
        val open = { field(fragment, "open") == true }
        runOnMainSync { grid.selectedPosition = 5 }
        await("Suggestions could not select the first row's end") { grid.selectedPosition == 5 && grid.hasFocus() && grid.scrollState == 0 }
        press(KeyEvent.KEYCODE_DPAD_UP)
        await("Up from under the keyboard's button did not reach it", detail = { "focus=${host.currentFocus}" }) { toggle.hasFocus() }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        await("The Persian keyboard did not open on its first letter", detail = { "open=${open()}, focus=${host.currentFocus}" }) {
            open() && keys.alpha == 1f && (host.currentFocus as? TextView)?.text?.toString() == "ا"
        }

        /** Row and place in it, counted from the row's start: the rows run right to left in either language. */
        fun at(key: View) = keys.indexOfChild(key.parent as View) to (key.parent as ViewGroup).indexOfChild(key)
        fun reach(name: String, target: (View) -> Boolean) {
            repeat(24) {
                var from = 0 to 0
                var to = 0 to 0
                runOnMainSync {
                    val focus = checkNotNull(host.currentFocus) { "Focus left the window on the way to $name" }
                    check(focus.parent?.parent === keys) { "Focus left the keys on the way to $name: $focus" }
                    from = at(focus)
                    to = at((0 until keys.childCount).flatMap { r -> (keys.getChildAt(r) as ViewGroup).let { row -> (0 until row.childCount).map(row::getChildAt) } }.single(target))
                }
                if (from == to) return press(KeyEvent.KEYCODE_DPAD_CENTER)
                press(when {
                    from.first < to.first -> KeyEvent.KEYCODE_DPAD_DOWN
                    from.first > to.first -> KeyEvent.KEYCODE_DPAD_UP
                    from.second < to.second -> KeyEvent.KEYCODE_DPAD_LEFT
                    else -> KeyEvent.KEYCODE_DPAD_RIGHT
                })
            }
            error("The D-pad did not reach $name")
        }
        fun letter(text: String) = reach(text) { (it as? TextView)?.text?.toString() == text }

        val word = "\u06A9\u06CC\u06A9\u200C\u0647\u0627 \u06F2" // کیک‌ها ۲
        for (c in listOf("\u06A9", "\u06CC", "\u06A9", "\u0628")) letter(c)
        reach("delete") { it.contentDescription == host.getString(R.string.search_key_delete) }
        letter(host.getString(R.string.search_key_zwnj))
        letter("\u0647")
        letter("\u0627")
        letter(host.getString(R.string.search_key_space))
        letter("\u06F2")
        await("The D-pad's word did not reach the search", detail = {
            "text=${editor.text.map { "%04X".format(it.code) }}, first=${first(fragment)?.title}, ime=${keyboardVisible(host)}"
        }) { editor.text.toString() == word && first(fragment)?.title == "$word 0" && grid.isFocusable }
        check(requests.any { it.first == word }) { "The word never reached the search: $requests" }
        await("The system keyboard came up over the keys") { !keyboardVisible(host) && !editor.hasFocus() }
        SystemClock.sleep(400)
        capture("search-keyboard-$language")

        // The keys' edges: on into the results and back, up to the button (never the field), down again.
        val towardResults = if (language == "fa") KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        fun pressUntil(code: Int, message: String, done: () -> Boolean) {
            repeat(10) {
                var there = false
                runOnMainSync { there = done(); check(!editor.hasFocus()) { "The keys handed the remote to the field" } }
                if (there) return
                press(code)
            }
            await(message, detail = { "focus=${host.currentFocus}" }, condition = done)
        }
        pressUntil(towardResults, "The keys did not lead on into the results") { grid.hasFocus() }
        press(if (language == "fa") KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
        await("The results did not lead back into the keys") { keys.hasFocus() }
        pressUntil(KeyEvent.KEYCODE_DPAD_UP, "Up from the keys did not reach their button") { toggle.hasFocus() }
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        await("Down from the button did not go back into the keys") { keys.hasFocus() }

        press(KeyEvent.KEYCODE_BACK)
        await("Back did not put the keys away onto the results", detail = { "open=${open()}, keys=${keys.visibility}, focus=${host.currentFocus}" }) {
            !open() && keys.visibility == View.GONE && grid.hasFocus() && !host.isFinishing
        }
        await("Putting the keys away lost the word or the button's name") {
            editor.text.toString() == word && (toggle as TextView).text.toString() == host.getString(R.string.search_keyboard)
        }
    }

    private fun first(fragment: SearchFragment): Card? =
        (field(fragment, "pager") as? Pager)?.adapter?.let { if (it.size() > 0) it[0] as? Card else null }

    private fun rowGap(grid: VerticalGridView) {
        val first = checkNotNull(grid.findViewHolderForAdapterPosition(0)).itemView
        val second = checkNotNull(grid.findViewHolderForAdapterPosition(6)).itemView
        check(second.top - first.bottom == grid.verticalSpacing) { "Poster rows do not have the shared vertical gap" }
    }

    private fun field(instance: Any, name: String): Any? =
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance)

    private fun keyboardVisible(host: Activity) =
        ViewCompat.getRootWindowInsets(host.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true

    private fun Instrumentation.text(editor: TextView, value: String) {
        runOnMainSync { editor.requestFocus(); editor.text = value }
        waitForIdleSync()
    }

    private fun Instrumentation.press(code: Int) {
        sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, code).apply { source = InputDevice.SOURCE_DPAD })
        sendKeySync(KeyEvent(KeyEvent.ACTION_UP, code).apply { source = InputDevice.SOURCE_DPAD })
        waitForIdleSync()
    }

    private fun Instrumentation.await(message: String, detail: (() -> String)? = null, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 7_000
        do {
            var passed = false
            runOnMainSync { passed = condition() }
            if (passed) return
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        var state = ""
        runOnMainSync { state = detail?.invoke().orEmpty() }
        error(if (state.isEmpty()) message else "$message: $state")
    }
}

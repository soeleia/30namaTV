package com.doxigo.tv30

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Run the existing instrumentation runner with -e check updates. */
object UpdatesChecks {
    fun run(instrumentation: Instrumentation) = with(instrumentation) {
        // Run alone on a fresh emulator no key has reached yet, the window is in touch mode and refuses focus.
        setInTouchMode(false)
        waitForIdleSync()
        val result = Bundle()
        var resultCode = Activity.RESULT_OK
        var activity: Activity? = null
        val language = Api.language
        val locale = Locale.getDefault()
        val state = mutableState()
        val original = state.value
        val prefs = targetContext.getSharedPreferences("updates", Activity.MODE_PRIVATE)
        val hadDownload = prefs.contains("download_id")
        val downloadId = prefs.getLong("download_id", -1)
        val hadVersion = prefs.contains("download_version")
        val downloadVersion = prefs.getString("download_version", null)
        val dismissed = prefs.getString("notice_dismissed", null)
        val originalClient = Api.http
        val originalToken = Api.token
        val originalUser = Api.user
        val originalIntro = Intro.shown
        val opened = mutableListOf<Activity>()
        try {
            check(prefs.edit().remove("download_id").remove("download_version").commit())
            releaseMetadata()
            providerAndInvalidApk()
            archiveSignersMatchInstalledApk()
            for (code in listOf("fa", "en")) {
                onMain { Api.language = code; state.value = AppUpdates.Status.Idle }
                activity = startActivitySync(SettingsActivity.update(targetContext).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                val host = checkNotNull(activity)
                waitForIdleSync()
                lateinit var update: View
                onMain {
                    update = host.row(R.string.app_update)
                    check(update.hasFocus()) { "Settings did not open on App update: focus ${host.currentFocus}, window ${host.hasWindowFocus()}, touch ${host.window.decorView.isInTouchMode}" }
                    check(update.findViewById<View>(R.id.settings_toggle).visibility == View.GONE)
                    check(update.findViewById<View>(R.id.settings_trail).visibility == View.VISIBLE)
                    check(host.findViewById<TextView>(R.id.settings_detail_title).text.toString() == host.getString(R.string.app_update))
                    check(host.findViewById<TextView>(R.id.settings_detail_body).text.toString() == host.getString(R.string.app_update_help))
                }
                press(KeyEvent.KEYCODE_DPAD_UP)
                onMain { check(host.row(R.string.version).hasFocus()) { "Remote could not reach Version" } }
                press(KeyEvent.KEYCODE_DPAD_DOWN)
                onMain { check(update.hasFocus()) }
                press(if (code == "fa") KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
                onMain { check(host.findViewById<ViewGroup>(R.id.settings_categories).getChildAt(2).hasFocus()) }
                press(if (code == "fa") KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
                repeat(6) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
                onMain { check(update.hasFocus()) { "Remote could not return to App update" } }

                val version = "9.0.0"
                val states = listOf(
                    Expected(AppUpdates.Status.Idle, R.string.update_check),
                    Expected(AppUpdates.Status.Checking, R.string.update_checking),
                    Expected(AppUpdates.Status.Current, R.string.update_current),
                    Expected(AppUpdates.Status.Downloading(version, 40, 100), R.string.settings_percent, R.string.update_download_version, 40),
                    Expected(AppUpdates.Status.Downloading(version, 0, 0), R.string.update_downloading, R.string.update_download_version),
                    Expected(AppUpdates.Status.Ready(version), R.string.update_install, R.string.update_ready),
                    Expected(AppUpdates.Status.Failed(AppUpdates.Failure.NETWORK), R.string.update_retry, R.string.update_failed),
                    Expected(AppUpdates.Status.Failed(AppUpdates.Failure.INVALID_APK), R.string.update_retry, R.string.update_invalid),
                    Expected(AppUpdates.Status.Failed(AppUpdates.Failure.INSTALLER_UNAVAILABLE), R.string.update_retry, R.string.update_no_installer),
                    Expected(AppUpdates.Status.Failed(AppUpdates.Failure.INCOMPATIBLE_SIGNATURE), R.string.update_releases, R.string.update_signature_mismatch),
                )
                for (expected in states) {
                    val value = if (expected.percent != null) host.getString(expected.value, expected.percent) else host.getString(expected.value)
                    val note = when (expected.note) {
                        0 -> null
                        R.string.update_download_version, R.string.update_ready -> host.getString(expected.note, version.localDigits())
                        else -> host.getString(expected.note)
                    }
                    onMain { state.value = expected.status }
                    await("Update row did not refresh for $code: ${expected.status}") {
                        update.findViewById<TextView>(R.id.settings_value).text.toString() == value &&
                            host.findViewById<TextView>(R.id.settings_note).let {
                                if (note == null) it.visibility == View.GONE else it.visibility == View.VISIBLE && it.text.toString() == note
                            }
                    }
                    waitForIdleSync()
                    onMain {
                        check(host.row(R.string.app_update) === update && update.hasFocus()) { "Status refresh rebuilt or unfocused the update row" }
                        fits(update.findViewById(R.id.settings_label))
                        fits(update.findViewById(R.id.settings_value))
                        val label = update.findViewById<TextView>(R.id.settings_label)
                        val action = update.findViewById<TextView>(R.id.settings_value)
                        // On screen: the label sits in its own column (with a service's second line), not straight in the row.
                        val labelAt = IntArray(2).also(label::getLocationOnScreen)[0]
                        val actionAt = IntArray(2).also(action::getLocationOnScreen)[0]
                        check(if (code == "fa") labelAt >= actionAt + action.width else labelAt + label.width <= actionAt) { "Update label overlapped its action" }
                        check(update.contentDescription.toString().contains(value))
                    }
                    if (expected.status is AppUpdates.Status.Ready) {
                        SystemClock.sleep(300) // a screenshot straight after the change can still hold the last frame
                        screenshot("updates-$code-ready.png")
                        val installer = addMonitor(null as IntentFilter?, null, true)
                        try {
                            press(KeyEvent.KEYCODE_DPAD_CENTER)
                            SystemClock.sleep(300)
                            check(installer.hits == 0) { "A Ready display without a verified download opened installation" }
                        } finally { removeMonitor(installer) }
                    }
                }
                val browser = addMonitor(IntentFilter(Intent.ACTION_VIEW).apply { addDataScheme("https") }, null, true)
                try {
                    press(KeyEvent.KEYCODE_DPAD_CENTER)
                    check(browser.hits == 1) { "Signature mismatch did not open the release guide" }
                } finally {
                    removeMonitor(browser)
                }
                onMain { host.finish() }
                await("Settings did not stop") { host.isDestroyed }
                activity = null
            }
            for (code in listOf("fa", "en")) homeNotice(code, state, prefs, opened)
            result.putString("stream", "PASS: App update row in Persian/English, D-pad/About navigation, live status/progress/notes without focus loss, release-guide action, private narrow FileProvider, install permission, invalid APK rejection and matching nonempty archive signers; home's notice in Persian/English (never takes the remote, end corner over bright art, Up/Down from the hero, Back remembered per version, waits while home is behind) and the rail's Settings dot leading to the update row\n")
        } catch (error: Throwable) {
            result.putString("stream", error.stackTraceToString())
            resultCode = Activity.RESULT_CANCELED
        } finally {
            onMain { activity?.finish(); opened.forEach(Activity::finish) }
            (listOfNotNull(activity) + opened).forEach { host -> await("$host did not stop during cleanup") { host.isDestroyed } }
            val edit = prefs.edit()
            if (hadDownload) edit.putLong("download_id", downloadId) else edit.remove("download_id")
            if (hadVersion) edit.putString("download_version", downloadVersion) else edit.remove("download_version")
            if (dismissed != null) edit.putString("notice_dismissed", dismissed) else edit.remove("notice_dismissed")
            check(edit.commit())
            onMain {
                state.value = original
                Api.language = language
                Locale.setDefault(locale)
                httpField.set(Api, originalClient)
                Api.token = originalToken
                Api.user = originalUser
                Intro.shown = originalIntro
                targetContext.filesDir.resolve("home").deleteRecursively()
            }
        }
        finish(resultCode, result)
    }

    /**
     * Home on fixture answers (a guest, two promoted titles over bright art), and fixture releases
     * set straight on AppUpdates' state. What it opens goes in [opened], for the caller's cleanup.
     */
    private fun Instrumentation.homeNotice(code: String, state: MutableStateFlow<AppUpdates.Status>, prefs: SharedPreferences,
                                           opened: MutableList<Activity>) {
        val art = brightArt()
        fun posts(name: String, count: Int) = JSONArray().apply {
            for (i in 0 until count) put(JSONObject().put("id", name.hashCode() + i).put("title", JSONObject().put("english", "$name $i"))
                .put("options", JSONObject().put("title_type", "movie")).put("info", JSONObject().put("year", "${2010 + i}"))
                .put("plot", JSONObject().put("english", "A fixture title over bright art."))
                .put("image", JSONObject().put("cover", JSONObject().put("jpg", art)).put("poster", JSONObject().put("jpg", JSONObject().put("large", art)))))
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val data = if (request.url.pathSegments.last() != "home_stream") JSONObject() else JSONObject()
                .put("hero_header", JSONObject().put("posts", posts("Promoted", 2)))
                .put("suggested", JSONObject().put("posts", posts("Suggested", 6)))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(JSONObject().put("success", true).put("result", data).toString().toResponseBody()).build()
        }.build()
        onMain {
            Api.language = code
            Api.token = null
            Api.user = null
            Intro.shown = true
            httpField.set(Api, client)
            state.value = AppUpdates.Status.Idle
            targetContext.filesDir.resolve("home").deleteRecursively()
        }
        check(prefs.edit().remove("notice_dismissed").commit())
        val home = startActivitySync(Intent(targetContext, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_GUEST, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        opened += home
        lateinit var more: View
        await("Home did not arrive on the hero for $code", 10_000) {
            more = home.findViewById(R.id.promo_more) ?: return@await false
            more.hasFocus() && home.findViewById<View>(androidx.leanback.R.id.browse_container_dock)?.alpha == 1f
        }
        val notice = home.findViewById<View>(R.id.update_notice)
        val items = home.findViewById<ViewGroup>(R.id.rail_items)
        val settings = items.getChildAt(items.childCount - 1)
        val badge = settings.findViewById<View>(R.id.rail_badge)
        onMain {
            check(notice.visibility == View.GONE && badge.visibility == View.GONE) { "Something showed with no update ready" }
            check(settings.contentDescription == home.getString(R.string.settings))
        }

        // Ready: the notice comes in and the rail marks Settings, while the remote stays on the hero.
        onMain { state.value = AppUpdates.Status.Ready("1.5.0") }
        await("The notice did not come in for $code") { notice.visibility == View.VISIBLE && notice.alpha == 1f && badge.visibility == View.VISIBLE }
        onMain {
            check(more.hasFocus() && !notice.hasFocus()) { "The notice took the remote: ${home.currentFocus}" }
            val text = notice.findViewById<TextView>(R.id.update_notice_text)
            check(text.text.toString() == home.getString(R.string.update_notice, "1.5.0".localDigits())) { "Notice said ${text.text}" }
            check(code == "en" || text.text.contains("۱.۵.۰")) { "Persian notice kept Latin digits: ${text.text}" }
            fits(text)
            fits(notice.findViewById(R.id.update_notice_action))
            check(notice.contentDescription.contains("1.5.0".localDigits()))
            check(settings.contentDescription.contains("1.5.0".localDigits())) { "The rail's Settings did not say an update is ready" }
            // In the end corner: left of centre in Persian, right of it in English, within the safe edges and clear of the words.
            val box = Rect().also(notice::getGlobalVisibleRect)
            val words = Rect().also(home.findViewById<View>(R.id.billboard)::getGlobalVisibleRect)
            val dp = home.resources.displayMetrics.density
            val width = home.window.decorView.width
            check(if (code == "fa") box.right < width / 2 && box.left >= (48 * dp).toInt() - 1
                else box.left > width / 2 && box.right <= width - (48 * dp).toInt() + 1) { "Notice at $box is not in the $code end corner of $width" }
            check(!Rect.intersects(box, words)) { "Notice at $box covers the billboard's words at $words" }
            check(box.top >= (24 * dp).toInt()) { "Notice at $box is outside the title-safe band" }
        }
        SystemClock.sleep(600) // the art crossfades in under it
        screenshot("updates-notice-$code.png")

        // Up from the hero reaches it; the corner holds the remote until Down.
        press(KeyEvent.KEYCODE_DPAD_UP)
        await("Up from the hero did not reach the notice") { notice.hasFocus() }
        onMain { check(home.findViewById<View>(R.id.promo_dots).alpha == 1f && more.alpha == 1f) { "The hero stepped aside for the notice" } }
        SystemClock.sleep(300) // the white fill and lift settle
        screenshot("updates-notice-focus-$code.png")
        for (key in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) {
            press(key)
            onMain { check(notice.hasFocus()) { "Key $key left the notice for ${home.currentFocus}" } }
        }
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        await("Down did not return to the hero") { more.hasFocus() }
        press(KeyEvent.KEYCODE_DPAD_UP)
        await("Up did not reach the notice again") { notice.hasFocus() }
        val installer = addMonitor(null as IntentFilter?, null, true)
        try {
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            SystemClock.sleep(300)
            check(installer.hits == 0) { "The notice opened installation without a verified download" }
        } finally { removeMonitor(installer) }

        // Back puts it away for that version, without opening the rail, and the remote goes back to the hero.
        press(KeyEvent.KEYCODE_BACK)
        await("Back did not put the notice away") { notice.visibility == View.GONE && more.hasFocus() }
        onMain {
            check(!home.findViewById<View>(R.id.rail).hasFocus()) { "Back on the notice opened the rail" }
            check(prefs.getString("notice_dismissed", null) == "1.5.0")
            check(badge.visibility == View.VISIBLE) { "Putting the notice away took the rail's dot too" }
            state.value = AppUpdates.Status.Idle
            state.value = AppUpdates.Status.Ready("1.5.0")
        }
        SystemClock.sleep(1_500) // past the notice's wait and entrance
        onMain { check(notice.visibility == View.GONE) { "A version put away came back" } }
        onMain { state.value = AppUpdates.Status.Ready("1.5.1") }
        await("A newer version did not show again") {
            notice.visibility == View.VISIBLE && notice.alpha == 1f &&
                notice.findViewById<TextView>(R.id.update_notice_text).text.toString() == home.getString(R.string.update_notice, "1.5.1".localDigits())
        }

        // The rail: Back from the hero opens it, the dot rides on Settings, and Settings opens on the update row.
        press(KeyEvent.KEYCODE_BACK)
        await("Back did not open the rail") { home.findViewById<View>(R.id.rail).hasFocus() }
        repeat(items.childCount) { if (!settings.hasFocus()) press(KeyEvent.KEYCODE_DPAD_DOWN) }
        await("The remote did not reach Settings") { settings.hasFocus() && badge.visibility == View.VISIBLE }
        SystemClock.sleep(400) // the panel and labels have come in
        screenshot("updates-rail-$code.png")
        val settingsMonitor = addMonitor(SettingsActivity::class.java.name, null, false)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        val page = waitForMonitorWithTimeout(settingsMonitor, 5_000)
        removeMonitor(settingsMonitor)
        checkNotNull(page) { "Settings did not open" }
        opened += page
        await("Settings did not open on the update row") { runCatching { page.row(R.string.app_update).hasFocus() }.getOrDefault(false) }
        // A release landing while home is behind waits for home to be in front.
        await("Home did not stop behind Settings") { !home.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        onMain { state.value = AppUpdates.Status.Ready("1.5.2") }
        SystemClock.sleep(300)
        onMain {
            check(notice.findViewById<TextView>(R.id.update_notice_text).text.toString() == home.getString(R.string.update_notice, "1.5.1".localDigits())) { "Home changed behind Settings" }
            page.finish()
        }
        await("Settings did not close") { page.isDestroyed }
        await("The newer version did not show on return") {
            notice.visibility == View.VISIBLE && more.hasFocus() &&
                notice.findViewById<TextView>(R.id.update_notice_text).text.toString() == home.getString(R.string.update_notice, "1.5.2".localDigits())
        }

        // Installed, or nothing ready: both marks go.
        onMain { state.value = AppUpdates.Status.Current }
        await("The notice or the dot outlived the update") {
            notice.visibility == View.GONE && badge.visibility == View.GONE && settings.contentDescription == home.getString(R.string.settings) && more.hasFocus()
        }
        onMain { home.finish() }
        await("Home did not close") { home.isDestroyed }
        client.dispatcher.cancelAll()
    }

    /** A cover as bright as art gets, white toward the top: where the notice sits, nothing behind it helps. */
    private fun Instrumentation.brightArt(): String {
        val file = File(targetContext.cacheDir, "updates-bright-art.png")
        if (!file.exists()) {
            val bitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 0f, 720f, Color.WHITE, Color.rgb(242, 196, 120), Shader.TileMode.CLAMP) })
            canvas.drawCircle(1100f, 140f, 220f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 244, 214) })
            canvas.drawCircle(180f, 140f, 220f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 244, 214) })
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        return "file://${file.absolutePath}"
    }

    private val httpField by lazy { Api::class.java.getDeclaredField("http").apply { isAccessible = true } }

    private data class Expected(val status: AppUpdates.Status, val value: Int, val note: Int = 0, val percent: Int? = null)

    @Suppress("UNCHECKED_CAST")
    private fun mutableState() = AppUpdates::class.java.getDeclaredField("current").apply { isAccessible = true }
        .get(AppUpdates) as MutableStateFlow<AppUpdates.Status>

    private fun Instrumentation.releaseMetadata() {
        val source = context.assets.open("updates/stable-release.json").bufferedReader().use { it.readText() }
        val release = AppUpdates.release(JSONObject(source))
        check(release.version == "1.1.0" && release.size == 4096L && release.sha256 == "b".repeat(64))
        val invalid = listOf<(JSONObject) -> Unit>(
            { it.put("draft", true) },
            { it.put("prerelease", true) },
            { it.getJSONArray("assets").getJSONObject(0).put("name", "unexpected.apk") },
            { it.getJSONArray("assets").getJSONObject(0).put("browser_download_url", "https://example.com/30namaTV.apk") },
            { it.getJSONArray("assets").getJSONObject(0).put("size", 101L * 1024 * 1024) },
            { it.getJSONArray("assets").getJSONObject(0).put("digest", "sha256:invalid") },
        )
        invalid.forEachIndexed { index, mutate ->
            val json = JSONObject(source).also(mutate)
            check(runCatching { AppUpdates.release(json) }.isFailure) { "Unsafe release fixture $index was accepted" }
        }
    }

    @Suppress("DEPRECATION")
    private fun Instrumentation.archiveSignersMatchInstalledApk() {
        val apk = File.createTempFile("updates-signed-self-", ".apk", targetContext.cacheDir)
        try {
            File(targetContext.applicationInfo.sourceDir).copyTo(apk, overwrite = true)
            val pm = targetContext.packageManager
            val flags = AppUpdates.signingFlags()
            val installed = pm.getPackageInfo(targetContext.packageName, flags)
            val archive = checkNotNull(pm.getPackageArchiveInfo(apk.absolutePath, flags)) { "Own signed APK could not be parsed" }
            val signers = AppUpdates::class.java.getDeclaredMethod("signers", PackageInfo::class.java).apply { isAccessible = true }
            val installedSigners = signers.invoke(AppUpdates, installed) as Set<*>
            val archiveSigners = signers.invoke(AppUpdates, archive) as Set<*>
            check(installedSigners.isNotEmpty() && archiveSigners == installedSigners) {
                "Archive signer lookup differed from installed APK: installed=$installedSigners, archive=$archiveSigners, flags=$flags"
            }
        } finally { apk.delete() }
    }

    @Suppress("DEPRECATION")
    private fun Instrumentation.providerAndInvalidApk() {
        val authority = "${targetContext.packageName}.updates"
        val pm = targetContext.packageManager
        val provider = checkNotNull(pm.resolveContentProvider(authority, PackageManager.GET_META_DATA))
        check(!provider.exported && provider.grantUriPermissions && provider.packageName == targetContext.packageName)
        check(provider.metaData.getInt("android.support.FILE_PROVIDER_PATHS") == R.xml.update_paths)
        val packageInfo = pm.getPackageInfo(targetContext.packageName, PackageManager.GET_PERMISSIONS)
        check("android.permission.REQUEST_INSTALL_PACKAGES" in packageInfo.requestedPermissions.orEmpty())
        val directory = checkNotNull(targetContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)).resolve("updates")
        check(directory.mkdirs() || directory.isDirectory)
        val version = "99.99.${SystemClock.elapsedRealtime() % 1_000_000}"
        val invalid = directory.resolve("30namaTV-$version.apk")
        check(!invalid.exists()) { "Update fixture would replace an existing file" }
        val outside = File.createTempFile("updates-outside-", ".txt", targetContext.filesDir)
        try {
            val bytes = "Invalid APK fixture".toByteArray()
            invalid.writeBytes(bytes)
            val uri = FileProvider.getUriForFile(targetContext, authority, invalid)
            check(uri.scheme == "content")
            check(targetContext.contentResolver.openInputStream(uri)?.use { it.readBytes().contentEquals(bytes) } == true)
            check(runCatching { FileProvider.getUriForFile(targetContext, authority, outside) }.exceptionOrNull() is IllegalArgumentException) { "Provider exposed files outside the update directory" }
            val verify = AppUpdates::class.java.getDeclaredMethod("verify", String::class.java, Long::class.javaPrimitiveType, String::class.java)
                .apply { isAccessible = true }
            check(verify.invoke(AppUpdates, version, invalid.length(), null) == AppUpdates.Status.Failed(AppUpdates.Failure.INVALID_APK)) { "Invalid APK was accepted" }
        } finally {
            invalid.delete()
            outside.delete()
        }
    }

    private fun Activity.row(label: Int): View {
        val options = findViewById<ViewGroup>(R.id.settings_options)
        val rows = (0 until options.childCount).map(options::getChildAt)
        return rows.singleOrNull { it.findViewById<TextView>(R.id.settings_label)?.text?.toString() == getString(label) }
            ?: error("Not one ${getString(label)} row among ${rows.mapNotNull { it.findViewById<TextView>(R.id.settings_label)?.text }}, focus on $currentFocus, touch mode ${window.decorView.isInTouchMode}")
    }

    private fun fits(text: TextView) {
        val layout = checkNotNull(text.layout)
        check(layout.getEllipsisCount(0) == 0 && layout.getLineWidth(0) <= text.width - text.compoundPaddingLeft - text.compoundPaddingRight + 1) { "${text.text} clipped in App update" }
    }

    private fun Instrumentation.screenshot(name: String) {
        val pixels = checkNotNull(uiAutomation.takeScreenshot())
        try {
            File(targetContext.filesDir, name).outputStream().use { pixels.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { pixels.recycle() }
    }

    private fun Instrumentation.press(code: Int) {
        sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, code))
        sendKeySync(KeyEvent(KeyEvent.ACTION_UP, code))
        waitForIdleSync()
        SystemClock.sleep(80)
    }

    private fun Instrumentation.await(message: String, timeout: Long = 5_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        do {
            var passed = false
            onMain { passed = condition() }
            if (passed) return
            SystemClock.sleep(25)
        } while (SystemClock.elapsedRealtime() < deadline)
        error(message)
    }

    private fun Instrumentation.onMain(action: () -> Unit) {
        var failure: Throwable? = null
        runOnMainSync { try { action() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
}

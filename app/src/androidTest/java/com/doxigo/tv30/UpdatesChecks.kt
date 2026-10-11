package com.doxigo.tv30

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Run the existing instrumentation runner with -e check updates. */
object UpdatesChecks {
    fun run(instrumentation: Instrumentation) = with(instrumentation) {
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
        try {
            check(prefs.edit().remove("download_id").remove("download_version").commit())
            releaseMetadata()
            providerAndInvalidApk()
            for (code in listOf("fa", "en")) {
                onMain { Api.language = code; state.value = AppUpdates.Status.Idle }
                activity = startActivitySync(Intent(targetContext, SettingsActivity::class.java)
                    .putExtra("section", 2).putExtra("row", 5).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                val host = checkNotNull(activity)
                waitForIdleSync()
                lateinit var update: View
                onMain {
                    update = host.row(R.string.app_update)
                    check(update.hasFocus()) { "Settings did not open on App update" }
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
                repeat(5) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
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
                        R.string.update_download_version, R.string.update_ready -> host.getString(expected.note, version)
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
                        check(if (code == "fa") label.left >= action.right else label.right <= action.left) { "Update label overlapped its action" }
                        check(update.contentDescription.toString().contains(value))
                    }
                    if (expected.status is AppUpdates.Status.Ready) {
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
            result.putString("stream", "PASS: App update row in Persian/English, D-pad/About navigation, live status/progress/notes without focus loss, release-guide action, private narrow FileProvider, install permission and invalid APK rejection\n")
        } catch (error: Throwable) {
            result.putString("stream", error.stackTraceToString())
            resultCode = Activity.RESULT_CANCELED
        } finally {
            onMain { activity?.finish() }
            activity?.let { host -> await("Settings did not stop during cleanup") { host.isDestroyed } }
            val edit = prefs.edit()
            if (hadDownload) edit.putLong("download_id", downloadId) else edit.remove("download_id")
            if (hadVersion) edit.putString("download_version", downloadVersion) else edit.remove("download_version")
            check(edit.commit())
            onMain { state.value = original; Api.language = language; Locale.setDefault(locale) }
        }
        finish(resultCode, result)
    }

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
        return (0 until options.childCount).map(options::getChildAt).single {
            it.findViewById<TextView>(R.id.settings_label)?.text?.toString() == getString(label)
        }
    }

    private fun fits(text: TextView) {
        val layout = checkNotNull(text.layout)
        check(layout.getEllipsisCount(0) == 0 && layout.getLineWidth(0) <= text.width - text.compoundPaddingLeft - text.compoundPaddingRight + 1) { "${text.text} clipped in App update" }
    }

    private fun Instrumentation.screenshot(name: String) {
        val pixels = checkNotNull(uiAutomation.takeScreenshot())
        try {
            File(targetContext.getExternalFilesDir(null), name).outputStream().use { pixels.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { pixels.recycle() }
    }

    private fun Instrumentation.press(code: Int) {
        sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, code))
        sendKeySync(KeyEvent(KeyEvent.ACTION_UP, code))
        waitForIdleSync()
        SystemClock.sleep(80)
    }

    private fun Instrumentation.await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 3_000
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

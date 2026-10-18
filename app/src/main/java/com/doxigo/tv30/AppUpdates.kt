package com.doxigo.tv30

import android.app.Activity
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object AppUpdates {
    const val RELEASES_URL = "https://github.com/soeleia/30namaTV/releases/latest"
    private const val API_URL = "https://api.github.com/repos/soeleia/30namaTV/releases/latest"
    private const val APK_NAME = "30namaTV.apk"
    /** The least time between two checks: home checks each time it comes to the front, at most this often. */
    private const val EVERY = 6 * 60 * 60 * 1000L
    private const val MAX_APK_SIZE = 100L * 1024 * 1024

    sealed interface Status {
        data object Idle : Status
        data object Checking : Status
        data object Current : Status
        data class Downloading(val version: String, val bytes: Long, val total: Long) : Status
        data class Ready(val version: String) : Status
        data class Failed(val reason: Failure) : Status
    }

    enum class Failure { NETWORK, INVALID_RELEASE, DOWNLOAD, INVALID_APK, INCOMPATIBLE_SIGNATURE, INSTALLER_UNAVAILABLE }

    internal data class Release(val version: String, val url: String, val size: Long, val sha256: String?)

    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var downloads: DownloadManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val checking = AtomicBoolean()
    private val current = MutableStateFlow<Status>(Status.Idle)
    val state = current.asStateFlow()
    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS).followRedirects(false).build()
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        prefs = app.getSharedPreferences("updates", Context.MODE_PRIVATE)
        val manager = app.getSystemService(DownloadManager::class.java)
        if (manager == null) {
            current.value = Status.Failed(Failure.DOWNLOAD)
            return
        }
        downloads = manager
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE &&
                    intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) == prefs.getLong("download_id", -2)) refresh()
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        // DownloadManager runs outside our UID; completion is checked against its private record.
        // The export flag exists from API 33; older versions need the legacy overload.
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else app.registerReceiver(receiver, filter)
        refresh()
    }

    fun check(force: Boolean = false) {
        if (!::downloads.isInitialized) return
        if (!force && BuildConfig.DEBUG && runCatching {
                Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                    .invoke(null, "debug.lily.readonly") == "1"
            }.getOrDefault(false)) return
        if (!checking.compareAndSet(false, true)) return
        scope.launch {
            try {
                lock.withLock {
                    refreshLocked()
                    if (current.value is Status.Downloading || current.value is Status.Ready) return@withLock
                    val now = System.currentTimeMillis()
                    if (!force && !checkDue(now, prefs.getLong("last_check", 0))) return@withLock
                    val previous = current.value
                    current.value = Status.Checking
                    prefs.edit().putLong("last_check", now).apply()
                    try {
                        val release = latest()
                        if (release == null || !newerVersion(release.version, BuildConfig.VERSION_NAME)) {
                            current.value = Status.Current
                        } else if (!force && previous == Status.Failed(Failure.INCOMPATIBLE_SIGNATURE) &&
                            release.version == prefs.getString("download_version", null)) {
                            current.value = previous
                        } else {
                            runCatching { enqueue(release) }.onFailure { current.value = Status.Failed(Failure.DOWNLOAD) }
                        }
                    } catch (error: IOException) {
                        current.value = Status.Failed(Failure.NETWORK)
                    } catch (error: Exception) {
                        current.value = Status.Failed(Failure.INVALID_RELEASE)
                    }
                }
            } finally {
                checking.set(false)
            }
        }
    }

    fun refresh() {
        if (!::downloads.isInitialized) return
        scope.launch {
            if (!lock.tryLock()) return@launch
            try { refreshLocked() } finally { lock.unlock() }
        }
    }

    fun openInstall(activity: Activity) {
        if (!::downloads.isInitialized) return
        scope.launch {
            lock.withLock {
                refreshLocked()
                if (current.value !is Status.Ready) return@withLock
                val file = updateFile(prefs.getString("download_version", null) ?: return@withLock)
                    ?: return@withLock
                withContext(Dispatchers.Main) {
                    if (activity.isFinishing || activity.isDestroyed) return@withContext
                    try {
                        if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
                            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")))
                        } else {
                            val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", file)
                            activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply {
                                    clipData = ClipData.newRawUri("30namaTV update", uri)
                                })
                        }
                    } catch (error: Exception) {
                        current.value = Status.Failed(Failure.INSTALLER_UNAVAILABLE)
                    }
                }
            }
        }
    }

    private fun latest(): Release? {
        val request = Request.Builder().url(API_URL).header("Accept", "application/vnd.github+json").build()
        return http.newCall(request).execute().use { response ->
            if (response.code == 404) return@use null
            if (!response.isSuccessful) throw IOException("Release check failed")
            val source = checkNotNull(response.body).source()
            require(!source.request(512 * 1024L + 1)) { "Release metadata is too large" }
            release(JSONObject(source.readUtf8()))
        }
    }

    internal fun release(json: JSONObject): Release {
        require(!json.optBoolean("draft", true) && !json.optBoolean("prerelease", true))
        val tag = json.getString("tag_name")
        require(versionParts(tag) != null)
        val assets = json.getJSONArray("assets")
        val matches = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.optString("name") == APK_NAME }
        require(matches.size == 1)
        val asset = matches.single()
        val url = asset.getString("browser_download_url")
        val size = asset.getLong("size")
        require(asset.optString("state") == "uploaded" && size in 1..MAX_APK_SIZE && trustedAsset(url, tag))
        val digest = asset.optString("digest").takeIf { it.isNotEmpty() && it != "null" }
        require(digest == null || Regex("sha256:[a-fA-F0-9]{64}").matches(digest))
        return Release(tag.removePrefix("v").removePrefix("V"), url, size, digest?.substringAfter(':')?.lowercase())
    }

    private fun enqueue(release: Release) {
        val file = updateFile(release.version) ?: throw IOException("Update storage is unavailable")
        clearDownload()
        check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
        if (file.exists()) check(file.delete())
        val request = DownloadManager.Request(Uri.parse(release.url)).setTitle("30namaTV ${release.version}")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, "updates/${file.name}")
        val id = downloads.enqueue(request)
        if (!prefs.edit().putLong("download_id", id).putString("download_version", release.version)
                .putLong("download_size", release.size).putString("download_sha256", release.sha256).commit()) {
            downloads.remove(id)
            throw IOException("Cannot retain update task")
        }
        current.value = Status.Downloading(release.version, 0, release.size)
    }

    private fun refreshLocked() {
        val id = prefs.getLong("download_id", -1)
        if (id < 0) return
        val version = prefs.getString("download_version", null)
        if (version == null || !newerVersion(version, BuildConfig.VERSION_NAME)) {
            clearDownload()
            current.value = Status.Current
            return
        }
        try {
            downloads.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                if (cursor == null || !cursor.moveToFirst()) {
                    clearDownload()
                    current.value = Status.Failed(Failure.DOWNLOAD)
                    return
                }
                val bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val size = prefs.getLong("download_size", 0)
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                if (bytes > size || size !in 1..MAX_APK_SIZE) {
                    clearDownload()
                    current.value = Status.Failed(Failure.INVALID_APK)
                } else if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    current.value = verify(version, size, prefs.getString("download_sha256", null))
                } else if (status == DownloadManager.STATUS_FAILED) {
                    current.value = Status.Failed(Failure.DOWNLOAD)
                } else {
                    current.value = Status.Downloading(version, bytes.coerceAtLeast(0), size)
                }
            }
        } catch (error: Exception) {
            current.value = Status.Failed(Failure.DOWNLOAD)
        }
    }

    private fun verify(version: String, size: Long, hash: String?): Status {
        val file = updateFile(version) ?: return Status.Failed(Failure.DOWNLOAD)
        if (!file.isFile || file.length() != size) return Status.Failed(Failure.INVALID_APK)
        if (hash != null && file.inputStream().use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
                digest.digest().hex() != hash
            }) return Status.Failed(Failure.INVALID_APK)
        val flags = signingFlags()
        val installed = app.packageManager.getPackageInfo(app.packageName, flags)
        val candidate = app.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: return Status.Failed(Failure.INVALID_APK)
        val failure = candidateFailure(app.packageName, versionCode(installed), signers(installed), candidate.packageName,
            versionCode(candidate), signers(candidate), versionParts(candidate.versionName.orEmpty()) == versionParts(version))
        return failure?.let { Status.Failed(it) } ?: Status.Ready(version)
    }

    private fun updateFile(version: String): File? {
        if (versionParts(version) == null) return null
        return app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.resolve("updates/30namaTV-$version.apk")
    }

    private fun clearDownload() {
        val id = prefs.getLong("download_id", -1)
        if (id >= 0) runCatching { downloads.remove(id) }
        prefs.getString("download_version", null)?.let { updateFile(it)?.delete() }
        prefs.edit().remove("download_id").remove("download_version").remove("download_size").remove("download_sha256").apply()
    }

    private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()

    @Suppress("DEPRECATION")
    internal fun signingFlags(): Int = when {
        Build.VERSION.SDK_INT < 28 -> PackageManager.GET_SIGNATURES
        // Android 9 only collects archive certificates when this legacy flag is present.
        Build.VERSION.SDK_INT == 28 -> PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        else -> PackageManager.GET_SIGNING_CERTIFICATES
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }.toSet()
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    internal fun checkDue(now: Long, last: Long) = last <= 0 || last > now || now - last >= EVERY

    /** The ready version home's notice may show: none once the viewer has put that version's away (see [dismiss]). */
    fun notice(): String? = (current.value as? Status.Ready)?.version
        ?.takeIf { ::prefs.isInitialized && it != prefs.getString("notice_dismissed", null) }

    /** Back on home's notice: that version's never shows again, though Settings and the rail still offer it. */
    fun dismiss(version: String) = prefs.edit().putString("notice_dismissed", version).apply()

    internal fun newerVersion(candidate: String, installed: String): Boolean {
        val next = versionParts(candidate) ?: return false
        val old = versionParts(installed) ?: return false
        for (index in 0..3) if (next[index] != old[index]) return next[index] > old[index]
        return false
    }

    private fun versionParts(version: String): List<Int>? {
        if (!Regex("[vV]?\\d+(?:\\.\\d+){1,3}").matches(version)) return null
        val parts = version.removePrefix("v").removePrefix("V").split('.').map { it.toIntOrNull() ?: return null }
        return parts + List(4 - parts.size) { 0 }
    }

    internal fun trustedAsset(value: String, tag: String): Boolean {
        val url = value.toHttpUrlOrNull() ?: return false
        return versionParts(tag) != null && url.scheme == "https" && url.host == "github.com" && url.port == 443 &&
            url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null &&
            url.encodedPath == "/soeleia/30namaTV/releases/download/$tag/$APK_NAME"
    }

    internal fun candidateFailure(installedPackage: String, installedCode: Long, installedSigners: Set<String>,
                                  candidatePackage: String?, candidateCode: Long, candidateSigners: Set<String>,
                                  versionMatches: Boolean): Failure? = when {
        candidatePackage != installedPackage || candidateCode <= installedCode || !versionMatches -> Failure.INVALID_APK
        installedSigners.isEmpty() || candidateSigners != installedSigners -> Failure.INCOMPATIBLE_SIGNATURE
        else -> null
    }
}

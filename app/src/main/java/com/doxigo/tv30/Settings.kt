package com.doxigo.tv30

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color

/**
 * What the Settings page sets and the player starts from, kept in the player's own preference file
 * so both read the same keys (the subtitle ones are the player's, unchanged). Quality, speed and
 * audio are defaults: a choice made in the player holds for that viewing only.
 */
object Settings {
    private var app: Context? = null

    /**
     * The file, opened on first read: through the context [init] was given or, read before any
     * [init] (an instrumentation's own onCreate runs before App's), the process's Application,
     * which exists by then. Either way a read never finds it missing.
     */
    private val prefs: SharedPreferences by lazy {
        // ponytail: ActivityThread.currentApplication is a hidden API on the unsupported (still
        // open) list; if a release ever blocks it, a ContentProvider that calls init is the route.
        val context = app ?: Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Context
        context.getSharedPreferences("player", Context.MODE_PRIVATE)
    }

    /** App, and anything that may run before it (the player after process death), hands over the application here. */
    fun init(context: Context) {
        if (app == null) app = context.applicationContext
    }

    /**
     * How far the disk read-ahead (Buffering.prefetch) runs, as a share of what is left of the
     * title. 25% by default: half an hour ahead in a two-hour film, or the disk's cap if smaller,
     * without spending a metered VPN on half a film nobody may finish.
     */
    val BUFFER_STEPS = listOf(0, 10, 25, 50, 100)
    var bufferPercent: Int
        get() = prefs.getInt("buffer", 25)
        set(v) = prefs.edit().putInt("buffer", v).apply()

    /** "Auto" or a height ("720p"); the player maps it onto a title's own labels with [pickQuality]. */
    val QUALITIES = listOf("Auto", "1080p", "720p", "480p")
    var quality: String
        get() = prefs.getString("quality", null) ?: "Auto"
        set(v) = prefs.edit().putString("quality", v).apply()

    /** In step with R.array.player_speeds. */
    val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    var speed: Float
        get() = prefs.getFloat("speed", 1f)
        set(v) = prefs.edit().putFloat("speed", v).apply()

    /** A quality's audio variant, keyed as the stream API keys them: "default" (Auto), "6ch" (5.1), "2ch" (stereo). */
    val AUDIOS = listOf("default", "6ch", "2ch")
    var audio: String
        get() = prefs.getString("audio", null) ?: "default"
        set(v) = prefs.edit().putString("audio", v).apply()

    var autoplay: Boolean
        get() = prefs.getBoolean("autoplay", true)
        set(v) = prefs.edit().putBoolean("autoplay", v).apply()

    /** Skips intros and recaps where 30nama marks them, instead of offering the button. */
    var autoSkip: Boolean
        get() = prefs.getBoolean("auto_skip", false)
        set(v) = prefs.edit().putBoolean("auto_skip", v).apply()

    /** «کاهش انیمیشن», off by default; Motion.reduced also follows the system's own switch. */
    var reduceMotion: Boolean
        get() = prefs.getBoolean("reduce_motion", false)
        set(v) = prefs.edit().putBoolean("reduce_motion", v).apply()

    /** "off" or a language code. The player's subtitle menu writes it too, so it is also the last choice. */
    var subtitle: String
        get() = prefs.getString("subtitle", null) ?: "fa"
        set(v) = prefs.edit().putString("subtitle", v).apply()

    /** Indexes into R.array.subtitle_sizes, subtitle_colors and subtitle_backgrounds. */
    var subtitleSize: Int
        get() = prefs.getInt("sub_size", 2).coerceIn(0, 4)
        set(v) = prefs.edit().putInt("sub_size", v).apply()
    var subtitleColor: Int
        get() = prefs.getInt("sub_color", 0).coerceIn(0, 1)
        set(v) = prefs.edit().putInt("sub_color", v).apply()
    var subtitleBackground: Int
        get() = prefs.getInt("sub_bg", 1).coerceIn(0, 2)
        set(v) = prefs.edit().putInt("sub_bg", v).apply()

    /**
     * The viewer's own API key for a service (Tmdb.NAME, Subtitles.SUBDL, Subtitles.OPENSUBTITLES),
     * entered on Settings' Services page; "" when none. Each service reads it through [pickKey].
     */
    fun savedKey(service: String) = prefs.getString("key_$service", null).orEmpty()
    fun saveKey(service: String, key: String) = prefs.edit().apply {
        if (key.isBlank()) remove("key_$service") else putString("key_$service", key.trim())
    }.apply()

    /** The key a service uses: the viewer's, else the one built in (local.properties, the developer's own builds only). */
    fun pickKey(saved: String, built: String) = saved.trim().ifEmpty { built.trim() }

    /** Subtitle text as a share of the screen's height; "medium" is about 5%, as streaming apps set it. */
    val subtitleHeightShare get() = floatArrayOf(0.036f, 0.042f, 0.049f, 0.058f, 0.068f)[subtitleSize]
    val subtitleTextColor get() = if (subtitleColor == 1) Color.YELLOW else Color.WHITE
    val subtitleBoxColor get() = when (subtitleBackground) {
        0 -> Color.BLACK
        1 -> 0x99000000.toInt()
        else -> Color.TRANSPARENT
    }

    /**
     * Which of a title's qualities ([labels], as the stream API names them) to start in for [wanted]:
     * that very label, else the best one not above its height (a title without 1080p starts at
     * 720p), else the lowest. "Auto", or a wish with no height in it, takes the title's Auto.
     */
    fun pickQuality(labels: List<String?>, wanted: String = quality): Int {
        labels.indexOf(wanted).takeIf { it >= 0 }?.let { return it }
        val auto = labels.indexOfFirst { it == null || it == "Auto" }.coerceAtLeast(0)
        val most = height(wanted) ?: return auto
        val heights = labels.withIndex().mapNotNull { (i, label) -> height(label)?.let { i to it } }
        return (heights.filter { it.second <= most }.maxByOrNull { it.second } ?: heights.minByOrNull { it.second })?.first ?: auto
    }

    private fun height(label: String?) = label?.let { Regex("\\d{3,4}").find(it)?.value?.toInt() }
}

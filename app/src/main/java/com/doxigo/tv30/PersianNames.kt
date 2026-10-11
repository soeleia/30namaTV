package com.doxigo.tv30

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Persian names for the titles 30nama leaves without one (seven in ten on home), from TMDB's
 * fa-IR translations, found by IMDb id. Off without a TMDB token, as for the trending row, and
 * quiet when TMDB is out of reach. Every answer, "none" included, is kept by IMDb id in memory
 * and in a small file, so no title is asked about twice.
 */
object PersianNames {
    private const val KEEP = 4000
    // The billboard's title and the hero's next one; more would only queue behind a slow TMDB.
    private val gate = Semaphore(2)
    private val disk = Mutex()
    /** IMDb id to name, "" for none, least recently shown first. Guarded by itself: cached() reads it on the main thread. */
    private val names = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > KEEP
    }
    private var loaded = false

    /** 30nama's Persian name, else TMDB's once looked up (earlier runs' too, after the first lookup reads the file). Never waits. */
    fun cached(title: JSONObject): String? =
        title.localName ?: title.imdbId?.let { synchronized(names) { names[it] } }?.ifEmpty { null }

    /**
     * Whether [lookup] may still find a name: a token, an IMDb id, no answer kept either way, and
     * TMDB not just out of reach. The billboard keeps the name's line for it meanwhile.
     */
    fun pending(title: JSONObject): Boolean {
        if (!Tmdb.enabled || Tmdb.backingOff || cached(title) != null) return false
        val imdb = title.imdbId ?: return false
        return synchronized(names) { imdb !in names }
    }

    /** [cached], else TMDB's Persian name, or null when it has none, is off, or is out of reach. */
    suspend fun lookup(context: Context, title: JSONObject): String? {
        cached(title)?.let { return it }
        if (!Tmdb.enabled) return null
        val imdb = title.imdbId ?: return null
        load(context)
        synchronized(names) { names[imdb] }?.let { return it.ifEmpty { null } }
        if (Tmdb.backingOff) return null
        // Tmdb.get records a failure itself, which backs every caller off.
        val name = runCatching { gate.withPermit { fetch(imdb, title) } }
            .onFailure { if (it is CancellationException) throw it }
            .getOrElse { return null }
        synchronized(names) { names[imdb] = name.orEmpty() }
        save(context)
        return name
    }

    private suspend fun fetch(imdb: String, title: JSONObject): String? {
        val hit = Tmdb.find(imdb, series = !title.isMovie, language = "fa-IR") ?: return null
        return persian(
            hit.str("title") ?: hit.str("name"),
            hit.str("original_title") ?: hit.str("original_name"),
            hit.optString("original_language"),
            title.englishName,
        )
    }

    /**
     * TMDB's fa-IR [name] in Persian orthography, or null when it is no Persian name. Untranslated,
     * TMDB answers with the English or the original name: a Latin one is plainly not Persian, and
     * an Arabic, Urdu or Kurdish original is the same script but not the language.
     */
    internal fun persian(name: String?, original: String?, language: String, english: String): String? {
        if (name == null || name.none { it.isLetter() && it in '؀'..'ۿ' }) return null
        if (name == original && language != "fa") return null
        // Typed on Arabic keyboards as often as not; ZWNJs stay as TMDB has them.
        val fixed = String(CharArray(name.length) { i ->
            when (val c = name[i]) {
                'ي', 'ى' -> 'ی'
                'ك' -> 'ک'
                in '0'..'9' -> '۰' + (c - '0')
                in '٠'..'٩' -> '۰' + (c - '٠')
                else -> c
            }
        }).trim()
        return fixed.takeIf { it.isNotEmpty() && it != english }
    }

    /** The kept answers, read once, off the main thread, before the first question. */
    private suspend fun load(context: Context) = disk.withLock {
        if (loaded) return@withLock
        val saved = withContext(Dispatchers.IO) { runCatching { JSONObject(file(context).readText()) }.getOrNull() }
        // In the file's order, eldest first, so the least recently shown go first again.
        synchronized(names) { saved?.keys()?.forEach { if (it !in names) names[it] = saved.optString(it) } }
        loaded = true
    }

    // ponytail: the whole file per new answer (a few hundred KB at most), one per settled title; debounce if that ever shows.
    private suspend fun save(context: Context) = disk.withLock {
        withContext(Dispatchers.IO) {
            val json = synchronized(names) { JSONObject(names).toString() }
            runCatching { file(context).writeText(json) }
        }
    }

    private fun file(context: Context) = context.cacheDir.resolve("persian-names.json")
}

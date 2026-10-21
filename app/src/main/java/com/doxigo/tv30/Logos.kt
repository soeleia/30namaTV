package com.doxigo.tv30

import android.content.Context
import android.graphics.Bitmap
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import kotlin.math.pow

/**
 * TMDB's logo art for a title, its name lettered as on the poster, which the billboard wears in
 * place of the English name: found by IMDb id, in the interface's language when there is one
 * (Tmdb.logo), and kept as the Persian names are, "none" included, so no title is asked about
 * twice. Off without a TMDB key, and quiet when TMDB is out of reach.
 */
object Logos {
    // Prefetches only, three at a time: the title on screen never queues behind its neighbours.
    private val gate = Semaphore(3)
    /** IMDb id and language ("tt0903747/fa") to the logo's URL, "" for none. */
    private val logos = Answers("logos.json")

    private val JSONObject.key get() = imdbId?.let { "$it/${Api.language}" }

    /** Reads the kept answers ahead of the first question (App), so [cached] knows them from the start. */
    suspend fun warm(context: Context) = logos.load(context)

    /** The logo, once looked up (earlier runs' too, once [warm] has read the file); none once the key is gone. Never waits. */
    fun cached(title: JSONObject): String? = title.key?.takeIf { Tmdb.enabled }?.let { logos[it] }?.ifEmpty { null }

    /** Whether [lookup] may still find one: a key, an IMDb id, no answer kept, and TMDB not just out of reach. */
    fun pending(title: JSONObject): Boolean =
        Tmdb.enabled && !Tmdb.backingOff && title.key?.let { it !in logos } == true

    /**
     * [cached], else TMDB's: from [entry] when the caller has the title's entry coming anyway (the
     * title page's one call, logos appended), else by IMDb id and the title's images. Null when
     * it has none, is off, or is out of reach.
     */
    suspend fun lookup(context: Context, title: JSONObject, entry: (suspend (JSONObject) -> JSONObject?)? = null): String? {
        if (!Tmdb.enabled) return null
        val key = title.key ?: return null
        logos.load(context)
        logos[key]?.let { return it.ifEmpty { null } }
        if (Tmdb.backingOff) return null
        // Tmdb.get records a failure itself, which backs every caller off.
        val url = runCatching {
            val images = if (entry != null) entry(title)?.optJSONObject("images") else Tmdb.find(title.imdbId!!, !title.isMovie)?.let { hit ->
                Tmdb.get("${hit.optString("media_type")}/${hit.optInt("id")}/images?include_image_language=fa,en,null")
            }
            Tmdb.logo(images)
        }.onFailure { if (it is CancellationException) throw it }.getOrElse { return null }
        logos.put(context, key, url.orEmpty())
        return url
    }

    /** A logo that would not read over the billboard's ink ([legible]): the words stand in for good. */
    suspend fun refuse(context: Context, title: JSONObject) {
        title.key?.let { logos.put(context, it, "") }
    }

    /** [title]'s logo, looked up and fetched into memory, for the billboard to write it whole when it comes to it. */
    suspend fun prefetch(context: Context, title: JSONObject) {
        val url = gate.withPermit { lookup(context, title) } ?: return
        context.imageLoader.enqueue(request(context, url).build())
    }

    /** How a logo is fetched, by the billboard and [prefetch] alike, so a prefetched one is a memory hit. In software, for [legible]. */
    fun request(context: Context, url: String) = ImageRequest.Builder(context).data(url).allowHardware(false)

    fun legible(art: Bitmap): Boolean {
        val small = Bitmap.createScaledBitmap(art, 48, maxOf(1, 48 * art.height / maxOf(1, art.width)), true)
        return legible(IntArray(small.width * small.height).also { small.getPixels(it, 0, small.width, 0, 0, small.width, small.height) })
    }

    /**
     * Whether a logo reads over the billboard's ink: a tenth of its lettering, by coverage, at
     * least 3:1 against it. Black lettering made for light posters would leave the title unseen;
     * a dark outline or extrusion around bright letters (Spider-Man's) is fine.
     */
    internal fun legible(pixels: IntArray): Boolean {
        var cover = 0.0
        var lit = 0.0
        for (p in pixels) {
            val a = (p ushr 24) / 255.0
            cover += a
            // The ink (#141414) is 0.007; 3:1 over it starts at 0.12.
            if (0.2126 * linear(p shr 16 and 0xFF) + 0.7152 * linear(p shr 8 and 0xFF) + 0.0722 * linear(p and 0xFF) >= 0.12) lit += a
        }
        return cover > 0 && lit >= cover / 10
    }

    private fun linear(channel: Int) = (channel / 255.0).let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
}

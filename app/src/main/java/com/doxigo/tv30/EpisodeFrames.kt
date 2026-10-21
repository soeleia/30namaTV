package com.doxigo.tv30

import android.content.Context
import android.graphics.Bitmap
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.PriorityDataSource
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.inspector.frame.FrameExtractor
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// ---- Episode frames: each episode's tile wears a frame of its own video. ----

/**
 * A frame from each episode's own video for its tile: 30nama has no stills. Media3's FrameExtractor
 * reads it from the lowest quality through the player's own sources (its client and user agent, and
 * the video cache, so a segment already watched costs nothing), a quarter of the way in, past the
 * recap and the titles. It is kept as a tile-sized JPEG per file id in cacheDir/frames, so each one is
 * fetched once ever: the playlist and one segment of the lowest quality, about a megabyte.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object EpisodeFrames {
    /** One extraction at a time, the callers queued here in turn. */
    private val lock = Mutex()
    /** Frames that failed this run (file names): they keep the series' art until the app restarts. */
    private val failed = HashSet<String>()

    /** Where [episode]'s frame lives on disk (whether or not it is there yet), or null without a file id. */
    fun file(context: Context, episode: JSONObject): File? =
        frameName(episode.str("info", "file_id"))?.let { File(context.cacheDir, "frames/$it") }

    /** A frame shown from disk counts as used, for the cap's LRU order (see [save]). */
    fun touch(file: File) {
        Api.scope.launch(Dispatchers.IO) { file.setLastModified(System.currentTimeMillis()) }
    }

    /**
     * [episode]'s frame on disk, [width] by [height], extracted first if it isn't there yet; null when
     * it can't be had. Cancelling withdraws the extraction's claim on the connection, so its reads fail
     * at once, and the next one waits for it to wind down: FrameExtractor runs one shared player and
     * can't drop a task half-way (a cancelled future would wedge it for the rest of the run).
     */
    suspend fun extract(context: Context, episode: JSONObject, width: Int, height: Int): File? {
        val out = file(context, episode) ?: return null
        if (out.name in failed) return null
        val url = lowest(episode) ?: return null
        val (first, later) = framePoints(episode.endMark)
        return lock.withLock {
            if (withContext(Dispatchers.IO) { out.exists() }) return@withLock out
            // Clipped to start at the first point, so the extractor's opening frame is the one wanted
            // and no segment from the episode's start is fetched for it.
            val item = MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8)
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(first).build())
                .build()
            val sources = DefaultMediaSourceFactory(PriorityDataSource.Factory(Buffering.dataSource(context), Buffering.priority, PRIORITY))
                .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(1)) // a thumbnail is worth one retry, not ten
            val extractor = FrameExtractor.Builder(context.applicationContext, item)
                .setMediaSourceFactory(sources)
                .setSeekParameters(SeekParameters.CLOSEST_SYNC) // any frame near the point: no decoding up to an exact one
                .setEffects(listOf(Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)))
                .build()
            var pending: ListenableFuture<FrameExtractor.Frame>? = null
            Buffering.priority.add(PRIORITY)
            try {
                val frame = extractor.getFrame(0).also { pending = it }.result().bitmap
                val light = withContext(Dispatchers.Default) { brightness(frame) }
                // A black scene or a fade: one more look further in, keeping whichever is brighter.
                val best = if (light >= DARK) frame else extractor.getFrame(later - first).also { pending = it }.result().bitmap
                    .let { next -> if (withContext(Dispatchers.Default) { brightness(next) } > light) next else frame }
                withContext(Dispatchers.IO) { save(best, out) }
                out
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed += out.name
                null
            } finally {
                Buffering.priority.remove(PRIORITY)
                extractor.close()
                withContext(NonCancellable) { runCatching { pending?.result() } }
            }
        }
    }

    /** The lowest quality's URL (the playlist's own if it lists only one), as the player picks its labels. */
    fun lowest(episode: JSONObject): String? {
        val qualities = episode.obj("file")?.optJSONArray("m3u8").objects()
        val quality = qualities.getOrNull(Settings.pickQuality(qualities.map { it.str("label") }, "144p"))
        return quality?.str("default") ?: episode.str("file", "m3u8")
    }

    /** Writes [frame] beside the others, then keeps the newest-used [CAP] bytes of them. */
    private fun save(frame: Bitmap, out: File) {
        val dir = out.parentFile!!.apply { mkdirs() }
        // Whole or not at all: a tile never reads a half-written file.
        val part = File(dir, "${out.name}.part")
        part.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        part.renameTo(out)
        var total = 0L
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.forEach { if (total + it.length() > CAP) it.delete() else total += it.length() }
    }

    private fun brightness(frame: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(frame, 16, 9, true)
        return luma(IntArray(16 * 9).also { small.getPixels(it, 0, 16, 0, 0, 16, 9) })
    }

    /** The future's result, without ever cancelling it (see [extract]). */
    private suspend fun <T> ListenableFuture<T>.result(): T = suspendCancellableCoroutine { cont ->
        addListener({
            try {
                cont.resume(get())
            } catch (e: Exception) {
                cont.resumeWithException((e as? ExecutionException)?.cause ?: e)
            }
        }, Runnable::run)
    }

    /** Below the player's read-ahead too (C.PRIORITY_DOWNLOAD), so nothing here reads while a player screen is up. */
    private const val PRIORITY = C.PRIORITY_DOWNLOAD - 1
    /** Mean brightness (of 255) under which a frame counts as black. */
    private const val DARK = 24
    /** About 30 KB a frame: some 700 episodes. */
    private const val CAP = 20L shl 20
}

/**
 * Where to look in an episode, in ms: a quarter of the way into one [lengthS] long (its credits mark),
 * past a cold open's dark, the recap and the titles; then two fifths in, for a black one. Without a
 * mark, 5 and 8 minutes in.
 */
internal fun framePoints(lengthS: Long?): Pair<Long, Long> =
    if (lengthS == null) 300_000L to 480_000L else lengthS * 250 to lengthS * 400

/** The cache's name for a file id: its letters and digits only, so no id reaches outside the folder. */
internal fun frameName(fileId: String?): String? =
    fileId?.filter { it.isLetterOrDigit() || it == '-' || it == '_' }?.takeIf { it.isNotEmpty() }?.let { "$it.jpg" }

/** Mean brightness 0..255 of ARGB [pixels], by Rec. 601's weights. */
internal fun luma(pixels: IntArray): Int = if (pixels.isEmpty()) 0
else (pixels.sumOf { 299L * (it shr 16 and 0xFF) + 587L * (it shr 8 and 0xFF) + 114L * (it and 0xFF) } / (1000L * pixels.size)).toInt()

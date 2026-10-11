package com.doxigo.tv30

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.PriorityTaskManager
import androidx.media3.common.util.UriUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.io.IOException

/**
 * How much of a title waits ahead of the picture, in two layers. In memory, ExoPlayer's own buffer,
 * sized to the heap ([loadControl]). On disk, a read-ahead ([prefetch]): the playing variant's
 * segments fetched ahead of the playhead into a cache the player reads through ([dataSource]), so a
 * VPN that drops for a minute or two plays on from disk. How far ahead is Settings.bufferPercent, a
 * share of what is left of the title, capped by the room the cache has.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object Buffering {
    /**
     * Video's own connections: the CDN signs every segment URL for the IP that asked for the
     * stream, and behind a VPN or carrier NAT with several exit IPs a connection can leave from the
     * wrong one and get 403 for good. HTTP/1.1 keeps one bad connection from carrying every
     * segment, and the player's 403 policy drops it so the retry dials a fresh one.
     */
    val cdn: OkHttpClient by lazy {
        Api.http.newBuilder().connectionPool(ConnectionPool()).protocols(listOf(Protocol.HTTP_1_1)).build()
    }

    /**
     * The player holds this while it loads (ExoPlayer.Builder.setPriorityTaskManager) and the
     * read-ahead yields to it: playback always has the connection to itself, and a seek, which sets
     * the player loading, pulls the read-ahead off its old position.
     */
    val priority = PriorityTaskManager()

    private val upstream by lazy { OkHttpDataSource.Factory(cdn).setUserAgent(Api.userAgent) }

    /** By path: the query is the CDN's signature, new with every `stream` call, while the bytes stay the same. */
    // ponytail: assumes the CDN never serves different bytes at one path by query; key on the full URL if it ever does.
    private val key = CacheKeyFactory { spec -> spec.key ?: spec.uri.buildUpon().clearQuery().build().toString() }

    /**
     * ExoPlayer's buffer lives on the Java heap, which a 2 GB TV caps at about 192 MB per app. A
     * third of it (64 MB there) holds a minute and a half of 1080p, and two minutes caps lighter
     * streams (it was 30 s / 48 MB). Loading pauses at the top and resumes at 50 s, which leaves the
     * read-ahead long turns on the connection.
     */
    fun loadControl(): LoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(50_000, 120_000, 2_500, 5_000)
        .setTargetBufferBytes((Runtime.getRuntime().maxMemory() / 3).coerceIn(32L shl 20, 96L shl 20).toInt())
        .build()

    /**
     * The player's sources: media through the disk cache (filled ahead by [prefetch], and by the
     * player's own loads, so a rewind or a return to the title reads from disk), playlists always
     * from the CDN. Without room on disk, straight to the CDN as before. The cache opens on the
     * first load, on a loader thread: it measures the disk.
     */
    fun dataSource(context: Context): DataSource.Factory {
        val app = context.applicationContext
        val cached by lazy { cache(app)?.let { cacheSources(it).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR) } }
        return DataSource.Factory {
            cached?.let { MediaThroughCache(it.createDataSource(), upstream.createDataSource()) } ?: upstream.createDataSource()
        }
    }

    private fun cacheSources(cache: Cache) =
        CacheDataSource.Factory().setCache(cache).setCacheKeyFactory(key).setUpstreamDataSourceFactory(upstream)

    /**
     * Keeps the read-ahead filled while [player] plays, until the caller's scope ends (the player's
     * screen stopping): the playing variant's segments from the playhead on, one at a time, up to
     * Settings.bufferPercent of what is left of the title or [readAhead] bytes, whichever comes
     * first. It only uses the connection while the player does not ([priority]). Paused, it goes on
     * for [PAUSED] (pausing to let it load is a habit on slow connections), then rests. Its errors
     * stay here: a 403 three times running (a signature for another exit IP, or an expired one) ends
     * it, anything else backs off, and the player sees neither.
     */
    suspend fun prefetch(context: Context, player: ExoPlayer) {
        val cache = withContext(Dispatchers.IO) { cache(context.applicationContext) } ?: return
        // Downloading flavour: waits on a segment the player is writing instead of fetching it twice.
        val source = cacheSources(cache).setUpstreamPriorityTaskManager(priority).createDataSourceForDownloading()
        val buffer = ByteArray(CacheWriter.DEFAULT_BUFFER_SIZE_BYTES)
        var pausedAt = 0L
        var failures = 0
        var forbidden = 0
        // proceed() lets a task through only while it is the highest one registered, so register.
        priority.add(C.PRIORITY_DOWNLOAD)
        try {
            while (true) {
                runInterruptible(Dispatchers.IO) { priority.proceed(C.PRIORITY_DOWNLOAD) } // the player's turn first
                val now = SystemClock.elapsedRealtime()
                pausedAt = if (player.playWhenReady) 0L else pausedAt.takeIf { it > 0 } ?: now
                val percent = Settings.bufferPercent
                // The variant playing now: Auto may have switched since the last turn.
                val playlist = (player.currentManifest as? HlsManifest)?.mediaPlaylist?.takeIf { it.hasEndTag }
                val positionUs = player.currentPosition * 1000
                val next = if (playlist == null || percent == 0 || pausedAt > 0 && now - pausedAt > PAUSED) null
                else withContext(Dispatchers.IO) { nextSegment(cache, playlist, positionUs, percent) }
                if (next == null) {
                    delay(IDLE)
                    continue
                }
                try {
                    download(CacheWriter(source, next, buffer, null))
                    failures = 0
                    forbidden = 0
                } catch (e: PriorityTaskManager.PriorityTooLowException) {
                    // The player started loading mid-segment. The part on disk stays; the next turn resumes it.
                } catch (e: IOException) {
                    if ((e as? HttpDataSource.InvalidResponseCodeException)?.responseCode == 403) {
                        cdn.connectionPool.evictAll() // as the player does: the retry dials a fresh connection
                        if (++forbidden == 3) return
                    } else {
                        delay(minOf(++failures * 5_000L, 60_000L))
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Whatever else goes wrong in here ends the read-ahead, never the film.
        } finally {
            priority.remove(C.PRIORITY_DOWNLOAD)
        }
    }

    /** Runs [writer] on the IO pool; cancelling the caller stops it at its next read, not at the end of the segment. */
    private suspend fun download(writer: CacheWriter) = coroutineScope {
        // CacheWriter checks its own flag between reads, which a thread interrupt does not set.
        val stop = launch { try { awaitCancellation() } finally { writer.cancel() } }
        try {
            runInterruptible(Dispatchers.IO) { writer.cache() }
        } finally {
            stop.cancel()
        }
    }

    /**
     * The first segment from the playhead to the window's end that is not on disk yet, or null once
     * the window is full: [percent] of what is left of the title, or [readAhead] bytes.
     */
    private fun nextSegment(cache: Cache, playlist: HlsMediaPlaylist, positionUs: Long, percent: Int): DataSpec? {
        val endUs = positionUs + (playlist.durationUs - positionUs) * percent / 100
        var bytes = 0L
        for (segment in playlist.segments) {
            val startUs = segment.relativeStartTimeUs
            if (startUs + segment.durationUs <= positionUs || segment.hasGapTag) continue
            if (startUs >= endUs || bytes >= readAhead) return null
            // Exactly the request the player makes for it, so both land on one cache entry.
            val spec = DataSpec.Builder().setUri(UriUtil.resolveToUri(playlist.baseUri, segment.url))
                .setPosition(segment.byteRangeOffset).setLength(segment.byteRangeLength).build()
            if (!spec.uri.isMedia()) return null // the player would not read it from the cache
            val id = key.buildCacheKey(spec)
            val length = if (spec.length != UNSET) spec.length
            else ContentMetadata.getContentLength(cache.getContentMetadata(id)).takeIf { it != UNSET }?.minus(spec.position) ?: return spec
            val cached = cache.getCachedBytes(id, spec.position, length)
            if (cached < length) return spec
            bytes += cached
        }
        return null
    }

    // ---- The disk cache: one per process, as SimpleCache requires. ----

    private var cache: SimpleCache? = null
    private var opened = false
    @Volatile private var capacity = 0L
    /** The read-ahead's share of the cache; the rest is what the playhead has passed, evicted first. */
    private val readAhead get() = capacity * 4 / 5

    /**
     * Made on first use, off the main thread (it measures the disk): a quarter of the free space,
     * our own files counted as free, up to 1 GB; under 64 MB there is no point. Evicted in the order
     * written, not by last read: the segments just ahead were fetched first and wait unread, so true
     * LRU would throw out exactly what plays next, while the oldest writes are what already played.
     */
    // ponytail: sized once per process; a disk that fills up meanwhile only finds out through failed writes, which the player then reads around.
    @Synchronized
    private fun cache(context: Context): SimpleCache? {
        if (!opened) {
            opened = true
            val dir = context.cacheDir.resolve("video")
            val ours = dir.walk().filter { it.isFile }.sumOf { it.length() }
            val bytes = minOf(1L shl 30, (context.cacheDir.usableSpace + ours) / 4)
            if (bytes >= 64L shl 20) {
                capacity = bytes
                cache = SimpleCache(dir, InWriteOrder(bytes), StandaloneDatabaseProvider(context))
            }
        }
        return cache
    }

    /** How much of the disk video may use, or 0 without room. Measures the disk the first time: not on the main thread. */
    fun capacity(context: Context): Long {
        cache(context.applicationContext)
        return capacity
    }

    /** How far the read-ahead may run, in bytes. Same caveat as [capacity]. */
    fun readAhead(context: Context): Long {
        cache(context.applicationContext)
        return readAhead
    }

    /** What the video cache holds now, in bytes. Blocks on the disk. */
    fun cacheBytes(context: Context): Long = cache(context.applicationContext)?.cacheSpace ?: 0L

    /** Empties the video cache. Blocks on the disk; not while a player is open. */
    fun clearCache(context: Context) {
        val cache = cache(context.applicationContext) ?: return
        cache.keys.toList().forEach(cache::removeResource)
    }

    private const val UNSET = C.LENGTH_UNSET.toLong()
    private const val IDLE = 5_000L
    private const val PAUSED = 10 * 60_000L

    /** Media files: segments, and the side-loaded subtitles. Never playlists: one kept from an earlier session lists segment URLs whose signatures have expired. */
    private val MEDIA = setOf("ts", "m4s", "mp4", "m4v", "m4a", "aac", "vtt", "srt")

    private fun Uri.isMedia() = lastPathSegment?.substringAfterLast('.', "")?.lowercase() in MEDIA

    /** The LRU evictor with touches off, so a span's age is when it was written. */
    private class InWriteOrder(bytes: Long) : CacheEvictor by LeastRecentlyUsedCacheEvictor(bytes) {
        override fun requiresCacheSpanTouches() = false
    }

    /** Media through the cache, everything else (playlists) straight from the CDN, chosen per request. */
    private class MediaThroughCache(private val cached: DataSource, private val direct: DataSource) : DataSource {
        private var open: DataSource? = null

        override fun open(dataSpec: DataSpec): Long = (if (dataSpec.uri.isMedia()) cached else direct).also { open = it }.open(dataSpec)
        override fun read(buffer: ByteArray, offset: Int, length: Int) = open!!.read(buffer, offset, length)
        override fun getUri(): Uri? = open?.uri
        override fun getResponseHeaders(): Map<String, List<String>> = open?.responseHeaders.orEmpty()
        override fun close() {
            val source = open ?: return
            open = null
            source.close()
        }

        override fun addTransferListener(transferListener: TransferListener) {
            cached.addTransferListener(transferListener)
            direct.addTransferListener(transferListener)
        }
    }
}

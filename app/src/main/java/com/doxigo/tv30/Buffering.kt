package com.doxigo.tv30

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PriorityTaskManager
import androidx.media3.common.util.UriUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceInputStream
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
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
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
import kotlin.math.abs

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

    private val key = CacheKeyFactory { spec -> spec.key ?: cacheKey(spec.uri.toString()) }

    /**
     * A segment's cache key: its URL less what the CDN signs, new with every `stream` call while the
     * bytes stay the same. That is the query, and the `/<viewer's IPv4>/<expiry>/<signature>/` it
     * writes into the path; with them in, a title played again never found what an earlier viewing
     * left on disk. Only those three parts together, so two files never share a key.
     */
    // ponytail: an IPv6 viewer's path keeps its signature (a cache miss, as before); add its shape if 30nama ever serves one.
    fun cacheKey(url: String) = url.substringBefore('?').replaceFirst(SIGNED, "/")

    private val SIGNED = Regex("/\\d{1,3}(?:\\.\\d{1,3}){3}/\\d{10}/[0-9a-f]{32,}/")

    /**
     * ExoPlayer's buffer lives on the Java heap, which a 2 GB TV caps at about 192 MB per app. A
     * third of it (64 MB there) holds a minute and a half of 1080p, and two minutes caps lighter
     * streams (it was 30 s / 48 MB). Loading pauses at the top and resumes at 50 s, which leaves the
     * read-ahead long turns on the connection. Never more than that third: a 32 MB floor took two
     * thirds of a 48 MB heap, and a paused film plus a subtitle search ran out of memory.
     */
    fun loadControl(): LoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(50_000, 120_000, 2_500, 5_000)
        .setTargetBufferBytes((Runtime.getRuntime().maxMemory() / 3).coerceAtMost(96L shl 20).toInt())
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
     * first; then, once [upcoming] names the next file's playlist (near the end, with autoplay),
     * that file's first [OPENING_US], so autoplay starts from disk. It only uses the connection while
     * the player does not ([priority]). Paused, it goes on until the window is full: pausing to let it
     * load is a habit on slow connections. It used to stop 10 minutes into a pause, against one
     * forgotten on a metered connection, but the window already bounds that in bytes (a fast
     * connection filled it inside those 10 minutes anyway); the clock only cut slow connections
     * short, where a 50% window can take over half an hour. Its errors stay here: a 403 three times
     * running (a signature for another exit IP, or an expired one) ends it, anything else backs off,
     * and the player sees neither.
     */
    suspend fun prefetch(context: Context, player: ExoPlayer, upcoming: () -> String?) {
        val cache = withContext(Dispatchers.IO) { cache(context.applicationContext) } ?: return
        // Downloading flavour: waits on a segment the player is writing instead of fetching it twice.
        val source = cacheSources(cache).setUpstreamPriorityTaskManager(priority).createDataSourceForDownloading()
        val buffer = ByteArray(CacheWriter.DEFAULT_BUFFER_SIZE_BYTES)
        // Playlists fetched here, by URL: a film's never change. A multivariant one leads to its
        // variant nearest the bitrate.
        val playlists = HashMap<String, HlsPlaylist>()
        suspend fun fetch(url: String) = playlists.getOrPut(url) { withContext(Dispatchers.IO) { playlist(url) } }
        suspend fun media(url: String, bitrate: Int): HlsMediaPlaylist? {
            val fetched = fetch(url)
            val variant = (fetched as? HlsMultivariantPlaylist)?.nearest(bitrate) ?: return fetched as? HlsMediaPlaylist
            return fetch(variant) as? HlsMediaPlaylist
        }
        var failures = 0
        var forbidden = 0
        // proceed() lets a task through only while it is the highest one registered, so register.
        priority.add(C.PRIORITY_DOWNLOAD)
        try {
            while (true) {
                runInterruptible(Dispatchers.IO) { priority.proceed(C.PRIORITY_DOWNLOAD) } // the player's turn first
                val percent = Settings.bufferPercent
                val manifest = player.currentManifest as? HlsManifest
                val bitrate = player.videoFormat?.bitrate
                val item = player.currentMediaItem
                val positionUs = player.currentPosition * 1000
                try {
                    // The variant playing now. A film's manifest keeps the one it started on, while
                    // Auto moves with the bandwidth: the variant nearest the playing bitrate is the
                    // one it reads, and the one the next file starts on.
                    val playing = bitrate?.let { manifest?.multivariantPlaylist?.nearest(it) }
                    val playlist = (if (playing == null || playing == manifest?.mediaPlaylist?.baseUri) manifest?.mediaPlaylist
                    else media(playing, bitrate))?.takeIf { it.hasEndTag }
                    var next: DataSpec? = null
                    if (playlist != null) {
                        val endUs = positionUs + (playlist.durationUs - positionUs) * percent / 100
                        val (missing, runUs) = withContext(Dispatchers.IO) { nextSegment(cache, playlist, positionUs, endUs) }
                        ready = Ready(item, positionUs / 1000, runUs / 1000)
                        next = missing.takeIf { percent > 0 }
                    }
                    // This file's window full: the next one's opening, once [upcoming] says so.
                    val url = if (next == null && percent > 0) upcoming() else null
                    if (url != null) next = media(url, bitrate ?: Format.NO_VALUE)?.let { withContext(Dispatchers.IO) { nextSegment(cache, it, 0, OPENING_US).first } }
                    if (next == null) {
                        delay(IDLE)
                        continue
                    }
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
     * Walks [playlist] from [fromUs] through the segments already on disk. Returns the first one that
     * is not, if it starts before [toUs] and within [readAhead] bytes (else null: the window is
     * full), and where that run on disk ends: how far playback could go on without the connection.
     */
    private fun nextSegment(cache: Cache, playlist: HlsMediaPlaylist, fromUs: Long, toUs: Long): Pair<DataSpec?, Long> {
        var bytes = 0L
        var runUs = fromUs
        for (segment in playlist.segments) {
            val startUs = segment.relativeStartTimeUs
            if (startUs + segment.durationUs <= fromUs || segment.hasGapTag) continue
            // Exactly the request the player makes for it, so both land on one cache entry.
            val spec = DataSpec.Builder().setUri(UriUtil.resolveToUri(playlist.baseUri, segment.url))
                .setPosition(segment.byteRangeOffset).setLength(segment.byteRangeLength).build()
            if (!spec.uri.isMedia()) break // the player would not read it from the cache
            val id = key.buildCacheKey(spec)
            val length = if (spec.length != UNSET) spec.length
            else ContentMetadata.getContentLength(cache.getContentMetadata(id)).takeIf { it != UNSET }?.minus(spec.position)
            val cached = length?.let { cache.getCachedBytes(id, spec.position, it) } ?: 0L
            if (length == null || cached < length) return spec.takeIf { startUs < toUs && bytes < readAhead } to runUs
            bytes += cached
            runUs = startUs + segment.durationUs
        }
        return null to runUs
    }

    /** The run on disk from where the playhead was ([fromMs]) to [toMs], in [item]'s time, as [prefetch] last saw it. */
    private class Ready(val item: MediaItem?, val fromMs: Long, val toMs: Long)

    private var ready: Ready? = null

    /**
     * Where the seek bar's loaded band ends: as far as playback could go on without the connection,
     * through the player's own buffer or the read-ahead's run on disk, whichever reaches further.
     * Main thread, as [prefetch] runs.
     */
    fun bufferedPosition(player: Player): Long {
        val run = ready?.takeIf { it.item == player.currentMediaItem && player.currentPosition in it.fromMs..it.toMs }
        return maxOf(player.bufferedPosition, run?.toMs ?: 0L)
    }

    /**
     * The playlist at [url], straight from the CDN and read as the player reads it: against wherever
     * a redirect led, so its segments resolve to the player's URLs. Blocks on the network.
     */
    private fun playlist(url: String): HlsPlaylist {
        val source = upstream.createDataSource()
        return DataSourceInputStream(source, DataSpec(Uri.parse(url))).use {
            it.open()
            HlsPlaylistParser().parse(source.uri ?: Uri.parse(url), it)
        }
    }

    /** The variant nearest [bitrate], the one the player reads at that rate. */
    private fun HlsMultivariantPlaylist.nearest(bitrate: Int) = variants.minByOrNull { abs(it.format.bitrate - bitrate) }?.url?.toString()

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
    /** How much of the next file the read-ahead loads before it plays. */
    private const val OPENING_US = 30_000_000L

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

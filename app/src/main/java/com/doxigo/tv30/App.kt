package com.doxigo.tv30

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.disk.DiskCache
import coil3.network.DeDupeConcurrentRequestStrategy
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import kotlinx.coroutines.launch
import okhttp3.Dispatcher
import okio.Path.Companion.toOkioPath

class App : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        Api.init(this)
        Settings.init(this)
        AppUpdates.init(this)
        // The kept logos, read behind the launch motion: the billboard's first title is written with its logo, not its words first.
        if (Tmdb.enabled) Api.scope.launch { Logos.warm(this@App) }
    }

    // Posters share the API's connection pool (the disk cache keeps rows instant on the next launch)
    // but not its dispatcher: OkHttp allows 5 calls per host, so the one CDN host took 4-5 round
    // trips to fill a screen; over HTTP/2 the rest are just streams on the same connection.
    // Two loads of one URL share one download (DeDupe): the billboard's cover and home's prefetch
    // of it, a title in two rows, a row's first posters and its see-all mosaic. Each used to fetch
    // its own copy, side by side.
    // The disk cache is the only one near the TV that lasts. The CDN keeps art at its edge for four
    // hours (max-age=14400); after that, or on a miss, it comes again from 30nama's origin in Iran,
    // seconds away. 250 MB, Coil's own ceiling, holds days of browsing with the covers (1920x1080,
    // 50-200 KB each, against a poster's 50); Android trims cacheDir itself when the TV runs short.
    // Coil's other defaults fit a 2 GB TV as they are: memory cache at 20% of the heap class,
    // hardware bitmaps (off the Java heap), no crossfade.
    @OptIn(ExperimentalCoilApi::class)
    override fun newImageLoader(context: PlatformContext) = ImageLoader.Builder(context)
        .components {
            add(OkHttpNetworkFetcherFactory(
                callFactory = { // called once; Coil holds it lazily
                    Api.http.newBuilder().dispatcher(Dispatcher().apply { maxRequestsPerHost = 24 }).build()
                },
                concurrentRequestStrategy = { DeDupeConcurrentRequestStrategy() },
            ))
        }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images").toOkioPath()).maxSizeBytes(250L shl 20).build() }
        .build()
}

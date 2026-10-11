package com.doxigo.tv30

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.Dispatcher
import okio.Path.Companion.toOkioPath

class App : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        Api.init(this)
        Settings.init(this)
        AppUpdates.init(this)
    }

    // Posters share the API's connection pool (the disk cache keeps rows instant on the next launch)
    // but not its dispatcher: OkHttp allows 5 calls per host, so the one CDN host took 4-5 round
    // trips to fill a screen; over HTTP/2 the rest are just streams on the same connection.
    // Coil's other defaults fit a 2 GB TV as they are: memory cache at 20% of the heap class,
    // hardware bitmaps (off the Java heap), no crossfade.
    override fun newImageLoader(context: PlatformContext) = ImageLoader.Builder(context)
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { // called once; Coil holds it lazily
                Api.http.newBuilder().dispatcher(Dispatcher().apply { maxRequestsPerHost = 24 }).build()
            }))
        }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images").toOkioPath()).maxSizeBytes(100L shl 20).build() }
        .build()
}

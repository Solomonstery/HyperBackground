package com.ciallo.hyperbackground

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import com.ciallo.hyperbackground.util.backgroundDecodeSize
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Decode off the UI thread; share pixels, never a mutable/animated Drawable between views. */
internal object BackgroundImageLoader {
    private val executor = Executors.newFixedThreadPool(2) { task ->
        Thread(task, "HyperBackground-Decode").apply { isDaemon = true }
    }
    private val bitmaps = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    private fun maxEdge(resources: Resources, zoom: Int): Int {
        val metrics = resources.displayMetrics
        return (maxOf(metrics.widthPixels, metrics.heightPixels) *
            zoom.coerceIn(100, 200) / 100f).toInt().coerceAtLeast(1)
    }

    private fun key(resources: Resources, mediaKey: String, zoom: Int) =
        "$mediaKey:${maxEdge(resources, zoom)}"

    fun cached(resources: Resources, mediaKey: String, zoom: Int = 100): Drawable? =
        bitmaps.get(key(resources, mediaKey, zoom))?.let { BitmapDrawable(resources, it) }

    fun load(
        resources: Resources,
        mediaKey: String,
        zoom: Int = 100,
        source: () -> ImageDecoder.Source,
        onResult: (Result<Drawable>) -> Unit,
    ): Future<*> = executor.submit {
        val result = runCatching {
            cached(resources, mediaKey, zoom) ?: ImageDecoder.decodeDrawable(source()) { decoder, info, _ ->
                val (width, height) = backgroundDecodeSize(info.size.width, info.size.height, maxEdge(resources, zoom))
                decoder.setTargetSize(width, height)
            }.also { drawable ->
                // AnimatedImageDrawable carries playback/callback state and must stay view-local.
                if (drawable is BitmapDrawable) bitmaps.put(key(resources, mediaKey, zoom), drawable.bitmap)
            }
        }
        if (!Thread.currentThread().isInterrupted) onResult(result)
    }
}

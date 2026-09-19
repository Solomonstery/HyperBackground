package com.ciallo.hyperbackground.appearance

import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.media.MediaPlayer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.util.Log
import java.io.IOException
import java.util.concurrent.Future
import com.ciallo.hyperbackground.BackgroundImageLoader

class SettingsBackgroundView(
    context: android.content.Context,
    private val source: SettingsAppearanceSource,
) : FrameLayout(context), TextureView.SurfaceTextureListener {
    private var imageDrawable: Drawable? = null
    private var imageView: ImageView? = null
    private var textureView: TextureView? = null
    private var mediaPlayer: MediaPlayer? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var videoWidth = 0
    private var videoHeight = 0
    private var hostResumed = true
    private var disposed = false

    /** 当背景 drawable 就绪（第一帧可显示）时回调，用于延迟隐藏原系统背景，消除黑帧空窗。 */
    var isReady = false
        private set
    var loadFailed = false
        private set
    private var imageTask: Future<*>? = null
    var onReady: (() -> Unit)? = null
        set(value) {
            field = value
            if (isReady && !disposed) value?.invoke()
        }

    init {
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO)
        isClickable = false
        isFocusable = false
        alpha = source.opacity / 100f
        if (Build.VERSION.SDK_INT >= 31 && source.blur > 0) {
            val radius = source.blur
            setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
        }
        if (source.isVideo) createVideoView() else createImageView()
    }

    fun sourceKey() = source.cacheKey()

    fun onHostResume() {
        hostResumed = true
        (imageDrawable as? AnimatedImageDrawable)?.start()
        runCatching { mediaPlayer?.start() }
    }

    fun onHostStop() {
        hostResumed = false
        (imageDrawable as? AnimatedImageDrawable)?.stop()
        runCatching { mediaPlayer?.takeIf { it.isPlaying }?.pause() }
    }

    fun dispose() {
        disposed = true
        onReady = null
        imageTask?.cancel(true)
        imageTask = null
        (imageDrawable as? AnimatedImageDrawable)?.stop()
        releasePlayer()
        textureView?.surfaceTextureListener = null
        removeAllViews()
    }

    private fun createImageView() {
        imageView = ImageView(context).also {
            it.scaleType = ImageView.ScaleType.CENTER_CROP
        }
        addView(imageView, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val key = mediaKey(source)
        val cached = BackgroundImageLoader.cached(resources, key)
        if (cached != null) {
            bindImage(cached)
            return
        }
        val resolver = context.contentResolver
        imageTask = BackgroundImageLoader.load(resources, key, source = {
            ImageDecoder.createSource(resolver, source.uri)
        }) { result ->
            post {
                if (!disposed) result.fold(::bindImage) {
                    loadFailed = true
                    Log.e(TAG, "Cannot decode Settings background", it)
                }
            }
        }
    }

    private fun bindImage(drawable: Drawable) {
        imageDrawable = drawable
        imageView?.setImageDrawable(drawable)
        (drawable as? AnimatedImageDrawable)?.apply {
            repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
            if (hostResumed) start()
        }
        markReady()
    }

    private fun markReady() {
        if (disposed) return
        isReady = true
        onReady?.invoke()
    }

    private fun createVideoView() {
        textureView = TextureView(context).also {
            it.isOpaque = false
            it.surfaceTextureListener = this
        }
        addView(textureView, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) = startPlayer(surfaceTexture)
    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) = updateVideoTransform()
    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean { releasePlayer(); return true }
    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit

    private fun startPlayer(surfaceTexture: SurfaceTexture) {
        releasePlayer()
        runCatching {
            descriptor = context.contentResolver.openFileDescriptor(source.uri, "r") ?: error("Cannot open video")
            mediaPlayer = MediaPlayer().apply {
                setDataSource(descriptor!!.fileDescriptor)
                val surface = Surface(surfaceTexture)
                setSurface(surface)
                surface.release()
                isLooping = true
                setVolume(0f, 0f)
                setOnVideoSizeChangedListener { _, width, height ->
                    this@SettingsBackgroundView.videoWidth = width
                    this@SettingsBackgroundView.videoHeight = height
                    this@SettingsBackgroundView.updateVideoTransform()
                }
                setOnPreparedListener {
                    closeDescriptor()
                    this@SettingsBackgroundView.videoWidth = it.videoWidth
                    this@SettingsBackgroundView.videoHeight = it.videoHeight
                    this@SettingsBackgroundView.updateVideoTransform()
                    if (hostResumed) it.start()
                    markReady()
                }
                setOnErrorListener { _, what, extra ->
                    loadFailed = true
                    Log.e(TAG, "Video background failed: $what/$extra")
                    closeDescriptor()
                    true
                }
                prepareAsync()
            }
        }.onFailure {
            loadFailed = true
            Log.e(TAG, "Cannot start Settings video background", it)
            releasePlayer()
        }
    }

    private fun updateVideoTransform() {
        val view = textureView ?: return
        if (videoWidth <= 0 || videoHeight <= 0 || view.width <= 0 || view.height <= 0) return
        val scale = maxOf(view.width.toFloat() / videoWidth, view.height.toFloat() / videoHeight)
        val matrix = Matrix().apply {
            setScale(videoWidth * scale / view.width, videoHeight * scale / view.height, view.width / 2f, view.height / 2f)
        }
        view.setTransform(matrix)
    }

    private fun releasePlayer() {
        closeDescriptor()
        mediaPlayer?.let { player ->
            runCatching { player.setSurface(null) }
            runCatching { player.reset() }
            runCatching { player.release() }
        }
        mediaPlayer = null
    }

    private fun closeDescriptor() { runCatching { descriptor?.close() }; descriptor = null }

    override fun onDetachedFromWindow() { dispose(); super.onDetachedFromWindow() }

    companion object {
        private const val TAG = "HyperChangerSettingsAppearance"

        private fun mediaKey(source: SettingsAppearanceSource) =
            "${source.uri}:${source.mime}:${source.size}:${source.modified}"

        /** Preload uses the same bounded decoder and pixel cache as visible backgrounds. */
        fun preload(context: android.content.Context, source: SettingsAppearanceSource) {
            if (!source.exists || source.isVideo) return
            val key = mediaKey(source)
            if (BackgroundImageLoader.cached(context.resources, key) != null) return
            val resolver = context.contentResolver
            BackgroundImageLoader.load(context.resources, key, source = {
                ImageDecoder.createSource(resolver, source.uri)
            }) { result ->
                result.onFailure { Log.e(TAG, "Cannot preload Settings background", it) }
            }
        }
    }
}

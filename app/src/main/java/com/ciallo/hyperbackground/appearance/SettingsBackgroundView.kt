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
import android.util.LruCache
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.util.Log
import java.io.IOException

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
    var onReady: (() -> Unit)? = null

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
        val key = source.cacheKey()
        val cached = drawableCache.get(key)
        if (cached != null) {
            // 缓存命中：同步设置 drawable，第一帧即为自定义背景，无黑帧。
            imageDrawable = cached
            imageView?.setImageDrawable(cached)
            (cached as? AnimatedImageDrawable)?.apply {
                repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                if (hostResumed) start()
            }
            post { if (!disposed) onReady?.invoke() }
            return
        }
        // 缓存未命中：异步解码，完成后入缓存并触发 onReady。
        val uri = source.uri
        val resolver = context.contentResolver
        Thread {
            runCatching {
                val drawable = ImageDecoder.decodeDrawable(
                    ImageDecoder.createSource(resolver, uri),
                )
                imageDrawable = drawable
                drawableCache.put(key, drawable)
                post {
                    if (!disposed) {
                        imageView?.setImageDrawable(drawable)
                        (drawable as? AnimatedImageDrawable)?.apply {
                            repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                            if (hostResumed) start()
                        }
                        onReady?.invoke()
                    }
                }
            }.onFailure { Log.e(TAG, "Cannot decode Settings background", it) }
        }.start()
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
                    if (!disposed) onReady?.invoke()
                }
                setOnErrorListener { _, what, extra -> Log.e(TAG, "Video background failed: $what/$extra"); closeDescriptor(); true }
                prepareAsync()
            }
        }.onFailure {
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

        /** 按图片像素数估算占用字节，上限约 12MB，通常可容纳 1–3 张全屏壁纸。 */
        private val drawableCache = object : LruCache<String, Drawable>(12 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Drawable): Int {
                val w = value.intrinsicWidth.coerceAtLeast(1)
                val h = value.intrinsicHeight.coerceAtLeast(1)
                // ARGB_8888 每像素 4 字节
                return (w * h * 4).coerceAtLeast(1)
            }
        }

        /**
         * 预加载：在进入「我的设备」页面前异步解码背景图入缓存，
         * 使页面首帧即可命中缓存同步显示自定义背景，消除黑帧。
         * 仅对图片有效，视频背景需 SurfaceTexture 无法预加载。
         */
        fun preload(context: android.content.Context, source: SettingsAppearanceSource) {
            if (!source.exists || source.isVideo) return
            val key = source.cacheKey()
            if (drawableCache.get(key) != null) return
            val resolver = context.contentResolver
            val uri = source.uri
            Thread {
                runCatching {
                    val drawable = ImageDecoder.decodeDrawable(
                        ImageDecoder.createSource(resolver, uri),
                    )
                    drawableCache.put(key, drawable)
                    Log.i(TAG, "Preloaded device background cacheKey=$key")
                }.onFailure { Log.e(TAG, "Cannot preload Settings background", it) }
            }.start()
        }
    }
}

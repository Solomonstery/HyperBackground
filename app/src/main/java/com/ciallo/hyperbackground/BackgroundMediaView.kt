package com.ciallo.hyperbackground

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.media.MediaPlayer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import java.io.IOException
import java.util.concurrent.Callable

internal class BackgroundMediaView(
    context: Context,
    private val source: BackgroundContract.Source,
) : FrameLayout(context), TextureView.SurfaceTextureListener {

    private var imageView: ImageView? = null
    private var textureView: TextureView? = null
    private var imageDrawable: Drawable? = null
    private var mediaPlayer: MediaPlayer? = null
    private var dataDescriptor: ParcelFileDescriptor? = null
    private var imageLayoutListener: View.OnLayoutChangeListener? = null
    private var videoWidth = 0
    private var videoHeight = 0
    private var hostResumed = true
    private var disposed = false

    // 顶部圆角半径（px，>0 才裁切）。用自绘 clipPath 而非 setClipToOutline，后者对内部 MATRIX 绘制的
    // ImageView 内容裁切不稳定，直接在 dispatchDraw 裁路径可确保对任意子内容一定生效。
    private var topCornerRadius = 0f
    private val clipPath = Path()
    private val clipRect = RectF()

    init {
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO)
        isClickable = false
        isFocusable = false
        alpha = source.opacity / 100f
        if (Build.VERSION.SDK_INT >= 31 && source.blurEnabled && source.blurRadius > 0) {
            val radius = source.blurRadius.toFloat()
            setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
        } else if (Build.VERSION.SDK_INT >= 31) {
            setRenderEffect(null)
        }
        if (source.isVideo()) {
            createVideoView()
        } else {
            createImageView()
        }
    }

    fun sourceKey(): String = source.cacheKey()

    fun onHostResume() {
        hostResumed = true
        (imageDrawable as? AnimatedImageDrawable)?.start()
        mediaPlayer?.let { player ->
            try {
                player.start()
            } catch (_: IllegalStateException) {
                // The asynchronous prepare callback will start it later.
            }
        }
    }

    fun onHostStop() {
        hostResumed = false
        (imageDrawable as? AnimatedImageDrawable)?.stop()
        mediaPlayer?.let { player ->
            try {
                if (player.isPlaying) player.pause()
            } catch (_: IllegalStateException) {
                // Already released or still preparing.
            }
        }
    }

    fun dispose() {
        disposed = true
        (imageDrawable as? AnimatedImageDrawable)?.stop()
        releasePlayer()
        textureView?.setSurfaceTextureListener(null)
        imageLayoutListener?.let { listener ->
            imageView?.removeOnLayoutChangeListener(listener)
        }
        imageLayoutListener = null
        removeAllViews()
    }

    // 设置圆角半径（px）：四角同半径圆角裁切。
    fun setTopCornerRadius(radiusPx: Float) {
        topCornerRadius = radiusPx.coerceAtLeast(0f)
        setWillNotDraw(false)
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (topCornerRadius <= 0f) {
            super.dispatchDraw(canvas)
            return
        }
        val w = width
        val h = height
        if (w <= 0 || h <= 0) {
            super.dispatchDraw(canvas)
            return
        }
        // 四角同半径圆角。半径不超过宽/高一半，避免面板从底部往上弹出、动画中途高度较小时圆角画不全。
        val r = minOf(topCornerRadius, minOf(w, h) / 2f)
        clipPath.reset()
        clipRect.set(0f, 0f, w.toFloat(), h.toFloat())
        clipPath.addRoundRect(clipRect, r, r, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(clipPath)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
    }

    private fun createImageView() {
        val view = ImageView(context)
        imageView = view
        view.adjustViewBounds = false
        val decoderSource = ImageDecoder.createSource(Callable {
            AssetFileDescriptor(source.openFile(), 0, AssetFileDescriptor.UNKNOWN_LENGTH)
        })
        val drawable = ImageDecoder.decodeDrawable(decoderSource)
        imageDrawable = drawable
        view.setImageDrawable(drawable)
        applyImageBrightness()
        addView(
            view, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        )
        applyImageScale()
        if (drawable is AnimatedImageDrawable) {
            drawable.setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE)
            drawable.start()
        }
    }

    // 背景亮度：对图片用 ColorMatrix 缩放 RGB 通道实现（100=原图，<100 变暗，>100 提亮），
    // 不改动原始 Drawable，GPU 处理、仅创建时设一次，无逐帧开销。
    private fun applyImageBrightness() {
        val view = imageView ?: return
        val b = source.brightness
        if (b == BackgroundContract.BRIGHTNESS_DEFAULT) {
            view.colorFilter = null
            return
        }
        val s = b / 100f
        val cm = ColorMatrix()
        cm.setScale(s, s, s, 1f)
        view.colorFilter = ColorMatrixColorFilter(cm)
    }

    // 仅首页（HOME）与拨号盘（CONTACTS_DIALPAD）提供缩放/定位滑块，用 MATRIX 微调；
    // 其它通道（全局二级页 GLOBAL、通讯录整页 CONTACTS 等）无缩放参数（zoom 恒 100、
    // focus 恒 50），直接用系统 CENTER_CROP 等比铺满并居中，既不出现矩阵错位，也不挂
    // layout 监听，省去逐帧布局回调开销。
    private fun applyImageScale() {
        val view = imageView ?: return
        imageLayoutListener?.let { view.removeOnLayoutChangeListener(it) }
        imageLayoutListener = null
        val matrixScaled = BackgroundContract.HOME == source.slot ||
            BackgroundContract.CONTACTS_DIALPAD == source.slot
        if (!matrixScaled) {
            view.scaleType = ImageView.ScaleType.CENTER_CROP
            view.imageMatrix = null
            return
        }
        // MATRIX 定位依赖 media 的屏幕坐标（getLocationOnScreen），需在布局后（进入视图树、位置确定）计算，
        // 故注册 layout 监听并兜底 post 到下一帧，确保定位一定落地。
        view.scaleType = ImageView.ScaleType.MATRIX
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateImageCropMatrix()
        }
        imageLayoutListener = listener
        view.addOnLayoutChangeListener(listener)
        updateImageCropMatrix()
        view.post { updateImageCropMatrix() }
    }

    // HOME：以自身视口为参照，scale=max(vw/dw,vh/dh)*zoom；100/50/50 与系统
    // CENTER_CROP 一致。拨号盘（CONTACTS_DIALPAD）沿用整屏宽度基准做纵向定位。
    private fun updateImageCropMatrix() {
        val view = imageView ?: return
        val drawable = imageDrawable ?: return
        if (view.scaleType != ImageView.ScaleType.MATRIX) return
        val dw = drawable.intrinsicWidth
        val dh = drawable.intrinsicHeight
        if (dw <= 0 || dh <= 0) return
        val vh = view.height
        if (vh <= 0) return // 视口高度未就绪，等布局监听 / post 回调再算
        val zoom = source.zoom.coerceIn(1, 200) / 100f

        if (BackgroundContract.HOME == source.slot) {
            val vw = view.width
            if (vw <= 0) return
            val baseScale = maxOf(vw.toFloat() / dw, vh.toFloat() / dh)
            val scale = baseScale * zoom
            val scaledW = dw * scale
            val scaledH = dh * scale
            val fx = source.focusX.coerceIn(0, 100) / 100f
            val fy = source.focusY.coerceIn(0, 100) / 100f
            val dx = (vw - scaledW) * fx
            val dy = (vh - scaledH) * fy
            val matrix = Matrix()
            matrix.setScale(scale, scale)
            matrix.postTranslate(Math.round(dx).toFloat(), Math.round(dy).toFloat())
            view.imageMatrix = matrix
            return
        }

        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        if (screenW <= 0) return
        val scale = screenW.toFloat() / dw * zoom
        val scaledW = dw * scale
        val scaledH = dh * scale
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val dx = (screenW - scaledW) / 2f - loc[0]
        val fy = source.focusY.coerceIn(0, 100) / 100f
        val dy = (vh - scaledH) * fy
        val matrix = Matrix()
        matrix.setScale(scale, scale)
        matrix.postTranslate(Math.round(dx).toFloat(), Math.round(dy).toFloat())
        view.imageMatrix = matrix
    }

    private fun createVideoView() {
        val view = TextureView(context)
        textureView = view
        view.isOpaque = false
        view.surfaceTextureListener = this
        addView(
            view, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        )
        applyVideoBrightness()
    }

    // 视频亮度：TextureView 无法用 colorFilter，故用叠加半透明遮罩近似——变暗叠黑、提亮叠白（封顶
    // 0.5 防过曝）。遮罩仅创建时加一次，随内容通道透明度自然生效。
    private fun applyVideoBrightness() {
        val b = source.brightness
        if (b == BackgroundContract.BRIGHTNESS_DEFAULT) return
        val overlayColor: Int
        val overlayAlpha: Float
        if (b < BackgroundContract.BRIGHTNESS_DEFAULT) {
            overlayColor = 0xFF000000.toInt()
            overlayAlpha = 1f - b / 100f // 0..1（越暗越黑）
        } else {
            overlayColor = 0xFFFFFFFF.toInt()
            overlayAlpha = minOf(0.5f, b / 100f - 1f) // 0..0.5（越亮越白，封顶）
        }
        val mask = View(context)
        mask.setBackgroundColor(overlayColor)
        mask.alpha = overlayAlpha
        mask.isClickable = false
        mask.isFocusable = false
        addView(
            mask, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        )
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        startPlayer(surface)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        updateVideoTransform()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        releasePlayer()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        // No-op.
    }

    private fun startPlayer(surfaceTexture: SurfaceTexture) {
        releasePlayer()
        try {
            val descriptor = source.openFile()
            dataDescriptor = descriptor

            val player = MediaPlayer()
            mediaPlayer = player
            player.setDataSource(descriptor.fileDescriptor)
            val surface = Surface(surfaceTexture)
            player.setSurface(surface)
            surface.release()
            player.isLooping = true
            player.setVolume(0f, 0f)
            player.setOnVideoSizeChangedListener { _, width, height ->
                videoWidth = width
                videoHeight = height
                updateVideoTransform()
            }
            player.setOnPreparedListener { mp ->
                closeDescriptor()
                videoWidth = mp.videoWidth
                videoHeight = mp.videoHeight
                updateVideoTransform()
                if (hostResumed) mp.start()
            }
            player.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "Video background failed: $what/$extra")
                closeDescriptor()
                true
            }
            player.prepareAsync()
        } catch (error: Throwable) {
            Log.e(TAG, "Cannot start video background", error)
            releasePlayer()
        }
    }

    private fun updateVideoTransform() {
        val view = textureView ?: return
        if (videoWidth <= 0 || videoHeight <= 0) return
        val viewWidth = view.width
        val viewHeight = view.height
        if (viewWidth <= 0 || viewHeight <= 0) return

        // 单一缩放模式（与图片一致）：TextureView 默认 FIT_XY 铺满，先以贴满基准(cover)×zoom 得到目标尺寸，
        // 再用矩阵把默认铺满还原成该尺寸，最后按横纵向焦点定位。
        // 缩放大小 1-200 → 倍数 0.01-2.0（100=贴满基准，>100 放大溢出、<100 缩小留边）。
        val zoom = source.zoom / 100f
        val baseScale = maxOf(
            viewWidth.toFloat() / videoWidth,
            viewHeight.toFloat() / videoHeight,
        ) // cover 基准
        val scaledWidth = videoWidth * baseScale * zoom
        val scaledHeight = videoHeight * baseScale * zoom
        // 相对「默认铺满」的缩放系数（默认铺满 = viewW×viewH）。
        val scaleX = scaledWidth / viewWidth
        val scaleY = scaledHeight / viewHeight
        val fx = source.focusX.coerceIn(0, 100) / 100f
        val fy = source.focusY.coerceIn(0, 100) / 100f

        val matrix = Matrix()
        // 绕左上角缩放，再按焦点平移定位：(view - scaled) * focus，放大/缩小都成立，默认 0.5 居中。
        matrix.setScale(scaleX, scaleY, 0f, 0f)
        matrix.postTranslate((viewWidth - scaledWidth) * fx, (viewHeight - scaledHeight) * fy)
        view.setTransform(matrix)
    }

    private fun releasePlayer() {
        closeDescriptor()
        val player = mediaPlayer
        mediaPlayer = null
        if (player != null) {
            try {
                player.setSurface(null)
                player.reset()
            } catch (_: Throwable) {
                // Ignore stale player state.
            }
            try {
                player.release()
            } catch (_: Throwable) {
                // Ignore stale player state.
            }
        }
    }

    private fun closeDescriptor() {
        val descriptor = dataDescriptor
        dataDescriptor = null
        if (descriptor != null) {
            try {
                descriptor.close()
            } catch (_: IOException) {
                // Nothing else to do.
            }
        }
    }

    override fun onDetachedFromWindow() {
        dispose()
        super.onDetachedFromWindow()
    }

    companion object {
        private const val TAG = "HyperBackground"
    }
}

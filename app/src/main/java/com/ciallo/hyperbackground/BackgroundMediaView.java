package com.ciallo.hyperbackground;

import android.content.Context;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.content.res.AssetFileDescriptor;
import android.media.MediaPlayer;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.IOException;

final class BackgroundMediaView extends FrameLayout implements TextureView.SurfaceTextureListener {
    private static final String TAG = "HyperBackground";

    private final BackgroundContract.Source source;
    private ImageView imageView;
    private TextureView textureView;
    private Drawable imageDrawable;
    private MediaPlayer mediaPlayer;
    private ParcelFileDescriptor dataDescriptor;
    private View.OnLayoutChangeListener imageLayoutListener;
    private int videoWidth;
    private int videoHeight;
    private boolean hostResumed = true;
    // 顶部圆角半径（px，>0 才裁切）。用自绘 clipPath 而非 setClipToOutline，后者对内部 MATRIX 绘制的
    // ImageView 内容裁切不稳定，直接在 dispatchDraw 裁路径可确保对任意子内容一定生效。
    private float topCornerRadius;
    private final android.graphics.Path clipPath = new android.graphics.Path();
    private final android.graphics.RectF clipRect = new android.graphics.RectF();

    BackgroundMediaView(Context context, BackgroundContract.Source source) throws IOException {
        super(context);
        // #region debug-point light-lag-rebuild —— 视图重建/重解码计数，定位后移除
        de.robv.android.xposed.XposedBridge.log("[HyperBackground][HBDBG] MediaView.new slot=" + source.slot + " cacheKey=" + source.cacheKey());
        // #endregion
        this.source = source;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setClickable(false);
        setFocusable(false);
        setAlpha(source.opacity / 100f);
        if (Build.VERSION.SDK_INT >= 31 && source.blurEnabled && source.blurRadius > 0) {
            float radius = source.blurRadius;
            setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
        } else if (Build.VERSION.SDK_INT >= 31) {
            setRenderEffect(null);
        }
        if (source.isVideo()) {
            createVideoView();
        } else {
            createImageView();
        }
    }

    String sourceKey() {
        return source.cacheKey();
    }

    void onHostResume() {
        hostResumed = true;
        if (imageDrawable instanceof AnimatedImageDrawable) {
            ((AnimatedImageDrawable) imageDrawable).start();
        }
        if (mediaPlayer != null) {
            try {
                mediaPlayer.start();
            } catch (IllegalStateException ignored) {
                // The asynchronous prepare callback will start it later.
            }
        }
    }

    void onHostStop() {
        hostResumed = false;
        if (imageDrawable instanceof AnimatedImageDrawable) {
            ((AnimatedImageDrawable) imageDrawable).stop();
        }
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) mediaPlayer.pause();
            } catch (IllegalStateException ignored) {
                // Already released or still preparing.
            }
        }
    }

    void dispose() {
        if (imageDrawable instanceof AnimatedImageDrawable) {
            ((AnimatedImageDrawable) imageDrawable).stop();
        }
        releasePlayer();
        if (textureView != null) textureView.setSurfaceTextureListener(null);
        if (imageView != null && imageLayoutListener != null) {
            imageView.removeOnLayoutChangeListener(imageLayoutListener);
        }
        imageLayoutListener = null;
        removeAllViews();
    }

    // 设置圆角半径（px）：四角同半径圆角裁切。
    void setTopCornerRadius(float radiusPx) {
        this.topCornerRadius = Math.max(0f, radiusPx);
        setWillNotDraw(false);
        invalidate();
    }

    @Override
    protected void dispatchDraw(android.graphics.Canvas canvas) {
        if (topCornerRadius <= 0f) {
            super.dispatchDraw(canvas);
            return;
        }
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            super.dispatchDraw(canvas);
            return;
        }
        // 四角同半径圆角。半径不超过宽/高一半，避免面板从底部往上弹出、动画中途高度较小时圆角画不全。
        float r = Math.min(topCornerRadius, Math.min(w, h) / 2f);
        clipPath.reset();
        clipRect.set(0f, 0f, w, h);
        clipPath.addRoundRect(clipRect, r, r, android.graphics.Path.Direction.CW);
        int save = canvas.save();
        canvas.clipPath(clipPath);
        super.dispatchDraw(canvas);
        canvas.restoreToCount(save);
    }

    private void createImageView() throws IOException {
        imageView = new ImageView(getContext());
        imageView.setAdjustViewBounds(false);
        ImageDecoder.Source decoderSource = ImageDecoder.createSource(() ->
                new AssetFileDescriptor(
                        source.openFile(),
                        0,
                        AssetFileDescriptor.UNKNOWN_LENGTH));
        imageDrawable = ImageDecoder.decodeDrawable(decoderSource);
        imageView.setImageDrawable(imageDrawable);
        applyImageBrightness();
        addView(imageView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        // 方案 A：默认（zoom=100 且横纵向居中）直接用系统 CENTER_CROP，等比铺满并自动居中，对任意宽高比
        // 的图都精确铺满不错位；仅当用户动了缩放 / 位置滑块时才切到 MATRIX 微调（以 CENTER_CROP 为基准）。
        applyImageScale();
        if (imageDrawable instanceof AnimatedImageDrawable) {
            AnimatedImageDrawable animated = (AnimatedImageDrawable) imageDrawable;
            animated.setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
            animated.start();
        }
    }

    // 亮度：用 ColorMatrix 缩放 RGB 分量（alpha 不变）。scale = brightness/100，
    // 100=原图不加 filter、<100 变暗、>100 变亮（高光溢出，为自然提亮效果）。仅对图片路径生效，
    // 视频路径（TextureView）无 colorFilter，另用遮罩 View 处理。
    private void applyImageBrightness() {
        if (imageView == null) return;
        int b = source.brightness;
        if (b == BackgroundContract.BRIGHTNESS_DEFAULT) {
            imageView.setColorFilter(null);
            return;
        }
        float s = b / 100f;
        android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
        cm.setScale(s, s, s, 1f);
        imageView.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));
    }

    // 默认（整页背景 zoom=100 且横纵向居中）直接用系统 CENTER_CROP：GPU 一次性等比铺满居中，
    // 不注册布局监听、无主线程逐帧回调，性能与 1.4.1 一致。仅当用户动了缩放 / 位置滑块（或拨号盘这类
    // 需要屏幕坐标系定位的通道）时，才切到 MATRIX 自绘矩阵。这样滑动设置页时默认背景不再触发重复计算。
    private void applyImageScale() {
        if (imageView == null) return;
        if (imageLayoutListener != null) {
            imageView.removeOnLayoutChangeListener(imageLayoutListener);
            imageLayoutListener = null;
        }
        boolean isDialpad = BackgroundContract.CONTACTS_DIALPAD.equals(source.slot);
        boolean isDefault = source.zoom == 100 && source.focusX == 50 && source.focusY == 50;
        // 整页背景在默认参数下用 CENTER_CROP；拨号盘始终需 MATRIX（屏幕坐标系）；整页背景一旦调过滑块也用 MATRIX。
        if (!isDialpad && isDefault) {
            imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            return;
        }
        // MATRIX 定位依赖 media 的屏幕坐标（getLocationOnScreen），需在布局后（进入视图树、位置确定）计算，
        // 故注册 layout 监听并兜底 post 到下一帧，确保定位一定落地。
        imageView.setScaleType(ImageView.ScaleType.MATRIX);
        imageLayoutListener = (v, l, t, r, b, ol, ot, or, ob) -> updateImageCropMatrix();
        imageView.addOnLayoutChangeListener(imageLayoutListener);
        updateImageCropMatrix();
        imageView.post(this::updateImageCropMatrix);
    }

    // 定位分两套基准，由 slot 区分：
    // 1) 拨号盘（contacts_dialpad）：图钉在整块屏幕上、横向以「屏幕宽」铺满并相对整屏居中，纵向以「拨号盘
    //    视口自身」为取景范围。拨号盘只是屏幕上的一个窗口，故需以屏幕为参照系。
    // 2) 整页背景（home/global/contacts 等）：以本视口自身为参照，等价于系统 CENTER_CROP——
    //    scale = max(vw/dw, vh/dh) × zoom，zoom=100 且 focus=50 时精确铺满并居中（与旧版 CENTER_CROP 一致），
    //    横纵向焦点分别在视口内插值，可在铺满基础上缩放/移动。
    private void updateImageCropMatrix() {
        if (imageView == null || imageDrawable == null) return;
        if (imageView.getScaleType() != ImageView.ScaleType.MATRIX) return;
        int dw = imageDrawable.getIntrinsicWidth();
        int dh = imageDrawable.getIntrinsicHeight();
        if (dw <= 0 || dh <= 0) return;
        int vw = imageView.getWidth();
        int vh = imageView.getHeight();
        if (vh <= 0) return;   // 视口高度未就绪，等布局监听 / post 回调再算
        float zoom = Math.max(1, Math.min(200, source.zoom)) / 100f;

        if (BackgroundContract.CONTACTS_DIALPAD.equals(source.slot)) {
            // —— 拨号盘：屏幕坐标系定位 ——
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            int screenW = dm.widthPixels;
            if (screenW <= 0) return;
            // 以屏幕宽度铺满为基准（图宽 = 屏幕宽），再乘缩放大小 zoom。100%=正好等于屏幕宽。
            float scale = (float) screenW / dw * zoom;
            float scaledW = dw * scale;
            float scaledH = dh * scale;
            // 横向：图相对整屏居中。视口在屏幕上的 x 偏移需扣除，使图对齐屏幕中线而非视口中线。
            int[] loc = new int[2];
            imageView.getLocationOnScreen(loc);
            float dx = (screenW - scaledW) / 2f - loc[0];
            // 纵向：以拨号盘视口自身为取景范围，focusY 在 [0, vh - scaledH] 内插值。
            float fy = Math.max(0, Math.min(100, source.focusY)) / 100f;
            float dy = (vh - scaledH) * fy;
            Matrix matrix = new Matrix();
            matrix.setScale(scale, scale);
            matrix.postTranslate(Math.round(dx), Math.round(dy));
            imageView.setImageMatrix(matrix);
            return;
        }

        // —— 整页背景：CENTER_CROP 基准（本视口参照）——
        if (vw <= 0) return;
        // CENTER_CROP 基准：取宽/高中更大的缩放比铺满视口，再乘 zoom。zoom=100 时正好等比铺满。
        float baseScale = Math.max((float) vw / dw, (float) vh / dh);
        float scale = baseScale * zoom;
        float scaledW = dw * scale;
        float scaledH = dh * scale;
        // 横纵向焦点在视口内插值：(view - scaled) × focus，0/100 贴边、50 居中（= CENTER_CROP 效果）。
        float fx = Math.max(0, Math.min(100, source.focusX)) / 100f;
        float fy = Math.max(0, Math.min(100, source.focusY)) / 100f;
        float dx = (vw - scaledW) * fx;
        float dy = (vh - scaledH) * fy;
        Matrix matrix = new Matrix();
        matrix.setScale(scale, scale);
        matrix.postTranslate(Math.round(dx), Math.round(dy));
        imageView.setImageMatrix(matrix);
    }

    private void createVideoView() {
        textureView = new TextureView(getContext());
        textureView.setOpaque(false);
        textureView.setSurfaceTextureListener(this);
        addView(textureView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        applyVideoBrightness();
    }

    // 视频亮度近似：TextureView 无法用 colorFilter，故在其上叠一层遮罩。<100 叠半透明黑变暗、
    // >100 叠半透明白提亮；100（默认）不叠。提亮遮罩上限 0.5，避免过曝彻底洗白。
    private void applyVideoBrightness() {
        int b = source.brightness;
        if (b == BackgroundContract.BRIGHTNESS_DEFAULT) return;
        float overlayAlpha;
        int overlayColor;
        if (b < BackgroundContract.BRIGHTNESS_DEFAULT) {
            overlayColor = 0xFF000000;
            overlayAlpha = 1f - b / 100f;              // 0..1（越暗越黑）
        } else {
            overlayColor = 0xFFFFFFFF;
            overlayAlpha = Math.min(0.5f, b / 100f - 1f); // 0..0.5（越亮越白，封顶）
        }
        View mask = new View(getContext());
        mask.setBackgroundColor(overlayColor);
        mask.setAlpha(overlayAlpha);
        mask.setClickable(false);
        mask.setFocusable(false);
        addView(mask, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
        startPlayer(surfaceTexture);
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
        updateVideoTransform();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
        releasePlayer();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
        // No-op.
    }

    private void startPlayer(SurfaceTexture surfaceTexture) {
        releasePlayer();
        try {
            dataDescriptor = source.openFile();
            if (dataDescriptor == null) throw new IOException("Cannot open video");

            MediaPlayer player = new MediaPlayer();
            mediaPlayer = player;
            player.setDataSource(dataDescriptor.getFileDescriptor());
            Surface surface = new Surface(surfaceTexture);
            player.setSurface(surface);
            surface.release();
            player.setLooping(true);
            player.setVolume(0f, 0f);
            player.setOnVideoSizeChangedListener((mp, width, height) -> {
                videoWidth = width;
                videoHeight = height;
                updateVideoTransform();
            });
            player.setOnPreparedListener(mp -> {
                closeDescriptor();
                videoWidth = mp.getVideoWidth();
                videoHeight = mp.getVideoHeight();
                updateVideoTransform();
                if (hostResumed) mp.start();
            });
            player.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "Video background failed: " + what + "/" + extra);
                closeDescriptor();
                return true;
            });
            player.prepareAsync();
        } catch (Throwable error) {
            Log.e(TAG, "Cannot start video background", error);
            releasePlayer();
        }
    }

    private void updateVideoTransform() {
        if (textureView == null || videoWidth <= 0 || videoHeight <= 0) return;
        int viewWidth = textureView.getWidth();
        int viewHeight = textureView.getHeight();
        if (viewWidth <= 0 || viewHeight <= 0) return;

        // 单一缩放模式（与图片一致）：TextureView 默认 FIT_XY 铺满，先以贴满基准(cover)×zoom 得到目标尺寸，
        // 再用矩阵把默认铺满还原成该尺寸，最后按横纵向焦点定位。
        // 缩放大小 1-200 → 倍数 0.01-2.0（100=贴满基准，>100 放大溢出、<100 缩小留边）。
        float zoom = source.zoom / 100f;
        float baseScale = Math.max(
                (float) viewWidth / videoWidth, (float) viewHeight / videoHeight);   // cover 基准
        float scaledWidth = videoWidth * baseScale * zoom;
        float scaledHeight = videoHeight * baseScale * zoom;
        // 相对「默认铺满」的缩放系数（默认铺满 = viewW×viewH）。
        float scaleX = scaledWidth / viewWidth;
        float scaleY = scaledHeight / viewHeight;
        float fx = Math.max(0, Math.min(100, source.focusX)) / 100f;
        float fy = Math.max(0, Math.min(100, source.focusY)) / 100f;

        Matrix matrix = new Matrix();
        // 绕左上角缩放，再按焦点平移定位：(view - scaled) * focus，放大/缩小都成立，默认 0.5 居中。
        matrix.setScale(scaleX, scaleY, 0f, 0f);
        matrix.postTranslate((viewWidth - scaledWidth) * fx, (viewHeight - scaledHeight) * fy);
        textureView.setTransform(matrix);
    }

    private void releasePlayer() {
        closeDescriptor();
        MediaPlayer player = mediaPlayer;
        mediaPlayer = null;
        if (player != null) {
            try {
                player.setSurface(null);
                player.reset();
            } catch (Throwable ignored) {
                // Ignore stale player state.
            }
            try {
                player.release();
            } catch (Throwable ignored) {
                // Ignore stale player state.
            }
        }
    }

    private void closeDescriptor() {
        ParcelFileDescriptor descriptor = dataDescriptor;
        dataDescriptor = null;
        if (descriptor != null) {
            try {
                descriptor.close();
            } catch (IOException ignored) {
                // Nothing else to do.
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        dispose();
        super.onDetachedFromWindow();
    }
}

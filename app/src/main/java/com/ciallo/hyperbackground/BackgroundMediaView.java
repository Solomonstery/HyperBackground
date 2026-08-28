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

    BackgroundMediaView(Context context, BackgroundContract.Source source) throws IOException {
        super(context);
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

    // 默认走系统 MATRIX 屏幕坐标系定位（图钉在整块屏幕上、横向铺满居中，拨号盘只是窗口）。此定位以屏幕
    // 为参照系，与 CENTER_CROP（以拨号盘区域为参照）不同，故即便 zoom=100/focusY=50 也需自绘矩阵。
    private void applyImageScale() {
        if (imageView == null) return;
        if (imageLayoutListener != null) imageView.removeOnLayoutChangeListener(imageLayoutListener);
        // MATRIX 定位依赖 media 的屏幕坐标（getLocationOnScreen），需在布局后（进入视图树、位置确定）计算，
        // 故注册 layout 监听并兜底 post 到下一帧，确保定位一定落地。
        imageView.setScaleType(ImageView.ScaleType.MATRIX);
        imageLayoutListener = (v, l, t, r, b, ol, ot, or, ob) -> updateImageCropMatrix();
        imageView.addOnLayoutChangeListener(imageLayoutListener);
        updateImageCropMatrix();
        imageView.post(this::updateImageCropMatrix);
    }

    // 屏幕坐标系定位：图按「屏幕宽度」等比缩放并钉在整块屏幕上（横向恒铺满、居中），拨号盘只是浮在
    // 图上的一个窗口——透过它看到的就是全屏图对应位置的那一块。基准用屏幕尺寸（DisplayMetrics，同步且
    // 恒定），不依赖 imageView 布局时机，根治此前依赖 getWidth() 导致的“缩到一角 / 闪一下才对位”。
    // zoom：以屏幕宽为基准放大/缩小。focusY：纵向取景偏移（0=顶部，100=底部），横向不再需要（恒居中）。
    private void updateImageCropMatrix() {
        if (imageView == null || imageDrawable == null) return;
        if (imageView.getScaleType() != ImageView.ScaleType.MATRIX) return;
        int dw = imageDrawable.getIntrinsicWidth();
        int dh = imageDrawable.getIntrinsicHeight();
        if (dw <= 0 || dh <= 0) return;
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenW = dm.widthPixels;
        int screenH = dm.heightPixels;
        if (screenW <= 0 || screenH <= 0) return;

        // 以屏幕宽度铺满为基准（图宽 = 屏幕宽），再乘缩放大小 zoom。100%=正好等于屏幕宽。
        float zoom = Math.max(1, Math.min(200, source.zoom)) / 100f;
        float scale = (float) screenW / dw * zoom;
        float scaledW = dw * scale;   // 缩放后图宽（zoom=100 时 = screenW）
        float scaledH = dh * scale;   // 缩放后图高（竖图通常 > screenH）

        // media（本视图）在屏幕上的绝对位置：把图钉在屏幕坐标系，需减去 media 的屏幕偏移，
        // 使图的“屏幕坐标原点”对齐到屏幕左上，而非 media 左上。
        int[] loc = new int[2];
        imageView.getLocationOnScreen(loc);
        int mediaX = loc[0];
        int mediaY = loc[1];

        // 横向：图相对屏幕居中（(screenW - scaledW)/2），再换算到 media 局部坐标（减 mediaX）。
        float dx = (screenW - scaledW) / 2f - mediaX;
        // 纵向：focusY 决定图相对屏幕的纵向摆放（0=顶部对齐、100=底部对齐、50=居中），再换算到 media 局部。
        float fy = Math.max(0, Math.min(100, source.focusY)) / 100f;
        float dy = (screenH - scaledH) * fy - mediaY;

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

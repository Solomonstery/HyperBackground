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

    // 是否需要手动微调：只要缩放不为 100% 或横纵向偏离居中，就进入 MATRIX 模式；否则用 CENTER_CROP。
    private boolean needsManualMatrix() {
        return source.zoom != 100 || source.focusX != 50 || source.focusY != 50;
    }

    // 默认走系统 CENTER_CROP（铺满 + 居中，任意图零错位）；用户动过滑块才切 MATRIX 微调。
    private void applyImageScale() {
        if (imageView == null) return;
        if (imageLayoutListener != null) imageView.removeOnLayoutChangeListener(imageLayoutListener);
        if (!needsManualMatrix()) {
            imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            imageLayoutListener = null;
            return;
        }
        // 手动微调模式：MATRIX 下 setImageMatrix 需要在布局后（拿到非零尺寸）计算，注册监听并兜底 post。
        imageView.setScaleType(ImageView.ScaleType.MATRIX);
        imageLayoutListener = (v, l, t, r, b, ol, ot, or, ob) -> updateImageCropMatrix();
        imageView.addOnLayoutChangeListener(imageLayoutListener);
        updateImageCropMatrix();
        imageView.post(this::updateImageCropMatrix);
    }

    // 手动微调：以 CENTER_CROP（铺满居中）为基准，叠加用户缩放倍率与相对中心的位移。zoom=100、focus=50
    // 时结果与 CENTER_CROP 完全一致（对任意图都居中铺满），根治“不同图居中点不同”。
    private void updateImageCropMatrix() {
        if (imageView == null || imageDrawable == null) return;
        if (imageView.getScaleType() != ImageView.ScaleType.MATRIX) return;
        int vw = imageView.getWidth();
        int vh = imageView.getHeight();
        int dw = imageDrawable.getIntrinsicWidth();
        int dh = imageDrawable.getIntrinsicHeight();
        if (vw <= 0 || vh <= 0 || dw <= 0 || dh <= 0) return;

        // 基准 = CENTER_CROP：等比铺满（cover）后在容器内居中。
        float cover = Math.max((float) vw / dw, (float) vh / dh);
        float zoom = Math.max(1, Math.min(200, source.zoom)) / 100f;
        float scale = cover * zoom;
        float scaledW = dw * scale;
        float scaledH = dh * scale;
        // 居中偏移（focus=50 时用此值，等价 CENTER_CROP）：把缩放后的图在容器内居中。
        float centerDx = (vw - scaledW) / 2f;
        float centerDy = (vh - scaledH) / 2f;
        // 相对中心的位移：focus=50 → 0（居中）；0/100 → 向一端各偏移半个可移动范围 (scaled - view)/2。
        float fx = Math.max(0, Math.min(100, source.focusX)) / 100f;
        float fy = Math.max(0, Math.min(100, source.focusY)) / 100f;
        float dx = centerDx + (scaledW - vw) / 2f * (2f * fx - 1f);
        float dy = centerDy + (scaledH - vh) / 2f * (2f * fy - 1f);

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

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
        // 必须在 addView 之后再配置矩阵缩放/定位：此前 imageView 尚未进入视图树、宽高为 0，
        // updateImageCropMatrix 会因尺寸为 0 直接返回，导致 focus/zoom 定位永不生效。
        applyImageScale();
        if (imageDrawable instanceof AnimatedImageDrawable) {
            AnimatedImageDrawable animated = (AnimatedImageDrawable) imageDrawable;
            animated.setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
            animated.start();
        }
    }

    // 单一缩放模式（等比不变形）：以「贴满基准」等比缩放（长边填满、cover），再乘以缩放大小 zoom，
    // 最后按横纵向定位焦点在区域内摆放。定位公式 (view - scaled) * focus 对放大/缩小都成立：
    //   focus=0.5 恒取中点（放大→居中裁切、缩小→居中留边），focus=0/1 贴向对应边，默认 50 居中。
    private void applyImageScale() {
        if (imageView == null) return;
        imageView.setScaleType(ImageView.ScaleType.MATRIX);
        // 视图尺寸在布局后才确定，且随宿主尺寸变化重算。先移除旧监听再注册，避免反复注册累积泄漏。
        if (imageLayoutListener != null) imageView.removeOnLayoutChangeListener(imageLayoutListener);
        imageLayoutListener = (v, l, t, r, b, ol, ot, or, ob) -> updateImageCropMatrix();
        imageView.addOnLayoutChangeListener(imageLayoutListener);
        updateImageCropMatrix();
        // 兜底：addView 当帧 imageView 宽高仍为 0，layout 监听在个别机型可能因尺寸未变化而不回调，
        // 故再 post 到下一帧强制重算一次，确保 focus/zoom 定位一定落地。
        imageView.post(this::updateImageCropMatrix);
    }

    private void updateImageCropMatrix() {
        if (imageView == null || imageDrawable == null) return;
        if (imageView.getScaleType() != ImageView.ScaleType.MATRIX) return;
        int vw = imageView.getWidth();
        int vh = imageView.getHeight();
        int dw = imageDrawable.getIntrinsicWidth();
        int dh = imageDrawable.getIntrinsicHeight();
        if (vw <= 0 || vh <= 0 || dw <= 0 || dh <= 0) return;

        // 缩放大小 1-200 → 倍数 0.01-2.0（100=贴满基准，>100 放大溢出、<100 缩小留边）。
        float zoom = source.zoom / 100f;
        float base = Math.max((float) vw / dw, (float) vh / dh);   // 贴满基准（cover）
        float scale = base * zoom;
        float scaledW = dw * scale;
        float scaledH = dh * scale;
        float fx = Math.max(0, Math.min(100, source.focusX)) / 100f;
        float fy = Math.max(0, Math.min(100, source.focusY)) / 100f;
        float dx = (vw - scaledW) * fx;   // 横向定位（默认 0.5 居中）
        float dy = (vh - scaledH) * fy;   // 纵向定位（默认 0.5 居中）

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

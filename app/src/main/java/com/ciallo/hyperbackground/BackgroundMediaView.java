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
        applyImageScale();
        addView(imageView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        if (imageDrawable instanceof AnimatedImageDrawable) {
            AnimatedImageDrawable animated = (AnimatedImageDrawable) imageDrawable;
            animated.setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
            animated.start();
        }
    }

    // 统一走 MATRIX 自绘，便于叠加「缩放大小」倍数与「纵向位置」焦点：
    //   基准缩放 = 贴满裁切按较大边填满 / 完整显示按较小边留边 / 拉伸按 XY 各自铺满（会变形）；
    //   再乘以 zoom（≥1），横向恒居中、纵向按 focusY 平移取景。
    private void applyImageScale() {
        if (imageView == null) return;
        imageView.setScaleType(ImageView.ScaleType.MATRIX);
        // 视图尺寸在布局后才确定，且随宿主尺寸变化重算。先移除旧监听再注册，避免反复注册累积泄漏。
        if (imageLayoutListener != null) imageView.removeOnLayoutChangeListener(imageLayoutListener);
        imageLayoutListener = (v, l, t, r, b, ol, ot, or, ob) -> updateImageCropMatrix();
        imageView.addOnLayoutChangeListener(imageLayoutListener);
        updateImageCropMatrix();
    }

    private void updateImageCropMatrix() {
        if (imageView == null || imageDrawable == null) return;
        if (imageView.getScaleType() != ImageView.ScaleType.MATRIX) return;
        int vw = imageView.getWidth();
        int vh = imageView.getHeight();
        int dw = imageDrawable.getIntrinsicWidth();
        int dh = imageDrawable.getIntrinsicHeight();
        if (vw <= 0 || vh <= 0 || dw <= 0 || dh <= 0) return;

        // 缩放大小 1-100 → 倍数 0.01-1.0（100=原始基准大小，往下按比例缩小四周留边）。
        float zoom = Math.max(0.01f, Math.min(1f, source.zoom / 100f));
        Matrix matrix = new Matrix();
        if (source.scaleMode == BackgroundContract.CONTACTS_DIALPAD_SCALE_STRETCH) {
            // 拉伸填充：XY 各自铺满（变形），再整体乘 zoom，围绕中心缩放。
            float sx = (float) vw / dw * zoom;
            float sy = (float) vh / dh * zoom;
            matrix.setScale(sx, sy, vw / 2f, vh / 2f);
            imageView.setImageMatrix(matrix);
            return;
        }
        float base = source.scaleMode == BackgroundContract.CONTACTS_DIALPAD_SCALE_FIT
                ? Math.min((float) vw / dw, (float) vh / dh)   // 完整显示：短边贴合、留边
                : Math.max((float) vw / dw, (float) vh / dh);  // 贴满裁切：长边填满、裁溢出
        float scale = base * zoom;
        float scaledW = dw * scale;
        float scaledH = dh * scale;
        float dx = (vw - scaledW) / 2f;                        // 横向居中
        float focus = Math.max(0, Math.min(100, source.focusY)) / 100f;
        // 完整显示纵向也留边、居中即可；贴满裁切时按焦点在可裁范围内平移。
        float dy = source.scaleMode == BackgroundContract.CONTACTS_DIALPAD_SCALE_FIT
                ? (vh - scaledH) / 2f
                : (vh - scaledH) * focus;
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

        // TextureView 默认已把内容拉伸铺满视图（相当于 FIT_XY），因此以「铺满」为基准，
        // 再叠加缩放矩阵得到不同缩放方式：贴满裁切按较大边、完整显示按较小边、拉伸不变；末尾统一乘 zoom。
        // 缩放大小 1-100 → 倍数 0.01-1.0（100=原始基准，往下缩小留边）。
        float zoom = Math.max(0.01f, Math.min(1f, source.zoom / 100f));
        Matrix matrix = new Matrix();
        if (source.scaleMode == BackgroundContract.CONTACTS_DIALPAD_SCALE_STRETCH) {
            // 拉伸填充：保持默认铺满，仅按 zoom 围绕中心缩放。
            matrix.setScale(zoom, zoom, viewWidth / 2f, viewHeight / 2f);
            textureView.setTransform(matrix);
            return;
        }

        float coverScale = Math.max(
                (float) viewWidth / videoWidth, (float) viewHeight / videoHeight);
        float baseScale = source.scaleMode == BackgroundContract.CONTACTS_DIALPAD_SCALE_FIT
                ? Math.min((float) viewWidth / videoWidth, (float) viewHeight / videoHeight)
                : coverScale;
        float scaledWidth = videoWidth * baseScale * zoom;
        float scaledHeight = videoHeight * baseScale * zoom;
        float scaleX = scaledWidth / viewWidth;
        float scaleY = scaledHeight / viewHeight;

        matrix.setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f);
        // 贴满裁切时按 focusY 做纵向取景平移（完整显示已留边、居中即可）。
        if (source.scaleMode == BackgroundContract.CONTACTS_DIALPAD_SCALE_CROP) {
            float focus = Math.max(0, Math.min(100, source.focusY)) / 100f;
            float centerDy = (viewHeight - scaledHeight) / 2f;   // 居中时的顶偏移
            float targetDy = (viewHeight - scaledHeight) * focus; // 焦点对应的顶偏移
            matrix.postTranslate(0f, targetDy - centerDy);
        }
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

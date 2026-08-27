package com.ciallo.hyperbackground;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把导入的 LOGO 素材（SVG / VectorDrawable(XML) / 位图）解析成 {@link Drawable}。
 *
 * <p>移植自 HyperChanger 的 LogoDrawableLoader，不依赖任何第三方 SVG 库：
 * SVG 用正则抓取 {@code <path d="…"/>} 后经 {@code android.util.PathParser} 反射生成
 * {@link Path}；VectorDrawable 走系统 {@code Drawable.createFromXml}；其它按位图解码。
 */
final class LogoDrawableLoader {
    private static final Pattern PATH_TAG = Pattern.compile("<path\\b([^>]*)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern PATH_DATA = Pattern.compile("\\bd\\s*=\\s*[\"']([^\"']+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FILL = Pattern.compile("\\bfill\\s*=\\s*[\"']([^\"']+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern VIEW_BOX = Pattern.compile(
            "\\bviewBox\\s*=\\s*[\"']\\s*([-+0-9.eE]+)[, ]+([-+0-9.eE]+)[, ]+([-+0-9.eE]+)[, ]+([-+0-9.eE]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern WIDTH = Pattern.compile("\\bwidth\\s*=\\s*[\"']\\s*([-+0-9.eE]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEIGHT = Pattern.compile("\\bheight\\s*=\\s*[\"']\\s*([-+0-9.eE]+)", Pattern.CASE_INSENSITIVE);

    private LogoDrawableLoader() {}

    /** 从字节流解析 LOGO Drawable，失败返回 null。 */
    static Drawable load(android.content.res.Resources resources, byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        try {
            String text = new String(bytes, StandardCharsets.UTF_8).trim();
            String lower = text.toLowerCase();
            if (lower.contains("<svg")) {
                return parseSvg(text);
            }
            if (lower.contains("<vector")) {
                XmlPullParser parser = Xml.newPullParser();
                parser.setInput(new ByteArrayInputStream(bytes), "UTF-8");
                return Drawable.createFromXml(resources, parser);
            }
            InputStream input = new ByteArrayInputStream(bytes);
            android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeStream(input);
            if (bitmap == null) return null;
            return new android.graphics.drawable.BitmapDrawable(resources, bitmap);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 用缩放（50–200，百分比）包装 Drawable，居中等比绘制。 */
    static Drawable withScale(Drawable drawable, int scalePercent) {
        float scale = Math.max(50, Math.min(200, scalePercent)) / 100f;
        return new AspectDrawable(drawable, scale);
    }

    private static Drawable parseSvg(String text) {
        List<PathFill> paths = new ArrayList<>();
        Matcher tags = PATH_TAG.matcher(text);
        while (tags.find()) {
            String attrs = tags.group(1);
            Matcher dm = PATH_DATA.matcher(attrs);
            if (!dm.find()) continue;
            Path path = createPath(dm.group(1));
            if (path == null) continue;
            Matcher fm = FILL.matcher(attrs);
            int color = parseColor(fm.find() ? fm.group(1) : null);
            paths.add(new PathFill(path, color));
        }
        if (paths.isEmpty()) return null;

        float minX = 0f, minY = 0f, srcW = 100f, srcH = 100f;
        Matcher vb = VIEW_BOX.matcher(text);
        if (vb.find()) {
            minX = toFloat(vb.group(1), 0f);
            minY = toFloat(vb.group(2), 0f);
            srcW = Math.max(1f, toFloat(vb.group(3), 100f));
            srcH = Math.max(1f, toFloat(vb.group(4), srcW));
        } else {
            Matcher wm = WIDTH.matcher(text);
            Matcher hm = HEIGHT.matcher(text);
            if (wm.find()) srcW = Math.max(1f, toFloat(wm.group(1), 100f));
            srcH = hm.find() ? Math.max(1f, toFloat(hm.group(1), srcW)) : srcW;
        }
        return new SvgPathDrawable(paths, minX, minY, srcW, srcH);
    }

    private static Path createPath(String data) {
        try {
            Method create = Class.forName("android.util.PathParser")
                    .getMethod("createPathFromPathData", String.class);
            Object result = create.invoke(null, data);
            return result instanceof Path ? (Path) result : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static float toFloat(String value, float fallback) {
        try {
            return Float.parseFloat(value);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static int parseColor(String value) {
        if (value == null || value.equalsIgnoreCase("none")) return Color.WHITE;
        if (value.startsWith("#")) {
            try {
                return Color.parseColor(value);
            } catch (Throwable ignored) {
                return Color.WHITE;
            }
        }
        if (value.equalsIgnoreCase("black")) return Color.BLACK;
        return Color.WHITE;
    }

    private static final class PathFill {
        final Path path;
        final int color;

        PathFill(Path path, int color) {
            this.path = path;
            this.color = color;
        }
    }

    /** 等比缩放包装：按 fit*scale 居中绘制子 Drawable。 */
    private static final class AspectDrawable extends Drawable {
        private final Drawable child;
        private final float scale;
        private final int sourceWidth;
        private final int sourceHeight;

        AspectDrawable(Drawable child, float scale) {
            this.child = child;
            this.scale = scale;
            this.sourceWidth = child.getIntrinsicWidth() > 0 ? child.getIntrinsicWidth() : 1;
            this.sourceHeight = child.getIntrinsicHeight() > 0 ? child.getIntrinsicHeight() : 1;
        }

        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (b.isEmpty()) return;
            float fit = Math.min(b.width() / (float) sourceWidth, b.height() / (float) sourceHeight) * scale;
            int width = Math.max(1, (int) (sourceWidth * fit));
            int height = Math.max(1, (int) (sourceHeight * fit));
            int left = b.left + (b.width() - width) / 2;
            int top = b.top + (b.height() - height) / 2;
            child.setBounds(left, top, left + width, top + height);
            child.draw(canvas);
        }

        @Override public void setAlpha(int alpha) { child.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { child.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return (int) (sourceWidth * scale); }
        @Override public int getIntrinsicHeight() { return (int) (sourceHeight * scale); }
    }

    /** 直接绘制 SVG path fill 的 Drawable（仅支持纯色填充路径，不支持渐变/描边）。 */
    private static final class SvgPathDrawable extends Drawable {
        private final List<PathFill> paths;
        private final float sourceMinX;
        private final float sourceMinY;
        private final float sourceWidth;
        private final float sourceHeight;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        SvgPathDrawable(List<PathFill> paths, float minX, float minY, float width, float height) {
            this.paths = paths;
            this.sourceMinX = minX;
            this.sourceMinY = minY;
            this.sourceWidth = width;
            this.sourceHeight = height;
            paint.setStyle(Paint.Style.FILL);
        }

        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            if (bounds.isEmpty()) return;
            float scale = Math.min(bounds.width() / sourceWidth, bounds.height() / sourceHeight);
            canvas.save();
            canvas.translate(
                    bounds.left + (bounds.width() - sourceWidth * scale) / 2f - sourceMinX * scale,
                    bounds.top + (bounds.height() - sourceHeight * scale) / 2f - sourceMinY * scale);
            canvas.scale(scale, scale);
            for (PathFill pf : paths) {
                paint.setColor(pf.color);
                canvas.drawPath(pf.path, paint);
            }
            canvas.restore();
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return (int) sourceWidth; }
        @Override public int getIntrinsicHeight() { return (int) sourceHeight; }
    }
}

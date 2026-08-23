package com.ciallo.hyperbackground;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;

public final class BackgroundProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) {
        enforceReader(); String slot = requireSlot(uri); File file = fileFor(slot);
        if (!file.isFile()) return null;
        return prefs().getString(BackgroundContract.MIME_PREFIX + slot, "application/octet-stream");
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        enforceReader(); if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        File file = fileFor(requireSlot(uri));
        if (!file.isFile()) throw new FileNotFoundException("No custom background");
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        enforceReader(); String slot = requireSlot(uri); File file = fileFor(slot); String[] columns = columns(projection);
        MatrixCursor result = new MatrixCursor(columns, 1); MatrixCursor.RowBuilder row = result.newRow();
        SharedPreferences p = prefs();
        for (String column : columns) {
            if (BackgroundContract.COLUMN_MIME.equals(column)) row.add(p.getString(BackgroundContract.MIME_PREFIX + slot, "application/octet-stream"));
            else if (BackgroundContract.COLUMN_SIZE.equals(column)) row.add(file.isFile() ? file.length() : -1L);
            else if (BackgroundContract.COLUMN_MODIFIED.equals(column)) row.add(file.isFile() ? file.lastModified() : -1L);
            else if (BackgroundContract.COLUMN_OPACITY.equals(column)) row.add(p.getInt(BackgroundContract.OPACITY_PREFIX + slot, 100));
            else if (BackgroundContract.COLUMN_BLUR_ENABLED.equals(column)) row.add(p.getBoolean(BackgroundContract.BLUR_ENABLED_PREFIX + slot, false) ? 1 : 0);
            else if (BackgroundContract.COLUMN_BLUR_RADIUS.equals(column)) row.add(p.getInt(BackgroundContract.BLUR_RADIUS_PREFIX + slot, 20));
            else if (BackgroundContract.COLUMN_FONT_MODE.equals(column)) row.add(p.getInt(BackgroundContract.FONT_MODE, BackgroundContract.FONT_FOLLOW));
            else if (BackgroundContract.COLUMN_DEVICE_LOGO_MODE.equals(column)) row.add(p.getInt(BackgroundContract.DEVICE_LOGO_MODE, BackgroundContract.DEVICE_LOGO_SYSTEM));
            else if (BackgroundContract.COLUMN_DEVICE_LOGO_TEXT.equals(column)) row.add(p.getString(BackgroundContract.DEVICE_LOGO_TEXT, "HyperOS"));
            else if (BackgroundContract.COLUMN_DEVICE_LOGO_COLOR.equals(column)) row.add(p.getInt(BackgroundContract.DEVICE_LOGO_COLOR, 0xFF111111));
            else if (BackgroundContract.COLUMN_SETTINGS_THEME_MODE.equals(column)) row.add(p.getInt(BackgroundContract.SETTINGS_THEME_MODE, BackgroundContract.SETTINGS_THEME_FOLLOW));
            else row.add(null);
        }
        return result;
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read only"); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("Read only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("Read only"); }

    private SharedPreferences prefs() { return getContext().getSharedPreferences(BackgroundContract.PREFS, 0); }
    private void enforceReader() {
        int caller = Binder.getCallingUid(); if (caller == android.os.Process.myUid() || caller == 1000) return;
        PackageManager pm = getContext().getPackageManager(); String[] packages = pm.getPackagesForUid(caller);
        if (packages != null) for (String packageName : packages) {
            if ("com.android.settings".equals(packageName)
                    || "com.milink.service".equals(packageName)) return;
        }
        throw new SecurityException("Only supported system settings packages may read backgrounds");
    }
    private String requireSlot(Uri uri) {
        if (!BackgroundContract.AUTHORITY.equals(uri.getAuthority())) throw new IllegalArgumentException("Unknown authority");
        String slot = uri.getLastPathSegment();
        if (!BackgroundContract.HOME.equals(slot)
                && !BackgroundContract.DEVICE.equals(slot)
                && !BackgroundContract.GLOBAL.equals(slot))
            throw new IllegalArgumentException("Unknown background slot");
        return slot;
    }
    private File fileFor(String slot) { return new File(new File(getContext().getFilesDir(), "backgrounds"), slot + ".bin"); }
    private static String[] columns(String[] projection) {
        if (projection != null && projection.length > 0) return projection;
        return new String[]{BackgroundContract.COLUMN_MIME, BackgroundContract.COLUMN_SIZE, BackgroundContract.COLUMN_MODIFIED,
                BackgroundContract.COLUMN_OPACITY, BackgroundContract.COLUMN_BLUR_ENABLED,
                BackgroundContract.COLUMN_BLUR_RADIUS, BackgroundContract.COLUMN_FONT_MODE,
                BackgroundContract.COLUMN_DEVICE_LOGO_MODE, BackgroundContract.COLUMN_DEVICE_LOGO_TEXT,
                BackgroundContract.COLUMN_DEVICE_LOGO_COLOR, BackgroundContract.COLUMN_SETTINGS_THEME_MODE};
    }
}

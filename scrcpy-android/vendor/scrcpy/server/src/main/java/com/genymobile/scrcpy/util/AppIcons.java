package com.genymobile.scrcpy.util;

import com.genymobile.scrcpy.FakeContext;
import com.genymobile.scrcpy.device.Device;
import com.genymobile.scrcpy.model.DeviceApp;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Optional icon records appended to list_apps; normal scrcpy clients are unchanged. */
public final class AppIcons {
    private static final int ICON_SIZE = 96;
    private static final int MAX_ICON_BYTES = 32 * 1024;
    private static final int MAX_TOTAL_BYTES = 8 * 1024 * 1024;

    private AppIcons() {
        // not instantiable
    }

    public static void write(List<DeviceApp> apps, OutputStream output) throws IOException {
        output.write("PADCONNECT_APPS_END\n".getBytes(StandardCharsets.UTF_8));
        PackageManager pm = FakeContext.get().getPackageManager();
        int totalBytes = 0;
        for (DeviceApp app : apps) {
            byte[] png;
            try {
                Drawable drawable = loadIcon(pm, app.getPackageName());
                png = render(drawable, ICON_SIZE);
                if (png.length > MAX_ICON_BYTES) {
                    png = render(drawable, 64);
                }
            } catch (PackageManager.NameNotFoundException | RuntimeException e) {
                // An app may have been removed, or its resources may be unavailable.
                Ln.w("Cannot load app icon: " + app.getPackageName());
                continue;
            }
            if (png.length == 0 || png.length > MAX_ICON_BYTES || png.length > MAX_TOTAL_BYTES - totalBytes) {
                continue;
            }
            totalBytes += png.length;
            String record = "PADCONNECT_ICON " + app.getPackageName() + " " + Base64.encodeToString(png, Base64.NO_WRAP) + "\n";
            output.write(record.getBytes(StandardCharsets.UTF_8));
        }
        output.flush();
    }

    private static Drawable loadIcon(PackageManager pm, String packageName) throws PackageManager.NameNotFoundException {
        Intent launcher = Device.getLaunchIntent(pm, packageName);
        if (launcher != null) {
            try {
                // Include launcher activity/alias icons rather than assuming the
                // ApplicationInfo icon is the one shown on the target's launcher.
                return pm.getActivityIcon(launcher);
            } catch (PackageManager.NameNotFoundException e) {
                // Fall back to the application's own icon.
            }
        }
        return pm.getApplicationIcon(packageName);
    }

    private static byte[] render(Drawable drawable, int size) {
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Rect originalBounds = new Rect(drawable.getBounds());
        try {
            int width = drawable.getIntrinsicWidth();
            int height = drawable.getIntrinsicHeight();
            if (width > 0 && height > 0) {
                float scale = Math.min((float) size / width, (float) size / height);
                width = Math.max(1, Math.round(width * scale));
                height = Math.max(1, Math.round(height * scale));
            } else {
                width = height = size;
            }
            int left = (size - width) / 2;
            int top = (size - height) / 2;
            drawable.setBounds(left, top, left + width, top + height);
            // Drawable.draw also flattens adaptive and vector icons correctly.
            drawable.draw(new Canvas(bitmap));
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                return new byte[0];
            }
            return output.toByteArray();
        } finally {
            drawable.setBounds(originalBounds);
            bitmap.recycle();
        }
    }
}

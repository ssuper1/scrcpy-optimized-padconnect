package invalid.lena.scrcpy;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Called on the app-query worker, never on the list's scrolling/UI path. */
final class RemoteAppIcons {
    private static final long MAX_TOTAL_PIXELS = 4 * 1024 * 1024;

    private RemoteAppIcons() {}

    static Map<String, Bitmap> decode(List<RemoteApps.Entry> apps) {
        Map<String, Bitmap> icons = new HashMap<>();
        long totalPixels = 0;
        for (RemoteApps.Entry app : apps) {
            byte[] png = app.iconPng;
            if (png == null) continue;
            try {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(png, 0, png.length, options);
                if (options.outWidth <= 0 || options.outWidth > 128
                        || options.outHeight <= 0 || options.outHeight > 128) continue;
                long pixels = (long) options.outWidth * options.outHeight;
                if (pixels > MAX_TOTAL_PIXELS - totalPixels) continue;
                options.inJustDecodeBounds = false;
                options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                Bitmap icon = BitmapFactory.decodeByteArray(png, 0, png.length, options);
                if (icon != null) {
                    icons.put(app.packageName, icon);
                    totalPixels += pixels;
                }
            } catch (RuntimeException e) {
                Log.w("remote apps: could not decode icon for %s", app.packageName);
            }
        }
        return icons;
    }
}

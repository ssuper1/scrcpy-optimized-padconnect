// Modified for padconnect (2026); see the root NOTICE for changes and upstream provenance.
package invalid.lena.scrcpy;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

// Thin wrapper around SharedPreferences for the small set of user
// choices the app exposes. No reactivity, no DataStore - the values
// are read once when a Session starts and applied to the scrcpy
// server command line.
public final class Settings {

    private static final String FILE = "scrcpy-android";

    public static final String VIDEO_CODEC    = "video_codec";
    public static final String AUDIO_CODEC    = "audio_codec";
    public static final String MAX_SIZE       = "max_size";        // px, long edge; 0 = no cap
    public static final String VIDEO_BIT_RATE = "video_bit_rate";  // bits/sec
    public static final String HINT_BACK_SHOWN = "hint_back_shown"; // first-run UI hint
    public static final String CLIPBOARD       = "clipboard";       // two-way clipboard sync
    public static final String PHONE_MODE = "phone_mode";
    public static final String PHONE_TEXT_SCALE = "phone_text_scale";
    public static final String PHONE_LAST_APP = "phone_last_app";
    public static final String FAVORITE_APPS = "favorite_apps";

    public static final String DEFAULT_VIDEO_CODEC    = "h264";
    public static final String DEFAULT_AUDIO_CODEC    = "opus";
    // Defaults sized for the worst link this is meant to work over, not
    // the best. The constraint is the target's UPLINK: an uncapped
    // stream at 8 Mbit/s is fine on a LAN and unusable over a VPN or a
    // home broadband uplink, which is a supported way to reach a target.
    // 1080p at 4 Mbit/s looks fine on a phone and fits an ordinary
    // uplink; raise both in Settings when both devices are on the LAN.
    public static final int    DEFAULT_MAX_SIZE       = 1080;
    public static final int    DEFAULT_VIDEO_BIT_RATE = 4_000_000;
    // On by default: it is a headline feature and the target is one the
    // user deliberately paired with. It is a setting because that trust
    // is not absolute - a compromised target
    // can read whatever is copied on this device and write anything it
    // likes back - and because there was previously no way to decline.
    public static final boolean DEFAULT_CLIPBOARD     = true;

    private Settings() {}

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String videoCodec(Context ctx) {
        String value = string(ctx, VIDEO_CODEC, DEFAULT_VIDEO_CODEC);
        return "h264".equals(value) || "h265".equals(value) || "av1".equals(value)
                ? value : DEFAULT_VIDEO_CODEC;
    }

    public static String audioCodec(Context ctx) {
        String value = string(ctx, AUDIO_CODEC, DEFAULT_AUDIO_CODEC);
        return "opus".equals(value) || "raw".equals(value)
                ? value : DEFAULT_AUDIO_CODEC;
    }

    public static int maxSize(Context ctx) {
        int value = integer(ctx, MAX_SIZE, DEFAULT_MAX_SIZE);
        switch (value) {
            case 0: case 480: case 720: case 1080: case 1440: case 2160:
                return value;
            default:
                return DEFAULT_MAX_SIZE;
        }
    }

    public static int videoBitRate(Context ctx) {
        int value = integer(ctx, VIDEO_BIT_RATE, DEFAULT_VIDEO_BIT_RATE);
        switch (value) {
            case 1_000_000: case 2_000_000: case 4_000_000:
            case 8_000_000: case 16_000_000:
                return value;
            default:
                return DEFAULT_VIDEO_BIT_RATE;
        }
    }

    public static void setVideoCodec(Context ctx, String v) {
        if (!"h264".equals(v) && !"h265".equals(v) && !"av1".equals(v)) {
            throw new IllegalArgumentException("invalid video codec");
        }
        prefs(ctx).edit().putString(VIDEO_CODEC, v).apply();
    }

    public static void setAudioCodec(Context ctx, String a) {
        if (!"opus".equals(a) && !"raw".equals(a)) {
            throw new IllegalArgumentException("invalid audio codec");
        }
        prefs(ctx).edit().putString(AUDIO_CODEC, a).apply();
    }

    public static void setMaxSize(Context ctx, int v) {
        if (v != 0 && v != 480 && v != 720 && v != 1080 && v != 1440 && v != 2160) {
            throw new IllegalArgumentException("invalid maximum size");
        }
        prefs(ctx).edit().putInt(MAX_SIZE, v).apply();
    }

    public static void setVideoBitRate(Context ctx, int v) {
        if (v != 1_000_000 && v != 2_000_000 && v != 4_000_000
                && v != 8_000_000 && v != 16_000_000) {
            throw new IllegalArgumentException("invalid video bit rate");
        }
        prefs(ctx).edit().putInt(VIDEO_BIT_RATE, v).apply();
    }

    public static boolean hintBackShown(Context ctx) {
        return bool(ctx, HINT_BACK_SHOWN, false);
    }

    public static void setHintBackShown(Context ctx, boolean v) {
        prefs(ctx).edit().putBoolean(HINT_BACK_SHOWN, v).apply();
    }

    public static boolean clipboardSync(Context ctx) {
        return bool(ctx, CLIPBOARD, DEFAULT_CLIPBOARD);
    }

    public static void setClipboardSync(Context ctx, boolean v) {
        prefs(ctx).edit().putBoolean(CLIPBOARD, v).apply();
    }

    public static boolean phoneMode(Context ctx) {
        return bool(ctx, PHONE_MODE, true);
    }

    public static void setPhoneMode(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(PHONE_MODE, enabled).apply();
    }

    public static int phoneTextScale(Context ctx) {
        int value = integer(ctx, PHONE_TEXT_SCALE, 100);
        return PhoneDisplay.isValidTextScale(value) ? value : 100;
    }

    public static void setPhoneTextScale(Context ctx, int value) {
        if (!PhoneDisplay.isValidTextScale(value)) {
            throw new IllegalArgumentException("invalid phone text scale");
        }
        prefs(ctx).edit().putInt(PHONE_TEXT_SCALE, value).apply();
    }

    public static String phoneLastApp(Context ctx) {
        return string(ctx, PHONE_LAST_APP, "com.android.settings");
    }

    public static void setPhoneLastApp(Context ctx, String packageName) {
        ControlMessages.startApp(packageName); // Validate before persisting.
        prefs(ctx).edit().putString(PHONE_LAST_APP, packageName).apply();
    }

    public static Set<String> favoriteApps(Context ctx) {
        try {
            Set<String> saved = prefs(ctx).getStringSet(FAVORITE_APPS, Collections.emptySet());
            // SharedPreferences owns the returned set; callers only receive a copy.
            return saved == null ? new HashSet<>() : new HashSet<>(saved);
        } catch (ClassCastException e) {
            Log.w("settings: %s has the wrong type", FAVORITE_APPS);
            return new HashSet<>();
        }
    }

    public static void setAppFavorite(Context ctx, String packageName, boolean favorite) {
        ControlMessages.startApp(packageName); // Validate without sending a command.
        Set<String> saved = favoriteApps(ctx);
        if (favorite) saved.add(packageName);
        else saved.remove(packageName);
        prefs(ctx).edit().putStringSet(FAVORITE_APPS, saved).apply();
    }

    private static String string(Context ctx, String key, String fallback) {
        try {
            String value = prefs(ctx).getString(key, fallback);
            return value == null ? fallback : value;
        } catch (ClassCastException e) {
            Log.w("settings: %s has the wrong type", key);
            return fallback;
        }
    }

    private static int integer(Context ctx, String key, int fallback) {
        try {
            return prefs(ctx).getInt(key, fallback);
        } catch (ClassCastException e) {
            Log.w("settings: %s has the wrong type", key);
            return fallback;
        }
    }

    private static boolean bool(Context ctx, String key, boolean fallback) {
        try {
            return prefs(ctx).getBoolean(key, fallback);
        } catch (ClassCastException e) {
            Log.w("settings: %s has the wrong type", key);
            return fallback;
        }
    }
}

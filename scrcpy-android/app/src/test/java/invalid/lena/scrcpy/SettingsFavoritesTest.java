package invalid.lena.scrcpy;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

public class SettingsFavoritesTest {
    @Test public void favoritesSurviveReopeningAndUnfavoritingKeepsOtherApps() {
        PreferenceStore store = new PreferenceStore();
        assertTrue(Settings.favoriteApps(store.context()).isEmpty());
        Settings.setAppFavorite(store.context(), "com.example.a", true);
        Settings.setAppFavorite(store.context(), "com.example.b", true);
        // A new Context/SharedPreferences instance reads the previously applied data.
        assertEquals(new HashSet<>(Arrays.asList("com.example.a", "com.example.b")),
                Settings.favoriteApps(store.context()));
        Settings.setAppFavorite(store.context(), "com.example.a", false);
        Settings.setAppFavorite(store.context(), "com.example.a", false);
        assertEquals(Collections.singleton("com.example.b"), Settings.favoriteApps(store.context()));
    }

    @Test public void callersAndUpdatesNeverMutateThePreferencesOwnedSet() {
        PreferenceStore store = new PreferenceStore();
        Set<String> original = Collections.singleton("com.example.a");
        store.values.put(Settings.FAVORITE_APPS, original);
        Set<String> snapshot = Settings.favoriteApps(store.context());
        snapshot.clear();
        assertEquals(original, Settings.favoriteApps(store.context()));
        Settings.setAppFavorite(store.context(), "com.example.b", true);
        assertEquals(Collections.singleton("com.example.a"), original);
        assertEquals(2, Settings.favoriteApps(store.context()).size());
    }

    @Test public void malformedTypeFallsBackAndCanBeReplacedByAValidFavorite() {
        PreferenceStore store = new PreferenceStore();
        store.values.put(Settings.FAVORITE_APPS, "legacy wrong type");
        assertTrue(Settings.favoriteApps(store.context()).isEmpty());
        Settings.setAppFavorite(store.context(), "com.example.a", true);
        assertEquals(Collections.singleton("com.example.a"), Settings.favoriteApps(store.context()));
    }

    @Test public void invalidPackagesLeaveSavedFavoritesIntact() {
        PreferenceStore store = new PreferenceStore();
        Settings.setAppFavorite(store.context(), "com.example.a", true);
        assertThrows(IllegalArgumentException.class,
                () -> Settings.setAppFavorite(store.context(), "com.example.a;reboot", true));
        assertEquals(Collections.singleton("com.example.a"), Settings.favoriteApps(store.context()));
    }

    @Test public void clearingRecentAppsOnlyClearsTheSelectedDeviceAndPreservesFavorites() {
        PreferenceStore store = new PreferenceStore();
        Settings.setAppFavorite(store.context(), "com.example.a", true);
        Settings.recordRecentApp(store.context(), "tablet-a", "com.example.a");
        Settings.recordRecentApp(store.context(), "tablet-b", "com.example.b");

        Settings.clearRecentApps(store.context(), "tablet-a");
        assertTrue(Settings.recentApps(store.context(), "tablet-a").isEmpty());
        assertEquals(Collections.singletonList("com.example.b"), Settings.recentApps(store.context(), "tablet-b"));
        assertEquals(Collections.singleton("com.example.a"), Settings.favoriteApps(store.context()));

        Settings.recordRecentApp(store.context(), "tablet-a", "com.example.c");
        assertEquals(Collections.singletonList("com.example.c"), Settings.recentApps(store.context(), "tablet-a"));
    }

    @Test public void clearingWithoutADeviceIdDoesNotEraseOtherHistory() {
        PreferenceStore store = new PreferenceStore();
        Settings.recordRecentApp(store.context(), "tablet-a", "com.example.a");
        Settings.clearRecentApps(store.context(), null);
        Settings.clearRecentApps(store.context(), "");
        assertEquals(Collections.singletonList("com.example.a"), Settings.recentApps(store.context(), "tablet-a"));
    }

    // Enforce Android's ownership contract: sets returned by preferences cannot
    // be mutated, and editor changes become visible only when applied.
    private static final class PreferenceStore {
        final Map<String, Object> values = new HashMap<>();

        Context context() {
            Map<String, Object> pending = new HashMap<>();
            Set<String> removed = new HashSet<>();
            SharedPreferences.Editor editor = (SharedPreferences.Editor) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "putStringSet":
                                Set<?> source = (Set<?>) args[1];
                                Set<String> copy = new HashSet<>();
                                for (Object value : source) copy.add((String) value);
                                pending.put((String) args[0], Collections.unmodifiableSet(copy));
                                return proxy;
                            case "putString":
                                pending.put((String) args[0], args[1]);
                                return proxy;
                            case "remove":
                                removed.add((String) args[0]);
                                return proxy;
                            case "apply":
                                for (String key : removed) values.remove(key);
                                values.putAll(pending);
                                removed.clear();
                                pending.clear();
                                return null;
                            default: throw new UnsupportedOperationException(method.getName());
                        }
                    });
            SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{SharedPreferences.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "getStringSet":
                                Object value = values.get(args[0]);
                                if (value == null) return args[1];
                                if (!(value instanceof Set<?>)) throw new ClassCastException();
                                return value;
                            case "getString":
                                Object text = values.get(args[0]);
                                if (text == null) return args[1];
                                if (!(text instanceof String)) throw new ClassCastException();
                                return text;
                            case "edit": return editor;
                            default: throw new UnsupportedOperationException(method.getName());
                        }
                    });
            return new ContextWrapper(null) {
                @Override public Context getApplicationContext() { return this; }
                @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                    return preferences;
                }
            };
        }
    }
}

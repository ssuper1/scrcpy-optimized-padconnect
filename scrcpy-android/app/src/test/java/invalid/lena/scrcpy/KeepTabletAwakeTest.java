package invalid.lena.scrcpy;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class KeepTabletAwakeTest {
    @Test public void defaultProtectsBothDisplayModesWithoutRequiringCharging() throws Exception {
        Store store = new Store();
        assertTrue(Settings.keepTabletAwake(store.context()));
        for (PhoneDisplay display : new PhoneDisplay[]{null, PhoneDisplay.fit(1080, 2400, 100)}) {
            String command = command(store.context(), display);
            assertTrue(command.contains(" screen_off_timeout=2147483647"));
            assertTrue(command.contains(" cleanup=true"));
            assertFalse(command.contains(" stay_awake=true"));
        }
    }

    @Test public void disablingPersistsAcrossContextsAndLeavesTargetTimeoutUntouched() throws Exception {
        Store store = new Store();
        Settings.setKeepTabletAwake(store.context(), false);
        assertFalse(Settings.keepTabletAwake(store.context()));
        assertFalse(command(store.context(), null).contains("screen_off_timeout="));
        assertFalse(command(store.context(), PhoneDisplay.fit(1080, 2400, 100))
                .contains("screen_off_timeout="));
        Settings.setKeepTabletAwake(store.context(), true);
        assertTrue(command(store.context(), null).contains("screen_off_timeout=2147483647"));
    }

    @Test public void runningServerKeepsItsSnapshotUntilReconnect() throws Exception {
        Store store = new Store();
        Server original = new Server(store.context(), null, null);
        Settings.setKeepTabletAwake(store.context(), false);
        assertTrue(command(original).contains("screen_off_timeout=2147483647"));
        assertFalse(command(store.context(), null).contains("screen_off_timeout="));
    }

    private static String command(Context ctx, PhoneDisplay display) throws Exception {
        return command(new Server(ctx, null, display));
    }

    private static String command(Server server) throws Exception {
        Method build = Server.class.getDeclaredMethod("buildCmdline", String.class, String.class);
        build.setAccessible(true);
        return (String) build.invoke(server, "4.1", "12345678");
    }

    private static final class Store {
        final Map<String, Object> values = new HashMap<>();

        Context context() {
            Map<String, Object> pending = new HashMap<>();
            SharedPreferences.Editor editor = (SharedPreferences.Editor) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "putBoolean": pending.put((String) args[0], args[1]); return proxy;
                            case "apply": values.putAll(pending); pending.clear(); return null;
                            default: throw new UnsupportedOperationException(method.getName());
                        }
                    });
            SharedPreferences prefs = (SharedPreferences) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{SharedPreferences.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "getBoolean": case "getString": case "getInt":
                                return values.containsKey(args[0]) ? values.get(args[0]) : args[1];
                            case "edit": return editor;
                            default: throw new UnsupportedOperationException(method.getName());
                        }
                    });
            return new ContextWrapper(null) {
                @Override public Context getApplicationContext() { return this; }
                @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                    return prefs;
                }
            };
        }
    }
}

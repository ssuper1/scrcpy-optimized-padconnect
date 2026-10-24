package invalid.lena.scrcpy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Bounded launch history, newest first. This is not Android's running-task list. */
public final class RecentApps {
    public static final int LIMIT = 20;

    private RecentApps() {}

    public static List<String> record(List<String> previous, String packageName) {
        ControlMessages.startApp(packageName);
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        unique.add(packageName);
        for (String entry : previous) {
            try {
                ControlMessages.startApp(entry);
                unique.add(entry);
            } catch (IllegalArgumentException ignored) {
                // Drop stale or malformed stored entries without losing valid history.
            }
            if (unique.size() == LIMIT) break;
        }
        return new ArrayList<>(unique);
    }

    public static List<RemoteApps.Entry> select(List<RemoteApps.Entry> installed, List<String> history) {
        Map<String, RemoteApps.Entry> byPackage = new LinkedHashMap<>();
        for (RemoteApps.Entry app : installed) byPackage.put(app.packageName, app);
        List<RemoteApps.Entry> result = new ArrayList<>();
        for (String packageName : history) {
            RemoteApps.Entry app = byPackage.remove(packageName);
            if (app != null) result.add(app);
            if (result.size() == LIMIT) break;
        }
        return result;
    }
}

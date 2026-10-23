package invalid.lena.scrcpy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the pinned scrcpy server's list_apps output, including wrapped labels. */
public final class RemoteApps {
    private static final String PACKAGE = "[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+";
    private static final Pattern INLINE = Pattern.compile("^(.*?) {2,}(" + PACKAGE + ")\\s*$");

    public static final class Entry {
        public final String label, packageName;

        Entry(String label, String packageName) {
            this.label = label;
            this.packageName = packageName;
        }

        @Override public String toString() {
            return label + "\n" + packageName;
        }
    }

    private RemoteApps() {}

    public static List<Entry> filter(List<Entry> apps, String query) {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        List<Entry> result = new ArrayList<>();
        for (Entry app : apps) {
            if (app.label.toLowerCase(Locale.ROOT).contains(needle)
                    || app.packageName.toLowerCase(Locale.ROOT).contains(needle)) {
                result.add(app);
            }
        }
        return result;
    }

    public static List<Entry> parse(String output) throws IOException {
        LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
        String pending = null;
        for (String line : output.split("\\r?\\n")) {
            if (line.startsWith(" * ") || line.startsWith(" - ")) {
                String body = line.substring(3);
                Matcher match = INLINE.matcher(body);
                if (match.matches()) {
                    String pkg = match.group(2);
                    entries.put(pkg, new Entry(match.group(1).trim(), pkg));
                    pending = null;
                } else {
                    pending = body.trim();
                }
            } else if (pending != null && line.trim().matches(PACKAGE)) {
                String pkg = line.trim();
                entries.put(pkg, new Entry(pending, pkg));
                pending = null;
            } else {
                pending = null;
            }
        }
        if (entries.isEmpty()) throw new IOException("No launchable apps returned by the target");
        return new ArrayList<>(entries.values());
    }
}

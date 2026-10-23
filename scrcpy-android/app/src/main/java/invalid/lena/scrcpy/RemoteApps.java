package invalid.lena.scrcpy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the pinned scrcpy server's list_apps output, including wrapped labels. */
public final class RemoteApps {
    private static final String PACKAGE = "[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+";
    private static final Pattern INLINE = Pattern.compile("^(.*?) {2,}(" + PACKAGE + ")\\s*$");
    private static final String ICON_PREFIX = "PADCONNECT_ICON ";
    private static final int MAX_ICON_BYTES = 32 * 1024;
    private static final int MAX_ICON_TOTAL_BYTES = 8 * 1024 * 1024;

    public static final class Entry {
        public final String label, packageName;
        final byte[] iconPng;

        Entry(String label, String packageName) {
            this(label, packageName, null);
        }

        private Entry(String label, String packageName, byte[] iconPng) {
            this.label = label;
            this.packageName = packageName;
            this.iconPng = iconPng;
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

    /** Keeps search results in their original order within the two groups. */
    public static List<Entry> filterAndPin(List<Entry> apps, String query, Set<String> favorites) {
        List<Entry> matches = filter(apps, query);
        matches.sort((left, right) -> Boolean.compare(
                favorites.contains(right.packageName), favorites.contains(left.packageName)));
        return matches;
    }

    public static List<Entry> parse(String output) throws IOException {
        LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
        String pending = null;
        int iconBytes = 0;
        for (String line : output.split("\\r?\\n")) {
            if (line.startsWith(ICON_PREFIX)) {
                pending = null;
                int separator = line.indexOf(' ', ICON_PREFIX.length());
                if (separator < 0) continue;
                String pkg = line.substring(ICON_PREFIX.length(), separator);
                Entry app = entries.get(pkg);
                if (app == null || app.iconPng != null) continue;
                String encoded = line.substring(separator + 1);
                if (encoded.length() > ((MAX_ICON_BYTES + 2) / 3) * 4) continue;
                try {
                    byte[] png = Base64.getDecoder().decode(encoded);
                    if (validIcon(png) && png.length <= MAX_ICON_TOTAL_BYTES - iconBytes) {
                        entries.put(pkg, new Entry(app.label, pkg, png));
                        iconBytes += png.length;
                    }
                } catch (IllegalArgumentException ignored) {
                    // A broken icon must not hide an otherwise launchable app.
                }
            } else if (line.startsWith(" * ") || line.startsWith(" - ")) {
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

    private static boolean validIcon(byte[] png) {
        if (png.length < 33 || png.length > MAX_ICON_BYTES) return false;
        byte[] header = {(byte) 137, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82};
        for (int i = 0; i < header.length; i++) {
            if (png[i] != header[i]) return false;
        }
        int width = Wire.readBe32(png, 16);
        int height = Wire.readBe32(png, 20);
        // Bound decoded memory before passing remote image data to BitmapFactory.
        return width > 0 && height > 0 && width <= 128 && height <= 128;
    }
}

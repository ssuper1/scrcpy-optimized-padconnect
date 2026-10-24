// Modified for padconnect (2026); see the root NOTICE for changes and upstream provenance.
package invalid.lena.scrcpy;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

// Tiny JSON-backed list of paired targets, stored at filesDir/devices.json.
// No SQLite, no Room. Read on demand, written whole-file on add/remove.
//
// parse() / serialize() are pure-java and android-free for unit tests.
// load() / save() add the Context-rooted file I/O.
public final class Devices {

    private static final String FILE = "devices.json";
    private static final long MAX_FILE_BYTES = 1024 * 1024;
    private static final int MAX_HOST_CHARS = 255;

    public static final class Device {
        public final String host;
        public final int    port;
        public final String id;
        public final String guid;

        public Device(String host, int port) {
            this(UUID.randomUUID().toString(), host, port, null);
        }

        Device(String id, String host, int port, String guid) {
            this.id = id;
            this.host = host;
            this.port = port;
            this.guid = normalizeGuid(guid);
        }

        public Device at(String host, int port) {
            return new Device(id, host, port, guid);
        }

        public Device withGuid(String guid) {
            return new Device(id, host, port, guid);
        }

        // Bracket IPv6 literals so the port stays unambiguous. Round-trips
        // through parseAddress().
        @Override
        public String toString() {
            return host.indexOf(':') < 0
                    ? host + ":" + port
                    : "[" + host + "]:" + port;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Device d)) return false;
            return port == d.port && host.equals(d.host);
        }

        @Override
        public int hashCode() {
            return host.hashCode() * 31 + port;
        }
    }

    private Devices() {}

    // Parse "host:port" exactly as the target's Wireless debugging screen
    // prints it. IPv6 literals must be bracketed ("[fe80::1]:5555") because
    // the address itself contains colons; an unbracketed one is rejected
    // rather than silently split at the wrong colon. Returns null on
    // anything malformed - the only caller is a text field.
    public static Device parseAddress(String s) {
        if (s == null) return null;
        s = s.trim();
        String host, port;
        if (s.startsWith("[")) {
            int end = s.indexOf(']');
            if (end < 0 || !s.startsWith("]:", end)) return null;
            host = s.substring(1, end);
            port = s.substring(end + 2);
        } else {
            int colon = s.indexOf(':');
            if (colon < 0 || colon != s.lastIndexOf(':')) return null;
            host = s.substring(0, colon);
            port = s.substring(colon + 1);
        }
        int p = parsePort(port);
        if (!validHost(host) || p < 0) return null;
        return new Device(host, p);
    }

    // Digits only, 1-65535. Returns -1 if it is not a usable port.
    // Integer.parseInt() alone would accept "+5555" and " 5555".
    public static int parsePort(String s) {
        if (s == null) return -1;
        s = s.trim();
        if (s.isEmpty() || s.length() > 5) return -1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') return -1;
        }
        int p = Integer.parseInt(s);
        return p >= 1 && p <= 65535 ? p : -1;
    }

    // Pure-java parse: returns whatever rows are well-formed; logs and
    // skips anything malformed instead of nuking the list.
    public static List<Device> parse(String json) {
        List<Device> out = new ArrayList<>();
        if (json == null) return out;
        json = json.trim();
        if (json.isEmpty()) return out;
        JSONArray arr;
        try {
            arr = new JSONArray(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("devices: expected a JSON array", e);
        }

        for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject o = arr.getJSONObject(i);
                String host = o.getString("host");
                Object portValue = o.get("port");
                if (!(portValue instanceof Integer) && !(portValue instanceof Long)) {
                    throw new IllegalArgumentException("port is not an integer");
                }
                long portLong = ((Number) portValue).longValue();
                if (portLong < 1 || portLong > 65535) {
                    throw new IllegalArgumentException("port is invalid");
                }
                int port = (int) portLong;
                if (!validHost(host) || port < 1 || port > 65535) {
                    throw new IllegalArgumentException("invalid device fields");
                }
                // Legacy IDs are deterministic until the next save migrates the file.
                String legacyId = "legacy:" + new Device(host, port).toString();
                String id = o.optString("id", legacyId);
                if (id.isEmpty() || id.length() > 512) id = legacyId;
                String guid = o.optString("guid", null);
                out.add(new Device(id, host, port, guid));
            } catch (Exception e) {
                Log.w("devices: skipping malformed row %d: %s", i, e);
            }
        }
        return out;
    }

    public static String serialize(List<Device> devices) {
        try {
            JSONArray arr = new JSONArray();
            for (Device d : devices) {
                if (!validHost(d.host) || d.port < 1 || d.port > 65535) {
                    throw new IllegalArgumentException("invalid saved device");
                }
                JSONObject o = new JSONObject();
                o.put("host", d.host);
                o.put("port", d.port);
                o.put("id", d.id);
                if (d.guid != null) o.put("guid", d.guid);
                arr.put(o);
            }
            return arr.toString(2);
        } catch (Exception e) {
            throw new IllegalStateException("devices: serialize failed", e);
        }
    }

    public static List<Device> load(Context ctx) throws IOException {
        File f = new File(ctx.getFilesDir(), FILE);
        if (!f.exists()) return new ArrayList<>();
        try {
            if (f.length() > MAX_FILE_BYTES) {
                throw new IOException("device list is too large: " + f.length());
            }
            byte[] data = readLimited(f);
            if (data.length == 0) throw new IOException("device list is empty");
            return parse(Wire.decodeUtf8(data));
        } catch (Exception e) {
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("devices: load failed", e);
        }
    }

    // The saved row for an address, or null if there is none.
    public static Device find(Context ctx, String host, int port) throws IOException {
        for (Device d : load(ctx)) {
            if (d.port == port && d.host.equals(host)) return d;
        }
        return null;
    }

    public static Device findById(Context ctx, String id) throws IOException {
        return findById(load(ctx), id);
    }

    static Device findById(List<Device> devices, String id) {
        if (id == null) return null;
        for (Device d : devices) if (id.equals(d.id)) return d;
        return null;
    }

    private static void save(Context ctx, List<Device> devices) throws IOException {
        File f = new File(ctx.getFilesDir(), FILE);
        AtomicFiles.write(f, serialize(devices).getBytes(StandardCharsets.UTF_8));
    }

    // Add or replace by host+port. Context-rooted; the in-place helper
    // is the test seam.
    public static synchronized List<Device> upsert(Context ctx, Device d) throws IOException {
        List<Device> list = load(ctx);
        upsertIn(list, d);
        save(ctx, list);
        return list;
    }

    // Remove the matching device (by host+port). Returns the updated list.
    public static synchronized List<Device> remove(Context ctx, Device d) throws IOException {
        List<Device> list = load(ctx);
        list.removeIf(row -> row.id.equals(d.id));
        save(ctx, list);
        return list;
    }

    static void upsertIn(List<Device> list, Device device) {
        // Re-pairing the same target keeps its stable local ID and avoids duplicate rows.
        Device previous = null;
        for (Device row : list) {
            if ((device.guid != null && device.guid.equals(row.guid))
                    || (device.equals(row) && (row.guid == null || device.guid == null))) {
                previous = row;
                break;
            }
        }
        if (previous != null) {
            String guid = device.guid != null ? device.guid : previous.guid;
            Device merged = new Device(previous.id, device.host, device.port, guid);
            list.removeIf(row -> row.id.equals(merged.id)
                    || (merged.guid != null && merged.guid.equals(row.guid)));
            list.add(merged);
        } else {
            list.removeIf(row -> row.id.equals(device.id));
            list.add(device);
        }
    }

    // Compare-and-replace: a stale discovery must neither resurrect a forgotten
    // row nor overwrite an address that was edited while it was connecting.
    public static synchronized Device replace(Context ctx, Device expected, Device updated) throws IOException {
        List<Device> list = load(ctx);
        Device replaced = replaceIn(list, expected, updated);
        if (replaced == null) throw new IOException("Saved device changed or was removed; select it again");
        save(ctx, list);
        return replaced;
    }

    static Device replaceIn(List<Device> list, Device expected, Device updated) {
        Device current = findById(list, expected.id);
        if (current == null || !current.equals(expected) || !Objects.equals(current.guid, expected.guid)
                || !expected.id.equals(updated.id) || !validHost(updated.host)
                || updated.port < 1 || updated.port > 65535) return null;
        if (current.guid != null && !current.guid.equals(updated.guid)) return null;
        for (int index = 0; index < list.size(); index++) {
            if (list.get(index).id.equals(current.id)) { list.set(index, updated); break; }
        }
        return updated;
    }

    static String normalizeGuid(String guid) {
        if (guid == null || guid.isEmpty() || guid.length() > 255) return null;
        for (int i = 0; i < guid.length(); i++) {
            char c = guid.charAt(i);
            if (c < 0x21 || c > 0x7e) return null;
        }
        return guid;
    }

    private static boolean validHost(String host) {
        if (host == null || host.isEmpty() || host.length() > MAX_HOST_CHARS) return false;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c) || c == '[' || c == ']') {
                return false;
            }
        }
        return true;
    }

    private static byte[] readLimited(File file) throws IOException {
        try (InputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            long total = 0;
            for (;;) {
                int n = in.read(buf);
                if (n < 0) return out.toByteArray();
                if (n == 0) throw new IOException("device list read made no progress");
                total += n;
                if (total > MAX_FILE_BYTES) throw new IOException("device list is too large");
                out.write(buf, 0, n);
            }
        }
    }
}

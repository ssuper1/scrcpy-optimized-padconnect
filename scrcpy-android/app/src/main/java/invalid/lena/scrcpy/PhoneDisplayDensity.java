package invalid.lena.scrcpy;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Checks the effective window-manager density, not just the requested display DPI. */
final class PhoneDisplayDensity {
    private static final Pattern NEW_DISPLAY = Pattern.compile(
            "New display: [0-9]+x[0-9]+/[0-9]+ \\(id=([0-9]+)\\)");
    private static final Pattern PHYSICAL = Pattern.compile(
            "(?m)^Physical density: ([0-9]+)\\s*$");
    private static final Pattern OVERRIDE = Pattern.compile(
            "(?m)^Override density: ([0-9]+)\\s*$");

    interface Shell {
        String run(String command) throws Exception;
    }

    private PhoneDisplayDensity() {}

    static int findDisplayId(String output) {
        Matcher match = NEW_DISPLAY.matcher(output);
        if (!match.find()) return -1;
        try {
            int id = Integer.parseInt(match.group(1));
            return id > 0 ? id : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static int effectiveDensity(String output) throws IOException {
        // Shell v1 merges stdout/stderr. Never accept an error followed by a
        // partial or stale successful-looking value.
        if (output.contains("Error") || output.contains("Exception")) {
            throw new IOException("Display density query was rejected");
        }
        Matcher physical = PHYSICAL.matcher(output);
        if (!physical.find()) throw new IOException("Missing display density");
        int value = parseDensity(physical.group(1));
        if (physical.find()) throw new IOException("Ambiguous display density");
        Matcher override = OVERRIDE.matcher(output);
        if (override.find()) value = parseDensity(override.group(1));
        if (override.find()) throw new IOException("Ambiguous density override");
        return value;
    }

    static int ensure(int displayId, int requestedDpi, Shell shell) throws Exception {
        // Require an explicit secondary display for EVERY query and mutation.
        // Never fall back to an unqualified wm command (which targets display 0).
        if (displayId <= 0 || requestedDpi < 72) {
            throw new IllegalArgumentException("Invalid secondary display density");
        }
        String query = "wm density -d " + displayId;
        int actual = effectiveDensity(shell.run(query));
        if (actual == requestedDpi) return actual;
        String result = shell.run("wm density " + requestedDpi + " -d " + displayId);
        if (!result.trim().isEmpty()) {
            throw new IOException("Display density change was rejected");
        }
        actual = effectiveDensity(shell.run(query));
        if (actual != requestedDpi) {
            throw new IOException("Display density stayed at " + actual + ", requested " + requestedDpi);
        }
        return actual;
    }

    private static int parseDensity(String value) throws IOException {
        try {
            int density = Integer.parseInt(value);
            if (density >= 72) return density;
        } catch (NumberFormatException ignored) {
            // Convert malformed/overflow values to the same checked failure.
        }
        throw new IOException("Invalid display density");
    }
}

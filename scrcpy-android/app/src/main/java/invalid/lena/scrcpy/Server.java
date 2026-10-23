// Modified for padconnect (2026); see the root NOTICE for changes and upstream provenance.
package invalid.lena.scrcpy;

import android.content.Context;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.net.ConnectException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.muntashirakon.adb.AdbStream;

// Bring the scrcpy server up on the target:
//   1. Push assets/scrcpy-server.jar to /data/local/tmp/scrcpy-server.jar
//      via the adb sync protocol.
//   2. Spawn `app_process / com.genymobile.scrcpy.Server <ver> key=value...`
//      via an adb shell stream and hold it open. cleanup=true means the
//      server exits when we close that stream.
//   3. Open three localabstract:scrcpy_<scid> streams in order
//      (video, audio, control). With tunnel_forward=true the server is
//      the listener, so we just dial.
//   4. Drain 1 probe byte + 64-byte device name from the FIRST stream
//      that the server accepts (video, if requested).
//
// Returns a Streams record with everything Session needs: typed
// InputStream/OutputStream wrappers (opened ONCE per AdbStream so the
// reader's offset is unambiguous), plus the underlying AdbStream
// handles so close() can release them.
public final class Server {

    private static final String REMOTE_PATH    = "/data/local/tmp/scrcpy-server.jar";
    private static final String ASSET_JAR      = "scrcpy-server.jar";
    private static final String ASSET_VERSION  = "scrcpy-server.version";
    private static final int    FILE_MODE      = 0100644;         // regular file, 0644
    private static final long   LISTENER_DEADLINE_MS = 20_000;
    private static final long   LISTENER_RETRY_MS = 100;
    public static final class Streams {
        public final AdbStream    videoAds, audioAds, controlAds;
        public final InputStream  videoIn, audioIn, controlIn;
        public final OutputStream controlOut;

        Streams(AdbStream va, AdbStream aa, AdbStream ca,
                InputStream vi, InputStream ai, InputStream ci, OutputStream co) {
            this.videoAds = va; this.audioAds = aa; this.controlAds = ca;
            this.videoIn = vi;  this.audioIn = ai;  this.controlIn = ci;
            this.controlOut = co;
        }
    }

    private final Context ctx;
    private final Adb     adb;
    private final PhoneDisplay phoneDisplay;
    private AdbStream     shell;
    private Thread        shellPump;
    private Streams       streams;
    private volatile boolean serverEof;
    private final CountDownLatch displayReady = new CountDownLatch(1);
    private volatile int virtualDisplayId = -1;

    public Server(Context ctx, Adb adb, PhoneDisplay phoneDisplay) {
        this.ctx = ctx;
        this.adb = adb;
        this.phoneDisplay = phoneDisplay;
    }

    public Streams bringUp() throws Exception {
        serverEof = false;
        String version = readVersion();
        long pushed = push();
        Log.i("push %s bytes=%d", REMOTE_PATH, pushed);

        String scid = newScid();
        String cmd = buildCmdline(version, scid);
        Log.i("spawn server ver=%s scid=%s", version, scid);
        Log.i("cmdline: %s", cmd);

        AdbStream va = null, aa = null, ca = null;
        boolean committed = false;
        try {
            shell = adb.openShell(cmd);
            AdbStream shellRef = shell;
            shellPump = new Thread(() -> pump(shellRef.openInputStream()), "server-stdout");
            shellPump.setDaemon(true);
            shellPump.start();

            // These accepts are ordered. If one times out, the whole ADB
            // connection is discarded by Session; retrying an individual
            // open could shift video/audio/control onto the wrong sockets.
            va = openAbstract(scid);
            aa = openAbstract(scid);
            ca = openAbstract(scid);

            InputStream  vi = va.openInputStream();
            InputStream  ai = aa.openInputStream();
            InputStream  ci = ca.openInputStream();
            OutputStream co = ca.openOutputStream();

            Log.i("device name=%s", readDeviceMeta(vi));
            streams = new Streams(va, aa, ca, vi, ai, ci, co);
            committed = true;
            return streams;
        } finally {
            if (!committed) {
                closeQuietly(va);
                closeQuietly(aa);
                closeQuietly(ca);
                closeShell();
            }
        }
    }

    // Idempotent. Closes the three media/control streams first (lets
    // the wire drain), then the shell (which makes the scrcpy server
    // exit via cleanup=true), then gives the cleanup helper up to
    // CLOSE_GRACE_MS to restore device state - display power, screen
    // timeout, brightness, etc. - before we kill the adb connection
    // out from under it. Best-effort: cleanup is server-side and we
    // can't synchronously confirm it.
    public void close() {
        displayReady.countDown();
        if (streams != null) {
            closeQuietly(streams.videoAds);
            closeQuietly(streams.audioAds);
            closeQuietly(streams.controlAds);
            streams = null;
        }
        closeShell();
    }

    private void closeShell() {
        Thread t = shellPump;
        AdbStream s = shell;
        shell = null;
        shellPump = null;
        closeQuietly(s);
        if (t != null) {
            try {
                t.join(CLOSE_GRACE_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (t.isAlive()) t.interrupt();
        }
    }

    private static final long CLOSE_GRACE_MS = 500;

    private static void closeQuietly(AdbStream s) {
        if (s == null) return;
        try { s.close(); } catch (IOException ignored) {}
    }

    // ---- helpers ----

    private String readVersion() throws IOException {
        try (InputStream in = ctx.getAssets().open(ASSET_VERSION)) {
            byte[] buf = new byte[64];
            int n = 0;
            while (n < buf.length) {
                int r = in.read(buf, n, buf.length - n);
                if (r < 0) break;
                if (r == 0) throw new IOException("scrcpy-server.version read made no progress");
                n += r;
            }
            if (n == buf.length && in.read() >= 0) {
                throw new IOException("scrcpy-server.version is too long");
            }
            String v = Wire.decodeUtf8(buf, 0, n).trim();
            if (!v.matches("[0-9]+(\\.[0-9]+)*")) {
                throw new IOException("scrcpy-server.version is invalid");
            }
            return v;
        }
    }

    private static String newScid() {
        // 31-bit random, 8 lowercase hex chars - matches scrcpy upstream client.
        int v = new SecureRandom().nextInt() & 0x7fffffff;
        return String.format(Locale.ROOT, "%08x", v);
    }

    private String buildCmdline(String version, String scid) {
        String videoCodec = Settings.videoCodec(ctx);
        String audioCodec = Settings.audioCodec(ctx);
        int maxSize     = Settings.maxSize(ctx);
        if (phoneDisplay != null) maxSize = PhoneDisplay.videoMaxSize(maxSize);
        int videoBitR   = Settings.videoBitRate(ctx);
        List<String> args = new ArrayList<>();
        args.add("CLASSPATH=" + REMOTE_PATH);
        args.add("app_process");
        args.add("/");
        args.add("com.genymobile.scrcpy.Server");
        args.add(version);
        args.add("scid=" + scid);
        args.add("log_level=info");
        args.add("video=true");
        args.add("audio=true");
        args.add("control=true");
        args.add("video_codec=" + videoCodec);
        args.add("audio_codec=" + audioCodec);
        args.add("max_size=" + maxSize);
        args.add("video_bit_rate=" + videoBitR);
        // Off means off at the source: the server never sends the
        // target's clipboard, rather than us receiving and discarding it.
        args.add("clipboard_autosync=" + Settings.clipboardSync(ctx));
        args.add("tunnel_forward=true");
        args.add("cleanup=true");
        args.add("power_on=true");
        if (phoneDisplay != null) {
            args.add("new_display=" + phoneDisplay.serverValue());
            args.add("flex_display=true");
            args.add("vd_destroy_content=false");
            args.add("display_ime_policy=local");
            // Let the tablet provide its supported system bars. Immersive
            // mode on the controller phone is a separate, local setting.
            // Home/app selection remains available through the edge menu.
            args.add("vd_system_decorations=true");
        }
        return String.join(" ", args);
    }

    public synchronized List<RemoteApps.Entry> queryApps() throws Exception {
        // Streaming cleanup unlinks its jar at startup. This process needs
        // its own file, which list mode unlinks after loading the classes.
        String queryPath = "/data/local/tmp/scrcpy-apps-" + newScid() + ".jar";
        push(queryPath);
        String cmd = "CLASSPATH=" + queryPath
                + " app_process / com.genymobile.scrcpy.Server " + readVersion()
                + " list_apps=true list_app_icons=true cleanup=true";
        AdbStream query = adb.openShell(cmd);
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean timedOut = new AtomicBoolean();
        Thread deadline = new Thread(() -> {
            try {
                if (!done.await(20, TimeUnit.SECONDS)) {
                    timedOut.set(true);
                    closeQuietly(query);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "app-list-deadline");
        deadline.setDaemon(true);
        deadline.start();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            InputStream input = query.openInputStream();
            byte[] buffer = new byte[4096];
            try {
                for (int count; (count = input.read(buffer)) >= 0;) {
                    if (count == 0) throw new IOException("App list read made no progress");
                    if (out.size() + count > 16_777_216) throw new IOException("App list is too large");
                    out.write(buffer, 0, count);
                }
            } catch (IOException e) {
                String partial = out.toString(StandardCharsets.UTF_8.name()).replace("\r\n", "\n");
                if (!timedOut.get() || !partial.contains("\nPADCONNECT_APPS_END\n")) throw e;
                // The full name/package list is already available. Late icon
                // failures must not make app selection unusable.
                Log.w("remote apps: icon query timed out; using available icons");
            }
            String output = out.toString(StandardCharsets.UTF_8.name());
            if (timedOut.get() && !output.replace("\r\n", "\n").contains("\nPADCONNECT_APPS_END\n")) {
                throw new IOException("App list query timed out before the app list was complete");
            }
            try {
                List<RemoteApps.Entry> apps = RemoteApps.parse(output);
                Log.i("remote apps: loaded %d entries", apps.size());
                return apps;
            } catch (IOException e) {
                Log.w("remote apps: server output: %s",
                        safeLogText(output.substring(0, Math.min(output.length(), 1024))));
                throw e;
            }
        } finally {
            done.countDown();
            deadline.interrupt();
            closeQuietly(query);
        }
    }

    private AdbStream openAbstract(String scid) throws Exception {
        String name = "scrcpy_" + scid;
        long deadline = monotonicMs() + LISTENER_DEADLINE_MS;
        ConnectException last = null;
        while (monotonicMs() < deadline) {
            if (serverEof) {
                throw new IOException("server exited before opening " + name, last);
            }
            try {
                AdbStream stream = adb.openAbstract(name);
                Log.i("openAbstract %s ok", name);
                return stream;
            } catch (ConnectException e) {
                // A rejected OPEN consumed no server accept. Retry while the
                // server creates its listener; timeout failures remain fatal
                // because their acceptance state is ambiguous.
                last = e;
                Thread.sleep(LISTENER_RETRY_MS);
            }
        }
        throw new IOException("server did not open " + name + " within "
                + LISTENER_DEADLINE_MS + " ms", last);
    }

    private static String readDeviceMeta(InputStream in) throws IOException {
        byte[] probe = new byte[1];
        Wire.readFully(in, probe);
        if (probe[0] != 0) throw new IOException("invalid scrcpy probe byte");
        byte[] name = new byte[64];
        Wire.readFully(in, name);
        int n = 0;
        while (n < name.length && name[n] != 0) n++;
        for (int i = n; i < name.length; i++) {
            if (name[i] != 0) throw new IOException("invalid device-name padding");
        }
        return safeLogText(Wire.decodeUtf8(name, 0, n));
    }

    private void pump(InputStream in) {
        // Do not use BufferedReader.readLine(): a hostile target can emit an
        // unterminated line of arbitrary size and make it allocate until OOM.
        byte[] bytes = new byte[2048];
        String displayLogTail = "";
        try (InputStream source = in) {
            int n;
            while ((n = source.read(bytes)) >= 0) {
                if (n == 0) throw new IOException("server stdout made no progress");
                String raw = safeLogBytes(bytes, n);
                if (phoneDisplay != null && virtualDisplayId < 0) {
                    // Keep chunk boundaries and whitespace: the display record
                    // may be split across multiple socket reads. Bound storage.
                    displayLogTail += raw;
                    virtualDisplayId = PhoneDisplayDensity.findDisplayId(displayLogTail);
                    if (virtualDisplayId > 0) displayReady.countDown();
                    if (displayLogTail.length() > 1024) {
                        displayLogTail = displayLogTail.substring(displayLogTail.length() - 1024);
                    }
                }
                String chunk = raw.trim();
                if (!chunk.isEmpty()) Log.i("server: %s", chunk);
            }
        } catch (IOException e) {
            if (!Thread.currentThread().isInterrupted()) Log.w("server-stdout closed: %s", e);
        } finally {
            serverEof = true;
            displayReady.countDown();
            Log.i("server: stream end");
        }
    }

    public void ensurePhoneDensity() throws Exception {
        if (phoneDisplay == null) return;
        if (!displayReady.await(5, TimeUnit.SECONDS) || virtualDisplayId <= 0 || serverEof) {
            throw new IOException("Virtual display ID was not confirmed");
        }
        int actual = PhoneDisplayDensity.ensure(virtualDisplayId, phoneDisplay.dpi,
                this::readDisplayShell);
        Log.i("phone display: id=%d requestedDpi=%d effectiveDpi=%d",
                virtualDisplayId, phoneDisplay.dpi, actual);
    }

    private String readDisplayShell(String command) throws Exception {
        AdbStream stream = adb.openShell(command);
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean timedOut = new AtomicBoolean();
        Thread deadline = new Thread(() -> {
            try {
                if (!done.await(3, TimeUnit.SECONDS)) {
                    timedOut.set(true);
                    closeQuietly(stream);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "display-density-deadline");
        deadline.setDaemon(true);
        deadline.start();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            InputStream input = stream.openInputStream();
            byte[] buffer = new byte[1024];
            for (int count; (count = input.read(buffer)) >= 0;) {
                if (count == 0) throw new IOException("Display density query made no progress");
                if (out.size() + count > 16_384) throw new IOException("Display density output is too large");
                out.write(buffer, 0, count);
            }
            if (timedOut.get()) throw new IOException("Display density query timed out");
            return out.toString(StandardCharsets.UTF_8.name());
        } finally {
            done.countDown();
            deadline.interrupt();
            closeQuietly(stream);
        }
    }

    // ---- adb sync push ----

    private long push() throws Exception {
        return push(REMOTE_PATH);
    }

    private long push(String remotePath) throws Exception {
        AdbStream sync = adb.openSync();
        // sync.openInput/OutputStream() return wrapper streams whose close()
        // is a no-op; the AdbStream itself owns the channel. Close it once
        // in finally.
        try (InputStream src = ctx.getAssets().open(ASSET_JAR)) {
            int mtime = (int)(System.currentTimeMillis() / 1000L);
            return Sync.push(src, sync.openOutputStream(), sync.openInputStream(),
                    remotePath, FILE_MODE, mtime);
        } finally {
            try { sync.close(); } catch (IOException ignored) {}
        }
    }

    private static String safeLogBytes(byte[] data, int len) {
        StringBuilder out = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            int b = data[i] & 0xff;
            if (b == '\n' || b == '\r' || b == '\t') out.append(' ');
            else if (b >= 0x20 && b <= 0x7e) out.append((char) b);
            else out.append('.');
        }
        return out.toString();
    }

    private static String safeLogText(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int type = Character.getType(c);
            out.append(Character.isISOControl(c) || type == Character.FORMAT ? '?' : c);
        }
        return out.toString();
    }

    private static long monotonicMs() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
    }
}

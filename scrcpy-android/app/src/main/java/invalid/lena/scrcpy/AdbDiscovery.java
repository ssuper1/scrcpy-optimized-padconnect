package invalid.lena.scrcpy;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;

import java.net.InetAddress;
import java.net.Inet4Address;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** A bounded NSD browse. It only discovers addresses; it never opens ADB or saves rows. */
public final class AdbDiscovery implements AutoCloseable {
    static final String SERVICE_TYPE = "_adb-tls-connect._tcp.";
    static final long SCAN_MS = 8_000;

    static final class Endpoint {
        final String serviceName;
        final String host;
        final int port;

        Endpoint(String serviceName, String host, int port) {
            this.serviceName = serviceName;
            this.host = host;
            this.port = port;
        }
    }

    // All NSD state is confined to this handler, including framework callbacks.
    private final NsdManager manager;
    private final HandlerThread thread;
    private final Handler handler;
    private final Consumer<List<Endpoint>> changed;
    private final Object updates = new Object();
    private final Map<String, NsdServiceInfo> present = new LinkedHashMap<>();
    private final Map<String, List<Endpoint>> endpoints = new LinkedHashMap<>();
    private final ArrayDeque<NsdServiceInfo> pending = new ArrayDeque<>();
    private volatile List<Endpoint> snapshot = Collections.emptyList();
    private volatile boolean closed;
    private boolean browsing;
    private NsdManager.ResolveListener resolving;

    public AdbDiscovery(Context context, Consumer<List<Endpoint>> changed) {
        manager = context.getApplicationContext().getSystemService(NsdManager.class);
        this.changed = changed;
        thread = new HandlerThread("adb-discovery");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(() -> {
            if (closed) return;
            try {
                if (manager == null) throw new IllegalStateException("NSD unavailable");
                browsing = true;
                manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener);
                handler.postDelayed(this::close, SCAN_MS);
            } catch (RuntimeException e) {
                Log.w("ADB discovery unavailable: %s", e);
                close();
            }
        });
    }

    private final NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
        @Override public void onDiscoveryStarted(String type) {}
        @Override public void onServiceFound(NsdServiceInfo info) {
            handler.post(() -> {
                if (closed || !isConnectType(info.getServiceType())) return;
                String name = info.getServiceName();
                if (Devices.normalizeGuid(name) == null || present.size() >= 128
                        || present.containsKey(name)) return;
                present.put(name, info);
                pending.add(info);
                resolveNext();
            });
        }
        @Override public void onServiceLost(NsdServiceInfo info) {
            handler.post(() -> {
                if (closed) return;
                present.remove(info.getServiceName());
                endpoints.remove(info.getServiceName());
                publish();
            });
        }
        @Override public void onDiscoveryStopped(String type) { close(); }
        @Override public void onStartDiscoveryFailed(String type, int error) {
            Log.w("ADB discovery start failed: %d", error);
            close();
        }
        @Override public void onStopDiscoveryFailed(String type, int error) {
            Log.w("ADB discovery stop failed: %d", error);
        }
    };

    static boolean isConnectType(String type) {
        return "_adb-tls-connect._tcp".equals(type) || SERVICE_TYPE.equals(type);
    }

    @SuppressWarnings("deprecation") // API 31-33 require the one-shot resolver.
    private void resolveNext() {
        if (closed || resolving != null) return;
        NsdServiceInfo next;
        do {
            next = pending.poll();
            if (next == null) return;
        } while (present.get(next.getServiceName()) != next);
        NsdServiceInfo requested = next;
        NsdManager.ResolveListener callback = new NsdManager.ResolveListener() {
            @Override public void onResolveFailed(NsdServiceInfo info, int error) {
                handler.post(() -> {
                    if (closed || resolving != this) return;
                    resolving = null;
                    // Older Android versions only permit one outstanding resolver
                    // per app. A recently closed screen may still have one finishing.
                    if (error == NsdManager.FAILURE_ALREADY_ACTIVE
                            && present.get(requested.getServiceName()) == requested) {
                        pending.add(requested);
                        handler.postDelayed(AdbDiscovery.this::resolveNext, 400);
                    } else {
                        resolveNext();
                    }
                });
            }
            @Override public void onServiceResolved(NsdServiceInfo info) {
                handler.post(() -> {
                    if (closed || resolving != this) return;
                    resolving = null;
                    if (present.get(requested.getServiceName()) == requested
                            && info.getPort() > 0 && info.getPort() <= 65535) {
                        List<Endpoint> found = new ArrayList<>();
                        List<InetAddress> addresses = Build.VERSION.SDK_INT >= 34
                                ? info.getHostAddresses()
                                : info.getHost() == null ? Collections.emptyList()
                                : Collections.singletonList(info.getHost());
                        // Prefer IPv4, retaining IPv6 scope IDs for link-local addresses.
                        for (InetAddress address : addresses) {
                            Endpoint endpoint = new Endpoint(requested.getServiceName(),
                                    address.getHostAddress(), info.getPort());
                            if (address instanceof Inet4Address) found.add(0, endpoint);
                            else found.add(endpoint);
                        }
                        endpoints.put(requested.getServiceName(), found);
                        publish();
                    }
                    resolveNext();
                });
            }
        };
        resolving = callback;
        try {
            manager.resolveService(requested, callback);
        } catch (RuntimeException e) {
            resolving = null;
            Log.w("ADB discovery resolve failed: %s", e);
            resolveNext();
        }
    }

    private void publish() {
        List<Endpoint> current = new ArrayList<>();
        for (List<Endpoint> entries : endpoints.values()) current.addAll(entries);
        snapshot = Collections.unmodifiableList(current);
        synchronized (updates) { updates.notifyAll(); }
        if (changed != null) changed.accept(snapshot);
    }

    static Endpoint match(Devices.Device target, List<Endpoint> candidates) {
        if (target.guid == null) return null; // Never infer identity from a reused IP.
        Endpoint first = null;
        for (Endpoint candidate : candidates) {
            if (!target.guid.equalsIgnoreCase(candidate.serviceName)
                    || Devices.parseAddress(new Devices.Device(candidate.host, candidate.port).toString()) == null) continue;
            if (candidate.host.equals(target.host)) return candidate;
            if (first == null) first = candidate;
        }
        return first;
    }

    public Devices.Device awaitTarget(Devices.Device target) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SCAN_MS);
        synchronized (updates) {
            for (;;) {
                Endpoint endpoint = match(target, snapshot);
                if (endpoint != null) return target.at(endpoint.host, endpoint.port);
                long remaining = end - System.nanoTime();
                if (closed || remaining <= 0 || target.guid == null) return target;
                TimeUnit.NANOSECONDS.timedWait(updates, remaining);
            }
        }
    }

    @Override public void close() {
        synchronized (updates) {
            if (closed) return;
            closed = true;
            updates.notifyAll();
        }
        handler.post(() -> {
            handler.removeCallbacksAndMessages(null);
            pending.clear();
            present.clear();
            if (browsing && manager != null) {
                try { manager.stopServiceDiscovery(listener); }
                catch (RuntimeException e) { Log.w("ADB discovery stop: %s", e); }
            }
            if (Build.VERSION.SDK_INT >= 34 && resolving != null && manager != null) {
                try { manager.stopServiceResolution(resolving); }
                catch (RuntimeException e) { Log.w("ADB resolution stop: %s", e); }
            }
            resolving = null;
            thread.quitSafely();
        });
    }
}

// Modified for padconnect (2026); see the root NOTICE for changes and upstream provenance.
package invalid.lena.scrcpy;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.window.OnBackInvokedDispatcher;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.PopupMenu;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.text.Editable;
import android.text.TextWatcher;

import java.util.Locale;
import java.util.List;
import java.util.ArrayList;

// Full-screen mirror activity. Pulls target host/port from intent
// extras, starts a Sessions foreground service to keep the process
// alive during brief backgrounding, and owns the Session itself.
//
// Every build uses the same SurfaceView layout. Tests must exercise the
// renderer users receive, not a debug-only TextureView substitute.
//
// Surface lifetime is decoupled from session lifetime: when the surface
// goes away (rotation, background) we swap the session's video surface
// to null and let the wire keep draining. When the surface comes back
// we swap the new one in. Audio and control streams are unaffected.
//
// Session state vs activity lifetime: a fatal session error does NOT
// finish() the activity any more - instead the status bar transitions
// to DISCONNECTED and exposes a Reconnect button.
public final class Mirror extends Activity {

    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_PORT = "port";

    // Arbitrary request code for POST_NOTIFICATIONS - we don't react to
    // the result; the system caches the choice for next launch.
    private static final int RQ_POST_NOTIFICATIONS = 1001;

    private enum State { CONNECTING, CONNECTED, DISCONNECTED }

    private volatile Adb   adb;
    private Devices.Device target;
    private Session        session;
    private Surface        currentSurface;
    private volatile boolean destroyed;
    private long           sessionGeneration;
    private boolean        stoppingSession;
    private State          state = State.CONNECTING;
    private int            connectedW, connectedH;
    private long           connectedGeometryVersion;
    private boolean        phoneModeSession;
    private boolean        appPickerRequested;
    private boolean        appListLoading;
    private View           modeButton;
    private EdgeMenuHandle menuHandle;
    private AlertDialog    appDialog;
    private List<RemoteApps.Entry> cachedApps;

    private SurfaceView surfaceView;

    // Always present (declared in both layouts).
    private View     root;
    private View     statusBar;
    private TextView statusText;
    private Button   reconnectBtn;

    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.mirror);
        immersive();
        // Hold the source screen awake for as long as Mirror is in
        // front. Cleared automatically when the activity is destroyed.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        String host = getIntent().getStringExtra(EXTRA_HOST);
        int port = getIntent().getIntExtra(EXTRA_PORT, -1);
        if (host == null || port <= 0 || port > 65535) {
            Log.e("mirror: bad extras host=%s port=%d", host, port);
            finish();
            return;
        }
        target = resolveTarget(host, port);
        if (target == null) {
            Log.e("mirror: refusing unsaved target %s:%d", host, port);
            Toast.makeText(this, R.string.device_not_paired, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        View video = findViewById(R.id.surface);
        if (!(video instanceof SurfaceView)) {
            Log.e("mirror: layout has no SurfaceView at R.id.surface");
            finish();
            return;
        }
        surfaceView = (SurfaceView) video;
        surfaceView.getHolder().addCallback(holderCallback);

        root = findViewById(R.id.root);
        // Rotation and insets change the container without touching the
        // video surface, so re-fit from here as well.
        root.addOnLayoutChangeListener(
                (view, l, t, r, b, ol, ot, or, ob) -> {
                    applyLetterbox();
                    resizePhoneDisplay();
                });
        root.requestApplyInsets();

        statusBar    = findViewById(R.id.status_bar);
        statusText   = findViewById(R.id.status_text);
        reconnectBtn = findViewById(R.id.reconnect);
        insetStatusBar();
        reconnectBtn.setOnClickListener(view -> reconnect());
        updateStatusBar();
        modeButton = findViewById(R.id.display_menu);
        modeButton.setOnClickListener(this::showDisplayMenu);
        menuHandle = new EdgeMenuHandle((FrameLayout) root, modeButton);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::onBackRequested);
        }

        requestNotificationsIfNeeded();
        startKeepalive();

        if (!Settings.hintBackShown(this)) {
            Toast.makeText(this, R.string.hint_back, Toast.LENGTH_LONG).show();
            Settings.setHintBackShown(this, true);
        }

        new Thread(() -> {
            try {
                Adb a = Adb.getInstance(getApplicationContext());
                runOnUiThread(() -> {
                    if (destroyed) return;
                    adb = a;
                    if (session == null && currentSurface != null) {
                        startSession(currentSurface);
                    }
                });
            } catch (Exception e) {
                Log.e(e, "mirror: adb init");
                runOnUiThread(() -> {
                    if (destroyed) return;
                    Toast.makeText(this, "adb init: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                    finish();
                });
            }
        }, "adb-init").start();
    }

    // ---- surface lifecycle ----

    private final SurfaceHolder.Callback holderCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            Log.i("mirror: surface created");
            attachSurface(holder.getSurface());
        }
        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
            Log.i("mirror: surface changed %dx%d", w, h);
            applyLetterbox();
        }
        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            Log.i("mirror: surface destroyed");
            detachSurface();
        }
    };

    // ---- session driver ----

    private void attachSurface(Surface s) {
        if (session != null && currentSurface != null) session.swapSurface(null);
        currentSurface = s;
        if (session != null) {
            session.swapSurface(s);
            applyLetterbox();
            return;
        }
        if (adb == null) return; // adb-init thread will start the session
        if (stoppingSession) return; // stop worker starts the latest target
        if (state == State.DISCONNECTED) return; // wait for user to tap reconnect
        startSession(s);
        applyLetterbox();
    }

    // Size the video view to the target's aspect ratio inside the root
    // frame and centre it, so the black root shows through as letterbox
    // bars instead of the picture being stretched to the source's screen
    // shape. Also tells the session where the picture ended up, because
    // touches arrive in window coordinates and must be offset by the bars.
    //
    // No-ops until both the container and the target geometry are known;
    // every caller is a point where one of them may have just changed.
    private void applyLetterbox() {
        View v = surfaceView;
        if (v == null || root == null || session == null) return;
        int cw = root.getWidth(), ch = root.getHeight();
        int availableH = ch;
        int tw = connectedW, th = connectedH;
        if (cw <= 0 || availableH <= 0 || tw <= 0 || th <= 0) return;

        float scale = Math.min(cw / (float) tw, availableH / (float) th);
        int w = Math.min(cw, Math.max(1, Math.round(tw * scale)));
        int h = Math.min(availableH, Math.max(1, Math.round(th * scale)));
        int x = (cw - w) / 2;
        int y = (availableH - h) / 2;

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
        if (lp.width != w || lp.height != h || lp.leftMargin != x
                || lp.topMargin != y || lp.gravity != (Gravity.TOP | Gravity.START)) {
            lp.width = w;
            lp.height = h;
            lp.leftMargin = x;
            lp.topMargin = y;
            lp.gravity = Gravity.TOP | Gravity.START;
            v.setLayoutParams(lp);   // re-layout re-enters here, then converges
            Log.i("mirror: letterbox %dx%d -> %dx%d in %dx%d",
                    tw, th, w, h, cw, ch);
        }
        session.setViewport(connectedGeometryVersion, x, y, w, h);
    }

    private void detachSurface() {
        if (session != null) session.swapSurface(null);
        currentSurface = null;
    }

    private void startSession(Surface s) {
        if (destroyed) return;
        // Re-read the row so forgetting a device invalidates stale tasks and
        // notifications before they open a new session.
        Devices.Device current = target;
        Devices.Device resolved = resolveTarget(current.host, current.port);
        if (resolved == null) {
            state = State.DISCONNECTED;
            updateStatusBar();
            Toast.makeText(this, R.string.device_not_paired, Toast.LENGTH_LONG).show();
            return;
        }
        target = resolved;
        state = State.CONNECTING;
        updateStatusBar();
        long generation = ++sessionGeneration;
        phoneModeSession = Settings.phoneMode(this);
        appPickerRequested = false;
        PhoneDisplay phone = phoneModeSession ? phoneDisplayForWindow() : null;
        session = new Session(this, adb, target, s, phone, new Session.Listener() {
            @Override public void onConnected(long geometryVersion, int w, int h) {
                runOnUiThread(() -> {
                    if (destroyed || generation != sessionGeneration) return;
                    state = State.CONNECTED;
                    connectedGeometryVersion = geometryVersion;
                    connectedW = w; connectedH = h;
                    updateStatusBar();
                    applyLetterbox();
                    if (session != null) session.syncClipboard();
                    resizePhoneDisplay();
                    if (phoneModeSession && !appPickerRequested) {
                        appPickerRequested = true;
                        showAppPicker();
                    }
                });
            }
            @Override public void onReconnecting() {
                Log.i("mirror: link lost, reconnecting");
                runOnUiThread(() -> {
                    if (destroyed || generation != sessionGeneration) return;
                    state = State.CONNECTING;
                    updateStatusBar();
                });
            }
            @Override public void onError(Throwable t) {
                runOnUiThread(() -> {
                    if (destroyed || generation != sessionGeneration) return;
                    Toast.makeText(Mirror.this, describe(t), Toast.LENGTH_LONG).show();
                });
            }
            @Override public void onDisplayScaleError() {
                runOnUiThread(() -> {
                    if (!destroyed && generation == sessionGeneration) {
                        Toast.makeText(Mirror.this, R.string.phone_scale_unverified,
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
            @Override public void onStopped() {
                Log.i("mirror: session stopped");
                runOnUiThread(() -> {
                    if (destroyed || generation != sessionGeneration) return;
                    state = State.DISCONNECTED;
                    updateStatusBar();
                });
            }
        });
        session.start();
    }

    private void reconnect() {
        if (appDialog != null) appDialog.dismiss();
        Log.i("mirror: reconnect tapped");
        state = State.CONNECTING;
        updateStatusBar();
        if (stoppingSession) return;
        stoppingSession = true;
        Session old = session;
        session = null;
        sessionGeneration++;
        connectedW = connectedH = 0;
        connectedGeometryVersion = 0;
        // Stop the old session off the UI thread (teardown closes
        // sockets and joins the server's log pump), THEN start the new
        // one. Sequencing matters: both sessions share the singleton
        // Adb, so the old teardown's disconnect must finish before the
        // new bring-up connects.
        new Thread(() -> {
            boolean stopped = old == null || old.stop();
            runOnUiThread(() -> {
                if (destroyed) return;
                stoppingSession = false;
                if (!stopped) {
                    state = State.DISCONNECTED;
                    updateStatusBar();
                    Toast.makeText(this, R.string.session_stop_timeout,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                if (session != null) return;  // another path already started one
                if (adb == null || currentSurface == null) return;
                startSession(currentSurface);
            });
        }, "session-stop").start();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String host = intent.getStringExtra(EXTRA_HOST);
        int port = intent.getIntExtra(EXTRA_PORT, -1);
        if (host == null || port <= 0 || port > 65535) {
            Log.w("mirror: ignoring bad replacement target host=%s port=%d", host, port);
            return;
        }
        if (target.host.equals(host) && target.port == port) return;

        Devices.Device replacement = resolveTarget(host, port);
        if (replacement == null) {
            Log.w("mirror: refusing unsaved replacement target %s:%d", host, port);
            Toast.makeText(this, R.string.device_not_paired, Toast.LENGTH_LONG).show();
            return;
        }
        setIntent(intent);
        target = replacement;
        cachedApps = null;
        connectedW = connectedH = 0;
        connectedGeometryVersion = 0;
        startKeepalive();   // repoint the notification at the new target
        reconnect();
    }

    private void updateStatusBar() {
        if (statusText == null) return;
        String s;
        switch (state) {
            case CONNECTED:
                s = String.format(Locale.ROOT, "%s:%d  %dx%d  %s",
                        target.host, target.port, connectedW, connectedH,
                        Settings.videoCodec(this));
                break;
            case DISCONNECTED:
                s = String.format(Locale.ROOT, "%s:%d  %s",
                        target.host, target.port, getString(R.string.disconnected));
                break;
            default:
                s = String.format(Locale.ROOT, "%s:%d  %s",
                        target.host, target.port, getString(R.string.connecting));
        }
        statusText.setText(s);
        reconnectBtn.setVisibility(state == State.DISCONNECTED ? View.VISIBLE : View.GONE);

        // Status is only needed during bring-up and after a terminal error.
        // Target navigation remains inside the mirrored frame.
        if (statusBar != null) {
            statusBar.setVisibility(state == State.CONNECTED ? View.GONE : View.VISIBLE);
        }
        if (modeButton != null) {
            // Keep the recovery menu accessible even if virtual-display creation fails.
            modeButton.setVisibility(View.VISIBLE);
        }
    }

    private PhoneDisplay phoneDisplayForWindow() {
        android.graphics.Rect bounds = getWindowManager().getCurrentWindowMetrics().getBounds();
        int width = root.getWidth() > 0 ? root.getWidth() : bounds.width();
        int height = root.getHeight() > 0 ? root.getHeight() : bounds.height();
        return PhoneDisplay.fit(Math.max(128, width),
                Math.max(128, height),
                Settings.phoneTextScale(this));
    }

    private void resizePhoneDisplay() {
        if (session != null && phoneModeSession) {
            session.resizePhoneDisplay(phoneDisplayForWindow());
        }
    }

    private void showDisplayMenu(View anchor) {
        menuHandle.wake();
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 6, 0, R.string.remote_back)
                .setEnabled(state == State.CONNECTED);
        menu.getMenu().add(0, 3, 1, phoneModeSession ? R.string.remote_home : R.string.open_remote_app)
                .setEnabled(state == State.CONNECTED);
        menu.getMenu().add(0, 7, 2, R.string.rotate_screen);
        menu.getMenu().add(0, 1, 3, R.string.mode_phone)
                .setCheckable(true).setChecked(Settings.phoneMode(this));
        menu.getMenu().add(0, 2, 4, R.string.mode_original)
                .setCheckable(true).setChecked(!Settings.phoneMode(this));
        menu.getMenu().add(0, 4, 5, R.string.phone_text_size);
        menu.getMenu().add(0, 5, 6, R.string.leave_mirror);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: case 2:
                    boolean enabled = item.getItemId() == 1;
                    if (Settings.phoneMode(this) != enabled) {
                        Settings.setPhoneMode(this, enabled);
                        reconnect();
                    }
                    return true;
                case 3: showAppPicker(); return true;
                case 6:
                    if (session != null && state == State.CONNECTED) session.onBack();
                    return true;
                case 7:
                    rotateScreen();
                    return true;
                case 4:
                    int[] textScales = {75, 85, 100, 125, 150};
                    int selected = 2;
                    for (int index = 0; index < textScales.length; index++) {
                        if (textScales[index] == Settings.phoneTextScale(this)) selected = index;
                    }
                    showImmersiveDialog(new AlertDialog.Builder(this).setTitle(R.string.phone_text_size)
                            .setSingleChoiceItems(R.array.phone_text_sizes, selected, (dialog, which) -> {
                                int value = textScales[which];
                                dialog.dismiss();
                                if (!phoneModeSession) {
                                    showImmersiveDialog(new AlertDialog.Builder(this)
                                            .setTitle(R.string.phone_text_size)
                                            .setMessage(R.string.phone_scale_requires_phone_mode)
                                            .setPositiveButton(R.string.use_phone_mode, (confirm, button) -> {
                                                Settings.setPhoneMode(this, true);
                                                Settings.setPhoneTextScale(this, value);
                                                reconnect();
                                            })
                                            .setNegativeButton(android.R.string.cancel, null).create());
                                } else {
                                    Settings.setPhoneTextScale(this, value);
                                    // Selecting the current size also retries
                                    // its application after a failed setup.
                                    reconnect();
                                }
                            }).setNegativeButton(android.R.string.cancel, null).create());
                    return true;
                case 5: finish(); return true;
                default: return false;
            }
        });
        menu.setOnDismissListener(ignored -> {
            if (!destroyed) {
                ui.post(this::immersive);
                menuHandle.fadeLater();
            }
        });
        menu.show();
    }

    private void rotateScreen() {
        // Override this activity's orientation even when system auto-rotate
        // is off. Its existing layout/inset callbacks resize the phone display
        // and update the touch viewport without reconnecting the session.
        boolean landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        setRequestedOrientation(landscape ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        Log.i("mirror: requested %s orientation", landscape ? "portrait" : "landscape");
    }

    private void showAppPicker() {
        if (state != State.CONNECTED || session == null || appListLoading || destroyed) return;
        if (appDialog != null && appDialog.isShowing()) return;
        if (cachedApps != null) {
            showAppList(cachedApps);
            return;
        }
        appListLoading = true;
        long generation = sessionGeneration;
        Session current = session;
        AlertDialog loading = new AlertDialog.Builder(this).setMessage(R.string.loading_apps)
                .setNegativeButton(android.R.string.cancel, null).create();
        appDialog = loading;
        showImmersiveDialog(loading);
        new Thread(() -> {
            try {
                List<RemoteApps.Entry> apps = current.queryApps();
                runOnUiThread(() -> {
                    appListLoading = false;
                    boolean show = loading.isShowing();
                    loading.dismiss();
                    if (destroyed || generation != sessionGeneration || state != State.CONNECTED) return;
                    cachedApps = apps;
                    if (show) showAppList(apps);
                });
            } catch (Exception e) {
                Log.e(e, "remote apps: query failed");
                runOnUiThread(() -> {
                    appListLoading = false;
                    loading.dismiss();
                    if (!destroyed && generation == sessionGeneration) {
                        showImmersiveDialog(new AlertDialog.Builder(this).setTitle(R.string.open_remote_app)
                                .setMessage(getString(R.string.app_list_failed, e.getMessage()))
                                .setPositiveButton(android.R.string.ok, null).create());
                    }
                });
            }
        }, "remote-apps").start();
    }

    private void showAppList(List<RemoteApps.Entry> apps) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, 0, padding, 0);
        TextView note = new TextView(this);
        note.setText(R.string.phone_app_help);
        content.addView(note);
        EditText search = new EditText(this);
        search.setHint(R.string.search_apps);
        search.setSingleLine(true);
        content.addView(search);
        ListView list = new ListView(this);
        int height = getWindowManager().getCurrentWindowMetrics().getBounds().height() / 2;
        content.addView(list, new LinearLayout.LayoutParams(-1, height));
        ArrayAdapter<RemoteApps.Entry> adapter = new ArrayAdapter<RemoteApps.Entry>(this,
                android.R.layout.simple_list_item_2, android.R.id.text1, new ArrayList<>(apps)) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                View row = super.getView(position, convertView, parent);
                RemoteApps.Entry app = getItem(position);
                ((TextView) row.findViewById(android.R.id.text1)).setText(app.label);
                ((TextView) row.findViewById(android.R.id.text2)).setText(app.packageName);
                return row;
            }
        };
        adapter.setNotifyOnChange(false);
        list.setAdapter(adapter);
        AlertDialog picker = new AlertDialog.Builder(this).setTitle(R.string.open_remote_app)
                .setView(content).setNegativeButton(android.R.string.cancel, null).create();
        appDialog = picker;
        long generation = sessionGeneration;
        list.setOnItemClickListener((parent, view, position, id) -> {
            RemoteApps.Entry app = adapter.getItem(position);
            if (app != null && generation == sessionGeneration && session != null
                    && state == State.CONNECTED && session.startApp(app.packageName)) {
                Settings.setPhoneLastApp(this, app.packageName);
                Toast.makeText(this, getString(R.string.opening_app, app.label), Toast.LENGTH_SHORT).show();
            }
            picker.dismiss();
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                List<RemoteApps.Entry> matches = RemoteApps.filter(apps, s.toString());
                adapter.clear();
                adapter.addAll(matches);
                adapter.notifyDataSetChanged();
            }
            @Override public void afterTextChanged(Editable text) {}
        });
        showImmersiveDialog(picker);
    }

    private void showImmersiveDialog(AlertDialog dialog) {
        dialog.setOnDismissListener(ignored -> {
            if (!destroyed) ui.post(this::immersive);
        });
        dialog.show();
        hideSystemBars(dialog.getWindow());
    }

    // ---- input ----

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (session != null) {
            session.onTouch(ev);
            return true;
        }
        return super.onTouchEvent(ev);
    }

    // Back goes to the target; Back twice in quick succession leaves the
    // mirror.
    //
    // It used to be long-press-Back to reach the target and short-press
    // to do nothing. That stopped working twice over: gesture navigation
    // has no Back key to hold, and at targetSdk 35 and later predictive back is on
    // by default, so the framework routes Back through
    // OnBackInvokedDispatcher and onKeyDown/onKeyLongPress are never
    // called for it at all. The advertised feature was unreachable on
    // every current device.
    private static final long DOUBLE_BACK_MS = 600L;
    private long lastBackAtMs;

    private void onBackRequested() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastBackAtMs < DOUBLE_BACK_MS) {
            finish();
            return;
        }
        lastBackAtMs = now;
        if (session != null) session.onBack();
    }

    // Pre-33 devices (minSdk is 31) still deliver Back this way. API 33+
    // uses the OnBackInvokedDispatcher callback registered in onCreate;
    // lint does not follow that version split.
    @Override
    @SuppressLint("GestureBackNavigation")
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        onBackRequested();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        if (session != null && shouldForward(ev)) {
            session.onKey(ev);
            return true;
        }
        return super.dispatchKeyEvent(ev);
    }

    // ---- lifecycle ----

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (menuHandle != null) menuHandle.dispose();
        if (appDialog != null) appDialog.dismiss();
        sessionGeneration++;
        ui.removeCallbacksAndMessages(null);
        currentSurface = null;
        Session s = session;
        session = null;
        if (s != null) {
            // Teardown blocks on socket closes and a thread join; keep
            // it off the UI thread.
            new Thread(() -> s.stop(), "session-stop").start();
        }
        stopService(new Intent(this, Sessions.class));
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        immersive();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && !destroyed) {
            immersive();
            if (session != null) session.syncClipboard();
        }
    }

    // Android 13+ requires runtime grant for POST_NOTIFICATIONS. The
    // foreground service still starts without the grant, but granting it
    // keeps the active session visible in the notification drawer.
    private void requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) return;
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                RQ_POST_NOTIFICATIONS);
    }

    // Most of what reaches here has a null message - a bare IOException
    // from the socket, an SSLHandshakeException - and "session error:
    // null" is what the user was being shown for the commonest failure
    // there is.
    private String describe(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            String m = c.getMessage();
            if (m != null && !m.isEmpty()) return getString(R.string.session_error, m);
        }
        return getString(R.string.session_error,
                t == null ? "unknown" : t.getClass().getSimpleName());
    }

    // Missing rows fail closed. A stale notification, restored task, or
    // malformed internal intent must not create a session for an unsaved row.
    private Devices.Device resolveTarget(String host, int port) {
        try {
            return Devices.find(this, host, port);
        } catch (java.io.IOException e) {
            Log.e(e, "mirror: cannot read paired devices");
            return null;
        }
    }

    // The foreground service exists only to keep this process alive while
    // mirroring. It carries the target so its notification can lead back
    // here rather than somewhere that would tear the session down.
    private void startKeepalive() {
        Intent i = new Intent(this, Sessions.class);
        i.putExtra(EXTRA_HOST, target.host);
        i.putExtra(EXTRA_PORT, target.port);
        startForegroundService(i);
    }

    @SuppressWarnings("deprecation")
    private void immersive() {
        // Without this the window stops at the cutout's safe area and the
        // system letterboxes it, which shows up as black bands down the
        // sides of what is supposed to be a full-screen mirror. Only the
        // overlay controls are moved around a display cutout.
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        getWindow().setAttributes(lp);

        getWindow().setDecorFitsSystemWindows(false);
        hideSystemBars(getWindow());
    }

    private static void hideSystemBars(Window window) {
        if (window == null) return;
        window.setNavigationBarContrastEnforced(false);
        WindowInsetsController c = window.getInsetsController();
        if (c != null) {
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            c.hide(WindowInsets.Type.systemBars());
        }
    }

    private void insetStatusBar() {
        int base = getResources().getDimensionPixelSize(R.dimen.space_sm);
        statusBar.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            Insets cutout = windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.displayCutout());
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) view.getLayoutParams();
            int left = base + cutout.left;
            int top = base + cutout.top;
            int right = base + cutout.right;
            if (lp.leftMargin != left || lp.topMargin != top
                    || lp.rightMargin != right || lp.bottomMargin != base) {
                lp.setMargins(left, top, right, base);
                view.setLayoutParams(lp);
            }
            return windowInsets;
        });
        statusBar.requestApplyInsets();
    }

    private static boolean shouldForward(KeyEvent ev) {
        int code = ev.getKeyCode();
        if (code >= KeyEvent.KEYCODE_DPAD_UP && code <= KeyEvent.KEYCODE_DPAD_CENTER) return true;
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) return true;
        if (code >= KeyEvent.KEYCODE_A && code <= KeyEvent.KEYCODE_Z) return true;
        switch (code) {
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DEL:
            case KeyEvent.KEYCODE_FORWARD_DEL:
            case KeyEvent.KEYCODE_TAB:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_MOVE_HOME:
            case KeyEvent.KEYCODE_MOVE_END:
            case KeyEvent.KEYCODE_INSERT:
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT:
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT:
            case KeyEvent.KEYCODE_META_LEFT:
            case KeyEvent.KEYCODE_META_RIGHT:
            case KeyEvent.KEYCODE_CAPS_LOCK:
            case KeyEvent.KEYCODE_NUM_LOCK:
            case KeyEvent.KEYCODE_SCROLL_LOCK:
            case KeyEvent.KEYCODE_COMMA:
            case KeyEvent.KEYCODE_PERIOD:
            case KeyEvent.KEYCODE_SLASH:
            case KeyEvent.KEYCODE_BACKSLASH:
            case KeyEvent.KEYCODE_SEMICOLON:
            case KeyEvent.KEYCODE_APOSTROPHE:
            case KeyEvent.KEYCODE_GRAVE:
            case KeyEvent.KEYCODE_LEFT_BRACKET:
            case KeyEvent.KEYCODE_RIGHT_BRACKET:
            case KeyEvent.KEYCODE_MINUS:
            case KeyEvent.KEYCODE_EQUALS:
                return true;
            default:
                return false;
        }
    }
}

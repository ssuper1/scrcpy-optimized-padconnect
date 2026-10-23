package invalid.lena.scrcpy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.List;

// Pairing form + saved-device list.
//
// Layout: device address ("ip:port"), pair-port, pair-code + a single
// "Pair and save" button. The address is the connect endpoint printed on
// the target's Wireless debugging screen; pairing happens on a different,
// short-lived port from the pairing dialog, against the same host. After a
// successful pair() against the daemon, the row is appended to devices.json
// with the *connect* port. Tapping a saved row launches the Mirror activity.
public final class Main extends Activity {

    private volatile Adb adb;
    private LinearLayout deviceList;
    private TextView     devicesEmpty;
    private Button       pairButton;
    private Button       discoverButton;
    private TextView     discoveryStatus;
    private AdbDiscovery discovery;
    private int discoveryGeneration;
    private boolean resumed;
    private List<AdbDiscovery.Endpoint> discovered = java.util.Collections.emptyList();
    private final android.os.Handler discoveryUi = new android.os.Handler(android.os.Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.main);

        // The IME inset is in the mask too: the window is edge-to-edge, so
        // nothing resizes it when the keyboard opens. The page is one
        // ScrollView, so padding the root keeps the form reachable behind
        // the keyboard.
        Ui.padForInsets(findViewById(R.id.root),
                WindowInsets.Type.systemBars() | WindowInsets.Type.ime());

        EditText address     = findViewById(R.id.device_address);
        EditText pairPort    = findViewById(R.id.pair_port);
        EditText pairCode    = findViewById(R.id.pair_code);
        pairButton           = findViewById(R.id.pair);
        View settingsBtn     = findViewById(R.id.settings);
        deviceList  = findViewById(R.id.devices);
        devicesEmpty = findViewById(R.id.devices_empty);
        discoverButton = findViewById(R.id.discover_devices);
        discoveryStatus = findViewById(R.id.discovery_status);
        discoverButton.setOnClickListener(v -> startDiscovery());

        settingsBtn.setOnClickListener(v -> startActivity(
                new Intent(this, SettingsActivity.class)));

        try {
            showDevices(Devices.load(this));
        } catch (IOException e) {
            Log.e(e, "devices: load failed");
            showDevices(java.util.Collections.emptyList());
            Toast.makeText(this, R.string.device_list_unreadable, Toast.LENGTH_LONG).show();
        }

        // RSA keygen on first launch can take 1-3 s; never on the UI thread.
        pairButton.setEnabled(false);
        new Thread(() -> {
            try {
                Adb a = Adb.getInstance(getApplicationContext());
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    adb = a;
                    pairButton.setEnabled(true);
                });
            } catch (Exception e) {
                Log.e(e, "adb init failed");
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(this, "adb init failed: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                    finish();
                });
            }
        }, "adb-init").start();

        pairButton.setOnClickListener(v -> {
            if (adb == null) return; // still initialising
            // The saved endpoint is the address field verbatim; pairing
            // reuses its host with the pairing port.
            Devices.Device target = Devices.parseAddress(address.getText().toString());
            if (target == null) {
                Toast.makeText(this, R.string.bad_address, Toast.LENGTH_LONG).show();
                return;
            }
            int pairP = Devices.parsePort(pairPort.getText().toString());
            if (pairP < 0) {
                Toast.makeText(this, R.string.bad_pair_port, Toast.LENGTH_LONG).show();
                return;
            }
            String pc = pairCode.getText().toString().trim();
            if (TextUtils.isEmpty(pc) || !pc.matches("[0-9]{6}")) {
                Toast.makeText(this, R.string.bad_pair_code, Toast.LENGTH_LONG).show();
                return;
            }
            pairButton.setEnabled(false);
            Toast.makeText(this, R.string.pairing, Toast.LENGTH_SHORT).show();
            new Thread(() -> pairAndSave(target, pairP, pc, pairButton), "pair").start();
        });
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        startDiscovery();
    }

    @Override protected void onPause() {
        resumed = false;
        stopDiscovery();
        super.onPause();
    }

    private void stopDiscovery() {
        discoveryGeneration++;
        discoveryUi.removeCallbacksAndMessages(null);
        if (discovery != null) discovery.close();
        discovery = null;
    }

    private void startDiscovery() {
        stopDiscovery();
        discovered = java.util.Collections.emptyList();
        reloadDevices();
        int generation = discoveryGeneration;
        discoverButton.setEnabled(false);
        discoveryStatus.setText(R.string.discovery_searching);
        discovery = new AdbDiscovery(this, endpoints -> runOnUiThread(() -> {
            if (generation != discoveryGeneration || isFinishing() || isDestroyed()) return;
            discovered = endpoints;
            reloadDevices();
        }));
        discoveryUi.postDelayed(() -> {
            if (generation != discoveryGeneration) return;
            discoverButton.setEnabled(true);
            boolean found = false;
            try {
                for (Devices.Device device : Devices.load(this)) {
                    if (AdbDiscovery.match(device, discovered) != null) { found = true; break; }
                }
            } catch (IOException e) { Log.w("device discovery list read: %s", e); }
            discoveryStatus.setText(found ? R.string.discovery_ready : R.string.discovery_not_found);
        }, AdbDiscovery.SCAN_MS);
    }

    private void reloadDevices() {
        try { showDevices(Devices.load(this)); }
        catch (IOException e) {
            Log.w("devices refresh failed: %s", e);
            discoveryStatus.setText(R.string.device_list_unreadable);
        }
    }

    // Rebuild the saved-device rows. There is no adapter: the whole page
    // is one ScrollView, a ListView cannot measure itself inside one, and
    // a handful of rows does not need recycling.
    private void showDevices(List<Devices.Device> devices) {
        deviceList.removeAllViews();
        devicesEmpty.setVisibility(devices.isEmpty() ? View.VISIBLE : View.GONE);
        for (Devices.Device d : devices) {
            View row = getLayoutInflater().inflate(R.layout.device_row, deviceList, false);
            AdbDiscovery.Endpoint endpoint = AdbDiscovery.match(d, discovered);
            String label = endpoint != null
                    ? getString(R.string.device_discovered, d.at(endpoint.host, endpoint.port).toString())
                    : d.toString() + "\n" + getString(d.guid == null
                    ? R.string.device_identity_pending : R.string.device_saved_address);
            ((TextView) row.findViewById(R.id.device_label)).setText(label);
            row.setOnClickListener(v -> {
                Log.i("connect tap: %s", d);
                Intent i = new Intent(this, Mirror.class);
                i.putExtra(Mirror.EXTRA_HOST, d.host);
                i.putExtra(Mirror.EXTRA_PORT, d.port);
                i.putExtra(Mirror.EXTRA_DEVICE_ID, d.id);
                startActivity(i);
            });
            row.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this).setTitle(d.toString())
                        .setItems(new String[]{getString(R.string.edit_device_address),
                                getString(R.string.forget_device_action)}, (dialog, which) -> {
                            if (which == 0) editAddress(d);
                            else confirmForget(d);
                        }).show();
                return true;
            });
            deviceList.addView(row);
        }
    }

    private void editAddress(Devices.Device device) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(true);
        input.setText(device.toString());
        input.setSelectAllOnFocus(true);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.edit_device_address).setMessage(R.string.edit_device_address_help)
                .setView(input).setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    Devices.Device address = Devices.parseAddress(input.getText().toString());
                    if (address == null) { input.setError(getString(R.string.bad_address)); return; }
                    try {
                        Devices.replace(this, device, device.at(address.host, address.port));
                        dialog.dismiss();
                        reloadDevices();
                        Toast.makeText(this, R.string.device_address_saved, Toast.LENGTH_SHORT).show();
                    } catch (IOException e) {
                        input.setError(getString(R.string.device_save_failed));
                    }
                }));
        dialog.show();
    }

    private void confirmForget(Devices.Device d) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.forget_device)
                .setMessage(d.toString())
                .setPositiveButton(android.R.string.ok, (dlg, w) -> {
                    Log.i("forget device: %s", d);
                    try {
                        showDevices(Devices.remove(this, d));
                    } catch (Exception e) {
                        Log.e(e, "forget device: save failed");
                        Toast.makeText(this, "could not save device list",
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void pairAndSave(Devices.Device target, int pairPort, String code, Button btn) {
        try {
            Log.i("pair: %s:%d", target.host, pairPort);
            String guid = adb.pairDevice(target.host, pairPort, code);
            Log.i("pair ok host=%s pair_port=%d", target.host, pairPort);
            List<Devices.Device> updated = Devices.upsert(getApplicationContext(), target.withGuid(guid));
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                showDevices(updated);
                Toast.makeText(this, "paired and saved", Toast.LENGTH_SHORT).show();
                btn.setEnabled(true);
                if (resumed) startDiscovery();
            });
        } catch (Exception e) {
            Log.e(e, "pair failed");
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(this, "pair failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                btn.setEnabled(true);
            });
        }
    }
}

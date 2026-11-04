package app.ktmnav.backend;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * Reconnects automatically to the saved motorcycle on boot, Bluetooth toggle, or device ACL connect.
 * For Gen3 BLE bikes, arms offloaded zero-power filtered detection (or starts a scan-gated reconnect);
 * a Classic ACL from the saved Gen3 bike connects over GATT after the dash settle delay, because a dash
 * that is already connected over Classic for audio may stop advertising. Gen2 bikes connect on ACL.
 */
public class AutoConnectReceiver extends BroadcastReceiver {
    private static final String TAG = "KTMnav.AutoConnect";
    private static final String PREFS_NAME = "ktmnav";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean autoConnect = prefs.getBoolean("auto_connect", true);
        if (!autoConnect) {
            AppLog.d(TAG, intent.getAction() + " received but auto_connect is disabled - ignoring");
            return;
        }

        boolean desiredConnect = prefs.getBoolean("desired_connect", false);
        if (!desiredConnect) {
            AppLog.d(TAG, intent.getAction() + " received but explicit desired connection is canceled - ignoring");
            return;
        }

        boolean keepBackground = prefs.getBoolean("keep_background", true);
        if (!keepBackground) {
            AppLog.d(TAG, intent.getAction() + " received but keep_background is false - ignoring");
            return;
        }

        Bridge bridge = Bridge.get(context);
        Bridge.Bike activeBike = bridge.getActiveBike();
        if (activeBike == null || activeBike.address == null || activeBike.address.trim().isEmpty()) {
            AppLog.d(TAG, intent.getAction() + " received but no motorcycle paired - ignoring");
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                AppLog.w(TAG, intent.getAction() + " received but BLUETOOTH_CONNECT not granted - ignoring");
                return;
            }
        }

        String action = intent.getAction();
        switch (action) {
            case BluetoothAdapter.ACTION_STATE_CHANGED:
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_ON) {
                    if (bridge.onBluetoothStateChanged(true)) {
                        AppLog.i(TAG, "Bluetooth turned on - resuming the active link search");
                    } else {
                        armOrConnect(context, bridge, activeBike, "Bluetooth turned on");
                    }
                } else if (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF) {
                    bridge.onBluetoothStateChanged(false);
                }
                break;

            case BluetoothDevice.ACTION_ACL_CONNECTED:
                BluetoothDevice device = getBluetoothDeviceExtra(intent);
                if (device != null && activeBike.address.equalsIgnoreCase(device.getAddress())) {
                    if (BikeGeneration.from(activeBike.generation) == BikeGeneration.GEN2) {
                        connect(bridge, activeBike, "bike ACL connected");
                    } else {
                        AppLog.i(TAG, "Saved Gen 3 bike's Classic ACL connected - connecting over GATT after the dash settle delay");
                        try {
                            bridge.onBikeAclConnected();
                        } catch (IllegalStateException | SecurityException e) {
                            AppLog.w(TAG, "Auto-connect rejected by the system", e);
                        }
                    }
                }
                break;

            case Intent.ACTION_BOOT_COMPLETED:
                armOrConnect(context, bridge, activeBike, "boot completed");
                break;
        }
    }

    private static void armOrConnect(Context context, Bridge bridge, Bridge.Bike activeBike, String triggerReason) {
        if (BikeGeneration.from(activeBike.generation) == BikeGeneration.GEN2) {
            AppLog.i(TAG, triggerReason + " - Gen2 bike, waiting for its ACL_CONNECTED instead");
        } else if (BikeDetector.arm(context)) {
            AppLog.i(TAG, triggerReason + " - armed zero-power detection for " + LogRedact.mac(activeBike.address));
        } else {
            AppLog.i(TAG, triggerReason + " - detector unavailable, starting a scan-gated reconnect");
            try {
                bridge.connectInBackground();
            } catch (IllegalStateException | SecurityException e) {
                AppLog.w(TAG, "Auto-connect rejected by the system", e);
            }
        }
    }

    private static void connect(Bridge bridge, Bridge.Bike activeBike, String triggerReason) {
        AppLog.i(TAG, triggerReason + " - starting connection to " + LogRedact.mac(activeBike.address));
        try {
            bridge.connect();
        } catch (IllegalStateException | SecurityException e) {
            AppLog.w(TAG, "Auto-connect rejected by the system", e);
        }
    }

    private static BluetoothDevice getBluetoothDeviceExtra(Intent intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
            } else {
                return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            }
        } catch (Exception e) {
            AppLog.w(TAG, "Error retrieving BluetoothDevice extra", e);
            return null;
        }
    }
}

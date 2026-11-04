package app.ktmnav.backend;

import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import java.util.ArrayList;
import java.util.List;

/**
 * BroadcastReceiver triggered by Android BluetoothLeScanner PendingIntent when a saved Gen3 bike is seen.
 * Matches original TurnIt BikeDetectedReceiver implementation.
 */
public class BikeDetectedReceiver extends BroadcastReceiver {
    private static final String TAG = "KTMnav.Detector";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }

        if (intent.hasExtra(BluetoothLeScanner.EXTRA_ERROR_CODE)) {
            int errorCode = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, -1);
            AppLog.w(TAG, "Offloaded scan failed with error " + errorCode + " - waiting for the next arm trigger");
            return;
        }

        BikeDetector.disarm(context);

        Bridge bridge = Bridge.get(context);
        if (bridge.isConnected() || "Authenticating".equalsIgnoreCase(bridge.getState())) {
            AppLog.d(TAG, "Detection fired but already connected or authenticating - ignoring");
            return;
        }
        if (!bridge.getPrefs().getBoolean("keep_background", true)
                || !bridge.getPrefs().getBoolean("auto_connect", true)
                || !bridge.getPrefs().getBoolean("desired_connect", false)) {
            AppLog.d(TAG, "Detection fired but background auto-connect is disabled or desired connection canceled - ignoring");
            return;
        }

        Bridge.Bike activeBike = bridge.getActiveBike();
        if (activeBike == null || activeBike.address == null || activeBike.address.trim().isEmpty()) {
            AppLog.d(TAG, "Detection fired but no bike paired - ignoring");
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                AppLog.w(TAG, "Detection fired but BLUETOOTH_CONNECT not granted - ignoring");
                return;
            }
        }

        if (!matchesSavedAddress(intent, activeBike.address)) {
            AppLog.w(TAG, "Detection fired for a different device - re-arming");
            BikeDetector.arm(context);
            return;
        }

        AppLog.i(TAG, "Bike detected by offloaded scan - connecting to " + LogRedact.mac(activeBike.address) + " after the dash settle delay");
        try {
            bridge.connectAfterDetection();
        } catch (IllegalStateException | SecurityException e) {
            AppLog.w(TAG, "Detector connect rejected by the system", e);
        }
    }

    private static boolean matchesSavedAddress(Intent intent, String targetAddress) {
        if (targetAddress == null) {
            return false;
        }
        String cleanTarget = targetAddress.trim();
        try {
            List<ScanResult> results = intent.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT);
            if (results != null && !results.isEmpty()) {
                for (ScanResult result : results) {
                    if (result != null && result.getDevice() != null
                            && cleanTarget.equalsIgnoreCase(result.getDevice().getAddress())) {
                        return true;
                    }
                }
            }
            ScanResult single = intent.getParcelableExtra("android.bluetooth.le.extra.SCAN_RESULT");
            if (single != null && single.getDevice() != null
                    && cleanTarget.equalsIgnoreCase(single.getDevice().getAddress())) {
                return true;
            }
        } catch (Exception e) {
            AppLog.w(TAG, "Exception parsing ScanResult from intent", e);
        }
        return false;
    }
}

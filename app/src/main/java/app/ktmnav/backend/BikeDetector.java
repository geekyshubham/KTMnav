package app.ktmnav.backend;

import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages zero-power offloaded BLE scanning for Gen3 BCCU motorcycles via PendingIntent.
 * Matches original TurnIt BikeDetector implementation.
 */
public final class BikeDetector {
    private static final String TAG = "KTMnav.Detector";
    private static final int REQUEST_CODE = 0;
    private static volatile boolean isArmed = false;

    private BikeDetector() {}

    public static synchronized boolean arm(Context context) {
        if (context == null) {
            isArmed = false;
            return false;
        }
        Context applicationContext = context.getApplicationContext();
        Bridge bridge = Bridge.get(applicationContext);
        Bridge.Bike activeBike = bridge.getActiveBike();
        if (activeBike == null || activeBike.address == null || activeBike.address.trim().isEmpty()) {
            AppLog.d(TAG, "Not arming - no bike paired");
            isArmed = false;
            return false;
        }

        if (BikeGeneration.from(activeBike.generation) == BikeGeneration.GEN2) {
            AppLog.d(TAG, "Not arming - " + LogRedact.mac(activeBike.address) + " is a classic-only Gen2 dash");
            isArmed = false;
            return false;
        }

        if (!bridge.getPrefs().getBoolean("keep_background", true)
                || !bridge.getPrefs().getBoolean("auto_connect", true)
                || !bridge.getPrefs().getBoolean("desired_connect", false)) {
            AppLog.d(TAG, "Not arming - background sync / auto-connect is disabled or desired connection canceled");
            isArmed = false;
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                if (applicationContext.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                    AppLog.w(TAG, "Not arming - BLUETOOTH_SCAN not granted");
                    isArmed = false;
                    return false;
                }
            } catch (SecurityException e) {
                AppLog.w(TAG, "Not arming - BLUETOOTH_SCAN permission revoked", e);
                isArmed = false;
                return false;
            }
        }

        BluetoothAdapter bluetoothAdapter = getAdapter(applicationContext);
        BluetoothLeScanner scanner = null;
        try {
            scanner = (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) ? null : bluetoothAdapter.getBluetoothLeScanner();
        } catch (SecurityException e) {
            AppLog.w(TAG, "Permission revoked accessing BluetoothLeScanner", e);
            isArmed = false;
            return false;
        }

        if (scanner == null) {
            AppLog.d(TAG, "Not arming - Bluetooth is off");
            isArmed = false;
            return false;
        }

        List<ScanFilter> filters = new ArrayList<>();
        filters.add(new ScanFilter.Builder().setDeviceAddress(activeBike.address.trim()).build());

        ScanSettings.Builder settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER);
        try {
            if (bluetoothAdapter.isOffloadedFilteringSupported()) {
                settings.setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH)
                        .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE);
            } else {
                settings.setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES);
            }
        } catch (SecurityException e) {
            AppLog.w(TAG, "SecurityException checking offloaded filtering support", e);
            settings.setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES);
        } catch (Exception e) {
            AppLog.w(TAG, "Exception checking offloaded filtering support", e);
            settings.setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES);
        }

        try {
            PendingIntent pi = getPendingIntent(applicationContext);
            int status = scanner.startScan(filters, settings.build(), pi);
            if (status == 0) {
                isArmed = true;
                AppLog.i(TAG, "Armed offloaded detection for " + LogRedact.mac(activeBike.address));
                return true;
            } else {
                isArmed = false;
                AppLog.w(TAG, "Arming failed with status " + status);
                return false;
            }
        } catch (SecurityException e) {
            AppLog.w(TAG, "BLUETOOTH permission revoked during startScan", e);
            isArmed = false;
            return false;
        } catch (Exception e) {
            AppLog.w(TAG, "Arming failed", e);
            isArmed = false;
            return false;
        }
    }

    public static synchronized void disarm(Context context) {
        if (context == null) {
            isArmed = false;
            return;
        }
        Context applicationContext = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                if (applicationContext.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                    isArmed = false;
                    return;
                }
            } catch (SecurityException e) {
                isArmed = false;
                return;
            }
        }

        BluetoothAdapter bluetoothAdapter = getAdapter(applicationContext);
        BluetoothLeScanner scanner = null;
        try {
            scanner = (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) ? null : bluetoothAdapter.getBluetoothLeScanner();
        } catch (SecurityException e) {
            isArmed = false;
            return;
        } catch (Exception e) {
            isArmed = false;
            return;
        }

        if (scanner == null) {
            isArmed = false;
            return;
        }

        try {
            scanner.stopScan(getPendingIntent(applicationContext));
            AppLog.d(TAG, "Disarmed offloaded detection");
        } catch (SecurityException e) {
            AppLog.w(TAG, "SecurityException disarming scan", e);
        } catch (Exception e) {
            AppLog.w(TAG, "Disarming failed", e);
        } finally {
            isArmed = false;
        }
    }

    public static boolean isArmed() {
        return isArmed;
    }

    private static BluetoothAdapter getAdapter(Context context) {
        try {
            BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            if (bluetoothManager == null) {
                return null;
            }
            return bluetoothManager.getAdapter();
        } catch (SecurityException e) {
            AppLog.w(TAG, "Permission denied retrieving BluetoothAdapter", e);
            return null;
        } catch (Exception e) {
            AppLog.w(TAG, "Error retrieving BluetoothAdapter", e);
            return null;
        }
    }

    private static PendingIntent getPendingIntent(Context context) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        return PendingIntent.getBroadcast(context, REQUEST_CODE, new Intent(context, BikeDetectedReceiver.class), flags);
    }
}

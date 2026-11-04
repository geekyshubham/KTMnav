package app.ktmnav.backend.obd;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import app.ktmnav.backend.AppLog;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Finds ELM327 adapters: BLE advertisers by name or UART service, plus bonded Classic SPP adapters.
 */
public final class ObdAdapterScanner {

    private static final String TAG = "ObdScan";

    public static final class Candidate {
        public final String address;
        public final String name;
        public final String transport;
        public final int rssi;
        public final boolean bonded;

        Candidate(String address, String name, String transport, int rssi, boolean bonded) {
            this.address = address;
            this.name = name;
            this.transport = transport;
            this.rssi = rssi;
            this.bonded = bonded;
        }
    }

    public interface Callback {
        void onCandidate(Candidate candidate);

        void onFinished(String error);
    }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Set<String> seen = new HashSet<>();
    private BluetoothLeScanner scanner;
    private Callback callback;
    private int generation = 0;

    private final Runnable timeout = this::stop;

    public ObdAdapterScanner(Context context) {
        this.context = context.getApplicationContext();
    }

    private BluetoothAdapter adapter() {
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        return bm != null ? bm.getAdapter() : null;
    }

    public List<Candidate> bondedCandidates() {
        List<Candidate> out = new ArrayList<>();
        BluetoothAdapter a = adapter();
        if (a == null) {
            return out;
        }
        try {
            for (BluetoothDevice d : a.getBondedDevices()) {
                String n = d.getName();
                if (!ObdUartProfile.isLikelyAdapterName(n)) {
                    continue;
                }
                String transport = d.getType() == BluetoothDevice.DEVICE_TYPE_LE ? ObdManager.TRANSPORT_BLE : ObdManager.TRANSPORT_SPP;
                out.add(new Candidate(d.getAddress(), n, transport, 0, true));
            }
        } catch (SecurityException e) {
            AppLog.w(TAG, "BLUETOOTH_CONNECT missing for bonded device list", e);
        }
        return out;
    }

    public synchronized void start(long timeoutMs, Callback cb) {
        stop();
        BluetoothAdapter a = adapter();
        if (a == null || !a.isEnabled()) {
            if (cb != null) {
                mainHandler.post(() -> cb.onFinished("Bluetooth is off"));
            }
            return;
        }
        this.callback = cb;
        this.seen.clear();
        final int gen = ++generation;
        try {
            scanner = a.getBluetoothLeScanner();
            if (scanner == null) {
                finish(gen, "BLE scanner unavailable");
                return;
            }
            ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            scanner.startScan(null, settings, scanCallback);
            mainHandler.postDelayed(timeout, timeoutMs);
            AppLog.i(TAG, "Scanning for OBD adapters");
        } catch (SecurityException e) {
            finish(gen, "Bluetooth scan permission missing");
        }
    }

    public synchronized void stop() {
        mainHandler.removeCallbacks(timeout);
        BluetoothLeScanner s = scanner;
        scanner = null;
        if (s != null) {
            try {
                s.stopScan(scanCallback);
            } catch (SecurityException | IllegalStateException ignored) {
            }
            finish(generation, null);
        }
    }

    private synchronized void finish(int gen, String error) {
        Callback cb = callback;
        if (gen != generation || cb == null) {
            return;
        }
        callback = null;
        generation++;
        mainHandler.post(() -> cb.onFinished(error));
    }

    private synchronized void onResult(ScanResult r) {
        Callback cb = callback;
        if (cb == null || r == null || r.getDevice() == null) {
            return;
        }
        BluetoothDevice d = r.getDevice();
        ScanRecord record = r.getScanRecord();
        String n = record != null ? record.getDeviceName() : null;
        if (n == null) {
            try {
                n = d.getName();
            } catch (SecurityException ignored) {
            }
        }
        List<UUID> uuids = new ArrayList<>();
        if (record != null && record.getServiceUuids() != null) {
            for (ParcelUuid p : record.getServiceUuids()) {
                uuids.add(p.getUuid());
            }
        }
        if (!ObdUartProfile.isLikelyAdapterName(n) && !ObdUartProfile.offersKnownService(uuids)) {
            return;
        }
        String addr = d.getAddress();
        if (!seen.add(addr)) {
            return;
        }
        Candidate c = new Candidate(addr, n != null ? n : addr, ObdManager.TRANSPORT_BLE, r.getRssi(), false);
        mainHandler.post(() -> cb.onCandidate(c));
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            onResult(result);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult r : results) {
                onResult(r);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            AppLog.w(TAG, "BLE scan failed: " + errorCode);
            synchronized (ObdAdapterScanner.this) {
                scanner = null;
                mainHandler.removeCallbacks(timeout);
                finish(generation, "Scan failed (" + errorCode + ")");
            }
        }
    };
}

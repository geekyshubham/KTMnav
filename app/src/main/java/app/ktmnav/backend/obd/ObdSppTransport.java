package app.ktmnav.backend.obd;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import app.ktmnav.backend.AppLog;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * Bluetooth Classic SPP (RFCOMM 0x1101) link for ELM327 adapters that are not BLE. The adapter must be bonded
 * in Android Bluetooth settings first (most clones use PIN 1234 or 0000).
 */
public final class ObdSppTransport implements ObdLink {

    private static final String TAG = "ObdSpp";

    private final Context context;
    private final String address;
    private final String name;
    private final Object writeLock = new Object();

    private volatile int generation = 0;
    private volatile boolean closed = false;
    private volatile BluetoothSocket socket;
    private volatile BluetoothSocket pending;
    private volatile OutputStream out;
    private Listener listener;

    public ObdSppTransport(Context context, String address, String name) {
        this.context = context.getApplicationContext();
        this.address = address;
        this.name = (name != null && !name.trim().isEmpty()) ? name.trim() : address;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public synchronized void open(Listener l) {
        this.listener = l;
        this.closed = false;
        final int gen = ++generation;
        Thread t = new Thread(() -> connectAndRead(gen), "ktmnav-obd-spp");
        t.setDaemon(true);
        t.start();
    }

    private void connectAndRead(int gen) {
        BluetoothSocket s = null;
        String failure = "Adapter connection failed";
        for (int attempt = 0; attempt < 2 && s == null && isCurrent(gen); attempt++) {
            try {
                s = connect(attempt == 1);
            } catch (SecurityException e) {
                failure = "Bluetooth permission missing";
                break;
            } catch (IOException | IllegalArgumentException e) {
                AppLog.w(TAG, "SPP connect attempt " + (attempt + 1) + " failed: " + e.getMessage());
            }
        }
        if (s == null) {
            closeAndNotify(gen, failure);
            return;
        }
        InputStream in;
        synchronized (this) {
            if (!isCurrent(gen)) {
                closeQuietly(s);
                return;
            }
            socket = s;
        }
        try {
            in = s.getInputStream();
            out = s.getOutputStream();
        } catch (IOException e) {
            closeAndNotify(gen, "Adapter stream unavailable");
            return;
        }
        Listener l = listener;
        if (l != null) {
            l.onOpened(name);
        }
        byte[] buf = new byte[256];
        try {
            while (isCurrent(gen)) {
                int n = in.read(buf);
                if (n < 0) {
                    break;
                }
                if (n > 0 && l != null) {
                    l.onBytes(Arrays.copyOf(buf, n));
                }
            }
        } catch (IOException e) {
            AppLog.d(TAG, "SPP read ended: " + e.getMessage());
        }
        closeAndNotify(gen, "Adapter disconnected");
    }

    private BluetoothSocket connect(boolean insecure) throws IOException {
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
        if (adapter == null || !adapter.isEnabled()) {
            throw new IOException("Bluetooth is off");
        }
        BluetoothDevice device = adapter.getRemoteDevice(address);
        BluetoothSocket s = insecure
                ? device.createInsecureRfcommSocketToServiceRecord(ObdUartProfile.SPP_UUID)
                : device.createRfcommSocketToServiceRecord(ObdUartProfile.SPP_UUID);
        pending = s;
        try {
            s.connect();
            return s;
        } catch (IOException e) {
            closeQuietly(s);
            throw e;
        } finally {
            pending = null;
        }
    }

    @Override
    public boolean write(byte[] data) {
        OutputStream o = out;
        if (closed || o == null || data == null) {
            return false;
        }
        synchronized (writeLock) {
            try {
                o.write(data);
                o.flush();
                return true;
            } catch (IOException e) {
                AppLog.w(TAG, "SPP write failed: " + e.getMessage());
                return false;
            }
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        generation++;
        BluetoothSocket s = socket;
        BluetoothSocket p = pending;
        socket = null;
        pending = null;
        out = null;
        closeQuietly(s);
        closeQuietly(p);
    }

    private void closeAndNotify(int gen, String reason) {
        Listener l;
        synchronized (this) {
            if (!isCurrent(gen)) {
                return;
            }
            l = listener;
            close();
        }
        AppLog.w(TAG, "OBD SPP link closed: " + reason);
        if (l != null) {
            l.onClosed(reason);
        }
    }

    private boolean isCurrent(int gen) {
        return !closed && gen == generation;
    }

    private static void closeQuietly(BluetoothSocket s) {
        if (s == null) {
            return;
        }
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }
}

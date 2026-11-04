package app.ktmnav.backend.obd;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import app.ktmnav.backend.AppLog;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * BLE UART link to an ELM327 clone: discovers the UART pair, enables notifications and writes 20-byte chunks
 * one GATT operation at a time. Stale callbacks from earlier connections are dropped by generation.
 */
public final class ObdBleTransport implements ObdLink {

    private static final String TAG = "ObdBle";
    private static final int CHUNK_SIZE = 20;
    private static final long CONNECT_TIMEOUT_MS = 15000L;
    private static final long GATT_OP_TIMEOUT_MS = 2000L;
    private static final long RETRY_DELAY_MS = 1500L;

    private final Context context;
    private final String address;
    private final String name;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object queueLock = new Object();
    private final ArrayDeque<byte[]> writeQueue = new ArrayDeque<>();

    private volatile int generation = 0;
    private volatile boolean closed = false;
    private volatile boolean opened = false;
    private Listener listener;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar;
    private BluetoothGattCharacteristic notifyChar;
    private boolean writeBusy = false;
    private int connectAttempts = 0;

    private final Runnable connectTimeout = () -> fail(generation, "Adapter connection timed out");
    private final Runnable retryConnect = this::connectGatt;
    private final Runnable opTimeout = () -> fail(generation, "Adapter write timed out");

    public ObdBleTransport(Context context, String address, String name) {
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
        this.connectAttempts = 0;
        connectGatt();
    }

    private synchronized void connectGatt() {
        if (closed) {
            return;
        }
        final int gen = ++generation;
        releaseGatt();
        connectAttempts++;
        try {
            BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
            if (adapter == null || !adapter.isEnabled()) {
                fail(gen, "Bluetooth is off");
                return;
            }
            BluetoothDevice device = adapter.getRemoteDevice(address);
            AppLog.i(TAG, "Connecting to OBD adapter " + name + " (attempt " + connectAttempts + ")");
            mainHandler.removeCallbacks(connectTimeout);
            mainHandler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException e) {
            fail(gen, "Bluetooth permission missing");
        } catch (IllegalArgumentException e) {
            fail(gen, "Invalid adapter address");
        }
    }

    @Override
    public boolean write(byte[] data) {
        if (closed || !opened || data == null) {
            return false;
        }
        synchronized (queueLock) {
            writeQueue.addAll(ObdUartProfile.chunk(data, CHUNK_SIZE));
        }
        mainHandler.post(this::pumpWrites);
        return true;
    }

    @Override
    public synchronized void close() {
        closed = true;
        opened = false;
        generation++;
        mainHandler.removeCallbacks(connectTimeout);
        mainHandler.removeCallbacks(opTimeout);
        mainHandler.removeCallbacks(retryConnect);
        releaseGatt();
    }

    private synchronized void fail(int gen, String reason) {
        if (closed || gen != generation) {
            return;
        }
        boolean wasOpened = opened;
        if (!wasOpened && connectAttempts < 2) {
            AppLog.w(TAG, reason + "; retrying once");
            generation++;
            mainHandler.removeCallbacks(connectTimeout);
            mainHandler.removeCallbacks(retryConnect);
            releaseGatt();
            mainHandler.postDelayed(retryConnect, RETRY_DELAY_MS);
            return;
        }
        AppLog.w(TAG, "OBD link closed: " + reason);
        Listener l = listener;
        close();
        if (l != null) {
            l.onClosed(reason);
        }
    }

    private void releaseGatt() {
        synchronized (queueLock) {
            writeQueue.clear();
            writeBusy = false;
        }
        mainHandler.removeCallbacks(opTimeout);
        BluetoothGatt g = gatt;
        gatt = null;
        writeChar = null;
        notifyChar = null;
        if (g != null) {
            try {
                g.disconnect();
            } catch (SecurityException ignored) {
            }
            try {
                g.close();
            } catch (SecurityException ignored) {
            }
        }
    }

    private void pumpWrites() {
        final BluetoothGatt g;
        final BluetoothGattCharacteristic c;
        final byte[] chunk;
        final int gen;
        synchronized (this) {
            g = gatt;
            c = writeChar;
            gen = generation;
        }
        synchronized (queueLock) {
            if (writeBusy || writeQueue.isEmpty() || g == null || c == null || closed) {
                return;
            }
            chunk = writeQueue.pollFirst();
            writeBusy = true;
        }
        mainHandler.removeCallbacks(opTimeout);
        mainHandler.postDelayed(opTimeout, GATT_OP_TIMEOUT_MS);
        int writeType = ObdUartProfile.prefersNoResponse(c.getProperties())
                ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        boolean queued;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                queued = g.writeCharacteristic(c, chunk, writeType) == BluetoothStatusCodes.SUCCESS;
            } else {
                c.setWriteType(writeType);
                c.setValue(chunk);
                queued = g.writeCharacteristic(c);
            }
        } catch (SecurityException e) {
            queued = false;
        }
        if (!queued) {
            fail(gen, "Adapter write rejected");
        }
    }

    private void writeFinished(BluetoothGatt g) {
        synchronized (this) {
            if (g != gatt) {
                return;
            }
        }
        synchronized (queueLock) {
            writeBusy = false;
        }
        mainHandler.removeCallbacks(opTimeout);
        mainHandler.post(this::pumpWrites);
    }

    private synchronized void onServices(BluetoothGatt g) {
        Map<UUID, Map<UUID, Integer>> model = new LinkedHashMap<>();
        for (BluetoothGattService s : g.getServices()) {
            Map<UUID, Integer> chars = new LinkedHashMap<>();
            for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                chars.put(c.getUuid(), c.getProperties());
            }
            model.put(s.getUuid(), chars);
        }
        ObdUartProfile.Choice choice = ObdUartProfile.choose(model);
        if (choice == null) {
            fail(generation, "No serial (UART) service on adapter");
            return;
        }
        BluetoothGattService service = g.getService(choice.service);
        writeChar = service.getCharacteristic(choice.write);
        notifyChar = service.getCharacteristic(choice.notify);
        AppLog.i(TAG, "UART " + choice.service + " write=" + choice.write + " notify=" + choice.notify);
        enableNotify(g, notifyChar);
    }

    private void enableNotify(BluetoothGatt g, BluetoothGattCharacteristic c) {
        int gen = generation;
        try {
            g.setCharacteristicNotification(c, true);
            BluetoothGattDescriptor d = c.getDescriptor(ObdUartProfile.CCCD);
            if (d == null) {
                markOpened(g);
                return;
            }
            boolean indicate = (c.getProperties() & ObdUartProfile.PROP_NOTIFY) == 0;
            byte[] value = indicate ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
            boolean queued;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                queued = g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS;
            } else {
                d.setValue(value);
                queued = g.writeDescriptor(d);
            }
            if (!queued) {
                fail(gen, "Could not enable adapter notifications");
            }
        } catch (SecurityException e) {
            fail(gen, "Bluetooth permission missing");
        }
    }

    private void markOpened(BluetoothGatt g) {
        Listener l;
        synchronized (this) {
            if (closed || g != gatt || opened) {
                return;
            }
            opened = true;
            l = listener;
        }
        mainHandler.removeCallbacks(connectTimeout);
        AppLog.i(TAG, "OBD adapter link ready: " + name);
        if (l != null) {
            l.onOpened(name);
        }
    }

    private void deliver(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
        Listener l;
        synchronized (this) {
            if (closed || g != gatt || notifyChar == null || c == null || !notifyChar.getUuid().equals(c.getUuid())) {
                return;
            }
            l = listener;
        }
        if (l != null && value != null) {
            l.onBytes(value);
        }
    }

    private boolean isCurrent(BluetoothGatt g) {
        synchronized (this) {
            return !closed && g == gatt;
        }
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (!isCurrent(g)) {
                if (g != null && g != gatt) {
                    try {
                        g.close();
                    } catch (SecurityException ignored) {
                    }
                }
                return;
            }
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                try {
                    if (!g.discoverServices()) {
                        fail(generation, "Service discovery failed");
                    }
                } catch (SecurityException e) {
                    fail(generation, "Bluetooth permission missing");
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                fail(generation, "Adapter disconnected (status " + status + ")");
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (!isCurrent(g)) {
                return;
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail(generation, "Service discovery failed (status " + status + ")");
                return;
            }
            onServices(g);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            if (!isCurrent(g)) {
                return;
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                AppLog.w(TAG, "CCCD write status " + status + ", continuing");
            }
            markOpened(g);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (!isCurrent(g)) {
                return;
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail(generation, "Adapter write failed (status " + status + ")");
                return;
            }
            writeFinished(g);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            deliver(g, c, value);
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                deliver(g, c, c.getValue());
            }
        }
    };
}

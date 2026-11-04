package app.ktmnav.backend;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Gen 3 BLE transport for KTM/Husqvarna BCCU dashboards. This class is the Android adapter:
 * LE scanning, connectGatt (TRANSPORT_LE, autoConnect=false), and a serialised GATT operation queue
 * with per-characteristic coalescing. Control operations (auth reply, state, subscriptions, requests)
 * wait up to 4 s for their callback and are retried a bounded number of times; the six dashboard
 * display characteristics are written without response and never tear the link down on their own.
 * All protocol sequencing lives in the pure BccuLink state machine.
 */
public final class BccuBleTransport implements BikeLink {

    private static final String TAG = "Gen3Ble";
    private static final long GATT_OP_TIMEOUT_MS = 4000L;
    private static final long DISPLAY_OP_TIMEOUT_MS = 1500L;
    private static final long GATT_RETRY_DELAY_MS = 250L;
    private static final int CONTROL_WRITE_ATTEMPTS = 3;
    private static final int CCCD_WRITE_ATTEMPTS = 3;
    /** Rider-visible text and icon characteristics: written without response, like RideConnect does. */
    private static final java.util.Set<UUID> DISPLAY_CHARACTERISTICS = java.util.Set.of(
            BccuProtocol.TURN_ICON, BccuProtocol.TURN_DISTANCE, BccuProtocol.TURN_INFO, BccuProtocol.TURN_ROAD,
            BccuProtocol.ETA, BccuProtocol.REMAINING_DISTANCE, BccuProtocol.NOTIFICATION);

    private final Context context;
    private final BikeManager bikeManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<ConnectionStateListener> listeners = new CopyOnWriteArrayList<>();
    private final BccuLink link;
    private final DashboardFeatures dashboardFeatures;

    private volatile ConnectionState state = ConnectionState.DISCONNECTED;
    private volatile String statusDetail = "Disconnected";
    private volatile boolean isShutdown = false;
    private volatile int connectionGeneration = 0;

    private String targetAddress;
    private String targetName;
    private volatile BluetoothGatt gatt;
    private volatile ScanCallback scanCallback;

    private Runnable onFeaturesChangedListener;
    private java.util.function.Consumer<String> onHandlebarButtonListener;
    private HandlebarMaskListener onHandlebarMaskListener;

    private final Object queueLock = new Object();
    private final ArrayDeque<GattOp> gattQueue = new ArrayDeque<>();
    private boolean gattBusy = false;
    private GattOp currentOp;
    private volatile boolean awaitingRssi = false;

    private final Object readCallbackLock = new Object();
    private int currentReadOpSeq = 0;
    private int completedReadOpSeq = 0;

    private static final class GattOp {
        final UUID coalesceKey;
        final Runnable runnable;
        final boolean write;
        /** Control ops count towards link teardown on timeout; display writes do not. */
        final boolean control;
        final long timeoutMs;
        final String label;
        int attemptsLeft;

        GattOp(UUID coalesceKey, Runnable runnable, boolean write, boolean control, long timeoutMs, int attempts, String label) {
            this.coalesceKey = coalesceKey;
            this.runnable = runnable;
            this.write = write;
            this.control = control;
            this.timeoutMs = timeoutMs;
            this.attemptsLeft = Math.max(1, attempts);
            this.label = label;
        }
    }

    private final Runnable gattOpTimeoutRunnable = this::onGattOpTimeout;
    private final Runnable gattOpRetryRunnable = this::retryCurrentOp;

    public BccuBleTransport(Context context, BikeManager bikeManager) {
        this.context = context.getApplicationContext();
        this.bikeManager = bikeManager != null ? bikeManager : new BikeManager(context);
        this.link = new BccuLink(this.context, new AndroidRadio(), new PrefsStore(this.bikeManager), new LinkListener(),
                DashBannerArbiter.handlerScheduler(mainHandler));
        this.dashboardFeatures = link.getFeatures();
    }

    public BccuBleTransport(Context context) {
        this(context, new BikeManager(context));
    }

    public BccuBleTransport(Context context, String address, String name) {
        this(context, new BikeManager(context));
        this.targetAddress = (address != null && !address.trim().isEmpty()) ? address.trim() : null;
        this.targetName = (name != null && !name.trim().isEmpty()) ? name.trim() : this.targetAddress;
    }

    // --- Listener wiring ---

    public void setOnFeaturesChangedListener(Runnable listener) {
        this.onFeaturesChangedListener = listener;
    }

    public void setOnHandlebarButtonListener(java.util.function.Consumer<String> listener) {
        this.onHandlebarButtonListener = listener;
    }

    public void setOnHandlebarMaskListener(HandlebarMaskListener listener) {
        this.onHandlebarMaskListener = listener;
    }

    private final class LinkListener implements BccuLink.Listener {
        @Override
        public void onState(ConnectionState newState, String detail) {
            updateState(newState, detail);
        }

        @Override
        public void onFeaturesChanged() {
            Runnable l = onFeaturesChangedListener;
            if (l != null && !isShutdown) {
                mainHandler.post(l);
            }
        }

        @Override
        public void onButton(String button) {
            final java.util.function.Consumer<String> l = onHandlebarButtonListener;
            if (l == null || isShutdown || state != ConnectionState.CONNECTED) {
                return;
            }
            final int gen = connectionGeneration;
            mainHandler.post(() -> {
                if (isShutdown || gen != connectionGeneration || state != ConnectionState.CONNECTED) {
                    return;
                }
                l.accept(button);
            });
        }

        @Override
        public void onMask(int mask, long uptimeMs) {
            final HandlebarMaskListener l = onHandlebarMaskListener;
            if (l == null || isShutdown || state != ConnectionState.CONNECTED) {
                return;
            }
            final int gen = connectionGeneration;
            mainHandler.post(() -> {
                if (isShutdown || gen != connectionGeneration || state != ConnectionState.CONNECTED) {
                    return;
                }
                l.onMask(mask, uptimeMs);
            });
        }
    }

    private static final class PrefsStore implements BccuLink.Store {
        private final BikeManager bikes;

        PrefsStore(BikeManager bikes) {
            this.bikes = bikes;
        }

        @Override
        public boolean hasPairedBefore(String address) {
            return bikes.hasPairedBefore(address);
        }

        @Override
        public void markPairedBefore(String address) {
            bikes.markPairedBefore(address);
        }

        @Override
        public List<byte[]> loadKeyPool(String address) {
            return bikes.loadSessionKeys(address);
        }

        @Override
        public void storeKeyPool(String address, List<byte[]> keys) {
            bikes.storeSessionKeys(address, keys);
        }

        @Override
        public String userName() {
            return bikes.getPrefs().getString("user_name", "");
        }
    }

    // --- Public API ---

    public void settingsChanged() {
        dashboardFeatures.settingsChanged();
    }

    public Map<String, String> getTelemetry() {
        return dashboardFeatures.getTelemetry();
    }

    public Map<String, String> getVehicleInfo() {
        return dashboardFeatures.getVehicleInfo();
    }

    public String getTelemetryStatus() {
        return isConnected() ? dashboardFeatures.getTelemetryStatus() : null;
    }

    public String getVehicleProfileLabel() {
        VehicleProfile p = link.getVehicleProfile();
        return p != null ? p.getLabel() : null;
    }

    public boolean isUnsupportedDash() {
        return link.getPhase() == BccuLink.Phase.UNSUPPORTED;
    }

    public boolean isHandshakeInProgress() {
        return link.isHandshakeInProgress();
    }

    public int getHandshakeRecoveries() {
        return link.getHandshakeRecoveries();
    }

    public Integer getRssi() {
        return isConnected() ? dashboardFeatures.getRssi() : null;
    }

    public String getLastButton() {
        return dashboardFeatures.getLastButton();
    }

    public long getButtonEventSequence() {
        return dashboardFeatures.getButtonEventSequence();
    }

    @Override
    public ConnectionState getConnectionState() {
        return state;
    }

    public boolean isConnected() {
        return state == ConnectionState.CONNECTED;
    }

    @Override
    public String getStatusDetail() {
        return statusDetail;
    }

    @Override
    public void addConnectionStateListener(ConnectionStateListener listener) {
        if (listener == null) return;
        listeners.add(listener);
        listener.onConnectionStateChanged(state, statusDetail);
    }

    @Override
    public void removeConnectionStateListener(ConnectionStateListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    private void updateState(ConnectionState newState, String detail) {
        if (isShutdown) {
            return;
        }
        this.state = newState;
        this.statusDetail = detail;
        AppLog.i(TAG, "State -> " + newState + " (" + detail + ")");
        mainHandler.post(() -> {
            if (isShutdown) {
                return;
            }
            for (ConnectionStateListener l : listeners) {
                l.onConnectionStateChanged(newState, detail);
            }
        });
    }

    public synchronized void connect() {
        if (this.targetAddress != null) {
            connect(this.targetAddress, this.targetName);
        }
    }

    @Override
    public synchronized void connect(String address, String name) {
        connect(address, name, BccuLink.Start.DIRECT, 0);
    }

    public synchronized void connect(String address, String name, BccuLink.Start mode, int handshakeRecoveries) {
        if (isShutdown || address == null || address.trim().isEmpty()) {
            return;
        }
        this.targetAddress = address.trim();
        this.targetName = (name != null && !name.trim().isEmpty()) ? name.trim() : targetAddress;
        this.connectionGeneration++;
        link.start(targetAddress, targetName, mode, handshakeRecoveries);
    }

    public void onBikeSeen(String source) {
        link.onBikeSeen(source);
    }

    public void onAdapterStateChanged(boolean on) {
        link.onAdapterStateChanged(on);
    }

    @Override
    public synchronized void disconnect() {
        this.connectionGeneration++;
        link.stop();
        updateState(ConnectionState.DISCONNECTED, "Disconnected");
    }

    public synchronized void shutdown() {
        this.isShutdown = true;
        this.onHandlebarButtonListener = null;
        this.onHandlebarMaskListener = null;
        this.onFeaturesChangedListener = null;
        this.connectionGeneration++;
        link.stop();
        mainHandler.removeCallbacksAndMessages(null);
        listeners.clear();
        this.state = ConnectionState.DISCONNECTED;
        this.statusDetail = "Disconnected";
    }

    @Override
    public void sendGuidance(String distanceText, String roadText, String etaText, String remainingDistanceText) {
        link.sendGuidance(distanceText, roadText, etaText, remainingDistanceText);
    }

    @Override
    public void sendTurnIcon(TurnIcon icon) {
        link.sendTurnIcon(icon);
    }

    @Override
    public void sendNotification(String text, BccuProtocol.NotificationIcon icon) {
        link.showBanner(DashboardFeatures.MANUAL_BANNER_OWNER, text, DashBannerArbiter.PRIORITY_MESSAGE, 0L, icon);
    }

    public boolean showBanner(String owner, String text, int priority, long holdMs, BccuProtocol.NotificationIcon icon) {
        return link.showBanner(owner, text, priority, holdMs, icon);
    }

    public void clearBanner(String owner) {
        link.clearBanner(owner);
    }

    public boolean sendSymbolTest(int turnIconId) {
        return link.sendSymbolTest(turnIconId);
    }

    public boolean sendBannerIconTest(int notificationIconId) {
        return link.sendBannerIconTest(notificationIconId);
    }

    public void endSymbolTest() {
        link.endSymbolTest();
    }

    @Override
    public void clearGuidance() {
        link.clearGuidance();
    }

    // --- Android radio ---

    private BluetoothAdapter adapter() {
        try {
            BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            return bm != null ? bm.getAdapter() : null;
        } catch (SecurityException e) {
            AppLog.w(TAG, "Permission denied retrieving BluetoothAdapter", e);
            return null;
        }
    }

    private boolean hasPermission(String permission) {
        try {
            return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
        } catch (SecurityException e) {
            return false;
        }
    }

    private void disconnectGatt() {
        BluetoothGatt g = this.gatt;
        this.gatt = null;
        clearQueue();
        if (g != null) {
            try {
                g.disconnect();
            } catch (SecurityException e) {
                AppLog.w(TAG, "BLUETOOTH_CONNECT permission revoked while disconnecting GATT", e);
            } catch (Exception ignored) {
            }
            try {
                g.close();
            } catch (SecurityException e) {
                AppLog.w(TAG, "BLUETOOTH_CONNECT permission revoked while closing GATT", e);
            } catch (Exception ignored) {
            }
        }
    }

    private final class AndroidRadio implements BccuLink.Radio {
        @Override
        public boolean startScan(String address) {
            stopScan();
            if (!hasPermission(android.Manifest.permission.BLUETOOTH_SCAN)) {
                AppLog.w(TAG, "BLUETOOTH_SCAN not granted; cannot scan before reconnecting");
                return false;
            }
            BluetoothAdapter a = adapter();
            BluetoothLeScanner scanner;
            try {
                scanner = (a != null && a.isEnabled()) ? a.getBluetoothLeScanner() : null;
            } catch (SecurityException e) {
                return false;
            }
            if (scanner == null) {
                return false;
            }
            ScanCallback cb = new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, ScanResult result) {
                    if (this != scanCallback || result == null || result.getDevice() == null) {
                        return;
                    }
                    link.onScanResult(result.getDevice().getAddress());
                }

                @Override
                public void onScanFailed(int errorCode) {
                    if (this == scanCallback) {
                        scanCallback = null;
                        link.onScanFailed(errorCode);
                    }
                }
            };
            try {
                List<ScanFilter> filters = Collections.singletonList(new ScanFilter.Builder().setDeviceAddress(address).build());
                ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
                scanCallback = cb;
                scanner.startScan(filters, settings, cb);
                return true;
            } catch (SecurityException | IllegalArgumentException | IllegalStateException e) {
                scanCallback = null;
                AppLog.w(TAG, "startScan failed", e);
                return false;
            }
        }

        @Override
        public void stopScan() {
            ScanCallback cb = scanCallback;
            scanCallback = null;
            if (cb == null) {
                return;
            }
            try {
                BluetoothAdapter a = adapter();
                BluetoothLeScanner scanner = (a != null && a.isEnabled()) ? a.getBluetoothLeScanner() : null;
                if (scanner != null) {
                    scanner.stopScan(cb);
                }
            } catch (SecurityException | IllegalStateException e) {
                AppLog.w(TAG, "stopScan failed", e);
            }
        }

        @Override
        public boolean connectGatt(String address) {
            disconnectGatt();
            BluetoothAdapter a = adapter();
            try {
                if (a == null || !a.isEnabled()) {
                    return false;
                }
                BluetoothDevice device = a.getRemoteDevice(address);
                gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
                return gatt != null;
            } catch (SecurityException e) {
                AppLog.w(TAG, "BLUETOOTH_CONNECT permission missing for connectGatt", e);
                return false;
            } catch (IllegalArgumentException e) {
                AppLog.w(TAG, "Invalid Bluetooth address", e);
                return false;
            }
        }

        @Override
        public void closeGatt() {
            disconnectGatt();
        }

        @Override
        public boolean discoverServices() {
            BluetoothGatt g = gatt;
            try {
                return g != null && g.discoverServices();
            } catch (SecurityException e) {
                AppLog.w(TAG, "SecurityException on discoverServices", e);
                return false;
            }
        }

        @Override
        public boolean requestMtu(int mtu) {
            BluetoothGatt g = gatt;
            try {
                return g != null && g.requestMtu(mtu);
            } catch (SecurityException e) {
                AppLog.w(TAG, "SecurityException on requestMtu", e);
                return false;
            }
        }

        @Override
        public void requestHighPriority() {
            BluetoothGatt g = gatt;
            try {
                if (g != null) {
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                }
            } catch (SecurityException e) {
                AppLog.w(TAG, "SecurityException on requestConnectionPriority", e);
            }
        }

        @Override
        public void write(UUID characteristic, byte[] value, boolean coalesce) {
            writeCharacteristic(characteristic, value, coalesce);
        }

        @Override
        public void read(UUID characteristic) {
            readCharacteristic(characteristic);
        }

        @Override
        public void subscribe(UUID characteristic, boolean indication) {
            enableCccd(characteristic, indication);
        }

        @Override
        public void requestRssi() {
            enqueueGattOp(null, false, true, GATT_OP_TIMEOUT_MS, 1, "rssi", () -> {
                BluetoothGatt g = gatt;
                try {
                    awaitingRssi = true;
                    if (g == null || isShutdown || !g.readRemoteRssi()) {
                        awaitingRssi = false;
                        gattOpFinished();
                    }
                } catch (SecurityException e) {
                    awaitingRssi = false;
                    AppLog.w(TAG, "SecurityException on readRemoteRssi", e);
                    gattOpFinished();
                }
            });
        }

        @Override
        public boolean refreshGattCache() {
            BluetoothGatt g = gatt;
            if (g == null) {
                return false;
            }
            try {
                java.lang.reflect.Method refresh = g.getClass().getMethod("refresh");
                Object result = refresh.invoke(g);
                return result instanceof Boolean && (Boolean) result;
            } catch (ReflectiveOperationException | RuntimeException e) {
                AppLog.w(TAG, "GATT cache refresh unavailable: " + e.getClass().getSimpleName());
                return false;
            }
        }

        @Override
        public Collection<UUID> services() {
            BluetoothGatt g = gatt;
            List<UUID> list = new ArrayList<>();
            if (g != null) {
                for (BluetoothGattService s : g.getServices()) {
                    list.add(s.getUuid());
                }
            }
            return list;
        }

        @Override
        public List<UUID> characteristics(UUID service) {
            BluetoothGatt g = gatt;
            List<UUID> list = new ArrayList<>();
            BluetoothGattService s = g != null ? g.getService(service) : null;
            if (s != null) {
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    list.add(c.getUuid());
                }
            }
            return list;
        }

        @Override
        public int properties(UUID characteristic) {
            BluetoothGatt g = gatt;
            BluetoothGattCharacteristic c = g != null ? findCharacteristic(g, characteristic) : null;
            return c != null ? c.getProperties() : 0;
        }

        @Override
        public String describeGatt() {
            BluetoothGatt g = gatt;
            if (g == null) {
                return "GATT table: (no connection)";
            }
            StringBuilder sb = new StringBuilder("GATT table (").append(g.getServices().size()).append(" services):");
            for (BluetoothGattService s : g.getServices()) {
                sb.append("\n  service ").append(s.getUuid());
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    int p = c.getProperties();
                    sb.append("\n    char ").append(c.getUuid()).append(" [")
                            .append((p & BluetoothGattCharacteristic.PROPERTY_READ) != 0 ? "R" : "")
                            .append((p & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ? "W" : "")
                            .append((p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0 ? "w" : "")
                            .append((p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ? "N" : "")
                            .append((p & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 ? "I" : "")
                            .append("]");
                }
            }
            return sb.toString();
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (isShutdown || g != gatt) {
                if (g != null && g != gatt) {
                    try {
                        g.close();
                    } catch (SecurityException e) {
                        AppLog.w(TAG, "BLUETOOTH_CONNECT permission revoked while closing stale GATT", e);
                    } catch (Exception ignored) {
                    }
                }
                return;
            }
            AppLog.i(TAG, "onConnectionStateChange status=" + status + " newState=" + newState);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                link.onLinkUp();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                link.onLinkDown(status);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (isShutdown || g != gatt) {
                return;
            }
            link.onServicesDiscovered(status == BluetoothGatt.GATT_SUCCESS);
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            if (isShutdown || g != gatt) {
                return;
            }
            link.onMtuChanged(mtu);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            if (isShutdown || g != gatt) {
                return;
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                AppLog.w(TAG, "Descriptor write failed with status=" + status);
                if (gattOpFailed("descriptor write status " + status)) {
                    return;
                }
            }
            link.onGattOperationCompleted();
            gattOpFinished();
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (isShutdown || g != gatt) {
                return;
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                AppLog.w(TAG, "Characteristic write failed with status=" + status);
                if (gattOpFailed("write status " + status)) {
                    return;
                }
            }
            link.onGattOperationCompleted();
            gattOpFinished();
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (isShutdown || g != gatt) {
                return;
            }
            handleCharacteristicRead(c, c != null ? c.getValue() : null, status);
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value, int status) {
            if (isShutdown || g != gatt) {
                return;
            }
            handleCharacteristicRead(c, value, status);
        }

        private void handleCharacteristicRead(BluetoothGattCharacteristic c, byte[] value, int status) {
            boolean shouldDeliver = false;
            synchronized (readCallbackLock) {
                if (currentReadOpSeq != completedReadOpSeq) {
                    completedReadOpSeq = currentReadOpSeq;
                    shouldDeliver = true;
                }
            }
            if (shouldDeliver) {
                link.onGattOperationCompleted();
                try {
                    if (status == BluetoothGatt.GATT_SUCCESS && c != null && value != null) {
                        link.onCharacteristicRead(c.getUuid(), value);
                    }
                } finally {
                    gattOpFinished();
                }
            }
        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt g, int rssi, int status) {
            if (isShutdown || g != gatt || !awaitingRssi) {
                return;
            }
            awaitingRssi = false;
            link.onGattOperationCompleted();
            try {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    link.onRssi(rssi);
                }
            } finally {
                gattOpFinished();
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (isShutdown || g != gatt || c == null) {
                return;
            }
            link.onCharacteristicChanged(c.getUuid(), c.getValue());
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            if (isShutdown || g != gatt || c == null) {
                return;
            }
            link.onCharacteristicChanged(c.getUuid(), value);
        }
    };

    // --- Serialised GATT operation queue ---

    private void onGattOpTimeout() {
        if (isShutdown) {
            return;
        }
        GattOp op;
        synchronized (queueLock) {
            if (!gattBusy || currentOp == null) {
                return;
            }
            op = currentOp;
        }
        awaitingRssi = false;
        synchronized (readCallbackLock) {
            completedReadOpSeq = currentReadOpSeq;
        }
        AppLog.w(TAG, "GATT " + op.label + " produced no callback in " + op.timeoutMs + " ms");
        // A descriptor write that never answered is re-issued (RideConnect retries every op); a control
        // characteristic write is not, because a late callback for the first copy could still arrive.
        if ("cccd".equals(op.label) && gattOpFailed("timeout")) {
            return;
        }
        link.onGattOperationTimeout(op.write, op.control);
        gattOpFinished();
    }

    /**
     * Called when the current op failed to start or its callback reported an error. Returns true when a
     * retry was scheduled, false when the caller should finish the op.
     */
    private boolean gattOpFailed(String why) {
        GattOp op;
        synchronized (queueLock) {
            if (!gattBusy || currentOp == null || currentOp.attemptsLeft <= 1 || isShutdown || gatt == null) {
                return false;
            }
            op = currentOp;
            op.attemptsLeft--;
        }
        mainHandler.removeCallbacks(gattOpTimeoutRunnable);
        AppLog.w(TAG, "GATT " + op.label + " failed (" + why + "); retrying in " + GATT_RETRY_DELAY_MS + " ms (" + op.attemptsLeft + " attempts left)");
        mainHandler.removeCallbacks(gattOpRetryRunnable);
        mainHandler.postDelayed(gattOpRetryRunnable, GATT_RETRY_DELAY_MS);
        return true;
    }

    private void retryCurrentOp() {
        GattOp op;
        synchronized (queueLock) {
            if (!gattBusy || currentOp == null || isShutdown) {
                return;
            }
            op = currentOp;
        }
        mainHandler.removeCallbacks(gattOpTimeoutRunnable);
        mainHandler.postDelayed(gattOpTimeoutRunnable, op.timeoutMs);
        try {
            op.runnable.run();
        } catch (Exception e) {
            AppLog.w(TAG, "Exception re-running GATT op", e);
            gattOpFinished();
        }
    }

    private void enableCccd(UUID charUuid, boolean indication) {
        enqueueGattOp(null, true, true, GATT_OP_TIMEOUT_MS, CCCD_WRITE_ATTEMPTS, "cccd", () -> {
            BluetoothGatt g = this.gatt;
            if (g == null || isShutdown) {
                gattOpFinished();
                return;
            }
            BluetoothGattCharacteristic c = findCharacteristic(g, charUuid);
            if (c == null) {
                AppLog.w(TAG, "Cannot enable CCCD for " + charUuid + " - characteristic not found");
                gattOpFinished();
                return;
            }
            try {
                g.setCharacteristicNotification(c, true);
                BluetoothGattDescriptor d = c.getDescriptor(BccuProtocol.CCCD);
                if (d == null) {
                    AppLog.w(TAG, "CCCD descriptor not found for " + charUuid);
                    gattOpFinished();
                    return;
                }
                d.setValue(indication ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                        : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                if (!g.writeDescriptor(d)) {
                    AppLog.w(TAG, "writeDescriptor returned false for " + charUuid);
                    if (!gattOpFailed("writeDescriptor rejected")) {
                        gattOpFinished();
                    }
                }
            } catch (SecurityException e) {
                AppLog.w(TAG, "SecurityException writing CCCD", e);
                gattOpFinished();
            } catch (Exception e) {
                AppLog.w(TAG, "Exception writing CCCD", e);
                gattOpFinished();
            }
        });
    }

    private void readCharacteristic(UUID charUuid) {
        enqueueGattOp(null, false, true, GATT_OP_TIMEOUT_MS, 1, "read", () -> {
            BluetoothGatt g = this.gatt;
            if (g == null || isShutdown) {
                gattOpFinished();
                return;
            }
            BluetoothGattCharacteristic c = findCharacteristic(g, charUuid);
            if (c == null) {
                AppLog.d(TAG, "Characteristic " + charUuid + " not found, skipping read");
                gattOpFinished();
                return;
            }
            synchronized (readCallbackLock) {
                currentReadOpSeq++;
            }
            try {
                if (!g.readCharacteristic(c)) {
                    AppLog.w(TAG, "readCharacteristic returned false for " + charUuid);
                    gattOpFinished();
                }
            } catch (SecurityException e) {
                AppLog.w(TAG, "SecurityException reading characteristic " + charUuid, e);
                gattOpFinished();
            } catch (Exception e) {
                AppLog.w(TAG, "Exception reading characteristic " + charUuid, e);
                gattOpFinished();
            }
        });
    }

    private void writeCharacteristic(UUID charUuid, byte[] data, boolean coalesce) {
        boolean canCoalesce = coalesce && !BccuProtocol.AUTH_REPLY.equals(charUuid);
        boolean display = DISPLAY_CHARACTERISTICS.contains(charUuid);
        enqueueGattOp(canCoalesce ? charUuid : null, true, !display, display ? DISPLAY_OP_TIMEOUT_MS : GATT_OP_TIMEOUT_MS,
                display ? 1 : CONTROL_WRITE_ATTEMPTS, display ? "display write" : "write", () -> {
            BluetoothGatt g = this.gatt;
            if (g == null || isShutdown) {
                gattOpFinished();
                return;
            }
            BluetoothGattCharacteristic c = findCharacteristic(g, charUuid);
            if (c == null) {
                AppLog.w(TAG, "Characteristic " + charUuid + " not found, skipping write");
                gattOpFinished();
                return;
            }
            try {
                boolean noResponse = display && (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
                c.setWriteType(noResponse ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                c.setValue(data);
                if (!g.writeCharacteristic(c)) {
                    AppLog.w(TAG, "writeCharacteristic returned false for " + charUuid);
                    if (!gattOpFailed("writeCharacteristic rejected")) {
                        gattOpFinished();
                    }
                }
            } catch (SecurityException e) {
                AppLog.w(TAG, "SecurityException writing characteristic " + charUuid, e);
                gattOpFinished();
            } catch (Exception e) {
                AppLog.w(TAG, "Exception writing characteristic " + charUuid, e);
                gattOpFinished();
            }
        });
    }

    private BluetoothGattCharacteristic findCharacteristic(BluetoothGatt g, UUID uuid) {
        for (BluetoothGattService s : g.getServices()) {
            BluetoothGattCharacteristic c = s.getCharacteristic(uuid);
            if (c != null) {
                return c;
            }
        }
        return null;
    }

    private void enqueueGattOp(UUID coalesceKey, boolean write, boolean control, long timeoutMs, int attempts, String label, Runnable op) {
        synchronized (queueLock) {
            if (isShutdown) {
                return;
            }
            if (coalesceKey != null) {
                Iterator<GattOp> it = gattQueue.iterator();
                while (it.hasNext()) {
                    if (Objects.equals(it.next().coalesceKey, coalesceKey)) {
                        it.remove();
                    }
                }
            }
            gattQueue.addLast(new GattOp(coalesceKey, op, write, control, timeoutMs, attempts, label));
        }
        runNextGattOp();
    }

    private void runNextGattOp() {
        GattOp op;
        synchronized (queueLock) {
            if (gattBusy || gattQueue.isEmpty() || isShutdown) {
                return;
            }
            op = gattQueue.pollFirst();
            gattBusy = true;
            currentOp = op;
        }
        mainHandler.removeCallbacks(gattOpTimeoutRunnable);
        mainHandler.postDelayed(gattOpTimeoutRunnable, op != null ? op.timeoutMs : GATT_OP_TIMEOUT_MS);
        if (op != null) {
            try {
                op.runnable.run();
            } catch (Exception e) {
                AppLog.w(TAG, "Exception running GATT op", e);
                gattOpFinished();
            }
        }
    }

    private void gattOpFinished() {
        synchronized (queueLock) {
            if (!gattBusy) {
                return;
            }
            mainHandler.removeCallbacks(gattOpTimeoutRunnable);
            mainHandler.removeCallbacks(gattOpRetryRunnable);
            gattBusy = false;
            currentOp = null;
        }
        runNextGattOp();
    }

    private void clearQueue() {
        synchronized (queueLock) {
            mainHandler.removeCallbacks(gattOpTimeoutRunnable);
            mainHandler.removeCallbacks(gattOpRetryRunnable);
            gattQueue.clear();
            gattBusy = false;
            currentOp = null;
        }
        awaitingRssi = false;
        synchronized (readCallbackLock) {
            completedReadOpSeq = currentReadOpSeq;
        }
    }
}

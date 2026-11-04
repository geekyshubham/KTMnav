package app.ktmnav.backend;

import android.content.Context;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pure Gen 3 BCCU link state machine (no Android Bluetooth types): reconnect search, dash-boot
 * settle, GATT bring-up, authentication, post-auth sequence and dashboard writes. The Android
 * adapter (BccuBleTransport) supplies a Radio and forwards GATT callbacks; tests drive it with a
 * fake radio and a virtual-time scheduler.
 * Sequencing and timing follow the field-verified Navigator Gen3 v0.3.0 release
 * (MIT, Copyright (c) 2026 OpenDash contributors).
 */
public final class BccuLink {

    private static final String TAG = "Gen3Link";

    public static final int MTU = 517;
    public static final long CONNECT_SETTLE_MS = 10000L;
    public static final long HANDSHAKE_TIMEOUT_KNOWN_MS = 25000L;
    public static final long HANDSHAKE_TIMEOUT_FIRST_PAIR_MS = 75000L;
    public static final long RETRY_COOLDOWN_FIRST_MS = 15000L;
    public static final long RETRY_COOLDOWN_LATER_MS = 45000L;
    public static final long LINK_LOSS_RESCAN_MS = 1500L;
    public static final long STUCK_CONNECTING_MS = 45000L;
    public static final long SCAN_RETRY_MS = 20000L;
    public static final long MTU_CALLBACK_TIMEOUT_MS = 2500L;
    public static final long GREETING_MS = 6000L;
    public static final long GUIDANCE_CLEAR_DELAY_MS = 4000L;
    public static final int NAV_STATE_VOLUME_UNSET = 255;
    public static final long UNSUPPORTED_INFO_READ_MS = 5000L;
    public static final int GATT_TIMEOUTS_BEFORE_TEARDOWN = 3;
    /**
     * After an authenticated session drops, reconnect directly to the retained address with these delays
     * (RideConnect's field-tested ladder) before falling back to the scan-then-settle search. A dash that is
     * still on answers the first attempt within a second or two; one that was switched off exhausts the
     * ladder in about half a minute and the caller's scan/offloaded detection takes over.
     */
    public static final long[] FAST_RECONNECT_DELAYS_MS = {500L, 1500L, 3000L};
    public static final long FAST_CONNECT_TIMEOUT_MS = 8000L;
    /** Device Information, VIN, PRPC and unknown-service probing start this long after authentication. */
    public static final long POST_AUTH_PROBE_DELAY_MS = 3000L;
    /** Keepalive key re-selections arriving closer together than this are answered once. */
    public static final long KEY_RESELECT_THROTTLE_MS = 200L;

    public enum Start { DIRECT, SCAN, SETTLE }

    public enum Phase { IDLE, WAITING_FOR_ADAPTER, SCANNING, SETTLING, RECONNECT_WAIT, CONNECTING, LINK_UP, AUTHENTICATED, COOLDOWN, UNSUPPORTED, STOPPED }

    /** Total wall-clock budget of the fast ladder when every attempt times out. */
    public static long fastReconnectBudgetMs() {
        long total = 0;
        for (long d : FAST_RECONNECT_DELAYS_MS) {
            total += d + FAST_CONNECT_TIMEOUT_MS;
        }
        return total;
    }

    public enum MissingNavAction { RETRY_WITH_CACHE_REFRESH, RETRY, UNSUPPORTED }

    public interface Radio {
        boolean startScan(String address);

        void stopScan();

        boolean connectGatt(String address);

        void closeGatt();

        boolean discoverServices();

        boolean requestMtu(int mtu);

        void requestHighPriority();

        void write(UUID characteristic, byte[] value, boolean coalesce);

        void read(UUID characteristic);

        void subscribe(UUID characteristic, boolean indication);

        void requestRssi();

        Collection<UUID> services();

        List<UUID> characteristics(UUID service);

        int properties(UUID characteristic);

        String describeGatt();

        default boolean refreshGattCache() {
            return false;
        }
    }

    public interface Store {
        boolean hasPairedBefore(String address);

        void markPairedBefore(String address);

        List<byte[]> loadKeyPool(String address);

        void storeKeyPool(String address, List<byte[]> keys);

        String userName();
    }

    public interface Listener {
        void onState(ConnectionState state, String detail);

        void onFeaturesChanged();

        void onButton(String button);

        void onMask(int mask, long uptimeMs);
    }

    static final int PROPERTY_READ = 0x02;
    static final int PROPERTY_NOTIFY = 0x10;
    static final int PROPERTY_INDICATE = 0x20;

    private final Radio radio;
    private final Store store;
    private final Listener listener;
    private final DashBannerArbiter.Scheduler scheduler;
    private final DashboardFeatures features;

    private Phase phase = Phase.IDLE;
    private ConnectionState reportedState = ConnectionState.DISCONNECTED;
    private String address;
    private String name;
    private int handshakeRecoveries;
    private boolean bikeSeenDuringCooldown;
    private boolean authSetupStarted;
    private boolean gattCacheRefreshed;
    private boolean unsupportedGattClosed;
    private int pendingInfoReads;
    private int consecutiveGattTimeouts;
    private int fastReconnectAttempt;
    private boolean reselectThrottled;
    private BccuHandshake handshake;
    private byte[] activeKey;
    private byte[] sessionIv;
    private VehicleProfile profile;
    private final AtomicLong lastButtonSeq = new AtomicLong();

    private Object phaseTimer;
    private Object handshakeTimer;
    private Object mtuTimer;
    private Object guidanceClearTimer;
    private Object probeTimer;
    private Object reselectTimer;

    public BccuLink(Context context, Radio radio, Store store, Listener listener, DashBannerArbiter.Scheduler scheduler) {
        this.radio = radio;
        this.store = store;
        this.listener = listener;
        this.scheduler = scheduler != null ? scheduler : DashBannerArbiter.noopScheduler();
        this.features = DashboardFeatures.withScheduler(context, new DashboardFeatures.Transport() {
            @Override
            public void write(UUID id, byte[] value, boolean coalesce) {
                radio.write(id, value, coalesce);
            }

            @Override
            public void read(UUID id) {
                radio.read(id);
            }

            @Override
            public void subscribe(UUID id, boolean indication) {
                radio.subscribe(id, indication);
            }

            @Override
            public void requestRssi() {
                radio.requestRssi();
            }

            @Override
            public void changed() {
                onFeaturesChanged();
            }

            @Override
            public void handlebarMask(int mask, long uptimeMs) {
                listener.onMask(mask, uptimeMs);
            }
        }, this.scheduler);
    }

    public DashboardFeatures getFeatures() {
        return features;
    }

    public synchronized Phase getPhase() {
        return phase;
    }

    public synchronized int getHandshakeRecoveries() {
        return handshakeRecoveries;
    }

    public synchronized boolean isAuthenticated() {
        return phase == Phase.AUTHENTICATED;
    }

    public synchronized boolean isHandshakeInProgress() {
        return phase == Phase.SETTLING || phase == Phase.RECONNECT_WAIT || phase == Phase.CONNECTING || phase == Phase.LINK_UP;
    }

    /** True while the link is retrying a dropped authenticated session directly, before any rescan. */
    public synchronized boolean isFastReconnecting() {
        return fastReconnectAttempt > 0 && (phase == Phase.RECONNECT_WAIT || phase == Phase.CONNECTING || phase == Phase.LINK_UP);
    }

    static MissingNavAction missingNavAction(boolean pairedBefore, boolean cacheAlreadyRefreshed) {
        if (!pairedBefore) {
            return MissingNavAction.UNSUPPORTED;
        }
        return cacheAlreadyRefreshed ? MissingNavAction.RETRY : MissingNavAction.RETRY_WITH_CACHE_REFRESH;
    }

    static boolean shouldDropOnGattTimeout(Phase phase, boolean write, int consecutiveTimeouts) {
        if (phase == Phase.LINK_UP && write) {
            return true;
        }
        return consecutiveTimeouts >= GATT_TIMEOUTS_BEFORE_TEARDOWN;
    }

    public synchronized String getAddress() {
        return address;
    }

    public synchronized VehicleProfile getVehicleProfile() {
        if (profile == null) {
            return null;
        }
        Map<String, String> info = features.getVehicleInfo();
        return profile.withDeviceInfo(info.get("ModelNumber"), info.get("Manufacturer"),
                info.get("FirmwareRevision"), info.get("HardwareRevision"), info.get("SoftwareRevision"));
    }

    // --- Lifecycle ---

    public synchronized void start(String targetAddress, String targetName, Start mode, int initialRecoveries) {
        if (targetAddress == null || targetAddress.trim().isEmpty()) {
            return;
        }
        teardown(false);
        this.address = targetAddress.trim();
        this.name = (targetName != null && !targetName.trim().isEmpty()) ? targetName.trim() : this.address;
        this.handshakeRecoveries = Math.max(0, initialRecoveries);
        this.profile = null;
        this.gattCacheRefreshed = false;
        this.fastReconnectAttempt = 0;
        this.lastButtonSeq.set(features.getButtonEventSequence());
        AppLog.i(TAG, "Starting link to " + LogRedact.mac(address) + " (" + mode + ", recoveries=" + handshakeRecoveries + ")");
        switch (mode != null ? mode : Start.DIRECT) {
            case SCAN:
                beginSearch();
                break;
            case SETTLE:
                settle("bike reported present");
                break;
            default:
                connectNow();
                break;
        }
    }

    public synchronized void stop() {
        teardown(true);
        phase = Phase.STOPPED;
    }

    private void teardown(boolean silent) {
        cancelAllTimers();
        boolean wasAuthenticated = phase == Phase.AUTHENTICATED;
        if (phase == Phase.SCANNING) {
            radio.stopScan();
        }
        if (hasGatt()) {
            radio.closeGatt();
        }
        resetSession();
        phase = Phase.IDLE;
        if (!silent && wasAuthenticated) {
            report(ConnectionState.DISCONNECTED, "Disconnected");
        }
    }

    private boolean hasGatt() {
        return phase == Phase.CONNECTING || phase == Phase.LINK_UP || phase == Phase.AUTHENTICATED
                || (phase == Phase.UNSUPPORTED && !unsupportedGattClosed);
    }

    private void resetSession() {
        handshake = null;
        activeKey = null;
        sessionIv = null;
        authSetupStarted = false;
        unsupportedGattClosed = false;
        pendingInfoReads = 0;
        consecutiveGattTimeouts = 0;
        reselectThrottled = false;
        features.onDisconnected();
    }

    private void cancelAllTimers() {
        phaseTimer = cancel(phaseTimer);
        handshakeTimer = cancel(handshakeTimer);
        mtuTimer = cancel(mtuTimer);
        guidanceClearTimer = cancel(guidanceClearTimer);
        probeTimer = cancel(probeTimer);
        reselectTimer = cancel(reselectTimer);
    }

    private Object cancel(Object token) {
        if (token != null) {
            scheduler.cancel(token);
        }
        return null;
    }

    private void beginSearch() {
        phaseTimer = cancel(phaseTimer);
        if (bikeSeenDuringCooldown) {
            bikeSeenDuringCooldown = false;
            settle("bike reported present during cooldown");
            return;
        }
        if (radio.startScan(address)) {
            phase = Phase.SCANNING;
            AppLog.i(TAG, "Scanning for " + LogRedact.mac(address) + " before reconnecting");
            report(ConnectionState.CONNECTING, "Searching for " + name + "...");
        } else {
            AppLog.w(TAG, "BLE scan unavailable; settling then connecting directly");
            settle("scan unavailable");
        }
    }

    private void settle(String reason) {
        phaseTimer = cancel(phaseTimer);
        if (phase == Phase.SCANNING) {
            radio.stopScan();
        }
        phase = Phase.SETTLING;
        AppLog.i(TAG, "Bike present (" + reason + "); settling " + CONNECT_SETTLE_MS + " ms before connecting (dash boot grace)");
        report(ConnectionState.CONNECTING, "Found " + name + "; waiting for the dashboard to finish starting...");
        phaseTimer = schedule(() -> {
            if (phase == Phase.SETTLING) {
                connectNow();
            }
        }, CONNECT_SETTLE_MS);
    }

    private void connectNow() {
        connectNow(STUCK_CONNECTING_MS);
    }

    private void connectNow(long watchdogMs) {
        phaseTimer = cancel(phaseTimer);
        if (!radio.connectGatt(address)) {
            fastReconnectAttempt = 0;
            phase = Phase.WAITING_FOR_ADAPTER;
            report(ConnectionState.CONNECTING, "Waiting for Bluetooth to be enabled...");
            return;
        }
        phase = Phase.CONNECTING;
        if (fastReconnectAttempt > 0) {
            report(ConnectionState.CONNECTING, "Reconnecting to " + name + " (attempt " + fastReconnectAttempt + " of " + FAST_RECONNECT_DELAYS_MS.length + ")...");
        } else {
            report(ConnectionState.CONNECTING, "Connecting to " + name + " (BLE)...");
        }
        phaseTimer = schedule(() -> {
            if (phase != Phase.CONNECTING) {
                return;
            }
            radio.closeGatt();
            if (fastReconnectAttempt > 0) {
                AppLog.w(TAG, "Direct reconnect attempt " + fastReconnectAttempt + " produced no link in " + watchdogMs + " ms");
                startFastReconnect();
            } else {
                AppLog.w(TAG, "connectGatt produced no link in " + watchdogMs + " ms; closing and searching again");
                beginSearch();
            }
        }, watchdogMs);
    }

    /** Next step of the direct reconnect ladder, or hand over to the caller's search once it is exhausted. */
    private void startFastReconnect() {
        phaseTimer = cancel(phaseTimer);
        if (fastReconnectAttempt >= FAST_RECONNECT_DELAYS_MS.length) {
            AppLog.w(TAG, "Direct reconnect ladder exhausted after " + fastReconnectAttempt + " attempts; handing over to the search");
            fastReconnectAttempt = 0;
            phase = Phase.IDLE;
            report(ConnectionState.DISCONNECTED, "Disconnected from " + name);
            return;
        }
        long delay = FAST_RECONNECT_DELAYS_MS[fastReconnectAttempt++];
        phase = Phase.RECONNECT_WAIT;
        AppLog.i(TAG, "Link lost; direct reconnect attempt " + fastReconnectAttempt + " of " + FAST_RECONNECT_DELAYS_MS.length + " in " + delay + " ms");
        report(ConnectionState.CONNECTING, "Link lost; reconnecting to " + name + "...");
        phaseTimer = schedule(() -> {
            if (phase == Phase.RECONNECT_WAIT) {
                connectNow(FAST_CONNECT_TIMEOUT_MS);
            }
        }, delay);
    }

    private Object schedule(Runnable body, long delayMs) {
        return scheduler.schedule(() -> {
            synchronized (BccuLink.this) {
                if (phase != Phase.STOPPED) {
                    body.run();
                }
            }
        }, delayMs);
    }

    public synchronized void onScanResult(String foundAddress) {
        if (phase == Phase.SCANNING && foundAddress != null && foundAddress.equalsIgnoreCase(address)) {
            AppLog.i(TAG, "Scan hit for " + LogRedact.mac(address));
            settle("scan hit");
        }
    }

    public synchronized void onScanFailed(int errorCode) {
        if (phase != Phase.SCANNING) {
            return;
        }
        AppLog.w(TAG, "Scan failed (" + errorCode + "); retrying search in " + SCAN_RETRY_MS + " ms");
        phase = Phase.IDLE;
        phaseTimer = schedule(this::beginSearch, SCAN_RETRY_MS);
    }

    public synchronized void onBikeSeen(String source) {
        switch (phase) {
            case SCANNING:
            case IDLE:
            case WAITING_FOR_ADAPTER:
                settle(source);
                break;
            case COOLDOWN:
                bikeSeenDuringCooldown = true;
                break;
            case RECONNECT_WAIT:
                AppLog.i(TAG, "Bike reported present (" + source + ") during the direct reconnect wait; connecting now");
                connectNow(FAST_CONNECT_TIMEOUT_MS);
                break;
            default:
                break;
        }
    }

    public synchronized void onAdapterStateChanged(boolean on) {
        if (phase == Phase.STOPPED || address == null) {
            return;
        }
        if (!on) {
            boolean wasAuthenticated = phase == Phase.AUTHENTICATED;
            AppLog.w(TAG, "Bluetooth adapter turned off - forcing link down");
            cancelAllTimers();
            if (phase == Phase.SCANNING) {
                radio.stopScan();
            }
            if (hasGatt()) {
                radio.closeGatt();
            }
            resetSession();
            fastReconnectAttempt = 0;
            phase = Phase.WAITING_FOR_ADAPTER;
            if (wasAuthenticated) {
                handshakeRecoveries = 0;
                report(ConnectionState.DISCONNECTED, "Bluetooth is off");
            } else {
                report(ConnectionState.CONNECTING, "Waiting for Bluetooth to be enabled...");
            }
        } else if (phase == Phase.WAITING_FOR_ADAPTER) {
            AppLog.i(TAG, "Bluetooth adapter turned on - resuming reconnect search");
            beginSearch();
        }
    }

    // --- GATT events ---

    public synchronized void onLinkUp() {
        if (phase != Phase.CONNECTING) {
            return;
        }
        phaseTimer = cancel(phaseTimer);
        phase = Phase.LINK_UP;
        authSetupStarted = false;
        handshake = new BccuHandshake(new BccuHandshake.Host() {
            @Override
            public void writeAuthReply(byte[] value) {
                radio.write(BccuProtocol.AUTH_REPLY, value, false);
            }

            @Override
            public boolean hasPairedBefore() {
                return store.hasPairedBefore(address);
            }

            @Override
            public List<byte[]> loadKeyPool() {
                return store.loadKeyPool(address);
            }

            @Override
            public void storeKeyPool(List<byte[]> keys) {
                store.storeKeyPool(address, keys);
            }
        }, handshakeRecoveries);
        long budget = store.hasPairedBefore(address) ? HANDSHAKE_TIMEOUT_KNOWN_MS : HANDSHAKE_TIMEOUT_FIRST_PAIR_MS;
        handshakeTimer = cancel(handshakeTimer);
        handshakeTimer = schedule(() -> onHandshakeTimeout(budget), budget);
        AppLog.i(TAG, "GATT link up; handshake budget " + budget + " ms; discovering services");
        report(ConnectionState.CONNECTING, "Connected to GATT, discovering services...");
        if (!radio.discoverServices()) {
            failLink("discoverServices was rejected");
        }
    }

    public synchronized void onServicesDiscovered(boolean success) {
        if (phase != Phase.LINK_UP) {
            return;
        }
        profile = VehicleProfile.fromServices(radio.services());
        AppLog.i(TAG, "Services discovered (success=" + success + "): " + profile.describe());
        AppLog.d(TAG, radio.describeGatt());
        if (!profile.isSupported()) {
            if (!success && radio.services().isEmpty()) {
                failLink("service discovery failed");
                return;
            }
            MissingNavAction action = missingNavAction(store.hasPairedBefore(address), gattCacheRefreshed);
            if (action != MissingNavAction.UNSUPPORTED) {
                if (action == MissingNavAction.RETRY_WITH_CACHE_REFRESH) {
                    gattCacheRefreshed = true;
                    AppLog.i(TAG, "Refreshing the GATT cache before retrying: " + (radio.refreshGattCache() ? "requested" : "unavailable"));
                }
                failAttempt("Navigation service missing on a paired dash (stale GATT cache?)");
                return;
            }
            handshakeTimer = cancel(handshakeTimer);
            phase = Phase.UNSUPPORTED;
            AppLog.w(TAG, "Unsupported dash: no 0700 navigation service. GATT table: " + radio.describeGatt());
            List<UUID> info = radio.characteristics(BccuProtocol.DEVICE_INFO_SERVICE);
            pendingInfoReads = info.size();
            for (UUID c : info) {
                radio.read(c);
            }
            report(ConnectionState.ERROR, VehicleProfile.UNSUPPORTED_STATUS);
            if (pendingInfoReads == 0) {
                closeUnsupportedGatt();
            } else {
                phaseTimer = schedule(this::closeUnsupportedGatt, UNSUPPORTED_INFO_READ_MS);
            }
            return;
        }
        if (radio.requestMtu(MTU)) {
            mtuTimer = cancel(mtuTimer);
            mtuTimer = schedule(() -> {
                if (phase == Phase.LINK_UP) {
                    AppLog.w(TAG, "MTU callback timed out; enabling auth indication directly");
                    beginAuth();
                }
            }, MTU_CALLBACK_TIMEOUT_MS);
        } else {
            beginAuth();
        }
    }

    public synchronized void onMtuChanged(int mtu) {
        if (phase != Phase.LINK_UP) {
            return;
        }
        AppLog.i(TAG, "MTU changed to " + mtu);
        beginAuth();
    }

    private void beginAuth() {
        if (authSetupStarted || phase != Phase.LINK_UP) {
            return;
        }
        authSetupStarted = true;
        mtuTimer = cancel(mtuTimer);
        radio.requestHighPriority();
        report(ConnectionState.AUTHENTICATING, "Authenticating with " + name + "...");
        radio.subscribe(BccuProtocol.AUTH_REQUEST, true);
    }

    private void closeUnsupportedGatt() {
        phaseTimer = cancel(phaseTimer);
        if (phase != Phase.UNSUPPORTED || unsupportedGattClosed) {
            return;
        }
        unsupportedGattClosed = true;
        AppLog.i(TAG, "Device Information read; closing the unsupported dash's GATT connection");
        radio.closeGatt();
    }

    public synchronized void onLinkDown(int status) {
        if (phase != Phase.CONNECTING && phase != Phase.LINK_UP && phase != Phase.AUTHENTICATED && phase != Phase.UNSUPPORTED) {
            return;
        }
        if (phase == Phase.UNSUPPORTED && unsupportedGattClosed) {
            return;
        }
        boolean wasAuthenticated = phase == Phase.AUTHENTICATED;
        boolean unsupported = phase == Phase.UNSUPPORTED;
        boolean inLadder = fastReconnectAttempt > 0;
        AppLog.i(TAG, "GATT link down (status=" + status + ", phase=" + phase + ")");
        cancelAllTimers();
        radio.closeGatt();
        resetSession();
        phase = Phase.IDLE;
        if (wasAuthenticated) {
            handshakeRecoveries = 0;
            fastReconnectAttempt = 0;
            startFastReconnect();
        } else if (unsupported) {
            fastReconnectAttempt = 0;
            report(ConnectionState.ERROR, VehicleProfile.UNSUPPORTED_STATUS);
        } else if (inLadder) {
            startFastReconnect();
        } else {
            report(ConnectionState.CONNECTING, "Link dropped before pairing finished; searching again...");
            phaseTimer = schedule(this::beginSearch, LINK_LOSS_RESCAN_MS);
        }
    }

    public synchronized void onGattOperationTimeout(boolean write) {
        onGattOperationTimeout(write, true);
    }

    /**
     * A queued GATT operation produced no callback. Only control operations (auth, state, subscriptions,
     * requests) count towards tearing the link down; a slow dashboard display write is logged and ignored.
     */
    public synchronized void onGattOperationTimeout(boolean write, boolean control) {
        if (phase != Phase.AUTHENTICATED && phase != Phase.LINK_UP) {
            return;
        }
        if (!control) {
            AppLog.w(TAG, "Dashboard display write produced no callback; keeping the link");
            return;
        }
        consecutiveGattTimeouts++;
        if (shouldDropOnGattTimeout(phase, write, consecutiveGattTimeouts)) {
            AppLog.w(TAG, "GATT operation timed out (" + consecutiveGattTimeouts + " in a row, phase=" + phase + "); dropping the link");
            onLinkDown(-1);
        } else {
            AppLog.w(TAG, "GATT operation timed out (" + consecutiveGattTimeouts + " of " + GATT_TIMEOUTS_BEFORE_TEARDOWN + "); keeping the link");
        }
    }

    public synchronized void onGattOperationCompleted() {
        consecutiveGattTimeouts = 0;
    }

    private void failLink(String reason) {
        AppLog.w(TAG, "Link setup failed: " + reason);
        onLinkDown(-1);
    }

    private void onHandshakeTimeout(long budget) {
        if (phase != Phase.LINK_UP) {
            return;
        }
        failAttempt("Handshake did not complete in " + (budget / 1000) + " s");
    }

    private void failAttempt(String reason) {
        handshakeRecoveries++;
        long cooldown = handshakeRecoveries <= 1 ? RETRY_COOLDOWN_FIRST_MS : RETRY_COOLDOWN_LATER_MS;
        AppLog.w(TAG, reason + " - dropping the link, retrying in " + (cooldown / 1000) + " s (attempt "
                + handshakeRecoveries + "; pairing kept)");
        cancelAllTimers();
        radio.closeGatt();
        resetSession();
        fastReconnectAttempt = 0;
        phase = Phase.COOLDOWN;
        bikeSeenDuringCooldown = false;
        report(ConnectionState.CONNECTING, reason + "; retrying in " + (cooldown / 1000) + " s");
        phaseTimer = schedule(this::beginSearch, cooldown);
    }

    public synchronized void onCharacteristicChanged(UUID uuid, byte[] value) {
        if (uuid == null || value == null) {
            return;
        }
        if (BccuProtocol.AUTH_REQUEST.equals(uuid)) {
            onAuthRequest(value);
            return;
        }
        if (phase != Phase.AUTHENTICATED) {
            return;
        }
        if (BccuProtocol.TBT_NAV_REQUEST.equals(uuid)) {
            AppLog.d(TAG, "TBT_NAV_REQUEST notification (" + value.length + " bytes)");
            return;
        }
        features.onCharacteristic(uuid, value);
    }

    public synchronized void onCharacteristicRead(UUID uuid, byte[] value) {
        if (uuid != null && value != null && phase != Phase.STOPPED) {
            features.onCharacteristic(uuid, value);
        }
        if (phase == Phase.UNSUPPORTED && pendingInfoReads > 0 && --pendingInfoReads == 0) {
            closeUnsupportedGatt();
        }
    }

    public synchronized void onRssi(int rssi) {
        if (phase == Phase.AUTHENTICATED) {
            features.onRssi(rssi);
        }
    }

    private void onAuthRequest(byte[] value) {
        if (phase == Phase.AUTHENTICATED && handshake != null) {
            onSessionKeyReselect(value);
            return;
        }
        if (phase != Phase.LINK_UP || handshake == null) {
            return;
        }
        BccuHandshake.Result result = handshake.onAuthRequest(value);
        if (result == BccuHandshake.Result.AUTHENTICATED) {
            onAuthenticated();
        } else if (result == BccuHandshake.Result.KEY_POOL_MISSING) {
            // The handshake already told the dash we hold no keys; it normally re-runs HELLO within the
            // same connection. The handshake budget still fails the attempt if it does not.
            AppLog.w(TAG, "Dash selected a session key this phone does not have; waiting for it to restart pairing");
        }
    }

    private void onSessionKeyReselect(byte[] value) {
        if (reselectThrottled) {
            AppLog.d(TAG, "Throttling keepalive key re-selection");
            return;
        }
        reselectThrottled = true;
        reselectTimer = cancel(reselectTimer);
        reselectTimer = schedule(() -> reselectThrottled = false, KEY_RESELECT_THROTTLE_MS);
        BccuHandshake.Result result = handshake.onAuthRequest(value);
        if (result == BccuHandshake.Result.AUTHENTICATED) {
            activeKey = handshake.getActiveSessionKey();
            AppLog.i(TAG, "Dash switched the session key mid-session (index " + handshake.getActiveKeyIndex() + ")");
            features.beginSession(activeKey, sessionIv, profile != null && profile.hasPrpc());
        } else if (result == BccuHandshake.Result.KEY_POOL_MISSING) {
            AppLog.w(TAG, "Dash selected a session key mid-session that this phone does not have; keeping the current key");
        }
    }

    private void onAuthenticated() {
        handshakeTimer = cancel(handshakeTimer);
        phaseTimer = cancel(phaseTimer);
        activeKey = handshake.getActiveSessionKey();
        sessionIv = handshake.getTempIv();
        boolean resumed = fastReconnectAttempt > 0;
        handshakeRecoveries = 0;
        fastReconnectAttempt = 0;
        phase = Phase.AUTHENTICATED;
        store.markPairedBefore(address);
        report(ConnectionState.CONNECTED, (resumed ? "Reconnected to " : "Connected to ") + name);

        boolean prpc = profile != null && profile.hasPrpc();
        features.beginSession(activeKey, sessionIv, prpc);
        features.subscribeRemoteControl();
        radio.subscribe(BccuProtocol.TBT_NAV_REQUEST, false);
        writeData(BccuProtocol.NAVIGATION_STATE,
                BccuProtocol.INSTANCE.buildNavigationStatePayload(true, true, NAV_STATE_VOLUME_UNSET), false);
        sendGreeting();
        // Everything the rider does not see is probed later so the handshake tail and the first guidance
        // writes are not queued behind dozens of reads with their own timeouts.
        probeTimer = cancel(probeTimer);
        probeTimer = schedule(this::probeAfterAuth, POST_AUTH_PROBE_DELAY_MS);
    }

    private void probeAfterAuth() {
        probeTimer = cancel(probeTimer);
        if (phase != Phase.AUTHENTICATED) {
            return;
        }
        features.startRssiPolling();
        List<UUID> dis = radio.characteristics(BccuProtocol.DEVICE_INFO_SERVICE);
        features.probeVehicleInfo(dis.isEmpty() ? null : dis, profile == null || profile.hasBase());
        probeMysteryService(BccuProtocol.MYSTERY_SERVICE_0200);
        probeMysteryService(BccuProtocol.MYSTERY_SERVICE_0300);
        features.startTelemetry();
    }

    private void probeMysteryService(UUID service) {
        for (UUID c : radio.characteristics(service)) {
            int props = radio.properties(c);
            if ((props & PROPERTY_NOTIFY) != 0) {
                radio.subscribe(c, false);
            } else if ((props & PROPERTY_INDICATE) != 0) {
                radio.subscribe(c, true);
            }
            if ((props & PROPERTY_READ) != 0) {
                radio.read(c);
            }
        }
    }

    private void onFeaturesChanged() {
        long seq = features.getButtonEventSequence();
        String button = features.getLastButton();
        long previous = lastButtonSeq.getAndAccumulate(seq, Math::max);
        if (seq > previous && button != null) {
            listener.onButton(button);
        }
        listener.onFeaturesChanged();
    }

    private void report(ConnectionState state, String detail) {
        reportedState = state;
        listener.onState(state, detail);
    }

    public synchronized ConnectionState getReportedState() {
        return reportedState;
    }

    // --- Dashboard writes (authenticated only) ---

    private boolean ready() {
        return phase == Phase.AUTHENTICATED && activeKey != null && sessionIv != null;
    }

    private void writeData(UUID characteristic, byte[] payload, boolean coalesce) {
        radio.write(characteristic, BccuCrypto.INSTANCE.encryptData(payload, activeKey, sessionIv), coalesce);
    }

    private void sendGreeting() {
        String user = store.userName();
        String text = (user == null || user.trim().isEmpty()) ? "Welcome to KTMnav" : "Hi " + user.trim() + "!";
        BccuProtocol p = BccuProtocol.INSTANCE;
        writeData(BccuProtocol.TURN_ICON, p.buildTurnIconPayload(TurnIcon.START, BccuProtocol.Visibility.FULL), true);
        writeData(BccuProtocol.TURN_ROAD, p.buildTurnRoadPayload(text, BccuProtocol.Visibility.FULL), true);
        writeData(BccuProtocol.TURN_DISTANCE, p.buildTurnDistancePayload("", BccuProtocol.Visibility.OFF), true);
        guidanceClearTimer = cancel(guidanceClearTimer);
        guidanceClearTimer = schedule(this::clearGuidanceNow, GREETING_MS);
    }

    public synchronized void sendGuidance(String distanceText, String roadText, String etaText, String remainingText) {
        if (!ready()) {
            return;
        }
        guidanceClearTimer = cancel(guidanceClearTimer);
        BccuProtocol p = BccuProtocol.INSTANCE;
        BccuProtocol.Visibility full = BccuProtocol.Visibility.FULL;
        if (distanceText != null) {
            writeData(BccuProtocol.TURN_DISTANCE, p.buildTurnDistancePayload(distanceText, full), true);
        }
        if (roadText != null) {
            writeData(BccuProtocol.TURN_ROAD, p.buildTurnRoadPayload(roadText, full), true);
        }
        if (etaText != null) {
            String eta = DashText.normalizeEta(etaText);
            writeData(BccuProtocol.ETA, eta != null ? p.buildEtaPayload(eta, full)
                    : p.buildEtaPayload("", BccuProtocol.Visibility.OFF), true);
        }
        if (remainingText != null) {
            writeData(BccuProtocol.REMAINING_DISTANCE, p.buildRemainingDistancePayload(remainingText, full), true);
        }
    }

    public synchronized void sendTurnIcon(TurnIcon icon) {
        if (!ready()) {
            return;
        }
        BccuProtocol.Visibility vis = (icon == null || icon == TurnIcon.UNDEFINED || icon == TurnIcon.UNKNOWN)
                ? BccuProtocol.Visibility.OFF : BccuProtocol.Visibility.FULL;
        writeData(BccuProtocol.TURN_ICON, BccuProtocol.INSTANCE.buildTurnIconPayload(icon, vis), true);
    }

    public synchronized void clearGuidance() {
        if (!ready()) {
            return;
        }
        guidanceClearTimer = cancel(guidanceClearTimer);
        guidanceClearTimer = schedule(this::clearGuidanceNow, GUIDANCE_CLEAR_DELAY_MS);
    }

    public synchronized void clearGuidanceNow() {
        guidanceClearTimer = cancel(guidanceClearTimer);
        if (!ready()) {
            return;
        }
        BccuProtocol p = BccuProtocol.INSTANCE;
        BccuProtocol.Visibility off = BccuProtocol.Visibility.OFF;
        writeData(BccuProtocol.TURN_ICON, p.buildTurnIconPayload(TurnIcon.UNDEFINED, off), true);
        writeData(BccuProtocol.TURN_DISTANCE, p.buildTurnDistancePayload("", off), true);
        writeData(BccuProtocol.TURN_INFO, p.buildTurnInfoPayload("", off), true);
        writeData(BccuProtocol.TURN_ROAD, p.buildTurnRoadPayload("", off), true);
        writeData(BccuProtocol.ETA, p.buildEtaPayload("", off), true);
        writeData(BccuProtocol.REMAINING_DISTANCE, p.buildRemainingDistancePayload("", off), true);
    }

    public boolean showBanner(String owner, String text, int priority, long holdMs, BccuProtocol.NotificationIcon icon) {
        synchronized (this) {
            if (!ready()) {
                return false;
            }
        }
        return features.showBanner(owner, text, priority, holdMs, icon);
    }

    public void clearBanner(String owner) {
        features.clearBanner(owner);
    }

    public synchronized boolean sendSymbolTest(int turnIconId) {
        if (!ready()) {
            return false;
        }
        guidanceClearTimer = cancel(guidanceClearTimer);
        int id = turnIconId & 0xFF;
        BccuProtocol p = BccuProtocol.INSTANCE;
        writeData(BccuProtocol.TURN_ICON, p.buildTurnIconPayload(id, BccuProtocol.Visibility.FULL), true);
        writeData(BccuProtocol.TURN_ROAD, p.buildTurnRoadPayload("Icon " + id, BccuProtocol.Visibility.FULL), true);
        return true;
    }

    public boolean sendBannerIconTest(int notificationIconId) {
        synchronized (this) {
            if (!ready()) {
                return false;
            }
        }
        return features.sendBannerIconTest(notificationIconId);
    }

    public void endSymbolTest() {
        clearGuidanceNow();
        features.endBannerTest();
    }
}

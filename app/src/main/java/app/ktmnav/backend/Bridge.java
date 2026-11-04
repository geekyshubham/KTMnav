package app.ktmnav.backend;

import android.app.Activity;
import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Public singleton controller that coordinates motorcycle transports, navigation sources,
 * idle displays, preferences, offloaded zero-power detection, provisional pairing rollback,
 * and UI updates.
 */
public final class Bridge implements BikeLink.ConnectionStateListener {
    private static final String TAG = "KTMnav.Bridge";
    private static final long PROVISIONAL_TIMEOUT_MS = 120000L;
    private static final long FIRST_RECONNECT_DELAY_MS = 1500L;

    public static final int BANNER_PRIORITY_INFO = DashBannerArbiter.PRIORITY_INFO;
    public static final int BANNER_PRIORITY_TRACK = DashBannerArbiter.PRIORITY_TRACK;
    public static final int BANNER_PRIORITY_MESSAGE = DashBannerArbiter.PRIORITY_MESSAGE;
    public static final int BANNER_PRIORITY_CALL = DashBannerArbiter.PRIORITY_CALL;
    public static final int BANNER_PRIORITY_ALERT = DashBannerArbiter.PRIORITY_ALERT;
    private static volatile Bridge instance;

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final BikeManager bikeManager;
    private final IdleDisplayManager idleDisplayManager;
    private final LocusMapSource locusMapSource;
    private final CoMapsNavSource coMapsNavSource;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<HandlebarMaskListener> maskListeners = new CopyOnWriteArrayList<>();
    private String carriedRecoveriesAddress;
    private int carriedRecoveries;

    private BikeLink activeTransport;
    private BikeLink.ConnectionStateListener activeStateListener;
    private String activeAddress;
    private int transportGeneration = 0;

    private ConnectionState connectionState = ConnectionState.DISCONNECTED;
    private String statusDetail = "Disconnected";
    private volatile boolean isAppInForeground = false;
    private long lastHandledButtonSequence = 0L;
    private final HandlebarController handlebarController;
    private volatile String lastHandlebarActionResult;
    private final FeatureWiring features;

    // Provisional pairing rollback state (matching original TurnIt OpenDashBikeLink)
    private boolean provisional = false;
    private String restoreAddress;
    private String restoreName;
    private String restoreGen;

    private String currentDistance;
    private String currentRoad;
    private String currentSource = "DASHBOARD";
    private String currentEta;
    private String currentRemaining;
    private String currentTurn = "IDLE";
    private String lastSourcePackage;

    private NavUpdate lastActiveUpdate;
    private String lastActiveSourcePkg;

    private Runnable reconnectRunnable;
    private int reconnectAttempts = 0;

    private final Runnable restoreAfterTestRunnable = this::restoreAfterTest;
    private BikeLink testTargetTransport;

    private final Runnable provisionalTimeoutRunnable = this::onProvisionalTimeout;
    private final Runnable idleWatcherRunnable = this::onIdleTimeoutReached;
    private boolean idleWatcherArmed = false;
    private long idleWatcherArmedAt;

    public interface Listener {
        void onChanged();
    }

    public static final class Bike {
        public final String address;
        public final String name;
        public final String generation; // Canonical "GEN2" or "GEN3"
        /** Rider-chosen catalogue model or style (for the Garage silhouette), or null. */
        public final String model;

        public Bike(String address, String name, String generation) {
            this(address, name, generation, null);
        }

        public Bike(String address, String name, String generation, String model) {
            this.address = address;
            this.name = name;
            this.generation = BikeGeneration.normalize(generation);
            this.model = model == null || model.trim().isEmpty() ? null : model.trim();
        }

        public BikeGeneration getBikeGeneration() {
            return BikeGeneration.from(generation);
        }

        /** Silhouette style: the chosen model first, otherwise a guess from the dashboard name. */
        public BikeCatalog.Style getStyle() {
            BikeCatalog.Style fromModel = model != null ? BikeCatalog.guess(model) : BikeCatalog.Style.GENERIC;
            if (fromModel == BikeCatalog.Style.GENERIC && model != null) {
                fromModel = BikeCatalog.Style.fromPreference(model);
            }
            return fromModel != BikeCatalog.Style.GENERIC ? fromModel : BikeCatalog.guess(name);
        }

        @Override
        public String toString() {
            return name + " (" + address + ", " + generation + (model != null ? ", " + model : "") + ")";
        }
    }

    public synchronized void setBikeModel(String address, String model) {
        bikeManager.setBikeModel(address, model);
        notifyChanged();
    }

    private Bridge(Context context) {
        this.appContext = context.getApplicationContext();
        this.bikeManager = new BikeManager(appContext);
        this.idleDisplayManager = new IdleDisplayManager(appContext);
        this.locusMapSource = new LocusMapSource(appContext);
        this.coMapsNavSource = new CoMapsNavSource(appContext);
        this.handlebarController = new HandlebarController(appContext);
        this.features = new FeatureWiring(this, appContext, handlebarController, summary -> {
            lastHandlebarActionResult = summary;
            AppLog.i(TAG, "Handlebar: " + summary);
            notifyChanged();
        }, this::handleHandlebarBack);

        AppLog.i(TAG, "Bridge initialized");
    }

    public static synchronized Bridge get(Context context) {
        if (instance == null) {
            Bridge b = new Bridge(context);
            instance = b;
            b.init();
        }
        return instance;
    }

    private void init() {
        idleDisplayManager.applySettings();
        features.start();
        registerActivityLifecycle();

        if (bikeManager.hasProvisionalRestore()) {
            AppLog.i(TAG, "Unconfirmed provisional pairing found on boot - rolling back");
            bikeManager.rollbackProvisionalRestore();
        }

        if (isDesiredConnect() && getPrefs().getBoolean("auto_connect", true) && getPrefs().getBoolean("keep_background", true)) {
            Bike active = getActiveBike();
            if (active != null && BikeGeneration.from(active.generation) == BikeGeneration.GEN3) {
                BikeDetector.arm(appContext);
            }
        }
    }

    private void registerActivityLifecycle() {
        if (appContext instanceof Application) {
            ((Application) appContext).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                private int startedActivities = 0;

                @Override
                public void onActivityStarted(Activity activity) {
                    startedActivities++;
                    isAppInForeground = true;
                    cancelIdleWatcher();
                    BridgeService.refreshForegroundType(appContext);
                }

                @Override
                public void onActivityStopped(Activity activity) {
                    startedActivities--;
                    if (startedActivities <= 0) {
                        startedActivities = 0;
                        isAppInForeground = false;
                        if (activity != null && activity.isChangingConfigurations()) {
                            return;
                        }
                        if (!getPrefs().getBoolean("keep_background", true)) {
                            AppLog.i(TAG, "App left foreground with keep_background=false; disconnecting");
                            disconnect();
                        } else if (isDesiredConnect() && getPrefs().getBoolean("auto_connect", true) && !isConnected()) {
                            scheduleIdleWatcher();
                        }
                    }
                }

                @Override public void onActivityCreated(Activity a, Bundle b) {}
                @Override public void onActivityResumed(Activity a) {}
                @Override public void onActivityPaused(Activity a) {}
                @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
                @Override public void onActivityDestroyed(Activity a) {}
            });
        }
    }

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    public void notifyChanged() {
        mainHandler.post(() -> {
            for (Listener l : listeners) {
                try {
                    l.onChanged();
                } catch (Throwable t) {
                    Log.w(TAG, "Listener onChanged error", t);
                }
            }
        });
    }

    // --- State and Connection Getters ---

    public synchronized String getState() {
        return connectionState.getDisplayState();
    }

    public synchronized boolean isConnected() {
        return connectionState == ConnectionState.CONNECTED;
    }

    public synchronized String getStatusDetail() {
        return statusDetail != null ? statusDetail : connectionState.getDisplayState();
    }

    // --- Navigation & Dashboard Values ---

    public synchronized String getDistance() {
        return currentDistance;
    }

    public synchronized String getRoad() {
        return currentRoad;
    }

    public synchronized String getSource() {
        return currentSource;
    }

    public synchronized String getEta() {
        return currentEta;
    }

    public synchronized String getRemaining() {
        return currentRemaining;
    }

    public synchronized String getTurn() {
        return currentTurn;
    }

    public synchronized String getMedia() {
        return idleDisplayManager.getCurrentMediaText();
    }

    public String getLogs() {
        return AppLog.getLogs();
    }

    public SharedPreferences getPrefs() {
        return bikeManager.getPrefs();
    }

    IdleDisplayManager getIdleDisplayManager() {
        return idleDisplayManager;
    }

    // --- Bike Management & Generation Handling ---

    public Bike getActiveBike() {
        return bikeManager.getActiveBike();
    }

    public List<Bike> getBikes() {
        return bikeManager.getAllBikes();
    }

    public BikeGeneration getBikeGeneration(String address) {
        return bikeManager.getBikeGeneration(address);
    }

    public synchronized void selectBike(String address) {
        if (provisional) {
            restorePreviousBond();
        }
        Bike current = getActiveBike();
        String currentAddr = current != null ? current.address : activeAddress;
        String newAddr = address != null ? address.trim() : "";

        if (!newAddr.isEmpty() && currentAddr != null && !currentAddr.equalsIgnoreCase(newAddr)) {
            AppLog.i(TAG, "Active bike switched from " + LogRedact.mac(currentAddr) + " to " + LogRedact.mac(newAddr) + "; disconnecting previous bike and disarming detector");
            disconnect();
        } else {
            cancelIdleWatcher();
        }
        bikeManager.selectBike(address);
        notifyChanged();
    }

    public synchronized void saveBike(String address, String name, String generation) {
        Bike prev = getActiveBike();
        String prevAddress = prev != null ? prev.address : activeAddress;
        String prevGen = (prev != null) ? prev.generation : BikeGeneration.CANONICAL_GEN3;
        String newGen = BikeGeneration.normalize(generation);
        String cleanAddr = address != null ? address.trim() : "";

        if (!cleanAddr.isEmpty() && prevAddress != null) {
            boolean addressDiffers = !prevAddress.equalsIgnoreCase(cleanAddr);
            boolean genDiffers = !prevGen.equals(newGen);
            if (addressDiffers || genDiffers) {
                AppLog.i(TAG, "Active bike address or generation changed; disconnecting and disarming previous bike");
                disconnect();
            }
        }
        cancelIdleWatcher();
        BikeDetector.disarm(appContext);
        bikeManager.saveBike(address, name, generation);
        notifyChanged();
    }

    public synchronized void renameBike(String address, String name) {
        bikeManager.renameBike(address, name);
        notifyChanged();
    }

    public synchronized void forgetBike(String address) {
        Bike active = getActiveBike();
        String activeAddr = active != null ? active.address : activeAddress;
        if (address != null && activeAddr != null && activeAddr.equalsIgnoreCase(address.trim())) {
            if (provisional) {
                cancelProvisionalTimeout();
                provisional = false;
                restoreAddress = null;
                restoreName = null;
                restoreGen = null;
                bikeManager.clearProvisionalRestore();
            }
            disconnect();
        }
        cancelIdleWatcher();
        BikeDetector.disarm(appContext);
        bikeManager.forgetBike(address);
        if (address != null && address.trim().equalsIgnoreCase(carriedRecoveriesAddress)) {
            carriedRecoveriesAddress = null;
            carriedRecoveries = 0;
        }
        notifyChanged();
    }

    // --- Connect / Disconnect & Provisional Pairing ---

    public synchronized void connect(String deviceAddress, String deviceName, BikeGeneration generation) {
        connect(deviceAddress, deviceName, generation != null ? generation.name() : null);
    }

    public synchronized void connect(String deviceAddress, String deviceName) {
        connect(deviceAddress, deviceName, (String) null);
    }

    private synchronized void connect(String deviceAddress, String deviceName, String generationOverride) {
        if (deviceAddress == null || deviceAddress.trim().isEmpty()) {
            return;
        }
        String cleanAddr = deviceAddress.trim();
        Bike currentActive = getActiveBike();
        String bondedAddress = currentActive != null ? currentActive.address : null;
        boolean isSame = cleanAddr.equalsIgnoreCase(bondedAddress);

        if (!isSame) {
            if (!provisional) {
                if (currentActive != null) {
                    this.restoreAddress = currentActive.address;
                    this.restoreName = currentActive.name;
                    this.restoreGen = currentActive.generation;
                    bikeManager.setProvisionalRestore(this.restoreAddress, this.restoreName, this.restoreGen);
                } else {
                    this.restoreAddress = null;
                    this.restoreName = null;
                    this.restoreGen = null;
                    bikeManager.clearProvisionalRestore();
                }
            }
            this.provisional = true;
            String gen = generationOverride != null ? BikeGeneration.normalize(generationOverride)
                    : bikeManager.getBikeGeneration(cleanAddr).name();
            bikeManager.saveBike(cleanAddr, deviceName, gen);
            armProvisionalTimeout();
        } else {
            if (generationOverride != null) {
                bikeManager.saveBike(cleanAddr, deviceName, BikeGeneration.normalize(generationOverride));
            }
            if (!provisional) {
                cancelProvisionalTimeout();
            }
        }
        connect();
    }

    public synchronized void connect() {
        connectWithMode(BccuLink.Start.DIRECT, "user request");
    }

    public synchronized void connectInBackground() {
        connectWithMode(BccuLink.Start.SCAN, "background reconnect");
    }

    public synchronized void connectAfterDetection() {
        connectWithMode(BccuLink.Start.SETTLE, "offloaded scan detected the bike");
    }

    public synchronized void onBikeAclConnected() {
        connectWithMode(BccuLink.Start.SETTLE, "Classic ACL connected");
    }

    public synchronized boolean onBluetoothStateChanged(boolean on) {
        if (activeTransport instanceof BccuBleTransport && isDesiredConnect()) {
            ((BccuBleTransport) activeTransport).onAdapterStateChanged(on);
            return true;
        }
        return false;
    }

    private synchronized void connectWithMode(BccuLink.Start mode, String reason) {
        Bike target = getActiveBike();
        if (mode != BccuLink.Start.DIRECT && target != null && activeTransport instanceof BccuBleTransport
                && target.address.equalsIgnoreCase(activeAddress)) {
            ConnectionState ts = activeTransport.getConnectionState();
            if (ts == ConnectionState.CONNECTED || ts == ConnectionState.AUTHENTICATING) {
                AppLog.d(TAG, "Ignoring " + reason + ": link already " + ts);
                return;
            }
            if (ts == ConnectionState.CONNECTING) {
                AppLog.i(TAG, reason + " - nudging the active link");
                ((BccuBleTransport) activeTransport).onBikeSeen(reason);
                return;
            }
        }
        cancelAutoReconnect();
        cancelTestGuidanceRestore();
        BikeDetector.disarm(appContext);
        persistDesiredConnect(true);

        Bike active = getActiveBike();
        if (active == null) {
            setState(ConnectionState.ERROR, "No motorcycle configured");
            AppLog.w(TAG, "Connect aborted: no active bike");
            return;
        }

        if (activeTransport != null && activeTransport.getConnectionState() == ConnectionState.CONNECTED
                && active.address.equalsIgnoreCase(activeAddress)) {
            AppLog.d(TAG, "Already connected to " + active.name);
            return;
        }

        boolean wasConnected = activeTransport != null && connectionState == ConnectionState.CONNECTED;
        retireActiveTransport();
        if (wasConnected) {
            stopProviders();
            features.onBikeDisconnected();
            idleDisplayManager.onConnectionStateChanged(ConnectionState.DISCONNECTED);
            clearGuidanceFields();
        }

        AppLog.i(TAG, "Connecting to " + LogRedact.mac(active.address) + " (" + active.generation + ")");
        setState(ConnectionState.CONNECTING, "Connecting to " + active.name);

        final int generation = ++transportGeneration;
        activeAddress = active.address;

        final BikeLink transport;
        if (BikeGeneration.from(active.generation) == BikeGeneration.GEN2) {
            transport = new Gen2RfcommTransport(appContext);
        } else {
            BccuBleTransport ble = new BccuBleTransport(appContext, bikeManager);
            ble.setOnHandlebarMaskListener((mask, uptimeMs) -> {
                synchronized (Bridge.this) {
                    if (generation != transportGeneration || activeTransport != ble) {
                        return;
                    }
                }
                for (HandlebarMaskListener l : maskListeners) {
                    try {
                        l.onMask(mask, uptimeMs);
                    } catch (RuntimeException e) {
                        Log.w(TAG, "Handlebar mask listener error", e);
                    }
                }
            });
            ble.setOnHandlebarButtonListener(buttonName -> {
                synchronized (Bridge.this) {
                    if (generation != transportGeneration || activeTransport != ble || !isConnected()) {
                        return;
                    }
                    notifyChanged();
                }
            });
            ble.setOnFeaturesChangedListener(() -> {
                synchronized (Bridge.this) {
                    if (generation != transportGeneration || activeTransport != ble) {
                        return;
                    }
                    handleBleFeaturesChanged(ble);
                }
            });
            transport = ble;
        }
        activeTransport = transport;

        activeStateListener = (state, detail) -> {
            mainHandler.post(() -> {
                synchronized (Bridge.this) {
                    if (generation != transportGeneration || activeTransport != transport) {
                        AppLog.d(TAG, "Stale callback suppressed: " + state + " (" + detail + ")");
                        return;
                    }
                    if (transport.getConnectionState() != state) {
                        AppLog.d(TAG, "Stale callback suppressed by transport state mismatch: queued=" + state + ", current=" + transport.getConnectionState());
                        return;
                    }
                    handleTransportStateChanged(state, detail);
                }
            });
        };
        transport.addConnectionStateListener(activeStateListener);

        try {
            if (transport instanceof BccuBleTransport) {
                int recoveries = active.address.equalsIgnoreCase(carriedRecoveriesAddress) ? carriedRecoveries : 0;
                AppLog.i(TAG, "Gen 3 link start: " + mode + " (" + reason + ")");
                ((BccuBleTransport) transport).connect(active.address, active.name, mode, recoveries);
            } else {
                transport.connect(active.address, active.name);
            }
        } catch (SecurityException e) {
            AppLog.w(TAG, "Permission missing for connect", e);
            setState(ConnectionState.ERROR, "Bluetooth permission missing");
            return;
        } catch (Exception e) {
            AppLog.w(TAG, "Connect failed", e);
            setState(ConnectionState.ERROR, "Connect failed: " + e.getMessage());
            return;
        }

        if (getPrefs().getBoolean("keep_background", true)) {
            BridgeService.start(appContext);
        }
        if (!isAppInForeground && getPrefs().getBoolean("keep_background", true) && getPrefs().getBoolean("auto_connect", true)) {
            scheduleIdleWatcher();
        }
    }

    public synchronized void disconnect() {
        AppLog.i(TAG, "Disconnect requested");
        cancelAutoReconnect();
        cancelTestGuidanceRestore();
        cancelIdleWatcher();
        persistDesiredConnect(false);

        if (provisional) {
            restorePreviousBond();
        }

        retireActiveTransport();

        setState(ConnectionState.DISCONNECTED, "Disconnected");

        stopProviders();
        features.onBikeDisconnected();
        idleDisplayManager.onConnectionStateChanged(ConnectionState.DISCONNECTED);

        clearGuidanceFields();
        lastActiveUpdate = null;
        lastActiveSourcePkg = null;

        BikeDetector.disarm(appContext);

        // Explicit disconnect ALWAYS stops service
        BridgeService.stop(appContext);
    }

    public synchronized boolean isProvisional() {
        return provisional;
    }

    private void armProvisionalTimeout() {
        cancelProvisionalTimeout();
        mainHandler.postDelayed(provisionalTimeoutRunnable, PROVISIONAL_TIMEOUT_MS);
    }

    private void cancelProvisionalTimeout() {
        mainHandler.removeCallbacks(provisionalTimeoutRunnable);
    }

    private synchronized void onProvisionalTimeout() {
        if (connectionState == ConnectionState.CONNECTED) {
            confirmProvisionalPairing();
        } else {
            AppLog.w(TAG, "Provisional pairing timed out after 30s without authentication - restoring previous bond");
            restorePreviousBond();
            retireActiveTransport();
            setState(ConnectionState.ERROR, "Pairing timed out (dash confirmation not completed)");
            BridgeService.stop(appContext);

            Bike active = getActiveBike();
            if (active != null && BikeGeneration.from(active.generation) == BikeGeneration.GEN3
                    && getPrefs().getBoolean("keep_background", true)
                    && getPrefs().getBoolean("auto_connect", true)
                    && getPrefs().getBoolean("desired_connect", false)) {
                BikeDetector.arm(appContext);
            }
        }
    }

    private synchronized void confirmProvisionalPairing() {
        if (provisional) {
            Bike active = getActiveBike();
            if (active != null) {
                bikeManager.removeInactiveBike(active.address);
            }
            if (restoreAddress != null && (active == null || !restoreAddress.equalsIgnoreCase(active.address))) {
                bikeManager.addInactiveBike(restoreAddress, restoreName, restoreGen);
            }
            provisional = false;
            restoreAddress = null;
            restoreName = null;
            restoreGen = null;
            bikeManager.clearProvisionalRestore();
        }
        cancelProvisionalTimeout();
    }

    private synchronized void restorePreviousBond() {
        bikeManager.rollbackProvisionalRestore();
        provisional = false;
        restoreAddress = null;
        restoreName = null;
        restoreGen = null;
        cancelProvisionalTimeout();
    }

    private synchronized void retireActiveTransport() {
        cancelTestGuidanceRestore();
        lastHandledButtonSequence = 0L;
        if (activeTransport != null) {
            BikeLink t = activeTransport;
            BikeLink.ConnectionStateListener l = activeStateListener;
            String retiredAddress = activeAddress;
            activeTransport = null;
            activeAddress = null;
            activeStateListener = null;
            if (t instanceof BccuBleTransport) {
                BccuBleTransport ble = (BccuBleTransport) t;
                ble.setOnHandlebarButtonListener(null);
                ble.setOnFeaturesChangedListener(null);
                ble.setOnHandlebarMaskListener(null);
                if (ble.getConnectionState() != ConnectionState.CONNECTED && ble.getHandshakeRecoveries() > 0) {
                    carriedRecoveriesAddress = retiredAddress;
                    carriedRecoveries = ble.getHandshakeRecoveries();
                }
            }
            if (l != null) {
                try {
                    t.removeConnectionStateListener(l);
                } catch (Exception ignored) {}
            }
            try {
                t.removeConnectionStateListener(this);
            } catch (Exception ignored) {}
            retireTransport(t);
        }
    }

    private void retireTransport(BikeLink transport) {
        if (transport == null) return;
        try {
            if (activeStateListener != null) {
                transport.removeConnectionStateListener(activeStateListener);
            }
        } catch (Exception ignored) {}
        try {
            transport.removeConnectionStateListener(this);
        } catch (Exception ignored) {}
        try {
            if (transport instanceof BccuBleTransport) {
                ((BccuBleTransport) transport).shutdown();
            } else if (transport instanceof Gen2RfcommTransport) {
                ((Gen2RfcommTransport) transport).shutdown();
            } else {
                transport.disconnect();
            }
        } catch (Throwable t) {
            try {
                transport.disconnect();
            } catch (Exception ignored) {}
        }
    }

    @Override
    public synchronized void onConnectionStateChanged(ConnectionState state, String statusDetail) {
        handleTransportStateChanged(state, statusDetail);
    }

    private synchronized void handleTransportStateChanged(ConnectionState state, String detail) {
        if (!isDesiredConnect()) {
            AppLog.d(TAG, "Dropping transport state change (" + state + ") because desiredConnect is false");
            retireActiveTransport();
            setState(ConnectionState.DISCONNECTED, "Disconnected");
            return;
        }

        if (state == ConnectionState.CONNECTED) {
            carriedRecoveriesAddress = null;
            carriedRecoveries = 0;
            cancelIdleWatcher();
            confirmProvisionalPairing();
            BikeDetector.disarm(appContext);
        }

        setState(state, detail);

        if (state == ConnectionState.CONNECTED) {
            cancelAutoReconnect();
            startProviders();
            idleDisplayManager.onConnectionStateChanged(state);
            if (lastActiveUpdate != null) {
                AppLog.i(TAG, "Restoring active route after reconnect (" + LogRedact.nav(lastActiveUpdate) + ")");
                idleDisplayManager.onNavGuidance(lastActiveUpdate);
            }
            features.onBikeConnected();
            Bike active = getActiveBike();
            BridgeService.update(appContext, active != null ? active.name : "KTMnav", "Connected to " + (active != null ? active.name : "motorcycle"));
        } else if (state == ConnectionState.CONNECTING || state == ConnectionState.AUTHENTICATING) {
            Bike active = getActiveBike();
            BridgeService.update(appContext, active != null ? active.name : "KTMnav", detail != null ? detail : state.getDisplayState());
        } else if (state == ConnectionState.DISCONNECTED || state == ConnectionState.ERROR) {
            stopProviders();
            features.onBikeDisconnected();
            idleDisplayManager.onConnectionStateChanged(state);
            clearGuidanceFields();
            BridgeService.update(appContext, "KTMnav", detail != null ? detail : state.getDisplayState());

            // Handle unexpected link loss retry per auto_connect and schedule idle watcher if background
            boolean unsupported = activeTransport instanceof BccuBleTransport && ((BccuBleTransport) activeTransport).isUnsupportedDash();
            if (unsupported) {
                AppLog.w(TAG, "Dashboard has no KTM navigation service; not retrying automatically");
            } else if (isDesiredConnect() && getPrefs().getBoolean("auto_connect", true) && getPrefs().getBoolean("keep_background", true)) {
                if (!isAppInForeground) {
                    scheduleIdleWatcher();
                }
                scheduleAutoReconnect();
            }
        }
    }

    private synchronized void handleBleFeaturesChanged(BccuBleTransport ble) {
        notifyChanged();
    }

    private String handleHandlebarBack() {
        if (!getPrefs().getBoolean("handlebar_return", true)) {
            AppLog.d(TAG, "Handlebar BACK ignored - handlebar_return is disabled");
            return "Ignored: handlebar_return disabled";
        }
        if (isAppInForeground) {
            AppLog.d(TAG, "Handlebar BACK ignored - app is already in foreground");
            return "Ignored: app already in foreground";
        }
        AppLog.i(TAG, "Handlebar BACK received while backgrounded; requesting MainActivity relaunch");
        try {
            Intent intent = appContext.getPackageManager().getLaunchIntentForPackage(appContext.getPackageName());
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                appContext.startActivity(intent);
                return "Requested KTMnav";
            } else {
                return "Launch intent not found";
            }
        } catch (Exception e) {
            AppLog.w(TAG, "Android restricted background activity launch from handlebar BACK", e);
            return "Could not open KTMnav: " + e.getMessage();
        }
    }

    private void scheduleAutoReconnect() {
        cancelAutoReconnect();
        if (!isDesiredConnect() || !getPrefs().getBoolean("auto_connect", true)) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (appContext.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                AppLog.d(TAG, "Suppressing auto-reconnect: BLUETOOTH_CONNECT permission missing");
                return;
            }
        }

        try {
            BluetoothManager bm = (BluetoothManager) appContext.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = (bm != null) ? bm.getAdapter() : BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                AppLog.d(TAG, "Suppressing auto-reconnect: Bluetooth is disabled");
                return;
            }
        } catch (SecurityException e) {
            AppLog.w(TAG, "Suppressing auto-reconnect: permission revoked checking Bluetooth state", e);
            return;
        } catch (Exception e) {
            AppLog.w(TAG, "Suppressing auto-reconnect: error checking Bluetooth state", e);
            return;
        }

        long delay = reconnectAttempts == 0 ? FIRST_RECONNECT_DELAY_MS
                : Math.min(3000L * (1L << Math.min(reconnectAttempts - 1, 2)), 15000L);
        reconnectAttempts++;
        AppLog.i(TAG, "Scheduling auto-reconnect attempt " + reconnectAttempts + " in " + delay + "ms");
        reconnectRunnable = () -> {
            synchronized (Bridge.this) {
                if (isDesiredConnect() && connectionState != ConnectionState.CONNECTED && connectionState != ConnectionState.CONNECTING) {
                    AppLog.i(TAG, "Executing auto-reconnect");
                    int attempts = reconnectAttempts;
                    connectWithMode(BccuLink.Start.SCAN, "auto-reconnect after link loss");
                    reconnectAttempts = attempts;
                }
            }
        };
        mainHandler.postDelayed(reconnectRunnable, delay);
    }

    private void cancelAutoReconnect() {
        if (reconnectRunnable != null) {
            mainHandler.removeCallbacks(reconnectRunnable);
            reconnectRunnable = null;
        }
        reconnectAttempts = 0;
    }

    public synchronized void suspendAndArmBackground() {
        if (isConnected()) {
            return;
        }
        if (isHandshakeInProgress()) {
            AppLog.i(TAG, "Background suspend requested mid-handshake; deferring to the idle watcher");
            scheduleIdleWatcher();
            return;
        }
        suspendNow();
    }

    private synchronized boolean isHandshakeInProgress() {
        if (activeTransport instanceof BccuBleTransport) {
            return ((BccuBleTransport) activeTransport).isHandshakeInProgress();
        }
        return activeTransport != null && connectionState == ConnectionState.AUTHENTICATING;
    }

    private synchronized void suspendNow() {
        AppLog.i(TAG, "Background active-wait deadline reached; suspending active transport and arming passive detection");
        cancelIdleWatcher();
        cancelAutoReconnect();
        cancelTestGuidanceRestore();

        retireActiveTransport();

        setState(ConnectionState.DISCONNECTED, "Waiting for motorcycle in background");

        stopProviders();
        features.onBikeDisconnected();
        idleDisplayManager.onConnectionStateChanged(ConnectionState.DISCONNECTED);

        clearGuidanceFields();

        BridgeService.stop(appContext);

        if (isDesiredConnect() && getPrefs().getBoolean("keep_background", true) && getPrefs().getBoolean("auto_connect", true)) {
            Bike active = getActiveBike();
            if (active != null) {
                if (BikeGeneration.from(active.generation) == BikeGeneration.GEN3) {
                    BikeDetector.arm(appContext);
                } else {
                    AppLog.i(TAG, "Gen2 bike active: suspended active retry, awaiting ACL connection");
                }
            }
        }
    }

    long getIdleTimeoutMs() {
        long ms = IdleWatchPolicy.DEFAULT_IDLE_MS;
        try {
            if (getPrefs().contains("idle_timeout")) {
                Object val = getPrefs().getAll().get("idle_timeout");
                if (val instanceof Number) {
                    ms = ((Number) val).longValue();
                } else if (val instanceof String) {
                    ms = Long.parseLong((String) val);
                }
            }
        } catch (Exception ignored) {}
        return IdleWatchPolicy.clamp(ms);
    }

    private synchronized void scheduleIdleWatcher() {
        if (idleWatcherArmed) {
            return;
        }
        if (!isDesiredConnect() || !getPrefs().getBoolean("keep_background", true) || !getPrefs().getBoolean("auto_connect", true)) {
            return;
        }
        if (connectionState == ConnectionState.CONNECTED || isAppInForeground) {
            return;
        }
        long timeoutMs = getIdleTimeoutMs();
        AppLog.d(TAG, "Scheduling background active-wait idle watcher for " + timeoutMs + "ms");
        mainHandler.postDelayed(idleWatcherRunnable, timeoutMs);
        idleWatcherArmed = true;
        idleWatcherArmedAt = SystemClock.uptimeMillis();
    }

    private synchronized void cancelIdleWatcher() {
        if (idleWatcherArmed) {
            mainHandler.removeCallbacks(idleWatcherRunnable);
            idleWatcherArmed = false;
        }
    }

    private synchronized void onIdleTimeoutReached() {
        idleWatcherArmed = false;
        if (!isDesiredConnect() || !getPrefs().getBoolean("keep_background", true) || !getPrefs().getBoolean("auto_connect", true)) {
            return;
        }
        if (connectionState == ConnectionState.CONNECTED || isAppInForeground) {
            return;
        }
        long elapsed = SystemClock.uptimeMillis() - idleWatcherArmedAt;
        long defer = IdleWatchPolicy.deferralMs(getIdleTimeoutMs(), elapsed, isHandshakeInProgress());
        if (defer > 0) {
            AppLog.i(TAG, "Idle deadline reached mid-handshake; waiting " + defer + " ms more before suspending");
            mainHandler.postDelayed(idleWatcherRunnable, defer);
            idleWatcherArmed = true;
            return;
        }
        suspendNow();
    }

    private void persistDesiredConnect(boolean desired) {
        getPrefs().edit().putBoolean("desired_connect", desired).apply();
    }

    private boolean isDesiredConnect() {
        return getPrefs().getBoolean("desired_connect", false);
    }

    private void setState(ConnectionState state, String detail) {
        this.connectionState = state;
        this.statusDetail = (detail != null && !detail.isEmpty()) ? detail : state.getDisplayState();
        notifyChanged();
    }

    // --- Guidance Forwarding & Test ---

    public synchronized void onNavGuidance(String sourcePkg, NavUpdate update) {
        if (update == null) return;
        this.lastSourcePackage = sourcePkg;
        this.currentSource = formatPackageName(sourcePkg);
        this.lastActiveUpdate = update;
        this.lastActiveSourcePkg = sourcePkg;

        idleDisplayManager.onNavGuidance(update);
        features.onGuidance(update);
    }

    public synchronized void onNavEnded(String sourcePkg) {
        if (sourcePkg != null && sourcePkg.equals(lastSourcePackage)) {
            lastSourcePackage = null;
            lastActiveUpdate = null;
            lastActiveSourcePkg = null;
            this.currentSource = "DASHBOARD";
            idleDisplayManager.onNavEnded();
            features.onGuidanceEnded();
        }
    }

    public synchronized void sendGuidanceToBike(String dist, String road, String eta, String remaining, TurnIcon icon) {
        this.currentDistance = dist;
        this.currentRoad = road;
        this.currentEta = eta;
        this.currentRemaining = remaining;
        this.currentTurn = icon != null ? icon.name() : "IDLE";

        if (activeTransport != null && activeTransport.getConnectionState() == ConnectionState.CONNECTED) {
            activeTransport.sendGuidance(dist, road, eta, remaining);
            if (icon != null) {
                activeTransport.sendTurnIcon(icon);
            }
        }
        notifyChanged();
    }

    public synchronized void clearBikeGuidance() {
        clearGuidanceFields();
        if (activeTransport != null && activeTransport.getConnectionState() == ConnectionState.CONNECTED) {
            activeTransport.clearGuidance();
        }
        notifyChanged();
    }

    private void clearGuidanceFields() {
        this.currentDistance = null;
        this.currentRoad = null;
        this.currentEta = null;
        this.currentRemaining = null;
        this.currentTurn = "IDLE";
        this.currentSource = "DASHBOARD";
    }

    public synchronized void sendTestGuidance() {
        if (!isConnected() || activeTransport == null) {
            AppLog.w(TAG, "Test guidance requested while disconnected");
            return;
        }
        AppLog.i(TAG, "Sending parked test guidance to motorcycle cluster");

        testTargetTransport = activeTransport;

        try {
            activeTransport.sendGuidance("250 m", "Test turn · Ride safely", "12:00", "5.0 km");
            activeTransport.sendTurnIcon(TurnIcon.QUITE_RIGHT);
        } catch (Exception e) {
            AppLog.w(TAG, "Error sending test guidance", e);
        }

        mainHandler.removeCallbacks(restoreAfterTestRunnable);
        mainHandler.postDelayed(restoreAfterTestRunnable, 4000L);
    }

    private synchronized void restoreAfterTest() {
        if (!isConnected() || activeTransport == null || activeTransport != testTargetTransport) {
            return;
        }
        if (lastActiveUpdate != null) {
            try {
                activeTransport.sendGuidance(
                        lastActiveUpdate.getDistanceText(),
                        lastActiveUpdate.getRoadText(),
                        lastActiveUpdate.getEtaText() != null ? lastActiveUpdate.getEtaText() : "--:--",
                        lastActiveUpdate.getRemainingDistanceText() != null ? lastActiveUpdate.getRemainingDistanceText() : "N/A"
                );
                if (lastActiveUpdate.getTurnIcon() != null) {
                    activeTransport.sendTurnIcon(lastActiveUpdate.getTurnIcon());
                }
            } catch (Exception e) {
                AppLog.w(TAG, "Error restoring guidance after test", e);
            }
        } else {
            try {
                activeTransport.clearGuidance();
            } catch (Exception e) {
                AppLog.w(TAG, "Error clearing test guidance", e);
            }
            idleDisplayManager.applySettings();
        }
    }

    private void cancelTestGuidanceRestore() {
        testTargetTransport = null;
        mainHandler.removeCallbacks(restoreAfterTestRunnable);
    }

    public synchronized void settingsChanged() {
        features.settingsChanged();
        if (isAppInForeground) {
            BridgeService.refreshForegroundType(appContext);
        }
        if (lastActiveUpdate != null && lastSourcePackage != null) {
            if (!getPrefs().getBoolean("enabled_" + lastSourcePackage, true)) {
                onNavEnded(lastSourcePackage);
            }
        }
        idleDisplayManager.applySettings();
        if (activeTransport instanceof BccuBleTransport) {
            ((BccuBleTransport) activeTransport).settingsChanged();
        }

        if (!isDesiredConnect()) {
            stopProviders();
            BridgeService.stop(appContext);
            BikeDetector.disarm(appContext);
            notifyChanged();
            return;
        }

        if (isConnected()) {
            startProviders();
        } else {
            stopProviders();
        }

        boolean shouldServiceRun = isDesiredConnect()
                && activeTransport != null
                && (connectionState == ConnectionState.CONNECTING
                    || connectionState == ConnectionState.AUTHENTICATING
                    || connectionState == ConnectionState.CONNECTED)
                && getPrefs().getBoolean("keep_background", true);

        if (shouldServiceRun) {
            BridgeService.start(appContext);
        } else {
            BridgeService.stop(appContext);
        }

        cancelIdleWatcher();
        if (!isAppInForeground && isDesiredConnect() && getPrefs().getBoolean("keep_background", true) && getPrefs().getBoolean("auto_connect", true) && !isConnected()) {
            scheduleIdleWatcher();
        }
        notifyChanged();
    }

    public synchronized void onNotificationListenerConnected() {
        idleDisplayManager.applySettings();
        if (isConnected() && isDesiredConnect()) {
            startProviders();
        }
    }

    private void startProviders() {
        if (getPrefs().getBoolean("enabled_menion.android.locus", true)) {
            locusMapSource.start();
        } else {
            locusMapSource.stop();
        }

        String coMapsPkg = CoMapsNavSource.installedPackage(appContext);
        if (coMapsPkg == null) coMapsPkg = CoMapsNavSource.PACKAGE_NAME;
        if (getPrefs().getBoolean("enabled_" + coMapsPkg, true)) {
            coMapsNavSource.start();
        } else {
            coMapsNavSource.stop();
        }
    }

    private void stopProviders() {
        locusMapSource.stop();
        coMapsNavSource.stop();
    }

    // --- Telemetry & Dashboard APIs wired to active Gen3 transport ---

    public Integer getSignalRssi() {
        if (isConnected() && activeTransport instanceof BccuBleTransport) {
            return ((BccuBleTransport) activeTransport).getRssi();
        }
        return null;
    }

    public Map<String, String> getTelemetry() {
        if (isConnected() && activeTransport instanceof BccuBleTransport) {
            return ((BccuBleTransport) activeTransport).getTelemetry();
        }
        return Collections.emptyMap();
    }

    public Map<String, String> getVehicleInfo() {
        if (isConnected() && activeTransport instanceof BccuBleTransport) {
            return ((BccuBleTransport) activeTransport).getVehicleInfo();
        }
        return Collections.emptyMap();
    }

    public String getLastButton() {
        if (isConnected() && activeTransport instanceof BccuBleTransport) {
            return ((BccuBleTransport) activeTransport).getLastButton();
        }
        return null;
    }

    public synchronized String getLastHandlebarActionResult() {
        return lastHandlebarActionResult;
    }

    public synchronized void sendDashboardNotification(String text) {
        sendDashboardNotification(text, BccuProtocol.NotificationIcon.NOTIFICATION_WAYPOINT);
    }

    public synchronized void sendDashboardNotification(String text, BccuProtocol.NotificationIcon icon) {
        showBanner(DashboardFeatures.MANUAL_BANNER_OWNER, text, BANNER_PRIORITY_MESSAGE, 0L, icon);
    }

    public synchronized boolean showDashBanner(String owner, String text, int priority, long holdMs) {
        return showBanner(owner, text, priority, holdMs, BccuProtocol.NotificationIcon.NOTIFICATION_WAYPOINT);
    }

    public synchronized void clearDashBanner(String owner) {
        if (activeTransport instanceof BccuBleTransport) {
            ((BccuBleTransport) activeTransport).clearBanner(owner);
        } else if (activeTransport instanceof Gen2RfcommTransport) {
            ((Gen2RfcommTransport) activeTransport).clearBanner(owner);
        }
    }

    private boolean showBanner(String owner, String text, int priority, long holdMs, BccuProtocol.NotificationIcon icon) {
        if (activeTransport == null || !isConnected()) {
            return false;
        }
        if (activeTransport instanceof BccuBleTransport) {
            return ((BccuBleTransport) activeTransport).showBanner(owner, text, priority, holdMs, icon);
        }
        if (activeTransport instanceof Gen2RfcommTransport) {
            return ((Gen2RfcommTransport) activeTransport).showBanner(owner, text, priority, holdMs);
        }
        return false;
    }

    public synchronized boolean sendSymbolTest(int turnIconId) {
        BccuBleTransport ble = connectedGen3();
        return ble != null && ble.sendSymbolTest(turnIconId);
    }

    public synchronized boolean sendBannerIconTest(int notificationIconId) {
        BccuBleTransport ble = connectedGen3();
        return ble != null && ble.sendBannerIconTest(notificationIconId);
    }

    public synchronized void endSymbolTest() {
        BccuBleTransport ble = connectedGen3();
        if (ble != null) {
            ble.endSymbolTest();
        }
    }

    private BccuBleTransport connectedGen3() {
        if (isConnected() && activeTransport instanceof BccuBleTransport && activeTransport.getConnectionState() == ConnectionState.CONNECTED) {
            return (BccuBleTransport) activeTransport;
        }
        return null;
    }

    public synchronized String getVehicleProfile() {
        if (activeTransport instanceof BccuBleTransport) {
            return ((BccuBleTransport) activeTransport).getVehicleProfileLabel();
        }
        return null;
    }

    public synchronized String getTelemetryStatus() {
        BccuBleTransport ble = connectedGen3();
        return ble != null ? ble.getTelemetryStatus() : null;
    }

    public void addHandlebarMaskListener(HandlebarMaskListener listener) {
        if (listener != null && !maskListeners.contains(listener)) {
            maskListeners.add(listener);
        }
    }

    public void removeHandlebarMaskListener(HandlebarMaskListener listener) {
        if (listener != null) {
            maskListeners.remove(listener);
        }
    }

    public app.ktmnav.backend.ride.RideServices getRideServices() {
        return features.rideServices;
    }

    public app.ktmnav.backend.obd.ObdManager getObd() {
        return features.obd;
    }

    public HandlebarRouter getHandlebarRouter() {
        return features.handlebarRouter;
    }

    public CallStateMonitor getCallStateMonitor() {
        return features.callStateMonitor;
    }

    public CallControl getCallControl() {
        return features.callControl;
    }

    public BannerSink getBannerSink() {
        return features.banner;
    }

    private String formatPackageName(String pkg) {
        if (pkg == null) return "NAVIGATION";
        if (pkg.startsWith("app.comaps")) return "CoMaps";
        if ("com.google.android.apps.maps".equals(pkg) || "com.google.android.apps.mapslite".equals(pkg)) return "Google Maps";
        if (pkg.contains("comaps")) return "CoMaps";
        if (pkg.contains("tomtom")) return "TomTom GO";
        if (pkg.contains("osmand")) return "OsmAnd";
        if (pkg.contains("kurviger")) return "Kurviger";
        if (pkg.contains("calimoto")) return "Calimoto";
        if (pkg.contains("organicmaps")) return "Organic Maps";
        if (pkg.contains("locus")) return "Locus Map";
        if (pkg.contains("google") && pkg.contains("maps")) return "Google Maps";
        if ("com.waze".equals(pkg)) return "Waze";
        return NavApps.nameOf(pkg);
    }
}

package app.ktmnav.backend;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Gen3 KTM dashboard features coordinator: telemetry configuration & decoding,
 * vehicle info probing, RSSI monitoring, handlebar remote control (RCM), and notifications.
 * Based on OpenDash BccuConnectionService (MIT License, Copyright (c) 2026 OpenDash contributors).
 */
public class DashboardFeatures {

    public interface Transport {
        void write(UUID id, byte[] value, boolean coalesce);
        void read(UUID id);
        void subscribe(UUID id, boolean indication);
        void requestRssi();
        void changed();

        default void handlebarMask(int mask, long uptimeMs) {
        }
    }

    public static final UUID CHAR_MANUFACTURER_NAME = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb");
    public static final UUID CHAR_MODEL_NUMBER      = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb");
    public static final UUID CHAR_SERIAL_NUMBER     = UUID.fromString("00002a25-0000-1000-8000-00805f9b34fb");
    public static final UUID CHAR_HARDWARE_REVISION = UUID.fromString("00002a27-0000-1000-8000-00805f9b34fb");
    public static final UUID CHAR_FIRMWARE_REVISION = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb");
    public static final UUID CHAR_SOFTWARE_REVISION = UUID.fromString("00002a28-0000-1000-8000-00805f9b34fb");

    private static final List<UUID> DEVICE_INFO_CHARS = Collections.unmodifiableList(Arrays.asList(
            CHAR_MANUFACTURER_NAME,
            CHAR_MODEL_NUMBER,
            CHAR_SERIAL_NUMBER,
            CHAR_HARDWARE_REVISION,
            CHAR_FIRMWARE_REVISION,
            CHAR_SOFTWARE_REVISION
    ));

    private static final Set<String> CORE_TELEMETRY_WHITELIST = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "ECU_Engine_Rpm",
            "ABS_Display_Front_Wheel_Speed",
            "ECU_Gear_Position",
            "ECU_Water_Temperature",
            "DASH_Fuel_Level",
            "bCCU_Voltage"
    )));

    static final int[] POLLED_DATAPOINTS = {349, 351, 355, 356, 202, 311, 312};

    public static final long RSSI_POLL_INTERVAL_MS = 3000L;
    public static final long VALUE_POLL_INTERVAL_MS = 10000L;
    public static final long NOTIFICATION_CLEAR_DELAY_MS = DashBannerArbiter.DEFAULT_HOLD_MS;
    public static final long MARQUEE_STEP_MS = DashBannerArbiter.MARQUEE_STEP_MS;
    public static final int MARQUEE_CYCLES = DashBannerArbiter.MARQUEE_CYCLES;
    public static final int NOTIFICATION_BANNER_CHARS = DashBannerArbiter.VISIBLE_CHARS;
    public static final String MANUAL_BANNER_OWNER = "manual";
    private static final String TAG = "Gen3Dash";
    private static final String PREFS_NAME = "ktmnav";
    private static final String KEY_MARQUEE_ENABLED = "marquee_enabled";

    private final Context context;
    private final Transport transport;
    private final DashBannerArbiter.Scheduler scheduler;
    private Object rssiToken;
    private Object valuePollToken;

    private byte[] sessionKey;
    private byte[] sessionIv;
    private boolean authenticated;
    private boolean prpcAvailable;

    private int telemetrySid;
    private List<Datapoint> telemetrySchema;

    private final Map<String, String> telemetry = new LinkedHashMap<>();
    private final Map<String, String> vehicleInfo = new LinkedHashMap<>();
    private Integer rssi;
    private String lastButton;
    private long buttonEventSequence = 0L;
    private int lastRcmMask;
    private BccuProtocol.NotificationIcon lastValidNotificationIcon = BccuProtocol.NotificationIcon.NOTIFICATION_WAYPOINT;
    private final DashBannerArbiter bannerArbiter;
    private boolean bannerTestActive;

    private Boolean marqueeEnabledOverride;

    private LongSupplier wallClock = System::currentTimeMillis;
    private LongSupplier uptimeClock = SystemClock::uptimeMillis;

    private static final class PendingRpc {
        final int opcode;
        final int datapointId;
        final boolean u16SampleRate;

        PendingRpc(int opcode, int datapointId, boolean u16SampleRate) {
            this.opcode = opcode;
            this.datapointId = datapointId;
            this.u16SampleRate = u16SampleRate;
        }
    }

    private final Map<Integer, PendingRpc> pendingRpcs = new HashMap<>();
    private boolean configureFallbackUsed;
    private String timeSyncResult = "not sent";
    private String configureResult = "not sent";
    private String controlResult = "not sent";
    private String lastRpcError;
    private int configureOk;
    private int configureRejected;
    private final Set<Integer> streamedDatapoints = new HashSet<>();
    private final Set<Integer> polledDatapoints = new HashSet<>();

    private final Runnable valuePollRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (DashboardFeatures.this) {
                if (!authenticated || !prpcAvailable) {
                    return;
                }
                pollValues();
                valuePollToken = scheduler.schedule(this, VALUE_POLL_INTERVAL_MS);
            }
        }
    };

    private final Runnable rssiRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (DashboardFeatures.this) {
                if (!authenticated) {
                    return;
                }
                transport.requestRssi();
                rssiToken = scheduler.schedule(this, RSSI_POLL_INTERVAL_MS);
            }
        }
    };

    public DashboardFeatures(Context context, Transport transport) {
        this(context, transport, createDefaultHandler());
    }

    public DashboardFeatures(Context context, Transport transport, Handler handler) {
        this(transport, context, DashBannerArbiter.handlerScheduler(handler));
    }

    public static DashboardFeatures withScheduler(Context context, Transport transport, DashBannerArbiter.Scheduler scheduler) {
        return new DashboardFeatures(transport, context, scheduler != null ? scheduler : DashBannerArbiter.noopScheduler());
    }

    private DashboardFeatures(Transport transport, Context context, DashBannerArbiter.Scheduler scheduler) {
        this.context = context;
        this.transport = transport;
        this.scheduler = scheduler;
        this.bannerArbiter = new DashBannerArbiter(scheduler, new DashBannerArbiter.Sink() {
            @Override
            public void showFrame(String text, BccuProtocol.NotificationIcon icon) {
                writeBannerFrame(text, icon);
            }

            @Override
            public void clearBanner() {
                writeBannerClear();
            }
        });
    }

    synchronized void setClocks(LongSupplier wall, LongSupplier uptime) {
        if (wall != null) {
            this.wallClock = wall;
        }
        if (uptime != null) {
            this.uptimeClock = uptime;
        }
    }

    private static Handler createDefaultHandler() {
        try {
            Looper looper = Looper.getMainLooper();
            if (looper != null) {
                return new Handler(looper);
            }
        } catch (Throwable ignored) {
        }
        try {
            if (Looper.myLooper() != null) {
                return new Handler(Looper.myLooper());
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public synchronized void setTelemetrySchema(List<Datapoint> schema) {
        this.telemetrySchema = schema;
    }

    public synchronized void setMarqueeEnabled(boolean enabled) {
        this.marqueeEnabledOverride = enabled;
    }

    public void settingsChanged() {
        boolean enabled = isMarqueeEnabled();
        bannerArbiter.setMarqueeEnabled(enabled);
        if (!enabled && bannerArbiter.isMarqueeRunning()) {
            bannerArbiter.clearActive();
        }
    }

    private boolean isMarqueeEnabled() {
        if (marqueeEnabledOverride != null) {
            return marqueeEnabledOverride;
        }
        if (context == null) {
            return true;
        }
        try {
            SharedPreferences p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            if (p != null && p.contains(KEY_MARQUEE_ENABLED)) {
                return p.getBoolean(KEY_MARQUEE_ENABLED, true);
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    private void ensureSchemaLoaded() {
        if (telemetrySchema != null) {
            return;
        }
        if (context != null) {
            try {
                telemetrySchema = TelemetrySchema.INSTANCE.load(context);
            } catch (Exception ignored) {
            }
        }
    }

    private int nextSid() {
        telemetrySid = (telemetrySid + 1) & 0xFF;
        return telemetrySid;
    }

    public synchronized void onAuthenticated(byte[] key, byte[] iv, boolean prpcAvailable) {
        if (!beginSession(key, iv, prpcAvailable)) {
            return;
        }
        subscribeRemoteControl();
        startRssiPolling();
        probeVehicleInfo(null, true);
        startTelemetry();
    }

    public synchronized boolean beginSession(byte[] key, byte[] iv, boolean prpcAvailable) {
        if (key == null || key.length != 16 || iv == null || iv.length != 16) {
            this.authenticated = false;
            this.sessionKey = null;
            this.sessionIv = null;
            this.prpcAvailable = false;
            return false;
        }
        this.sessionKey = key.clone();
        this.sessionIv = iv.clone();
        this.authenticated = true;
        this.prpcAvailable = prpcAvailable;
        return true;
    }

    public synchronized void subscribeRemoteControl() {
        if (authenticated) {
            transport.subscribe(BccuProtocol.RCM_REMOTE_CONTROL, true);
        }
    }

    public synchronized void startRssiPolling() {
        if (!authenticated) {
            return;
        }
        stopRssiPolling();
        transport.requestRssi();
        rssiToken = scheduler.schedule(rssiRunnable, RSSI_POLL_INTERVAL_MS);
    }

    public synchronized void probeVehicleInfo(List<UUID> deviceInfoChars, boolean hasBaseService) {
        if (!authenticated) {
            return;
        }
        for (UUID charUuid : deviceInfoChars != null ? deviceInfoChars : DEVICE_INFO_CHARS) {
            transport.read(charUuid);
        }
        if (hasBaseService) {
            transport.subscribe(BccuProtocol.BASE_VIN, false);
            byte[] encryptedReq = BccuCrypto.INSTANCE.encryptData(new byte[]{1}, sessionKey, sessionIv);
            transport.write(BccuProtocol.BASE_GET_VIN_REQUEST, encryptedReq, false);
        }
        transport.read(BccuProtocol.BASE_VIN);
    }

    public synchronized void startTelemetry() {
        if (!authenticated || !prpcAvailable) {
            return;
        }
        transport.subscribe(BccuProtocol.PRPC_NOTIFICATION, false);
        transport.subscribe(BccuProtocol.PRPC_RESPONSE, false);
        ensureSchemaLoaded();
        resetTelemetryStatus();
        sendTimeSync();
        configureTelemetry(true);
        sendTelemetryControl(BccuProtocol.TELEMETRY_CMD_START);
        pollValues();
        cancelToken(valuePollToken);
        valuePollToken = scheduler.schedule(valuePollRunnable, VALUE_POLL_INTERVAL_MS);
    }

    private void cancelToken(Object token) {
        if (token != null) {
            scheduler.cancel(token);
        }
    }

    private void resetTelemetryStatus() {
        pendingRpcs.clear();
        configureFallbackUsed = false;
        timeSyncResult = "not sent";
        configureResult = "not sent";
        controlResult = "not sent";
        lastRpcError = null;
        configureOk = 0;
        configureRejected = 0;
        streamedDatapoints.clear();
        polledDatapoints.clear();
    }

    private void sendPrpc(byte[] request, PendingRpc pending) {
        if (!authenticated || sessionKey == null || sessionIv == null) {
            return;
        }
        pendingRpcs.put(request[2] & 0xFF, pending);
        transport.write(BccuProtocol.PRPC_REQUEST, BccuCrypto.INSTANCE.encryptData(request, sessionKey, sessionIv), false);
    }

    private void sendTimeSync() {
        long now = wallClock.getAsLong();
        sendPrpc(BccuProtocol.INSTANCE.buildTimeSyncRequest(nextSid(), now),
                new PendingRpc(BccuProtocol.RPC_OPCODE_SET_VALUE, BccuProtocol.DP_TIME_SYNC, false));
        timeSyncResult = "sent";
        AppLog.i(TAG, "PRPC: TimeSync (dp 200) setValue sent");
    }

    private void configureTelemetry(boolean u16SampleRate) {
        if (telemetrySchema == null) {
            return;
        }
        int count = 0;
        for (Datapoint dp : telemetrySchema) {
            if (dp.getTelemetryable() && CORE_TELEMETRY_WHITELIST.contains(dp.getName())) {
                int sampleRate = Math.max(dp.getSampleRateDefaultMs(), 1000);
                sendPrpc(BccuProtocol.INSTANCE.buildTelemetryConfigureRequest(nextSid(), dp.getId(), sampleRate, u16SampleRate),
                        new PendingRpc(BccuProtocol.RPC_OPCODE_TELEMETRY_CONFIGURE, dp.getId(), u16SampleRate));
                count++;
            }
        }
        configureResult = "sent " + count + " (" + (u16SampleRate ? "u16" : "u32") + " sample rate)";
        AppLog.i(TAG, "PRPC: telemetryConfigure sent for " + count + " datapoints with " + (u16SampleRate ? "u16" : "u32") + " sample rate");
    }

    private void sendTelemetryControl(int command) {
        sendPrpc(BccuProtocol.INSTANCE.buildTelemetryControlRequest(nextSid(), command),
                new PendingRpc(BccuProtocol.RPC_OPCODE_TELEMETRY_CONTROL, -1, false));
    }

    private void pollValues() {
        for (int dpId : POLLED_DATAPOINTS) {
            sendPrpc(BccuProtocol.INSTANCE.buildGetValueRequest(nextSid(), dpId),
                    new PendingRpc(BccuProtocol.RPC_OPCODE_GET_VALUE, dpId, false));
        }
    }

    private void handlePrpcResponse(byte[] raw) {
        if (sessionKey == null || sessionIv == null) {
            return;
        }
        byte[] decrypted;
        try {
            decrypted = BccuCrypto.INSTANCE.decryptData(raw, sessionKey, sessionIv);
        } catch (Exception e) {
            AppLog.w(TAG, "PRPC_RESPONSE could not be decrypted");
            return;
        }
        BccuProtocol.PrpcResponse response = BccuProtocol.INSTANCE.parsePrpcResponse(decrypted);
        if (response == null) {
            AppLog.d(TAG, "PRPC_RESPONSE (unparsed): " + BccuCrypto.INSTANCE.hex(decrypted));
            return;
        }
        PendingRpc pending = pendingRpcs.remove(response.sid);
        if (pending == null || pending.opcode != response.opcode) {
            AppLog.d(TAG, "PRPC_RESPONSE without matching request: " + response.describe() + " raw=" + BccuCrypto.INSTANCE.hex(decrypted));
            return;
        }
        String dpLabel = pending.datapointId >= 0 ? " (dp " + pending.datapointId + ")" : "";
        AppLog.i(TAG, "PRPC_RESPONSE " + response.describe() + dpLabel);
        if (!response.isSuccess()) {
            lastRpcError = response.describe() + dpLabel;
        }
        switch (response.opcode) {
            case BccuProtocol.RPC_OPCODE_SET_VALUE:
                timeSyncResult = response.isSuccess() ? "accepted" : "rejected (" + response.describe() + ")";
                break;
            case BccuProtocol.RPC_OPCODE_TELEMETRY_CONFIGURE:
                onConfigureResponse(response, pending);
                break;
            case BccuProtocol.RPC_OPCODE_TELEMETRY_CONTROL:
                controlResult = response.isSuccess() ? "accepted" : "rejected (" + response.describe() + ")";
                break;
            case BccuProtocol.RPC_OPCODE_GET_VALUE:
                onGetValueResponse(response, pending);
                break;
            default:
                break;
        }
        transport.changed();
    }

    private void onConfigureResponse(BccuProtocol.PrpcResponse response, PendingRpc pending) {
        if (response.isSuccess()) {
            configureOk++;
        } else {
            configureRejected++;
        }
        String variant = pending.u16SampleRate ? "u16" : "u32";
        configureResult = variant + ": " + configureOk + " accepted, " + configureRejected + " rejected";
        if (!response.isSuccess() && pending.u16SampleRate && !configureFallbackUsed) {
            configureFallbackUsed = true;
            configureOk = 0;
            configureRejected = 0;
            AppLog.w(TAG, "PRPC: u16 sample rate rejected; retrying telemetryConfigure with u32 sample rate");
            sendTelemetryControl(BccuProtocol.TELEMETRY_CMD_STOP);
            configureTelemetry(false);
            sendTelemetryControl(BccuProtocol.TELEMETRY_CMD_START);
        }
    }

    private void onGetValueResponse(BccuProtocol.PrpcResponse response, PendingRpc pending) {
        if (!response.isSuccess() || response.returns.length < 2) {
            return;
        }
        ByteBuffer buf = ByteBuffer.wrap(response.returns).order(ByteOrder.LITTLE_ENDIAN);
        int dpId = buf.getShort() & 0xFFFF;
        Datapoint dp = findDatapoint(dpId);
        if (dpId != pending.datapointId || dp == null) {
            return;
        }
        int len = TelemetrySchema.INSTANCE.typeLength(dp.getType());
        if (len <= 0 || buf.remaining() != len) {
            return;
        }
        byte[] value = new byte[len];
        buf.get(value);
        telemetry.put(dp.getName(), formatValue(dp, value));
        polledDatapoints.add(dpId);
    }

    private Datapoint findDatapoint(int dpId) {
        ensureSchemaLoaded();
        if (telemetrySchema == null) {
            return null;
        }
        for (Datapoint dp : telemetrySchema) {
            if (dp.getId() == dpId) {
                return dp;
            }
        }
        return null;
    }

    private static String formatValue(Datapoint dp, byte[] value) {
        String decoded = TelemetrySchema.INSTANCE.decodeValue(dp.getType(), value);
        String unit = dp.getUnit();
        return decoded + ((unit != null && !unit.trim().isEmpty()) ? " " + unit : "");
    }

    public synchronized String getTelemetryStatus() {
        if (!authenticated) {
            return null;
        }
        if (!prpcAvailable) {
            return "PRPC telemetry service (0600) not offered by this dash";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("TimeSync: ").append(timeSyncResult)
                .append("; configure: ").append(configureResult)
                .append(configureFallbackUsed ? " (after u16 fallback)" : "")
                .append("; control: ").append(controlResult)
                .append("; streamed datapoints: ").append(streamedDatapoints.size())
                .append("; polled datapoints: ").append(polledDatapoints.size());
        if (lastRpcError != null) {
            sb.append("; last error: ").append(lastRpcError);
        }
        return sb.toString();
    }

    public synchronized void onCharacteristic(UUID id, byte[] raw) {
        if (id == null || raw == null) {
            return;
        }

        if (BccuProtocol.PRPC_NOTIFICATION.equals(id)) {
            handlePrpcNotification(raw);
            return;
        }

        if (BccuProtocol.PRPC_RESPONSE.equals(id)) {
            handlePrpcResponse(raw);
            return;
        }

        if (BccuProtocol.RCM_REMOTE_CONTROL.equals(id)) {
            handleRcm(raw);
            return;
        }

        if (BccuProtocol.BASE_VIN.equals(id)) {
            handleVinData(raw);
            return;
        }

        if (CHAR_MANUFACTURER_NAME.equals(id)) {
            updateVehicleInfo("Manufacturer", printableAscii(raw));
            return;
        }
        if (CHAR_MODEL_NUMBER.equals(id)) {
            updateVehicleInfo("ModelNumber", printableAscii(raw));
            return;
        }
        if (CHAR_SERIAL_NUMBER.equals(id)) {
            updateVehicleInfo("SerialNumber", printableAscii(raw));
            return;
        }
        if (CHAR_HARDWARE_REVISION.equals(id)) {
            updateVehicleInfo("HardwareRevision", printableAscii(raw));
            return;
        }
        if (CHAR_FIRMWARE_REVISION.equals(id)) {
            updateVehicleInfo("FirmwareRevision", printableAscii(raw));
            return;
        }
        if (CHAR_SOFTWARE_REVISION.equals(id)) {
            updateVehicleInfo("SoftwareRevision", printableAscii(raw));
            return;
        }

        String uuidStr = id.toString().toLowerCase(Locale.US);
        // Preserved mystery 0200/0300 probe reads/notifications as bounded hex under Raw/Unknown
        if (uuidStr.startsWith("71ced1ac-020") || uuidStr.startsWith("71ced1ac-030")) {
            byte[] bounded = raw.length > 64 ? Arrays.copyOf(raw, 64) : raw;
            updateVehicleInfo("Raw/Unknown:" + id, BccuCrypto.INSTANCE.hex(bounded));
            return;
        }

        // Standard DeviceInfo extra UUIDs (e.g. 180A service or 2Axx characteristics)
        if (uuidStr.contains("-180a-") || uuidStr.startsWith("00002a")) {
            byte[] bounded = raw.length > 64 ? Arrays.copyOf(raw, 64) : raw;
            updateVehicleInfo("Raw:" + id, BccuCrypto.INSTANCE.hex(bounded));
            return;
        }
    }

    private void handlePrpcNotification(byte[] raw) {
        byte[] decrypted = null;
        if (sessionKey != null && sessionIv != null) {
            try {
                decrypted = BccuCrypto.INSTANCE.decryptData(raw, sessionKey, sessionIv);
            } catch (Exception ignored) {
            }
        }
        if (decrypted == null && raw.length >= 4) {
            ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            if ((buf.getShort() & 0xFFFF) == 7) {
                decrypted = raw;
            }
        }
        if (decrypted == null) {
            return;
        }

        ensureSchemaLoaded();
        final List<Datapoint> schema = this.telemetrySchema;
        List<BccuProtocol.TelemetryTriple> triples = BccuProtocol.INSTANCE.parseTelemetryNotification(decrypted, dpId -> {
            if (schema == null) {
                return 0;
            }
            for (Datapoint dp : schema) {
                if (dp.getId() == dpId) {
                    return TelemetrySchema.INSTANCE.typeLength(dp.getType());
                }
            }
            return 0;
        });

        if (triples.isEmpty()) {
            return;
        }

        boolean updated = false;
        for (BccuProtocol.TelemetryTriple triple : triples) {
            Datapoint found = null;
            if (schema != null) {
                for (Datapoint dp : schema) {
                    if (dp.getId() == triple.getDatapointId()) {
                        found = dp;
                        break;
                    }
                }
            }
            if (found != null) {
                telemetry.put(found.getName(), formatValue(found, triple.getValue()));
                streamedDatapoints.add(found.getId());
                updated = true;
            }
        }

        if (updated) {
            transport.changed();
        }
    }

    private void handleVinData(byte[] raw) {
        byte[] decrypted = null;
        if (sessionKey != null && sessionIv != null) {
            try {
                decrypted = BccuCrypto.INSTANCE.decryptData(raw, sessionKey, sessionIv);
            } catch (Exception ignored) {
            }
        }
        if (decrypted == null) {
            decrypted = raw;
        }
        String vin = printableAscii(decrypted);
        if (!vin.isEmpty()) {
            updateVehicleInfo("VIN", vin);
        }
    }

    private void updateVehicleInfo(String key, String value) {
        if (value != null && !value.isEmpty()) {
            vehicleInfo.put(key, value);
            transport.changed();
        }
    }

    public synchronized void onRssi(int rssi) {
        this.rssi = rssi;
        transport.changed();
    }

    public synchronized void onDisconnected() {
        authenticated = false;
        prpcAvailable = false;
        sessionKey = null;
        sessionIv = null;

        stopRssiPolling();
        bannerArbiter.reset();
        bannerTestActive = false;
        pendingRpcs.clear();
        cancelToken(valuePollToken);
        valuePollToken = null;

        telemetry.clear();
        vehicleInfo.clear();
        rssi = null;
        lastButton = null;
        lastRcmMask = 0;

        transport.changed();
    }

    private void stopRssiPolling() {
        cancelToken(rssiToken);
        rssiToken = null;
    }

    public synchronized Map<String, String> getTelemetry() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(telemetry));
    }

    public synchronized Map<String, String> getVehicleInfo() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(vehicleInfo));
    }

    public synchronized Integer getRssi() {
        return rssi;
    }

    public synchronized String getLastButton() {
        return lastButton;
    }

    public synchronized long getButtonEventSequence() {
        return buttonEventSequence;
    }

    public boolean sendNotification(String message, BccuProtocol.NotificationIcon icon) {
        return showBanner(MANUAL_BANNER_OWNER, message, DashBannerArbiter.PRIORITY_MESSAGE, 0L, icon);
    }

    public boolean showBanner(String owner, String text, int priority, long holdMs, BccuProtocol.NotificationIcon icon) {
        synchronized (this) {
            if (!authenticated || sessionKey == null || sessionIv == null) {
                return false;
            }
            bannerTestActive = false;
        }
        bannerArbiter.setMarqueeEnabled(isMarqueeEnabled());
        return bannerArbiter.show(owner, text, priority, holdMs, icon);
    }

    public void clearBanner(String owner) {
        bannerArbiter.clear(owner);
    }

    public synchronized boolean sendBannerIconTest(int iconBinary) {
        if (!authenticated || sessionKey == null || sessionIv == null) {
            return false;
        }
        bannerArbiter.reset();
        bannerTestActive = true;
        writeNotificationPayload(BccuProtocol.INSTANCE.buildNotificationPayload(iconBinary & 0xFF, "Icon " + (iconBinary & 0xFF), BccuProtocol.Visibility.FULL));
        return true;
    }

    public synchronized void endBannerTest() {
        if (bannerTestActive) {
            bannerTestActive = false;
            writeBannerClear();
        }
    }

    DashBannerArbiter getBannerArbiter() {
        return bannerArbiter;
    }

    private synchronized void writeBannerFrame(String text, BccuProtocol.NotificationIcon icon) {
        BccuProtocol.NotificationIcon renderable = DashBannerArbiter.renderableIcon(icon);
        lastValidNotificationIcon = renderable;
        writeNotificationPayload(BccuProtocol.INSTANCE.buildNotificationPayload(renderable, text, BccuProtocol.Visibility.FULL));
    }

    private synchronized void writeBannerClear() {
        writeNotificationPayload(BccuProtocol.INSTANCE.buildNotificationPayload(lastValidNotificationIcon, "", BccuProtocol.Visibility.OFF));
    }

    private void writeNotificationPayload(byte[] payload) {
        if (!authenticated || sessionKey == null || sessionIv == null) {
            return;
        }
        transport.write(BccuProtocol.NOTIFICATION, BccuCrypto.INSTANCE.encryptData(payload, sessionKey, sessionIv), true);
    }

    public synchronized void handleRcm(byte[] encrypted) {
        if (encrypted == null || sessionKey == null || sessionIv == null || encrypted.length < 32 || encrypted.length % 16 != 0) {
            return;
        }
        long uptimeMs = uptimeClock.getAsLong();
        byte[] decrypted;
        try {
            decrypted = BccuCrypto.INSTANCE.decryptControl(encrypted, sessionKey, sessionIv);
        } catch (Exception e) {
            return;
        }
        int currentMask = BccuProtocol.rcmMask(decrypted);
        transport.handlebarMask(currentMask, uptimeMs);

        for (BccuProtocol.HandlebarButton button : BccuProtocol.HandlebarButton.values()) {
            boolean wasPressed = ((lastRcmMask >> button.getBitIndex()) & 1) == 1;
            boolean isPressed = ((currentMask >> button.getBitIndex()) & 1) == 1;
            if (!wasPressed && isPressed) {
                lastButton = button.name();
                buttonEventSequence++;
                transport.changed();
            }
        }
        lastRcmMask = currentMask;
    }

    private static String printableAscii(byte[] data) {
        if (data == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            int v = b & 0xFF;
            if (v >= 32 && v < 127) {
                sb.append((char) v);
            } else if (v == 0) {
                break;
            }
        }
        return sb.toString().trim();
    }
}

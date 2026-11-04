package app.ktmnav.backend;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * Protocol constants and packet framing for KTM BCCU dashboard over BLE.
 * Based on OpenDash BccuProtocol (MIT License, Copyright (c) 2026 OpenDash contributors).
 */
public final class BccuProtocol {

    public static final BccuProtocol INSTANCE = new BccuProtocol();

    public static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    public static final UUID DEVICE_INFO_SERVICE = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb");

    public static final UUID MAIN_SERVICE = uuid("0700");
    public static final UUID AUTH_REQUEST = uuid("0701");
    public static final UUID AUTH_REPLY = uuid("0702");
    public static final UUID NAVIGATION_STATE = uuid("0703");
    public static final UUID TURN_ICON = uuid("0704");
    public static final UUID TURN_DISTANCE = uuid("0705");
    public static final UUID TURN_INFO = uuid("0706");
    public static final UUID TURN_ROAD = uuid("0707");
    public static final UUID ETA = uuid("0708");
    public static final UUID REMAINING_DISTANCE = uuid("0709");
    public static final UUID NOTIFICATION = uuid("070a");
    public static final UUID TBT_NAV_REQUEST = uuid("070b");
    public static final UUID TBT_NAV_RESPONSE = uuid("070c");

    public static final UUID BASE_SERVICE = uuid("0000");
    public static final UUID BASE_GET_VIN_REQUEST = uuid("0001");
    public static final UUID BASE_VIN = uuid("0002");

    public static final UUID MYSTERY_SERVICE_0200 = uuid("0200");
    public static final UUID MYSTERY_SERVICE_0300 = uuid("0300");

    public static final UUID RCM_SERVICE = uuid("0100");
    public static final UUID RCM_REMOTE_CONTROL = uuid("0103");

    public static final UUID PRPC_SERVICE = uuid("0600");
    public static final UUID PRPC_REQUEST = uuid("0601");
    public static final UUID PRPC_RESPONSE = uuid("0602");
    public static final UUID PRPC_NOTIFICATION = uuid("0603");

    public static final int CMD_HELLO = 0;
    public static final int CMD_GENERATE_KEYS = 1;
    public static final int CMD_KEY_ACK_BASE = 16;

    public static final int RPC_OPCODE_GET_VALUE = 1;
    public static final int RPC_OPCODE_SET_VALUE = 2;
    public static final int RPC_OPCODE_TELEMETRY_CONFIGURE = 3;
    public static final int RPC_OPCODE_TELEMETRY_CONTROL = 4;

    public static final int DP_TIME_SYNC = 200;

    public static final int TELEMETRY_CMD_STOP = 0;
    public static final int TELEMETRY_CMD_START = 1;
    public static final int TELEMETRY_CMD_RESET = 2;

    public enum Visibility {
        UNKNOWN(-1),
        FULL(3),
        HALF(2),
        OFF(1);

        private final int binary;

        Visibility(int binary) {
            this.binary = binary;
        }

        public int getBinary() {
            return binary;
        }
    }

    public enum NotificationIcon {
        UNKNOWN(0),
        NOTIFICATION_REROUTING(1),
        NOTIFICATION_WAYPOINT(2),
        TARGET_REACHED(3),
        GPS_LOST(4),
        WARNING(5),
        INFORMATION(6),
        SPEED(7);

        private final int binary;

        NotificationIcon(int binary) {
            this.binary = binary;
        }

        public int getBinary() {
            return binary;
        }

        public static NotificationIcon fromBinary(int binary) {
            for (NotificationIcon icon : values()) {
                if (icon.binary == binary) {
                    return icon;
                }
            }
            return UNKNOWN;
        }
    }

    public enum HandlebarButton {
        SET(0),
        BACK(1),
        DOWN(2),
        UP(3);

        private final int bitIndex;

        HandlebarButton(int bitIndex) {
            this.bitIndex = bitIndex;
        }

        public int getBitIndex() {
            return bitIndex;
        }
    }

    public enum OnOff {
        OFF(0),
        ON(1);

        private final int binary;

        OnOff(int binary) {
            this.binary = binary;
        }

        public int getBinary() {
            return binary;
        }
    }

    public static final class TelemetryTriple {
        private final long timestampMs;
        private final int datapointId;
        private final byte[] value;

        public TelemetryTriple(long timestampMs, int datapointId, byte[] value) {
            this.timestampMs = timestampMs;
            this.datapointId = datapointId;
            this.value = value != null ? value.clone() : new byte[0];
        }

        public long getTimestampMs() {
            return timestampMs;
        }

        public int getDatapointId() {
            return datapointId;
        }

        public byte[] getValue() {
            return value.clone();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TelemetryTriple)) return false;
            TelemetryTriple that = (TelemetryTriple) o;
            return timestampMs == that.timestampMs &&
                    datapointId == that.datapointId &&
                    Arrays.equals(value, that.value);
        }

        @Override
        public int hashCode() {
            int result = Long.hashCode(timestampMs);
            result = 31 * result + datapointId;
            result = 31 * result + Arrays.hashCode(value);
            return result;
        }

        @Override
        public String toString() {
            return "TelemetryTriple(timestampMs=" + timestampMs + ", datapointId=" + datapointId + ", value=" + Arrays.toString(value) + ")";
        }
    }

    public static final class RcmState {
        private final boolean active;
        private final Map<HandlebarButton, Boolean> pressed;

        public RcmState(boolean active, Map<HandlebarButton, Boolean> pressed) {
            this.active = active;
            this.pressed = pressed != null ? Collections.unmodifiableMap(new LinkedHashMap<>(pressed)) : Collections.emptyMap();
        }

        public boolean getActive() {
            return active;
        }

        public Map<HandlebarButton, Boolean> getPressed() {
            return pressed;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof RcmState)) return false;
            RcmState rcmState = (RcmState) o;
            return active == rcmState.active && Objects.equals(pressed, rcmState.pressed);
        }

        @Override
        public int hashCode() {
            return (Boolean.hashCode(active) * 31) + (pressed != null ? pressed.hashCode() : 0);
        }

        @Override
        public String toString() {
            return "RcmState(active=" + active + ", pressed=" + pressed + ")";
        }
    }

    private static UUID uuid(String suffix) {
        return UUID.fromString("71ced1ac-" + suffix + "-44f5-9454-806ff70b3e02");
    }

    private BccuProtocol() {
    }

    public byte[] buildTurnIconPayload(TurnIcon icon, Visibility visibility) {
        if (icon == null) {
            icon = TurnIcon.UNDEFINED;
        }
        if (visibility == null) {
            visibility = Visibility.FULL;
        }
        return buildTurnIconPayload(icon.getBinary(), visibility);
    }

    public byte[] buildTurnIconPayload(int iconBinary, Visibility visibility) {
        if (visibility == null) {
            visibility = Visibility.FULL;
        }
        return new byte[]{(byte) visibility.getBinary(), (byte) iconBinary};
    }

    public byte[] buildNotificationPayload(NotificationIcon icon, String text, Visibility visibility) {
        if (icon == null) {
            icon = NotificationIcon.NOTIFICATION_WAYPOINT;
        }
        return buildNotificationPayload(icon.getBinary(), text, visibility);
    }

    public byte[] buildNotificationPayload(int iconBinary, String text, Visibility visibility) {
        if (visibility == null) {
            visibility = Visibility.FULL;
        }
        String ascii = DashText.asciiOnly(text);
        if (ascii.length() > 16) {
            ascii = ascii.substring(0, 16);
        }
        byte[] textBytes = ascii.getBytes(StandardCharsets.US_ASCII);
        byte[] payload = new byte[textBytes.length + 2];
        payload[0] = (byte) visibility.getBinary();
        payload[1] = (byte) iconBinary;
        System.arraycopy(textBytes, 0, payload, 2, textBytes.length);
        return payload;
    }

    public byte[] buildNavigationStatePayload(boolean guidanceOn, boolean gpsIconOn, int volume) {
        int state = (guidanceOn ? 1 : 0) | ((gpsIconOn ? 1 : 0) << 1);
        return new byte[]{(byte) state, (byte) (volume & 0xFF)};
    }

    public static String elipse(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLen) {
            return text;
        }
        if (maxLen <= 3) {
            return text.substring(0, maxLen);
        }
        return text.substring(0, maxLen - 3) + "...";
    }

    private byte[] buildLabelPayload(String text, int maxLen, Visibility visibility) {
        if (visibility == null) {
            visibility = Visibility.FULL;
        }
        byte[] bytes = elipse(DashText.asciiClean(text), maxLen).getBytes(StandardCharsets.US_ASCII);
        byte[] payload = new byte[bytes.length + 1];
        payload[0] = (byte) visibility.getBinary();
        System.arraycopy(bytes, 0, payload, 1, bytes.length);
        return payload;
    }

    public byte[] buildTurnDistancePayload(String text, Visibility visibility) {
        return buildLabelPayload(text != null ? text : "", 8, visibility);
    }

    public byte[] buildTurnInfoPayload(String text, Visibility visibility) {
        return buildLabelPayload(text != null ? text : "", 16, visibility);
    }

    public byte[] buildTurnRoadPayload(String text, Visibility visibility) {
        return buildLabelPayload(text != null ? text : "", 32, visibility);
    }

    public byte[] buildEtaPayload(String text, Visibility visibility) {
        return buildLabelPayload(text != null ? text : "", 8, visibility);
    }

    public byte[] buildRemainingDistancePayload(String text, Visibility visibility) {
        return buildLabelPayload(text != null ? text : "", 8, visibility);
    }

    public byte[] buildTelemetryConfigureRequest(int sid, int datapointId, int sampleRateMs) {
        return buildTelemetryConfigureRequest(sid, datapointId, sampleRateMs, false);
    }

    public byte[] buildTelemetryConfigureRequest(int sid, int datapointId, int sampleRateMs, boolean u16SampleRate) {
        ByteBuffer buf = ByteBuffer.allocate(u16SampleRate ? 8 : 10).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) RPC_OPCODE_TELEMETRY_CONFIGURE);
        buf.put((byte) sid);
        buf.put((byte) (u16SampleRate ? 4 : 6));
        buf.putShort((short) datapointId);
        if (u16SampleRate) {
            buf.putShort((short) Math.max(0, Math.min(0xFFFF, sampleRateMs)));
        } else {
            buf.putInt(sampleRateMs);
        }
        return buf.array();
    }

    public byte[] buildGetValueRequest(int sid, int datapointId) {
        ByteBuffer buf = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) RPC_OPCODE_GET_VALUE);
        buf.put((byte) sid);
        buf.put((byte) 2);
        buf.putShort((short) datapointId);
        return buf.array();
    }

    public byte[] buildSetValueRequest(int sid, int datapointId, byte[] value) {
        if (datapointId != DP_TIME_SYNC) {
            throw new IllegalArgumentException("setValue is only permitted for TimeSync (dp " + DP_TIME_SYNC + ")");
        }
        if (value == null || value.length != 8) {
            throw new IllegalArgumentException("TimeSync value must be a T_UINT64");
        }
        ByteBuffer buf = ByteBuffer.allocate(4 + 2 + value.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) RPC_OPCODE_SET_VALUE);
        buf.put((byte) sid);
        buf.put((byte) (2 + value.length));
        buf.putShort((short) datapointId);
        buf.put(value);
        return buf.array();
    }

    public byte[] buildTimeSyncRequest(int sid, long unixMs) {
        byte[] value = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(unixMs).array();
        return buildSetValueRequest(sid, DP_TIME_SYNC, value);
    }

    public byte[] buildTelemetryControlRequest(int sid, int command) {
        ByteBuffer buf = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) RPC_OPCODE_TELEMETRY_CONTROL);
        buf.put((byte) sid);
        buf.put((byte) 2);
        buf.putShort((short) command);
        return buf.array();
    }

    public List<TelemetryTriple> parseTelemetryNotification(byte[] data, Function<Integer, Integer> typeLengthOf) {
        if (data == null || data.length < 4 || typeLengthOf == null) {
            return Collections.emptyList();
        }
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int opcode = buf.getShort() & 0xFFFF;
        buf.get(); // sid / flags
        if (opcode != 7) {
            return Collections.emptyList();
        }
        List<TelemetryTriple> list = new ArrayList<>();
        while (buf.remaining() >= 10) {
            long timestampMs = buf.getLong();
            int datapointId = buf.getShort() & 0xFFFF;
            Integer lenObj = typeLengthOf.apply(datapointId);
            int len = lenObj != null ? lenObj : 0;
            if (len <= 0 || buf.remaining() < len) {
                break;
            }
            byte[] value = new byte[len];
            buf.get(value);
            list.add(new TelemetryTriple(timestampMs, datapointId, value));
        }
        return Collections.unmodifiableList(list);
    }

    public static int rcmMask(byte[] decrypted) {
        if (decrypted == null || decrypted.length <= 17 || (decrypted[16] & 0xFF) != 0xFF) {
            return 0;
        }
        return decrypted[17] & 0x0F;
    }

    public static final class PrpcResponse {
        public final int opcode;
        public final int sid;
        public final int rpcError;
        public final int opError;
        public final byte[] returns;

        PrpcResponse(int opcode, int sid, int rpcError, int opError, byte[] returns) {
            this.opcode = opcode;
            this.sid = sid;
            this.rpcError = rpcError;
            this.opError = opError;
            this.returns = returns;
        }

        public boolean isSuccess() {
            return rpcError == 0 && opError == 0;
        }

        public String describe() {
            return opcodeName(opcode) + " sid=" + sid + ": " + rpcErrorName(rpcError)
                    + (rpcError == 0 ? ", " + operationErrorName(opcode, opError) : "");
        }
    }

    /** Hypothesised layout (unverified on hardware): opcode u16 | sid u8 | len u8 | rpcError u8 | opError u8 | returns. */
    public PrpcResponse parsePrpcResponse(byte[] data) {
        if (data == null || data.length < 5) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int opcode = buf.getShort() & 0xFFFF;
        int sid = buf.get() & 0xFF;
        int len = buf.get() & 0xFF;
        if (opcode < RPC_OPCODE_GET_VALUE || opcode > RPC_OPCODE_TELEMETRY_CONTROL || len < 1 || 4 + len != data.length) {
            return null;
        }
        int rpcError = buf.get() & 0xFF;
        int opError = len >= 2 ? buf.get() & 0xFF : 0;
        byte[] returns = new byte[buf.remaining()];
        buf.get(returns);
        return new PrpcResponse(opcode, sid, rpcError, opError, returns);
    }

    public static String opcodeName(int opcode) {
        switch (opcode) {
            case RPC_OPCODE_GET_VALUE: return "getValue";
            case RPC_OPCODE_SET_VALUE: return "setValue";
            case RPC_OPCODE_TELEMETRY_CONFIGURE: return "telemetryConfigure";
            case RPC_OPCODE_TELEMETRY_CONTROL: return "telemetryControl";
            default: return "opcode " + opcode;
        }
    }

    public static String rpcErrorName(int code) {
        switch (code) {
            case 0: return "RPC_SUCCESS";
            case 1: return "RPC_BUSYWORKER";
            case 2: return "RPC_ENCRYPTION_FAIL";
            default: return "RPC_ERROR_" + code;
        }
    }

    public static String operationErrorName(int opcode, int code) {
        String[] names;
        switch (opcode) {
            case RPC_OPCODE_GET_VALUE:
                names = new String[]{"GET_VALUE_SUCCESS", "GET_VALUE_NULLPOINTER", "GET_VALUE_DPNOTFOUND", "GET_VALUE_READWRITEMISSMATCH"};
                break;
            case RPC_OPCODE_SET_VALUE:
                names = new String[]{"SET_VALUE_SUCCESS", "SET_VALUE_NULLPOINTER", "SET_VALUE_DPNOTFOUND", "SET_VALUE_READWRITEMISSMATCH", "SET_VALUE_SIZEMISSMATCH"};
                break;
            case RPC_OPCODE_TELEMETRY_CONFIGURE:
                names = new String[]{"TELEMETRY_CONFIGURE_SUCCESS", "TELEMETRY_CONFIGURE_DP_NOT_TELEMETRYABLE",
                        "TELEMETRY_CONFIGURE_INVALID_DATAPOINT", "TELEMETRY_CONFIGURE_INVALID_SAMPLERATE",
                        "TELEMETRY_CONFIGURE_INTERNAL_FAILURE", "TELEMETRY_CONFIGURE_SERVICE_RUNNING",
                        "TELEMETRY_CONFIGURE_DP_NOT_AVAILABLE", "TELEMETRY_CONFIGURE_TIME_SYNC_FAILURE"};
                break;
            case RPC_OPCODE_TELEMETRY_CONTROL:
                names = new String[]{"TELEMETRY_CTRL_SUCCESS", "TELEMETRY_CTRL_INVALID_COMMAND", "TELEMETRY_CTRL_INTERNAL_FAILURE"};
                break;
            default:
                names = new String[0];
        }
        return code >= 0 && code < names.length ? names[code] : "ERROR_" + code;
    }

    public RcmState parseRcmValue(byte[] v) {
        boolean active = v != null && v.length > 16 && (v[16] == -1 || (v[16] & 0xFF) == 0xFF);
        byte b = (v != null && v.length > 17) ? v[17] : (byte) 0;
        Map<HandlebarButton, Boolean> map = new LinkedHashMap<>();
        for (HandlebarButton button : HandlebarButton.values()) {
            map.put(button, ((b >> button.getBitIndex()) & 1) == 1);
        }
        return new RcmState(active, map);
    }
}

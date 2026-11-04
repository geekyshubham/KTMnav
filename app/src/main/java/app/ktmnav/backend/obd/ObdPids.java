package app.ktmnav.backend.obd;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * SAE J1979 Mode 01 PID table, decoders and supported-PID bitmask helpers.
 */
public final class ObdPids {

    public static final int SUPPORTED_01_20 = 0x00;
    public static final int MONITOR_STATUS = 0x01;
    public static final int SUPPORTED_21_40 = 0x20;
    public static final int SUPPORTED_41_60 = 0x40;

    public enum Pid {
        RPM(0x0C, 2, "rpm", "rpm", true),
        SPEED(0x0D, 1, "speed", "km/h", true),
        THROTTLE(0x11, 1, "throttle", "%", false),
        LOAD(0x04, 1, "load", "%", false),
        COOLANT(0x05, 1, "coolant", "\u00B0C", false),
        INTAKE(0x0F, 1, "intake", "\u00B0C", false),
        OIL(0x5C, 1, "oil", "\u00B0C", false),
        AMBIENT(0x46, 1, "ambient", "\u00B0C", false),
        VOLTAGE(0x42, 2, "voltage", "V", false),
        FUEL(0x2F, 1, "fuel", "%", false),
        RUN_TIME(0x1F, 2, "run_time", "s", false),
        DISTANCE_MIL(0x21, 2, "distance_mil", "km", false),
        DISTANCE_CLEARED(0x31, 2, "distance_cleared", "km", false);

        public final int code;
        public final int bytes;
        public final String key;
        public final String unit;
        public final boolean fast;

        Pid(int code, int bytes, String key, String unit, boolean fast) {
            this.code = code;
            this.bytes = bytes;
            this.key = key;
            this.unit = unit;
            this.fast = fast;
        }

        public Double decode(int[] data) {
            if (data == null || data.length < bytes) {
                return null;
            }
            int a = data[0] & 0xFF;
            int b = bytes > 1 ? data[1] & 0xFF : 0;
            switch (this) {
                case RPM:
                    return (a * 256 + b) / 4.0;
                case SPEED:
                    return (double) a;
                case THROTTLE:
                case LOAD:
                case FUEL:
                    return a * 100.0 / 255.0;
                case COOLANT:
                case INTAKE:
                case OIL:
                case AMBIENT:
                    return a - 40.0;
                case VOLTAGE:
                    return (a * 256 + b) / 1000.0;
                case RUN_TIME:
                case DISTANCE_MIL:
                case DISTANCE_CLEARED:
                    return (double) (a * 256 + b);
                default:
                    return null;
            }
        }

        public static Pid byCode(int code) {
            for (Pid p : values()) {
                if (p.code == code) {
                    return p;
                }
            }
            return null;
        }
    }

    public static final class MonitorStatus {
        public final boolean milOn;
        public final int dtcCount;

        MonitorStatus(boolean milOn, int dtcCount) {
            this.milOn = milOn;
            this.dtcCount = dtcCount;
        }
    }

    private ObdPids() {
    }

    public static Set<Integer> decodeSupported(int basePid, int[] data) {
        Set<Integer> out = new TreeSet<>();
        if (data == null || data.length < 4) {
            return out;
        }
        for (int byteIdx = 0; byteIdx < 4; byteIdx++) {
            int v = data[byteIdx] & 0xFF;
            for (int bit = 0; bit < 8; bit++) {
                if ((v & (0x80 >> bit)) != 0) {
                    out.add(basePid + byteIdx * 8 + bit + 1);
                }
            }
        }
        return out;
    }

    public static boolean hasNextRange(int basePid, Set<Integer> supported) {
        return supported != null && supported.contains(basePid + 0x20);
    }

    public static MonitorStatus decodeMonitorStatus(int[] data) {
        if (data == null || data.length < 1) {
            return null;
        }
        int a = data[0] & 0xFF;
        return new MonitorStatus((a & 0x80) != 0, a & 0x7F);
    }

    public static List<Pid> pollable(Set<Integer> supported, boolean fast) {
        List<Pid> out = new ArrayList<>();
        for (Pid p : Pid.values()) {
            if (p.fast == fast && supported != null && supported.contains(p.code)) {
                out.add(p);
            }
        }
        return Collections.unmodifiableList(out);
    }
}

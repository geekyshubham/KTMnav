package app.ktmnav.backend.obd;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Pure selection of the BLE UART service/characteristics an ELM327 clone exposes, plus adapter-name heuristics.
 */
public final class ObdUartProfile {

    public static final int PROP_WRITE_NO_RESPONSE = 0x04;
    public static final int PROP_WRITE = 0x08;
    public static final int PROP_NOTIFY = 0x10;
    public static final int PROP_INDICATE = 0x20;

    public static final UUID CCCD = uuid16(0x2902);
    public static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    public static final class Choice {
        public final UUID service;
        public final UUID write;
        public final UUID notify;

        Choice(UUID service, UUID write, UUID notify) {
            this.service = service;
            this.write = write;
            this.notify = notify;
        }
    }

    private static final UUID[][] KNOWN = {
            {uuid16(0xFFF0), uuid16(0xFFF2), uuid16(0xFFF1)},
            {uuid16(0xFFE0), uuid16(0xFFE1), uuid16(0xFFE1)},
            {uuid16(0x18F0), uuid16(0x2AF1), uuid16(0x2AF0)},
            {UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E"),
                    UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E"),
                    UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")}};

    private static final List<UUID> IGNORED_SERVICES = Collections.unmodifiableList(Arrays.asList(
            uuid16(0x1800), uuid16(0x1801), uuid16(0x180A), uuid16(0x180F)));

    public static final List<String> NAME_HINTS = Collections.unmodifiableList(Arrays.asList(
            "OBD", "ELM", "VGATE", "VLINK", "OBDII", "VIECAR", "LELINK", "IOS-VLINK", "KONNWEI"));

    private ObdUartProfile() {
    }

    public static UUID uuid16(int shortId) {
        return UUID.fromString(String.format(Locale.ROOT, "0000%04X-0000-1000-8000-00805F9B34FB", shortId & 0xFFFF));
    }

    public static List<UUID> knownServices() {
        UUID[] out = new UUID[KNOWN.length];
        for (int i = 0; i < KNOWN.length; i++) {
            out[i] = KNOWN[i][0];
        }
        return Collections.unmodifiableList(Arrays.asList(out));
    }

    public static boolean isLikelyAdapterName(String name) {
        if (name == null) {
            return false;
        }
        String n = compactName(name);
        for (String hint : NAME_HINTS) {
            if (n.contains(compactName(hint))) {
                return true;
            }
        }
        return false;
    }

    private static String compactName(String s) {
        return s.toUpperCase(Locale.ROOT).replace(" ", "").replace("-", "").replace("_", "");
    }

    public static boolean offersKnownService(List<UUID> advertised) {
        if (advertised == null) {
            return false;
        }
        for (UUID[] k : KNOWN) {
            if (advertised.contains(k[0])) {
                return true;
            }
        }
        return false;
    }

    public static Choice choose(Map<UUID, Map<UUID, Integer>> services) {
        if (services == null) {
            return null;
        }
        for (UUID[] k : KNOWN) {
            Map<UUID, Integer> chars = services.get(k[0]);
            if (chars == null) {
                continue;
            }
            Integer wp = chars.get(k[1]);
            Integer np = chars.get(k[2]);
            if (wp != null && np != null && canWrite(wp) && canNotify(np)) {
                return new Choice(k[0], k[1], k[2]);
            }
        }
        for (Map.Entry<UUID, Map<UUID, Integer>> s : services.entrySet()) {
            if (IGNORED_SERVICES.contains(s.getKey())) {
                continue;
            }
            UUID write = null;
            UUID notify = null;
            int writers = 0;
            int notifiers = 0;
            for (Map.Entry<UUID, Integer> c : s.getValue().entrySet()) {
                if (canWrite(c.getValue())) {
                    writers++;
                    write = c.getKey();
                }
                if (canNotify(c.getValue())) {
                    notifiers++;
                    notify = c.getKey();
                }
            }
            if (writers == 1 && notifiers == 1) {
                return new Choice(s.getKey(), write, notify);
            }
        }
        return null;
    }

    public static boolean canWrite(int props) {
        return (props & (PROP_WRITE | PROP_WRITE_NO_RESPONSE)) != 0;
    }

    public static boolean canNotify(int props) {
        return (props & (PROP_NOTIFY | PROP_INDICATE)) != 0;
    }

    public static boolean prefersNoResponse(int props) {
        return (props & PROP_WRITE) == 0 && (props & PROP_WRITE_NO_RESPONSE) != 0;
    }

    public static List<byte[]> chunk(byte[] data, int size) {
        java.util.ArrayList<byte[]> out = new java.util.ArrayList<>();
        if (data == null) {
            return out;
        }
        for (int i = 0; i < data.length; i += size) {
            out.add(Arrays.copyOfRange(data, i, Math.min(data.length, i + size)));
        }
        return out;
    }
}

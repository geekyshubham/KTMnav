package app.ktmnav.backend.obd;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Decoder for Mode 03 (stored) and Mode 07 (pending) diagnostic trouble codes. Read-only.
 */
public final class ObdDtc {

    public static final int STORED_RESPONSE_MODE = 0x43;
    public static final int PENDING_RESPONSE_MODE = 0x47;

    private static final char[] SYSTEMS = {'P', 'C', 'B', 'U'};

    private ObdDtc() {
    }

    public static String decode(int a, int b) {
        int hi = a & 0xFF;
        int lo = b & 0xFF;
        if (hi == 0 && lo == 0) {
            return null;
        }
        char system = SYSTEMS[(hi >> 6) & 0x03];
        int digit = (hi >> 4) & 0x03;
        return String.format(java.util.Locale.ROOT, "%c%d%X%02X", system, digit, hi & 0x0F, lo);
    }

    public static List<String> parse(Elm327Protocol.Response response, int responseMode, Boolean canProtocol) {
        Set<String> codes = new LinkedHashSet<>();
        for (int[] payload : Elm327Protocol.findModePayloads(response, responseMode)) {
            boolean hasCount = canProtocol != null ? canProtocol : (payload.length % 2) == 1;
            int start = hasCount && payload.length > 0 ? 1 : 0;
            int end = payload.length;
            if (hasCount && payload.length > 0) {
                end = Math.min(payload.length, 1 + 2 * (payload[0] & 0xFF));
            }
            for (int i = start; i + 1 < end; i += 2) {
                String code = decode(payload[i], payload[i + 1]);
                if (code != null && !"P0000".equals(code)) {
                    codes.add(code);
                }
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(codes));
    }
}

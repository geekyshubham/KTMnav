package app.ktmnav.backend.obd;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Read-only ELM327 command builder and response parser (SAE J1979 modes 01, 03, 07, 09 only).
 */
public final class Elm327Protocol {

    public static final char PROMPT = '>';

    public static final List<String> INIT_SEQUENCE = Collections.unmodifiableList(
            Arrays.asList("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0"));
    public static final String ADAPTIVE_TIMING = "ATAT1";
    public static final String DESCRIBE_PROTOCOL_NUMBER = "ATDPN";
    public static final String IDENTIFY = "ATI";

    private static final Set<String> AT_WHITELIST = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0", "ATAT1", "ATDPN", "ATI")));

    private static final String[][] ERROR_MARKERS = {
            {"UNABLETOCONNECT", "UNABLE TO CONNECT"}, {"CANERROR", "CAN ERROR"}, {"BUSERROR", "BUS ERROR"},
            {"BUSBUSY", "BUS BUSY"}, {"BUFFERFULL", "BUFFER FULL"}, {"STOPPED", "STOPPED"},
            {"<DATAERROR", "DATA ERROR"}, {"DATAERROR", "DATA ERROR"}, {"<RXERROR", "RX ERROR"},
            {"FBERROR", "FB ERROR"}, {"ACTALERT", "ACT ALERT"}, {"LVRESET", "LV RESET"},
            {"LPALERT", "LP ALERT"}, {"ERROR", "ERROR"}};

    public enum Status { OK, DATA, NO_DATA, ERROR, EMPTY }

    public static final class Response {
        public final Status status;
        public final String error;
        public final List<String> lines;
        public final String raw;

        Response(Status status, String error, List<String> lines, String raw) {
            this.status = status;
            this.error = error;
            this.lines = Collections.unmodifiableList(lines);
            this.raw = raw;
        }

        public boolean isData() {
            return status == Status.DATA;
        }

        public boolean isOk() {
            return status == Status.OK || status == Status.DATA;
        }
    }

    private Elm327Protocol() {
    }

    public static String normalize(String command) {
        if (command == null) {
            return "";
        }
        return command.replace(" ", "").replace("\r", "").replace("\n", "").trim().toUpperCase(Locale.ROOT);
    }

    public static boolean isAllowed(String command) {
        String c = normalize(command);
        if (c.isEmpty()) {
            return false;
        }
        if (c.startsWith("AT")) {
            return AT_WHITELIST.contains(c);
        }
        if (!isHex(c) || (c.length() % 2) != 0) {
            return false;
        }
        String mode = c.substring(0, 2);
        switch (mode) {
            case "01":
                return c.length() == 4;
            case "03":
            case "07":
                return c.length() == 2;
            case "09":
                return c.equals("0902");
            default:
                return false;
        }
    }

    public static String build(String command) {
        if (!isAllowed(command)) {
            throw new IllegalArgumentException("OBD command not permitted (read-only whitelist): " + normalize(command));
        }
        return normalize(command) + "\r";
    }

    public static byte[] buildBytes(String command) {
        return build(command).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    public static String mode01(int pid) {
        return "01" + hex2(pid);
    }

    public static String readStoredDtcs() {
        return "03";
    }

    public static String readPendingDtcs() {
        return "07";
    }

    public static String readVin() {
        return "0902";
    }

    public static Response parse(String raw, String sentCommand) {
        String text = raw == null ? "" : raw;
        String echo = normalize(sentCommand);
        List<String> lines = new ArrayList<>();
        String error = null;
        boolean noData = false;
        boolean ok = false;
        String cleaned = text.replace(String.valueOf(PROMPT), "\r").replace('\n', '\r');
        for (String part : cleaned.split("\r")) {
            String line = part.trim().toUpperCase(Locale.ROOT);
            String compact = line.replace(" ", "");
            if (compact.isEmpty() || compact.equals(echo)) {
                continue;
            }
            if (!echo.isEmpty() && compact.length() > echo.length() && compact.startsWith(echo)
                    && isConcatenatedEcho(echo, compact.substring(echo.length()))) {
                compact = compact.substring(echo.length());
            }
            while (compact.startsWith("SEARCHING...")) {
                compact = compact.substring("SEARCHING...".length());
            }
            if (compact.startsWith("BUSINIT")) {
                String rest = compact.substring("BUSINIT".length());
                if (rest.contains("ERROR")) {
                    error = "BUS INIT ERROR";
                    continue;
                }
                rest = rest.replace(":", "").replace("...", "");
                if (rest.startsWith("OK")) {
                    rest = rest.substring(2);
                }
                compact = rest;
            }
            if (compact.isEmpty()) {
                continue;
            }
            if (compact.equals("NODATA")) {
                noData = true;
                continue;
            }
            if (compact.equals("OK")) {
                ok = true;
                continue;
            }
            String marker = matchError(compact);
            if (marker != null) {
                error = marker;
                continue;
            }
            lines.add(compact);
        }
        if (!lines.isEmpty()) {
            return new Response(Status.DATA, error, lines, text);
        }
        if (error != null) {
            return new Response(Status.ERROR, error, lines, text);
        }
        if (noData) {
            return new Response(Status.NO_DATA, null, lines, text);
        }
        if (ok) {
            return new Response(Status.OK, null, lines, text);
        }
        return new Response(Status.EMPTY, null, lines, text);
    }

    private static boolean isConcatenatedEcho(String echo, String rest) {
        if (echo.startsWith("AT")) {
            return true;
        }
        if (!isHex(echo) || echo.length() < 2) {
            return false;
        }
        int mode = Integer.parseInt(echo.substring(0, 2), 16);
        return rest.startsWith(hex2(mode + 0x40)) || rest.startsWith("SEARCHING") || rest.startsWith("NODATA");
    }

    private static String matchError(String compact) {
        if (compact.equals("?") || compact.endsWith("?")) {
            return "?";
        }
        for (String[] marker : ERROR_MARKERS) {
            if (compact.startsWith(marker[0])) {
                return marker[1];
            }
        }
        if (compact.endsWith("ERROR")) {
            return compact;
        }
        return null;
    }

    public static int[] findMode01Payload(Response response, int pid) {
        if (response == null || !response.isData()) {
            return null;
        }
        String key = "41" + hex2(pid);
        for (String message : assembleMessages(response.lines)) {
            int[] payload = payloadAfter(message, key);
            if (payload != null) {
                return payload;
            }
        }
        return null;
    }

    public static List<int[]> findModePayloads(Response response, int responseMode) {
        List<int[]> out = new ArrayList<>();
        if (response == null || !response.isData()) {
            return out;
        }
        String key = hex2(responseMode);
        for (String message : assembleMessages(response.lines)) {
            int[] payload = payloadAfter(message, key);
            if (payload != null) {
                out.add(payload);
            }
        }
        return out;
    }

    static List<String> assembleMessages(List<String> lines) {
        List<String> messages = new ArrayList<>();
        StringBuilder multi = null;
        for (String line : lines) {
            if (line.length() == 3 && isHex(line) && multi == null) {
                multi = new StringBuilder();
                continue;
            }
            if (line.length() >= 2 && line.charAt(1) == ':' && isHex(line.substring(0, 1))) {
                String body = line.substring(2);
                if (line.charAt(0) == '0' && multi != null && multi.length() > 0) {
                    messages.add(multi.toString());
                    multi = new StringBuilder();
                }
                if (multi == null) {
                    multi = new StringBuilder();
                }
                multi.append(body);
                continue;
            }
            if (multi != null && multi.length() > 0) {
                messages.add(multi.toString());
            }
            multi = null;
            messages.add(stripHeader(line));
        }
        if (multi != null && multi.length() > 0) {
            messages.add(multi.toString());
        }
        return messages;
    }

    static String stripHeader(String line) {
        if (!isHex(line)) {
            return line;
        }
        if (line.length() % 2 == 1 && line.length() >= 7) {
            int pci = Integer.parseInt(line.substring(3, 5), 16);
            if (pci >= 1 && pci <= 7 && line.length() - 5 >= pci * 2) {
                return line.substring(5, 5 + pci * 2);
            }
        }
        if (line.length() >= 12 && line.startsWith("18DA")) {
            int pci = Integer.parseInt(line.substring(8, 10), 16);
            if (pci >= 1 && pci <= 7 && line.length() - 10 >= pci * 2) {
                return line.substring(10, 10 + pci * 2);
            }
        }
        return line;
    }

    private static int[] payloadAfter(String message, String key) {
        if (!isHex(message)) {
            return null;
        }
        int idx = -1;
        for (int i = 0; i + key.length() <= message.length(); i += 2) {
            if (message.startsWith(key, i)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            return null;
        }
        String rest = message.substring(idx + key.length());
        int[] out = new int[rest.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = Integer.parseInt(rest.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    public static String parseProtocolNumber(Response response) {
        if (response == null || response.lines.isEmpty()) {
            return null;
        }
        String p = response.lines.get(0);
        return p.isEmpty() || p.length() > 2 ? null : p;
    }

    public static boolean isCanProtocol(String protocolNumber) {
        if (protocolNumber == null || protocolNumber.isEmpty()) {
            return false;
        }
        char c = protocolNumber.charAt(protocolNumber.length() - 1);
        return (c >= '6' && c <= '9') || c == 'A' || c == 'B' || c == 'C';
    }

    public static String describeProtocol(String protocolNumber) {
        if (protocolNumber == null || protocolNumber.isEmpty()) {
            return "unknown";
        }
        boolean auto = protocolNumber.length() == 2 && protocolNumber.charAt(0) == 'A';
        char c = protocolNumber.charAt(protocolNumber.length() - 1);
        String name;
        switch (c) {
            case '1': name = "SAE J1850 PWM"; break;
            case '2': name = "SAE J1850 VPW"; break;
            case '3': name = "ISO 9141-2"; break;
            case '4': name = "ISO 14230-4 KWP (5 baud init)"; break;
            case '5': name = "ISO 14230-4 KWP (fast init)"; break;
            case '6': name = "ISO 15765-4 CAN 11-bit 500k"; break;
            case '7': name = "ISO 15765-4 CAN 29-bit 500k"; break;
            case '8': name = "ISO 15765-4 CAN 11-bit 250k"; break;
            case '9': name = "ISO 15765-4 CAN 29-bit 250k"; break;
            case 'A': name = "SAE J1939 CAN"; break;
            case 'B': name = "USER1 CAN"; break;
            case 'C': name = "USER2 CAN"; break;
            default: name = "unknown"; break;
        }
        return auto ? name + " (auto)" : name;
    }

    static boolean isHex(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            boolean hex = (ch >= '0' && ch <= '9') || (ch >= 'A' && ch <= 'F') || (ch >= 'a' && ch <= 'f');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    static String hex2(int value) {
        return String.format(Locale.ROOT, "%02X", value & 0xFF);
    }
}

package app.ktmnav.backend.obd;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Pure-Java ELM327 simulator used as an {@link ObdLink} test double.
 */
final class FakeElm327 implements ObdLink {

    final Map<Integer, String> pidData = new LinkedHashMap<>();
    final Set<Integer> noDataPids = new HashSet<>();
    final Set<String> silentCommands = new HashSet<>();
    final List<String> storedDtcs = new ArrayList<>();
    final List<String> pendingDtcs = new ArrayList<>();
    final List<String> received = Collections.synchronizedList(new ArrayList<>());

    String name = "OBDII-FAKE";
    String protocol = "A6";
    boolean can = true;
    boolean ecuPresent = true;
    boolean echoAlways = false;
    boolean searching = true;
    boolean milOn = false;
    long delayMs = 0L;
    int chunkSize = 20;

    private boolean echo = true;
    private boolean spaces = true;
    private boolean searched = false;
    private final StringBuilder rx = new StringBuilder();
    private Listener listener;
    private ScheduledExecutorService executor;
    private volatile boolean open = false;

    FakeElm327 withPid(int pid, String hexData) {
        pidData.put(pid, hexData.replace(" ", ""));
        return this;
    }

    @Override
    public void open(Listener l) {
        this.listener = l;
        this.open = true;
        if (delayMs > 0) {
            executor = Executors.newSingleThreadScheduledExecutor();
        }
        l.onOpened(name);
    }

    @Override
    public synchronized boolean write(byte[] data) {
        if (!open) {
            return false;
        }
        rx.append(new String(data, StandardCharsets.US_ASCII));
        int idx;
        while ((idx = rx.indexOf("\r")) >= 0) {
            String cmd = rx.substring(0, idx).trim().toUpperCase(Locale.ROOT);
            rx.delete(0, idx + 1);
            received.add(cmd);
            if (silentCommands.contains(cmd)) {
                continue;
            }
            String body = respond(cmd);
            String full = ((echo || echoAlways) ? cmd + "\r" : "") + body + "\r\r>";
            deliver(full);
        }
        return true;
    }

    @Override
    public void close() {
        boolean was = open;
        open = false;
        if (executor != null) {
            executor.shutdownNow();
        }
        if (was && listener != null) {
            listener.onClosed("Closed");
        }
    }

    void dropLink(String reason) {
        open = false;
        if (listener != null) {
            listener.onClosed(reason);
        }
    }

    @Override
    public String getName() {
        return name;
    }

    private void deliver(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        Runnable r = () -> {
            for (int i = 0; i < bytes.length && open; i += chunkSize) {
                int end = Math.min(bytes.length, i + chunkSize);
                byte[] chunk = new byte[end - i];
                System.arraycopy(bytes, i, chunk, 0, chunk.length);
                listener.onBytes(chunk);
            }
        };
        if (executor != null) {
            executor.schedule(r, delayMs, TimeUnit.MILLISECONDS);
        } else {
            r.run();
        }
    }

    private String respond(String cmd) {
        switch (cmd) {
            case "ATZ":
                echo = true;
                spaces = true;
                searched = false;
                return "\rELM327 v1.5";
            case "ATI":
                return "ELM327 v1.5";
            case "ATE0":
                echo = false;
                return "OK";
            case "ATS0":
                spaces = false;
                return "OK";
            case "ATL0":
            case "ATH0":
            case "ATSP0":
            case "ATAT1":
                return "OK";
            case "ATDPN":
                return protocol;
            default:
                break;
        }
        if (cmd.startsWith("AT")) {
            return "?";
        }
        String prefix = "";
        if (searching && !searched) {
            searched = true;
            prefix = "SEARCHING...\r";
        }
        if (!ecuPresent) {
            return prefix + "UNABLE TO CONNECT";
        }
        if (cmd.length() == 4 && cmd.startsWith("01")) {
            int pid = Integer.parseInt(cmd.substring(2), 16);
            String data = mode01(pid);
            return prefix + (data == null ? "NO DATA" : format("41" + cmd.substring(2) + data));
        }
        if (cmd.equals("03")) {
            return prefix + dtcResponse("43", storedDtcs);
        }
        if (cmd.equals("07")) {
            return prefix + dtcResponse("47", pendingDtcs);
        }
        return "?";
    }

    private String mode01(int pid) {
        if (noDataPids.contains(pid)) {
            return null;
        }
        if (pid == 0x00 || pid == 0x20 || pid == 0x40) {
            return supportMask(pid);
        }
        if (pid == 0x01) {
            int a = (milOn ? 0x80 : 0) | (storedDtcs.size() & 0x7F);
            return String.format(Locale.ROOT, "%02X070000", a);
        }
        return pidData.get(pid);
    }

    private String supportMask(int base) {
        Set<Integer> all = new HashSet<>(pidData.keySet());
        all.addAll(noDataPids);
        all.add(0x01);
        boolean hasHigher = false;
        for (int p : all) {
            if (p > base + 0x20) {
                hasHigher = true;
            }
        }
        if (base > 0 && !anyInRange(all, base)) {
            return null;
        }
        long mask = 0;
        for (int p : all) {
            if (p > base && p <= base + 0x20) {
                mask |= 1L << (32 - (p - base));
            }
        }
        if (hasHigher) {
            mask |= 1L;
        }
        return String.format(Locale.ROOT, "%08X", mask);
    }

    private boolean anyInRange(Set<Integer> all, int base) {
        for (int p : all) {
            if (p > base) {
                return true;
            }
        }
        return false;
    }

    private String dtcResponse(String mode, List<String> codes) {
        StringBuilder bytes = new StringBuilder();
        for (String c : codes) {
            bytes.append(encodeDtc(c));
        }
        if (can) {
            String payload = mode + String.format(Locale.ROOT, "%02X", codes.size()) + bytes;
            int len = payload.length() / 2;
            if (len <= 7) {
                return format(payload);
            }
            StringBuilder out = new StringBuilder(String.format(Locale.ROOT, "%03X", len));
            int frame = 0;
            int pos = 0;
            while (pos < payload.length()) {
                int take = frame == 0 ? 12 : 14;
                String part = payload.substring(pos, Math.min(payload.length(), pos + take));
                out.append('\r').append(Integer.toHexString(frame % 16).toUpperCase(Locale.ROOT)).append(':')
                        .append(spaces ? " " : "").append(format(part));
                pos += take;
                frame++;
            }
            return out.toString();
        }
        if (codes.isEmpty()) {
            return format(mode + "000000000000");
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < codes.size(); i += 3) {
            StringBuilder line = new StringBuilder(mode);
            for (int j = i; j < i + 3; j++) {
                line.append(j < codes.size() ? encodeDtc(codes.get(j)) : "0000");
            }
            if (out.length() > 0) {
                out.append('\r');
            }
            out.append(format(line.toString()));
        }
        return out.toString();
    }

    static String encodeDtc(String code) {
        int system = "PCBU".indexOf(code.charAt(0));
        int digit = code.charAt(1) - '0';
        int rest = Integer.parseInt(code.substring(2), 16);
        int hi = (system << 6) | (digit << 4) | ((rest >> 8) & 0x0F);
        return String.format(Locale.ROOT, "%02X%02X", hi, rest & 0xFF);
    }

    private String format(String hex) {
        if (!spaces) {
            return hex;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hex.length(); i += 2) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(hex, i, Math.min(hex.length(), i + 2));
        }
        return sb.toString();
    }

    int count(String cmd) {
        int n = 0;
        synchronized (received) {
            for (String c : received) {
                if (c.equals(cmd)) {
                    n++;
                }
            }
        }
        return n;
    }
}

package app.ktmnav.backend.obd;

import app.ktmnav.backend.AppLog;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One OBD-II session over an {@link ObdLink}: ELM327 init, supported-PID discovery, rotating Mode 01 polling
 * and on-demand Mode 03/07 DTC reads. Strictly read-only.
 */
public final class ObdSession {

    private static final String TAG = "Obd";
    private static final Pattern ELM_VERSION = Pattern.compile("(ELM327\\s*V?[0-9.]+[A-Z]?)", Pattern.CASE_INSENSITIVE);
    public static final int MAX_PID_FAILURES = 2;

    public interface Listener {
        void onSnapshot(ObdSession session, ObdSnapshot snapshot);

        void onEnded(ObdSession session, String reason, boolean reachedConnected);
    }

    public static final class Options {
        public long commandTimeoutMs = 2500L;
        public long resetTimeoutMs = 5000L;
        public long minCommandIntervalMs = 220L;
        public long drainMs = 600L;
        public long noEcuRetryMs = 5000L;
        public int slowEvery = 5;
        public int maxConsecutiveTimeouts = 3;
        public boolean adaptiveTiming = true;
    }

    private final ObdLink link;
    private final Options options;
    private final LongSupplier clock;
    private final Listener listener;
    private final ElmChannel channel;

    private final Object stateLock = new Object();
    private ObdSnapshot.Builder builder = new ObdSnapshot.Builder();
    private volatile ObdSnapshot snapshot = ObdSnapshot.EMPTY;

    private final Set<Integer> supported = new TreeSet<>();
    private final Map<Integer, Integer> failures = new HashMap<>();
    private final Set<Integer> dropped = new HashSet<>();
    private String protocolNumber;
    private int cycle = 0;
    private int consecutiveTimeouts = 0;
    private long lastCommandAtNs = 0L;
    private boolean reachedConnected = false;

    private volatile boolean running = false;
    private volatile boolean stopRequested = false;
    private volatile boolean dtcRequested = false;
    private volatile boolean ended = false;
    private Thread worker;

    public ObdSession(ObdLink link, Options options, LongSupplier clock, Listener listener) {
        this.link = link;
        this.options = options != null ? options : new Options();
        this.clock = clock != null ? clock : System::currentTimeMillis;
        this.listener = listener;
        this.channel = new ElmChannel(link, this.options.drainMs);
        synchronized (stateLock) {
            builder.adapterName(link.getName());
        }
    }

    public ObdSnapshot getSnapshot() {
        return snapshot;
    }

    public ElmChannel channel() {
        return channel;
    }

    public synchronized void start() {
        if (running || ended) {
            return;
        }
        running = true;
        publish("Connecting to " + link.getName(), false);
        link.open(new ObdLink.Listener() {
            @Override
            public void onOpened(String adapterName) {
                startWorker(adapterName);
            }

            @Override
            public void onBytes(byte[] data) {
                channel.onBytes(data);
            }

            @Override
            public void onClosed(String reason) {
                channel.onClosed(reason);
                Thread w;
                synchronized (ObdSession.this) {
                    w = worker;
                }
                if (w == null) {
                    finish(reason != null ? reason : "Adapter disconnected");
                }
            }
        });
    }

    void attachWithoutWorker() {
        link.open(new ObdLink.Listener() {
            @Override
            public void onOpened(String adapterName) {
            }

            @Override
            public void onBytes(byte[] data) {
                channel.onBytes(data);
            }

            @Override
            public void onClosed(String reason) {
                channel.onClosed(reason);
            }
        });
    }

    public void stop() {
        Thread w;
        synchronized (this) {
            running = false;
            stopRequested = true;
            w = worker;
        }
        channel.onClosed("Stopped");
        if (w != null) {
            w.interrupt();
        }
        link.close();
        finish("Stopped");
    }

    public void requestDtcRead() {
        dtcRequested = true;
    }

    public boolean isRunning() {
        return running && !ended;
    }

    private synchronized void startWorker(String adapterName) {
        if (!running || worker != null) {
            return;
        }
        if (adapterName != null && !adapterName.isEmpty()) {
            synchronized (stateLock) {
                builder.adapterName(adapterName);
            }
        }
        worker = new Thread(this::runLoop, "ktmnav-obd");
        worker.setDaemon(true);
        worker.start();
    }

    private void runLoop() {
        String reason = "Stopped";
        try {
            publish("Initialising adapter", false);
            if (!initialize()) {
                reason = "Adapter did not answer ATZ";
                return;
            }
            while (!stopRequested && !discover()) {
                publish("No ECU response (ignition on?)", false);
                sleepMs(options.noEcuRetryMs);
            }
            if (stopRequested) {
                return;
            }
            reachedConnected = true;
            publish(liveStateText(), true);
            while (!stopRequested) {
                if (dtcRequested) {
                    dtcRequested = false;
                    readDtcs();
                }
                pollCycle();
            }
        } catch (ObdException e) {
            if (stopRequested) {
                reason = "Stopped";
            } else {
                reason = e.kind == ObdException.Kind.TIMEOUT ? "Adapter not responding" : e.getMessage();
            }
        } catch (RuntimeException e) {
            AppLog.e(TAG, "OBD session crashed", e);
            reason = "Internal error: " + e.getClass().getSimpleName();
        } finally {
            link.close();
            finish(reason);
        }
    }

    private void finish(String reason) {
        synchronized (this) {
            if (ended) {
                return;
            }
            ended = true;
            running = false;
        }
        AppLog.i(TAG, "Session ended: " + reason);
        publish(reason, false);
        if (listener != null) {
            listener.onEnded(this, reason, reachedConnected);
        }
    }

    boolean initialize() throws ObdException {
        String reset = null;
        for (int attempt = 0; attempt < 2 && reset == null; attempt++) {
            try {
                reset = send("ATZ", options.resetTimeoutMs);
            } catch (ObdException e) {
                if (e.kind != ObdException.Kind.TIMEOUT) {
                    throw e;
                }
                AppLog.w(TAG, "ATZ timed out (attempt " + (attempt + 1) + ")");
            }
        }
        if (reset == null) {
            return false;
        }
        consecutiveTimeouts = 0;
        Matcher m = ELM_VERSION.matcher(reset);
        if (m.find()) {
            String version = m.group(1).trim();
            synchronized (stateLock) {
                builder.elmVersion(version);
            }
            AppLog.i(TAG, "Adapter: " + version);
        }
        List<String> sequence = new ArrayList<>(Elm327Protocol.INIT_SEQUENCE.subList(1, Elm327Protocol.INIT_SEQUENCE.size()));
        if (options.adaptiveTiming) {
            sequence.add(Elm327Protocol.ADAPTIVE_TIMING);
        }
        for (String cmd : sequence) {
            Elm327Protocol.Response r = Elm327Protocol.parse(send(cmd, options.commandTimeoutMs), cmd);
            if (!r.isOk()) {
                AppLog.w(TAG, cmd + " not accepted (" + r.status + "), continuing");
            }
        }
        republish();
        return true;
    }

    boolean discover() throws ObdException {
        Elm327Protocol.Response first;
        try {
            first = query(ObdPids.SUPPORTED_01_20, options.resetTimeoutMs);
        } catch (ObdException e) {
            if (e.kind != ObdException.Kind.TIMEOUT) {
                throw e;
            }
            consecutiveTimeouts = 0;
            return false;
        }
        int[] mask = Elm327Protocol.findMode01Payload(first, ObdPids.SUPPORTED_01_20);
        if (mask == null) {
            AppLog.w(TAG, "0100 failed: " + first.status + (first.error != null ? " " + first.error : ""));
            return false;
        }
        Set<Integer> found = new TreeSet<>(ObdPids.decodeSupported(ObdPids.SUPPORTED_01_20, mask));
        try {
            String dpn = send(Elm327Protocol.DESCRIBE_PROTOCOL_NUMBER, options.commandTimeoutMs);
            protocolNumber = Elm327Protocol.parseProtocolNumber(Elm327Protocol.parse(dpn, Elm327Protocol.DESCRIBE_PROTOCOL_NUMBER));
        } catch (ObdException e) {
            if (e.kind != ObdException.Kind.TIMEOUT) {
                throw e;
            }
            protocolNumber = null;
        }
        for (int base : new int[]{ObdPids.SUPPORTED_21_40, ObdPids.SUPPORTED_41_60}) {
            if (!found.contains(base)) {
                break;
            }
            int[] more = Elm327Protocol.findMode01Payload(query(base, options.commandTimeoutMs), base);
            if (more == null) {
                break;
            }
            found.addAll(ObdPids.decodeSupported(base, more));
        }
        synchronized (stateLock) {
            supported.clear();
            supported.addAll(found);
            builder.supported(new ArrayList<>(found));
            builder.protocol(protocolNumber, Elm327Protocol.describeProtocol(protocolNumber));
        }
        AppLog.i(TAG, "Protocol " + Elm327Protocol.describeProtocol(protocolNumber) + ", supported PIDs " + hexList(found));
        republish();
        return true;
    }

    void pollCycle() throws ObdException {
        cycle++;
        List<ObdPids.Pid> fast = active(true);
        boolean slowTurn = fast.isEmpty() || options.slowEvery <= 1 || cycle % options.slowEvery == 0;
        for (ObdPids.Pid pid : fast) {
            if (stopRequested) {
                return;
            }
            poll(pid);
        }
        if (slowTurn) {
            if (supported.contains(ObdPids.MONITOR_STATUS) && !dropped.contains(ObdPids.MONITOR_STATUS)) {
                pollMonitorStatus();
            }
            for (ObdPids.Pid pid : active(false)) {
                if (stopRequested) {
                    return;
                }
                poll(pid);
            }
        }
        if (fast.isEmpty() && active(false).isEmpty()) {
            sleepMs(1000L);
        }
        publish(liveStateText(), true);
    }

    void readDtcs() throws ObdException {
        Boolean can = protocolNumber != null ? Elm327Protocol.isCanProtocol(protocolNumber) : null;
        if (supported.contains(ObdPids.MONITOR_STATUS)) {
            pollMonitorStatus();
        }
        List<String> stored = readDtcMode(Elm327Protocol.readStoredDtcs(), ObdDtc.STORED_RESPONSE_MODE, can);
        List<String> pending = readDtcMode(Elm327Protocol.readPendingDtcs(), ObdDtc.PENDING_RESPONSE_MODE, can);
        synchronized (stateLock) {
            builder.dtcs(stored, pending, clock.getAsLong());
        }
        AppLog.i(TAG, "DTC read: stored=" + stored + " pending=" + pending);
        publish(liveStateText(), true);
    }

    private List<String> readDtcMode(String cmd, int responseMode, Boolean can) throws ObdException {
        try {
            Elm327Protocol.Response r = Elm327Protocol.parse(send(cmd, options.commandTimeoutMs), cmd);
            consecutiveTimeouts = 0;
            return ObdDtc.parse(r, responseMode, can);
        } catch (ObdException e) {
            if (e.kind != ObdException.Kind.TIMEOUT) {
                throw e;
            }
            noteTimeout();
            return new ArrayList<>();
        }
    }

    private void pollMonitorStatus() throws ObdException {
        int[] data = pollRaw(ObdPids.MONITOR_STATUS);
        ObdPids.MonitorStatus status = ObdPids.decodeMonitorStatus(data);
        if (status == null) {
            fail(ObdPids.MONITOR_STATUS);
            return;
        }
        succeed(ObdPids.MONITOR_STATUS);
        synchronized (stateLock) {
            builder.monitorStatus(status.milOn, status.dtcCount);
        }
    }

    private void poll(ObdPids.Pid pid) throws ObdException {
        Double value = pid.decode(pollRaw(pid.code));
        if (value == null) {
            fail(pid.code);
            return;
        }
        succeed(pid.code);
        synchronized (stateLock) {
            builder.value(pid, value, clock.getAsLong());
        }
    }

    private int[] pollRaw(int pid) throws ObdException {
        try {
            Elm327Protocol.Response r = query(pid, options.commandTimeoutMs);
            consecutiveTimeouts = 0;
            return Elm327Protocol.findMode01Payload(r, pid);
        } catch (ObdException e) {
            if (e.kind != ObdException.Kind.TIMEOUT) {
                throw e;
            }
            noteTimeout();
            return null;
        }
    }

    private void noteTimeout() throws ObdException {
        consecutiveTimeouts++;
        if (consecutiveTimeouts >= options.maxConsecutiveTimeouts) {
            throw new ObdException(ObdException.Kind.TIMEOUT, "Adapter not responding");
        }
    }

    private void succeed(int pid) {
        failures.remove(pid);
    }

    private void fail(int pid) {
        int n = failures.containsKey(pid) ? failures.get(pid) + 1 : 1;
        failures.put(pid, n);
        if (n >= MAX_PID_FAILURES && dropped.add(pid)) {
            AppLog.w(TAG, "PID " + Elm327Protocol.hex2(pid) + " failed " + n + " times, dropped for this session");
            synchronized (stateLock) {
                builder.dropped(Elm327Protocol.hex2(pid));
            }
        }
    }

    private List<ObdPids.Pid> active(boolean fast) {
        List<ObdPids.Pid> out = new ArrayList<>();
        for (ObdPids.Pid p : ObdPids.pollable(supported, fast)) {
            if (!dropped.contains(p.code)) {
                out.add(p);
            }
        }
        return out;
    }

    private Elm327Protocol.Response query(int pid, long timeoutMs) throws ObdException {
        String cmd = Elm327Protocol.mode01(pid);
        return Elm327Protocol.parse(send(cmd, timeoutMs), cmd);
    }

    private String send(String cmd, long timeoutMs) throws ObdException {
        pace();
        try {
            return channel.transact(cmd, timeoutMs);
        } finally {
            lastCommandAtNs = System.nanoTime();
        }
    }

    private void pace() throws ObdException {
        if (options.minCommandIntervalMs <= 0 || lastCommandAtNs == 0L) {
            return;
        }
        long waitMs = options.minCommandIntervalMs - (System.nanoTime() - lastCommandAtNs) / 1_000_000L;
        if (waitMs > 0) {
            sleepMs(waitMs);
        }
    }

    private void sleepMs(long ms) throws ObdException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ObdException(ObdException.Kind.INTERRUPTED, "Stopped");
        }
    }

    private String liveStateText() {
        return "Connected" + (protocolNumber != null ? " (" + Elm327Protocol.describeProtocol(protocolNumber) + ")" : "");
    }

    private void republish() {
        ObdSnapshot s;
        synchronized (stateLock) {
            s = builder.build(clock.getAsLong());
            snapshot = s;
        }
        if (listener != null) {
            listener.onSnapshot(this, s);
        }
    }

    private void publish(String state, boolean connected) {
        ObdSnapshot s;
        synchronized (stateLock) {
            builder.state(state).connected(connected);
            s = builder.build(clock.getAsLong());
            snapshot = s;
        }
        if (listener != null) {
            listener.onSnapshot(this, s);
        }
    }

    Set<Integer> droppedPids() {
        return new TreeSet<>(dropped);
    }

    int cycleCount() {
        return cycle;
    }

    private static String hexList(Set<Integer> pids) {
        StringBuilder sb = new StringBuilder();
        for (int p : pids) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(Elm327Protocol.hex2(p));
        }
        return sb.toString();
    }
}

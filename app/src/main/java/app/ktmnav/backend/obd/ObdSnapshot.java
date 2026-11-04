package app.ktmnav.backend.obd;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable view of the OBD session: connection state, adapter, protocol, live values and last DTC read.
 */
public final class ObdSnapshot {

    public static final class Value {
        public final double value;
        public final String unit;
        public final long timestampMs;

        public Value(double value, String unit, long timestampMs) {
            this.value = value;
            this.unit = unit;
            this.timestampMs = timestampMs;
        }
    }

    public final String state;
    public final boolean connected;
    public final String adapterName;
    public final String elmVersion;
    public final String protocol;
    public final String protocolDescription;
    public final List<Integer> supportedPids;
    public final List<String> droppedPids;
    public final Map<String, Value> values;
    public final List<String> storedDtcs;
    public final List<String> pendingDtcs;
    public final long dtcReadAtMs;
    public final Boolean milOn;
    public final Integer dtcCount;
    public final long updatedAtMs;

    public static final ObdSnapshot EMPTY = new Builder().state("Disabled").build(0L);

    private ObdSnapshot(Builder b, long now) {
        this.state = b.state;
        this.connected = b.connected;
        this.adapterName = b.adapterName;
        this.elmVersion = b.elmVersion;
        this.protocol = b.protocol;
        this.protocolDescription = b.protocolDescription;
        this.supportedPids = Collections.unmodifiableList(new ArrayList<>(b.supportedPids));
        this.droppedPids = Collections.unmodifiableList(new ArrayList<>(b.droppedPids));
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(b.values));
        this.storedDtcs = Collections.unmodifiableList(new ArrayList<>(b.storedDtcs));
        this.pendingDtcs = Collections.unmodifiableList(new ArrayList<>(b.pendingDtcs));
        this.dtcReadAtMs = b.dtcReadAtMs;
        this.milOn = b.milOn;
        this.dtcCount = b.dtcCount;
        this.updatedAtMs = now;
    }

    public Value get(ObdPids.Pid pid) {
        return pid == null ? null : values.get(pid.key);
    }

    public Double fresh(ObdPids.Pid pid, long nowMs, long maxAgeMs) {
        Value v = get(pid);
        if (v == null || nowMs - v.timestampMs > maxAgeMs || nowMs < v.timestampMs - maxAgeMs) {
            return null;
        }
        return v.value;
    }

    public Integer estimatedGear(GearEstimator.Profile profile, long nowMs, long maxAgeMs) {
        return GearEstimator.estimate(fresh(ObdPids.Pid.RPM, nowMs, maxAgeMs),
                fresh(ObdPids.Pid.SPEED, nowMs, maxAgeMs), profile);
    }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.state = state;
        b.connected = connected;
        b.adapterName = adapterName;
        b.elmVersion = elmVersion;
        b.protocol = protocol;
        b.protocolDescription = protocolDescription;
        b.supportedPids.addAll(supportedPids);
        b.droppedPids.addAll(droppedPids);
        b.values.putAll(values);
        b.storedDtcs.addAll(storedDtcs);
        b.pendingDtcs.addAll(pendingDtcs);
        b.dtcReadAtMs = dtcReadAtMs;
        b.milOn = milOn;
        b.dtcCount = dtcCount;
        return b;
    }

    public static final class Builder {
        private String state = "Disconnected";
        private boolean connected = false;
        private String adapterName;
        private String elmVersion;
        private String protocol;
        private String protocolDescription;
        private final List<Integer> supportedPids = new ArrayList<>();
        private final List<String> droppedPids = new ArrayList<>();
        private final Map<String, Value> values = new LinkedHashMap<>();
        private final List<String> storedDtcs = new ArrayList<>();
        private final List<String> pendingDtcs = new ArrayList<>();
        private long dtcReadAtMs = 0L;
        private Boolean milOn;
        private Integer dtcCount;

        public Builder state(String state) {
            this.state = state;
            return this;
        }

        public Builder connected(boolean connected) {
            this.connected = connected;
            return this;
        }

        public Builder adapterName(String name) {
            this.adapterName = name;
            return this;
        }

        public Builder elmVersion(String version) {
            this.elmVersion = version;
            return this;
        }

        public Builder protocol(String number, String description) {
            this.protocol = number;
            this.protocolDescription = description;
            return this;
        }

        public Builder supported(List<Integer> pids) {
            supportedPids.clear();
            supportedPids.addAll(pids);
            return this;
        }

        public Builder dropped(String pidHex) {
            if (!droppedPids.contains(pidHex)) {
                droppedPids.add(pidHex);
            }
            return this;
        }

        public Builder value(ObdPids.Pid pid, double value, long timestampMs) {
            values.put(pid.key, new Value(value, pid.unit, timestampMs));
            return this;
        }

        public Builder dtcs(List<String> stored, List<String> pending, long readAtMs) {
            storedDtcs.clear();
            storedDtcs.addAll(stored);
            pendingDtcs.clear();
            pendingDtcs.addAll(pending);
            dtcReadAtMs = readAtMs;
            return this;
        }

        public Builder monitorStatus(Boolean mil, Integer count) {
            this.milOn = mil;
            this.dtcCount = count;
            return this;
        }

        public ObdSnapshot build(long nowMs) {
            return new ObdSnapshot(this, nowMs);
        }
    }
}

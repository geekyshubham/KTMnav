package app.ktmnav.backend;

import java.util.Objects;

/**
 * Immutable metadata and sample configuration for a KTM dashboard telemetry datapoint.
 * Based on OpenDash Datapoint (MIT License, Copyright (c) 2026 OpenDash contributors).
 */
public final class Datapoint {
    private final int id;
    private final String name;
    private final String type;
    private final String unit;
    private final int sampleRateDefaultMs;
    private final int sampleRateMinMs;
    private final int sampleRateMaxMs;
    private final boolean telemetryable;

    public Datapoint(int id, String name, String type, String unit,
                     int sampleRateDefaultMs, int sampleRateMinMs, int sampleRateMaxMs,
                     boolean telemetryable) {
        if (name == null) {
            throw new NullPointerException("name == null");
        }
        if (type == null) {
            throw new NullPointerException("type == null");
        }
        this.id = id;
        this.name = name;
        this.type = type;
        this.unit = unit;
        this.sampleRateDefaultMs = sampleRateDefaultMs;
        this.sampleRateMinMs = sampleRateMinMs;
        this.sampleRateMaxMs = sampleRateMaxMs;
        this.telemetryable = telemetryable;
    }

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public String getUnit() {
        return unit;
    }

    public int getSampleRateDefaultMs() {
        return sampleRateDefaultMs;
    }

    public int getSampleRateMinMs() {
        return sampleRateMinMs;
    }

    public int getSampleRateMaxMs() {
        return sampleRateMaxMs;
    }

    public boolean getTelemetryable() {
        return telemetryable;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Datapoint)) return false;
        Datapoint datapoint = (Datapoint) o;
        return id == datapoint.id &&
                sampleRateDefaultMs == datapoint.sampleRateDefaultMs &&
                sampleRateMinMs == datapoint.sampleRateMinMs &&
                sampleRateMaxMs == datapoint.sampleRateMaxMs &&
                telemetryable == datapoint.telemetryable &&
                name.equals(datapoint.name) &&
                type.equals(datapoint.type) &&
                Objects.equals(unit, datapoint.unit);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, type, unit, sampleRateDefaultMs, sampleRateMinMs, sampleRateMaxMs, telemetryable);
    }

    @Override
    public String toString() {
        return "Datapoint(id=" + id +
                ", name=" + name +
                ", type=" + type +
                ", unit=" + unit +
                ", sampleRateDefaultMs=" + sampleRateDefaultMs +
                ", sampleRateMinMs=" + sampleRateMinMs +
                ", sampleRateMaxMs=" + sampleRateMaxMs +
                ", telemetryable=" + telemetryable + ")";
    }
}

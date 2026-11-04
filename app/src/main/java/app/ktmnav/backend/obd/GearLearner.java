package app.ktmnav.backend.obd;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Learns the motorcycle's rpm-per-km/h gear ratios from steady riding by clustering the measured ratio.
 * Results stay estimates; nothing is sent to the vehicle.
 */
public final class GearLearner {

    public static final int MIN_SAMPLES = 400;
    static final double LOG_MIN = Math.log(30.0);
    static final double LOG_MAX = Math.log(450.0);
    static final double BIN_WIDTH = 0.02;
    static final int BINS = (int) Math.ceil((LOG_MAX - LOG_MIN) / BIN_WIDTH);
    private static final double MERGE_DISTANCE = 0.08;
    private static final double MIN_SPEED_KMH = 15.0;
    private static final double MAX_SPEED_KMH = 220.0;
    private static final double MIN_RPM = 1800.0;
    private static final double MAX_RPM = 12000.0;
    private static final double MAX_SPEED_DELTA_KMH = 2.0;
    private static final long MAX_PAIR_GAP_MS = 1500L;

    private final int[] bins = new int[BINS];
    private int total;
    private double lastSpeed = Double.NaN;
    private long lastTimeMs = Long.MIN_VALUE;

    public synchronized boolean add(double rpm, double speedKmh, long timeMs) {
        boolean steady = !Double.isNaN(lastSpeed)
                && timeMs > lastTimeMs
                && timeMs - lastTimeMs <= MAX_PAIR_GAP_MS
                && Math.abs(speedKmh - lastSpeed) <= MAX_SPEED_DELTA_KMH;
        lastSpeed = speedKmh;
        lastTimeMs = timeMs;
        if (!steady || speedKmh < MIN_SPEED_KMH || speedKmh > MAX_SPEED_KMH || rpm < MIN_RPM || rpm > MAX_RPM) {
            return false;
        }
        int bin = (int) Math.floor((Math.log(rpm / speedKmh) - LOG_MIN) / BIN_WIDTH);
        if (bin < 0 || bin >= BINS) {
            return false;
        }
        bins[bin]++;
        total++;
        return true;
    }

    public synchronized int samples() {
        return total;
    }

    public synchronized void reset() {
        Arrays.fill(bins, 0);
        total = 0;
        lastSpeed = Double.NaN;
        lastTimeMs = Long.MIN_VALUE;
    }

    public synchronized double[] learn(int gears) {
        if (gears <= 0 || total < MIN_SAMPLES) {
            return null;
        }
        double[] smooth = new double[BINS];
        for (int i = 0; i < BINS; i++) {
            smooth[i] = (bins[i] * 2.0 + (i > 0 ? bins[i - 1] : 0) + (i + 1 < BINS ? bins[i + 1] : 0)) / 4.0;
        }
        double minPeak = Math.max(10.0, total * 0.02);
        List<Integer> peaks = new ArrayList<>();
        for (int i = 0; i < BINS; i++) {
            boolean localMax = (i == 0 || smooth[i] >= smooth[i - 1]) && (i + 1 == BINS || smooth[i] > smooth[i + 1]);
            if (!localMax || smooth[i] < minPeak) {
                continue;
            }
            if (!peaks.isEmpty() && (i - peaks.get(peaks.size() - 1)) * BIN_WIDTH < MERGE_DISTANCE) {
                int previous = peaks.get(peaks.size() - 1);
                if (smooth[i] > smooth[previous]) {
                    peaks.set(peaks.size() - 1, i);
                }
                continue;
            }
            peaks.add(i);
        }
        if (peaks.size() != gears) {
            return null;
        }
        double[] ratios = new double[gears];
        int window = (int) Math.round(MERGE_DISTANCE / 2 / BIN_WIDTH);
        for (int g = 0; g < gears; g++) {
            int centre = peaks.get(gears - 1 - g);
            double weighted = 0;
            double count = 0;
            for (int i = Math.max(0, centre - window); i <= Math.min(BINS - 1, centre + window); i++) {
                weighted += bins[i] * (LOG_MIN + (i + 0.5) * BIN_WIDTH);
                count += bins[i];
            }
            ratios[g] = Math.exp(weighted / count);
        }
        for (int g = 1; g < gears; g++) {
            double step = ratios[g - 1] / ratios[g];
            if (step < 1.05 || step > 1.8) {
                return null;
            }
        }
        return ratios;
    }

    public synchronized String serialize() {
        StringBuilder out = new StringBuilder("v1");
        for (int i = 0; i < BINS; i++) {
            if (bins[i] > 0) {
                out.append(';').append(i).append(':').append(bins[i]);
            }
        }
        return out.toString();
    }

    public static GearLearner restore(String serialized) {
        GearLearner learner = new GearLearner();
        if (serialized == null || !serialized.startsWith("v1")) {
            return learner;
        }
        for (String part : serialized.split(";")) {
            int colon = part.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            try {
                int bin = Integer.parseInt(part.substring(0, colon));
                int count = Integer.parseInt(part.substring(colon + 1));
                if (bin >= 0 && bin < BINS && count > 0) {
                    learner.bins[bin] += count;
                    learner.total += count;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return learner;
    }

    public static String formatRatios(double[] ratios) {
        StringBuilder out = new StringBuilder();
        for (double r : ratios) {
            if (out.length() > 0) {
                out.append(',');
            }
            out.append(String.format(Locale.ROOT, "%.1f", r));
        }
        return out.toString();
    }

    public static double[] parseRatios(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        String[] parts = text.split(",");
        double[] out = new double[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                out[i] = Double.parseDouble(parts[i].trim());
                if (out[i] <= 0) {
                    return null;
                }
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }
}

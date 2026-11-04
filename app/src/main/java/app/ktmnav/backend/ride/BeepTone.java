package app.ktmnav.backend.ride;

public final class BeepTone {
    public static final int SAMPLE_RATE = 44_100;
    public static final double TONE_HZ = 750.0;
    public static final int BEEP_MS = 110;

    private BeepTone() {
    }

    public static int frames() {
        return SAMPLE_RATE * BEEP_MS / 1000;
    }

    public static short[] stereoPcm(float leftGain, float rightGain) {
        int frames = frames();
        short[] buf = new short[frames * 2];
        float l = clamp(leftGain);
        float r = clamp(rightGain);
        for (int i = 0; i < frames; i++) {
            double env = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (frames - 1)));
            double s = Math.sin(2.0 * Math.PI * TONE_HZ * i / SAMPLE_RATE) * env * Short.MAX_VALUE;
            buf[i * 2] = (short) Math.round(s * l);
            buf[i * 2 + 1] = (short) Math.round(s * r);
        }
        return buf;
    }

    private static float clamp(float v) {
        return Float.isNaN(v) ? 0f : Math.max(0f, Math.min(1f, v));
    }
}

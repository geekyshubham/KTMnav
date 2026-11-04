package app.ktmnav.backend;

/**
 * Receives every authenticated handlebar (RCM) frame in order: bits SET=0x1 BACK=0x2 DOWN=0x4 UP=0x8,
 * mask 0 for released or inactive frames, timestamped with SystemClock.uptimeMillis().
 */
public interface HandlebarMaskListener {
    void onMask(int mask, long uptimeMs);
}

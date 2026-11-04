package app.ktmnav.backend;

/**
 * Background active-wait budget. A link that is settling, linked or authenticating is never suspended
 * before a full first-pairing attempt (dash settle + 75 s handshake + margin) could finish, and is
 * re-checked until a hard cap so a stuck link still yields to passive detection.
 */
final class IdleWatchPolicy {
    static final long DEFAULT_IDLE_MS = 30000L;
    static final long MIN_IDLE_MS = 15000L;
    static final long MAX_IDLE_MS = 300000L;
    static final long HANDSHAKE_MARGIN_MS = 35000L;
    static final long HANDSHAKE_FLOOR_MS = BccuLink.CONNECT_SETTLE_MS + BccuLink.HANDSHAKE_TIMEOUT_FIRST_PAIR_MS + HANDSHAKE_MARGIN_MS;
    static final long HARD_CAP_MS = 2 * HANDSHAKE_FLOOR_MS;
    static final long RECHECK_MS = 15000L;

    private IdleWatchPolicy() {
    }

    static long clamp(long configuredMs) {
        return Math.max(MIN_IDLE_MS, Math.min(MAX_IDLE_MS, configuredMs));
    }

    /** Returns 0 to suspend now, otherwise how much longer to wait before checking again. */
    static long deferralMs(long configuredMs, long elapsedMs, boolean handshakeInProgress) {
        if (!handshakeInProgress) {
            return 0L;
        }
        long floor = Math.max(clamp(configuredMs), HANDSHAKE_FLOOR_MS);
        if (elapsedMs < floor) {
            return floor - elapsedMs;
        }
        if (elapsedMs < HARD_CAP_MS) {
            return Math.min(RECHECK_MS, HARD_CAP_MS - elapsedMs);
        }
        return 0L;
    }
}

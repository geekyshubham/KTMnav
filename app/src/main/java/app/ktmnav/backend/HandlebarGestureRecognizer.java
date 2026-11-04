package app.ktmnav.backend;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns RCM button masks into per-button SINGLE / DOUBLE / LONG gestures, recognised on release.
 * LONG fires at release (not at the 400 ms threshold), matching upstream; a held button never repeats.
 * DOUBLE waits up to 350 ms only for buttons the policy allows; others emit SINGLE immediately.
 * Time comes from the mask timestamps; delayed single taps use the injected scheduler.
 */
public final class HandlebarGestureRecognizer {

    public static final long LONG_PRESS_MS = 400L;
    public static final long DOUBLE_TAP_WINDOW_MS = 350L;

    public static final int MASK_SET = 0x1;
    public static final int MASK_BACK = 0x2;
    public static final int MASK_DOWN = 0x4;
    public static final int MASK_UP = 0x8;

    private static final String[] NAMES = new String[]{"SET", "BACK", "DOWN", "UP"};

    public interface Cancellable {
        void cancel();
    }

    public interface Scheduler {
        Cancellable schedule(Runnable task, long delayMs);
    }

    public interface DoubleTapPolicy {
        boolean allowsDouble(String button);
    }

    public interface Listener {
        void onGesture(String button, HandlebarMappings.Gesture gesture);
    }

    private static final class Emission {
        final String button;
        final HandlebarMappings.Gesture gesture;

        Emission(String button, HandlebarMappings.Gesture gesture) {
            this.button = button;
            this.gesture = gesture;
        }
    }

    private final Scheduler scheduler;
    private final DoubleTapPolicy policy;
    private final Listener listener;

    private final long[] pressedAt = new long[4];
    private final long[] pendingReleaseAt = new long[4];
    private final long[] pendingSeq = new long[4];
    private final Cancellable[] pending = new Cancellable[4];
    private int lastMask;
    private long generation;

    public HandlebarGestureRecognizer(Scheduler scheduler, DoubleTapPolicy policy, Listener listener) {
        this.scheduler = scheduler;
        this.policy = policy;
        this.listener = listener;
    }

    public static String buttonName(int bit) {
        return bit >= 0 && bit < NAMES.length ? NAMES[bit] : null;
    }

    public void onMask(int mask, long uptimeMs) {
        List<Emission> out = new ArrayList<>(2);
        synchronized (this) {
            int current = mask & 0xF;
            for (int bit = 0; bit < 4; bit++) {
                boolean was = ((lastMask >> bit) & 1) == 1;
                boolean is = ((current >> bit) & 1) == 1;
                if (!was && is) {
                    pressedAt[bit] = uptimeMs;
                } else if (was && !is) {
                    release(bit, Math.max(0L, uptimeMs - pressedAt[bit]), uptimeMs, out);
                }
            }
            lastMask = current;
        }
        dispatch(out);
    }

    public void reset() {
        synchronized (this) {
            generation++;
            for (int bit = 0; bit < 4; bit++) {
                cancelPending(bit);
            }
            lastMask = 0;
        }
    }

    public synchronized boolean hasPending() {
        for (Cancellable c : pending) {
            if (c != null) {
                return true;
            }
        }
        return false;
    }

    private void release(int bit, long heldMs, long uptimeMs, List<Emission> out) {
        String name = NAMES[bit];
        boolean hadPending = pending[bit] != null;
        if (hadPending && uptimeMs - pendingReleaseAt[bit] > DOUBLE_TAP_WINDOW_MS) {
            cancelPending(bit);
            out.add(new Emission(name, HandlebarMappings.Gesture.SINGLE));
            hadPending = false;
        }
        if (heldMs >= LONG_PRESS_MS) {
            out.add(new Emission(name, HandlebarMappings.Gesture.LONG));
            return;
        }
        if (hadPending) {
            cancelPending(bit);
            out.add(new Emission(name, HandlebarMappings.Gesture.DOUBLE));
            return;
        }
        if (scheduler != null && policy != null && policy.allowsDouble(name)) {
            final long gen = generation;
            final long seq = ++pendingSeq[bit];
            pendingReleaseAt[bit] = uptimeMs;
            pending[bit] = scheduler.schedule(() -> onTimeout(bit, gen, seq), DOUBLE_TAP_WINDOW_MS);
            if (pending[bit] == null) {
                pending[bit] = () -> { };
            }
            return;
        }
        out.add(new Emission(name, HandlebarMappings.Gesture.SINGLE));
    }

    private void onTimeout(int bit, long gen, long seq) {
        List<Emission> out = new ArrayList<>(1);
        synchronized (this) {
            if (gen != generation || seq != pendingSeq[bit] || pending[bit] == null) {
                return;
            }
            pending[bit] = null;
            out.add(new Emission(NAMES[bit], HandlebarMappings.Gesture.SINGLE));
        }
        dispatch(out);
    }

    private void cancelPending(int bit) {
        Cancellable c = pending[bit];
        pending[bit] = null;
        pendingSeq[bit]++;
        if (c != null) {
            c.cancel();
        }
    }

    private void dispatch(List<Emission> out) {
        if (listener == null) {
            return;
        }
        for (Emission e : out) {
            listener.onGesture(e.button, e.gesture);
        }
    }
}

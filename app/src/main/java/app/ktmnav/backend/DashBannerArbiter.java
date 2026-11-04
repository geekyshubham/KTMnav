package app.ktmnav.backend;

import android.os.Handler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Owns the dashboard bottom banner. Text is ASCII-cleaned; up to 16 characters are shown for a
 * hold time, longer text scrolls as a marquee (320 ms per character, 2 cycles, 3-space gap) and
 * then clears. A request of equal or higher priority pre-empts the current banner, a lower one is
 * dropped, and the current owner may always replace its own banner. Clearing always keeps the last
 * renderable icon and sends visibility OFF with empty text (never icon 0). Sink writes are serialized
 * and re-checked against the current banner so a stale marquee frame never overwrites a newer one.
 */
public final class DashBannerArbiter {

    public static final int PRIORITY_INFO = 0;
    public static final int PRIORITY_TRACK = 1;
    public static final int PRIORITY_MESSAGE = 2;
    public static final int PRIORITY_CALL = 3;
    public static final int PRIORITY_ALERT = 4;

    public static final int VISIBLE_CHARS = 16;
    public static final long MARQUEE_STEP_MS = 320L;
    public static final int MARQUEE_CYCLES = 2;
    public static final String MARQUEE_GAP = "   ";
    public static final long DEFAULT_HOLD_MS = 5000L;

    public interface Scheduler {
        Object schedule(Runnable task, long delayMs);

        void cancel(Object token);
    }

    public interface Sink {
        void showFrame(String text, BccuProtocol.NotificationIcon icon);

        void clearBanner();
    }

    private final Scheduler scheduler;
    private final Sink sink;
    private final Object writeLock = new Object();

    private boolean marqueeEnabled = true;
    private String activeOwner;
    private int activePriority;
    private BccuProtocol.NotificationIcon activeIcon = BccuProtocol.NotificationIcon.NOTIFICATION_WAYPOINT;
    private List<String> frames = Collections.emptyList();
    private int nextFrame;
    private Object pendingToken;
    private int epoch;

    public DashBannerArbiter(Scheduler scheduler, Sink sink) {
        this.scheduler = scheduler != null ? scheduler : noopScheduler();
        this.sink = sink;
    }

    public static Scheduler handlerScheduler(Handler handler) {
        if (handler == null) {
            return noopScheduler();
        }
        return new Scheduler() {
            @Override
            public Object schedule(Runnable task, long delayMs) {
                handler.postDelayed(task, delayMs);
                return task;
            }

            @Override
            public void cancel(Object token) {
                if (token instanceof Runnable) {
                    handler.removeCallbacks((Runnable) token);
                }
            }
        };
    }

    public static Scheduler noopScheduler() {
        return new Scheduler() {
            @Override
            public Object schedule(Runnable task, long delayMs) {
                return null;
            }

            @Override
            public void cancel(Object token) {
            }
        };
    }

    public static BccuProtocol.NotificationIcon renderableIcon(BccuProtocol.NotificationIcon icon) {
        return icon == BccuProtocol.NotificationIcon.NOTIFICATION_REROUTING
                ? BccuProtocol.NotificationIcon.NOTIFICATION_REROUTING
                : BccuProtocol.NotificationIcon.NOTIFICATION_WAYPOINT;
    }

    public static List<String> marqueeFrames(String text) {
        String padded = text + MARQUEE_GAP;
        String stream = padded + padded;
        int total = padded.length() * MARQUEE_CYCLES;
        List<String> list = new ArrayList<>(total);
        for (int step = 0; step < total; step++) {
            int offset = step % padded.length();
            list.add(stream.substring(offset, offset + VISIBLE_CHARS));
        }
        return list;
    }

    public synchronized void setMarqueeEnabled(boolean enabled) {
        this.marqueeEnabled = enabled;
    }

    public boolean show(String owner, String text, int priority, long holdMs) {
        return show(owner, text, priority, holdMs, BccuProtocol.NotificationIcon.NOTIFICATION_WAYPOINT);
    }

    public boolean show(String owner, String text, int priority, long holdMs, BccuProtocol.NotificationIcon icon) {
        String who = owner != null ? owner : "";
        int prio = Math.max(PRIORITY_INFO, Math.min(PRIORITY_ALERT, priority));
        String clean = DashText.asciiClean(text);
        if (clean.isEmpty()) {
            return false;
        }
        BccuProtocol.NotificationIcon renderIcon = renderableIcon(icon);
        synchronized (writeLock) {
            String firstFrame;
            int written;
            synchronized (this) {
                if (activeOwner != null && !activeOwner.equals(who) && prio < activePriority) {
                    return false;
                }
                cancelPendingLocked();
                epoch++;
                activeOwner = who;
                activePriority = prio;
                activeIcon = renderIcon;
                if (clean.length() > VISIBLE_CHARS && marqueeEnabled) {
                    frames = marqueeFrames(clean);
                    nextFrame = 1;
                    firstFrame = frames.get(0);
                    scheduleLocked(MARQUEE_STEP_MS);
                } else {
                    frames = Collections.emptyList();
                    nextFrame = 0;
                    firstFrame = clean.length() > VISIBLE_CHARS ? clean.substring(0, VISIBLE_CHARS) : clean;
                    scheduleLocked(holdMs > 0 ? holdMs : DEFAULT_HOLD_MS);
                }
                written = epoch;
            }
            if (sink != null && isCurrent(written)) {
                sink.showFrame(firstFrame, renderIcon);
            }
            return true;
        }
    }

    public void clear(String owner) {
        String who = owner != null ? owner : "";
        synchronized (writeLock) {
            synchronized (this) {
                if (activeOwner == null || !activeOwner.equals(who)) {
                    return;
                }
                endLocked();
            }
            if (sink != null) {
                sink.clearBanner();
            }
        }
    }

    public void clearActive() {
        synchronized (writeLock) {
            synchronized (this) {
                if (activeOwner == null) {
                    return;
                }
                endLocked();
            }
            if (sink != null) {
                sink.clearBanner();
            }
        }
    }

    public synchronized void reset() {
        endLocked();
    }

    public synchronized boolean isActive() {
        return activeOwner != null;
    }

    public synchronized boolean isMarqueeRunning() {
        return activeOwner != null && !frames.isEmpty();
    }

    public synchronized String getActiveOwner() {
        return activeOwner;
    }

    public synchronized int getActivePriority() {
        return activeOwner != null ? activePriority : -1;
    }

    private void endLocked() {
        cancelPendingLocked();
        epoch++;
        activeOwner = null;
        frames = Collections.emptyList();
        nextFrame = 0;
    }

    private void cancelPendingLocked() {
        if (pendingToken != null) {
            scheduler.cancel(pendingToken);
            pendingToken = null;
        }
    }

    private void scheduleLocked(long delayMs) {
        final int expected = epoch;
        pendingToken = scheduler.schedule(() -> onTick(expected), delayMs);
    }

    private synchronized boolean isCurrent(int expected) {
        return expected == epoch;
    }

    private void onTick(int expected) {
        synchronized (writeLock) {
            String frame = null;
            BccuProtocol.NotificationIcon icon;
            boolean clearNow = false;
            int written;
            synchronized (this) {
                if (expected != epoch || activeOwner == null) {
                    return;
                }
                pendingToken = null;
                icon = activeIcon;
                if (nextFrame < frames.size()) {
                    frame = frames.get(nextFrame++);
                    scheduleLocked(MARQUEE_STEP_MS);
                } else {
                    endLocked();
                    clearNow = true;
                }
                written = epoch;
            }
            if (sink == null || !isCurrent(written)) {
                return;
            }
            if (clearNow) {
                sink.clearBanner();
            } else {
                sink.showFrame(frame, icon);
            }
        }
    }
}

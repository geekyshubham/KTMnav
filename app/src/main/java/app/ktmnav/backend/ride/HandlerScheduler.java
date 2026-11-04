package app.ktmnav.backend.ride;

import android.os.Handler;
import android.os.SystemClock;

public final class HandlerScheduler implements RideScheduler {
    private final Handler handler;

    public HandlerScheduler(Handler handler) {
        this.handler = handler;
    }

    @Override public long now() { return SystemClock.elapsedRealtime(); }

    @Override public void postDelayed(Runnable task, long delayMs) { handler.postDelayed(task, Math.max(0L, delayMs)); }

    @Override public void cancel(Runnable task) { handler.removeCallbacks(task); }
}

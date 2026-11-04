package app.ktmnav.backend;

import android.os.Handler;
import android.os.Looper;

/** Production {@link HandlebarGestureRecognizer.Scheduler} backed by the main looper. */
public final class MainThreadScheduler implements HandlebarGestureRecognizer.Scheduler {

    private final Handler handler;

    public MainThreadScheduler() {
        this(new Handler(Looper.getMainLooper()));
    }

    public MainThreadScheduler(Handler handler) {
        this.handler = handler;
    }

    @Override
    public HandlebarGestureRecognizer.Cancellable schedule(Runnable task, long delayMs) {
        handler.postDelayed(task, delayMs);
        return () -> handler.removeCallbacks(task);
    }
}

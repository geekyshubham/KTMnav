package app.ktmnav.backend;

import java.util.ArrayList;
import java.util.List;

/** Virtual-time scheduler for JVM tests: tasks run only when advance() passes their due time. */
final class FakeDashScheduler implements DashBannerArbiter.Scheduler {

    private static final class Task {
        final long due;
        final long order;
        final Runnable body;
        boolean cancelled;

        Task(long due, long order, Runnable body) {
            this.due = due;
            this.order = order;
            this.body = body;
        }
    }

    private final List<Task> tasks = new ArrayList<>();
    private long now;
    private long counter;

    @Override
    public synchronized Object schedule(Runnable task, long delayMs) {
        Task t = new Task(now + Math.max(0, delayMs), counter++, task);
        tasks.add(t);
        return t;
    }

    @Override
    public synchronized void cancel(Object token) {
        if (token instanceof Task) {
            ((Task) token).cancelled = true;
            tasks.remove(token);
        }
    }

    long now() {
        return now;
    }

    void advance(long ms) {
        long target = now + ms;
        while (true) {
            Task next = null;
            synchronized (this) {
                for (Task t : tasks) {
                    if (t.due <= target && (next == null || t.due < next.due || (t.due == next.due && t.order < next.order))) {
                        next = t;
                    }
                }
                if (next == null) {
                    now = target;
                    return;
                }
                tasks.remove(next);
                now = next.due;
            }
            if (!next.cancelled) {
                next.body.run();
            }
        }
    }

    synchronized int pending() {
        return tasks.size();
    }
}

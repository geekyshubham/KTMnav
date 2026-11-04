package app.ktmnav.backend;

import java.util.ArrayList;
import java.util.List;

final class FakeScheduler implements HandlebarGestureRecognizer.Scheduler {

    private static final class Task {
        final Runnable runnable;
        final long dueAt;
        boolean cancelled;

        Task(Runnable runnable, long dueAt) {
            this.runnable = runnable;
            this.dueAt = dueAt;
        }
    }

    private final List<Task> tasks = new ArrayList<>();
    private long now;

    long now() {
        return now;
    }

    @Override
    public HandlebarGestureRecognizer.Cancellable schedule(Runnable task, long delayMs) {
        Task t = new Task(task, now + delayMs);
        tasks.add(t);
        return () -> t.cancelled = true;
    }

    void advanceTo(long time) {
        while (true) {
            Task next = null;
            for (Task t : tasks) {
                if (!t.cancelled && t.dueAt <= time && (next == null || t.dueAt < next.dueAt)) {
                    next = t;
                }
            }
            if (next == null) {
                break;
            }
            tasks.remove(next);
            now = next.dueAt;
            next.runnable.run();
        }
        now = time;
    }

    int activeTasks() {
        int n = 0;
        for (Task t : tasks) {
            if (!t.cancelled) {
                n++;
            }
        }
        return n;
    }
}

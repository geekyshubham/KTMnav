package app.ktmnav.backend.ride;

import java.util.ArrayList;
import java.util.List;

final class FakeScheduler implements RideScheduler {
    private static final class Task {
        final Runnable r;
        final long at;

        Task(Runnable r, long at) {
            this.r = r;
            this.at = at;
        }
    }

    long now = 1_000_000L;
    private final List<Task> tasks = new ArrayList<>();

    @Override public long now() { return now; }

    @Override public void postDelayed(Runnable task, long delayMs) { tasks.add(new Task(task, now + delayMs)); }

    @Override public void cancel(Runnable task) { tasks.removeIf(t -> t.r == task); }

    int pending() { return tasks.size(); }

    void advance(long ms) {
        long end = now + ms;
        while (true) {
            Task next = null;
            for (Task t : tasks) if (t.at <= end && (next == null || t.at < next.at)) next = t;
            if (next == null) break;
            tasks.remove(next);
            now = next.at;
            next.r.run();
        }
        now = end;
    }
}

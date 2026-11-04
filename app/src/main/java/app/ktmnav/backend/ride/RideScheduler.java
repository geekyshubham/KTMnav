package app.ktmnav.backend.ride;

public interface RideScheduler {
    long now();

    void postDelayed(Runnable task, long delayMs);

    void cancel(Runnable task);
}

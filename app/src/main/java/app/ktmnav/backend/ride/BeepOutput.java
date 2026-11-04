package app.ktmnav.backend.ride;

public interface BeepOutput {
    void play(float leftGain, float rightGain);

    void release();
}

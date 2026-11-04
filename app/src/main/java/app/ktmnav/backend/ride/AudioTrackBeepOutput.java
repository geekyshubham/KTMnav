package app.ktmnav.backend.ride;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class AudioTrackBeepOutput implements BeepOutput {
    private static final int MAX_CACHED = 8;
    private final Map<String, AudioTrack> tracks = new HashMap<>();

    @Override
    public void play(float leftGain, float rightGain) {
        String key = String.format(Locale.ROOT, "%.3f/%.3f", leftGain, rightGain);
        AudioTrack track = tracks.get(key);
        if (track != null) {
            try {
                track.stop();
                track.reloadStaticData();
                track.play();
                return;
            } catch (IllegalStateException e) {
                tracks.remove(key);
                track.release();
            }
        }
        if (tracks.size() >= MAX_CACHED) release();
        track = build(BeepTone.stereoPcm(leftGain, rightGain));
        tracks.put(key, track);
        track.play();
    }

    @Override
    public void release() {
        for (AudioTrack t : tracks.values()) {
            try {
                t.release();
            } catch (RuntimeException ignored) {
            }
        }
        tracks.clear();
    }

    private static AudioTrack build(short[] pcm) {
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(BeepTone.SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.length * 2)
                .build();
        track.write(pcm, 0, pcm.length);
        return track;
    }
}

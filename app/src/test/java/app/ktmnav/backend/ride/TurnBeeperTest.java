package app.ktmnav.backend.ride;

import app.ktmnav.backend.TurnIcon;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class TurnBeeperTest {
    private final List<float[]> beeps = new ArrayList<>();
    private final List<Long> beepTimes = new ArrayList<>();
    private FakeScheduler clock;
    private Float speed;
    private TurnBeeper beeper;
    private int releases;

    @Before
    public void setUp() {
        clock = new FakeScheduler();
        BeepOutput out = new BeepOutput() {
            @Override public void play(float l, float r) {
                beeps.add(new float[]{l, r});
                beepTimes.add(clock.now());
            }

            @Override public void release() { releases++; }
        };
        beeper = new TurnBeeper(clock, out, () -> speed);
        beeper.configure(true, 100, false);
    }

    @Test
    public void gapIsProportionalToDistance() {
        Assert.assertEquals(160L, BeepScheduler.gapForMetres(0));
        Assert.assertEquals(880L, BeepScheduler.gapForMetres(200));
        Assert.assertEquals(1600L, BeepScheduler.gapForMetres(400));
        Assert.assertEquals(1600L, BeepScheduler.gapForMetres(900));
        Assert.assertEquals(160L, BeepScheduler.gapForMetres(-5));
    }

    @Test
    public void sidesFromIconNames() {
        Assert.assertEquals(BeepScheduler.Side.LEFT, BeepScheduler.sideOf(TurnIcon.QUITE_LEFT));
        Assert.assertEquals(BeepScheduler.Side.LEFT, BeepScheduler.sideOf(TurnIcon.UTURN_LEFT));
        Assert.assertEquals(BeepScheduler.Side.LEFT, BeepScheduler.sideOf(TurnIcon.RAB_SECT_3_LH));
        Assert.assertEquals(BeepScheduler.Side.RIGHT, BeepScheduler.sideOf(TurnIcon.HIGHWAY_KEEP_RIGHT));
        Assert.assertEquals(BeepScheduler.Side.RIGHT, BeepScheduler.sideOf(TurnIcon.RAB_SECT_12_RH));
        Assert.assertEquals(BeepScheduler.Side.CENTRE, BeepScheduler.sideOf(TurnIcon.GO_STRAIGHT));
        Assert.assertEquals(BeepScheduler.Side.CENTRE, BeepScheduler.sideOf(TurnIcon.KEEP_MIDDLE));
        Assert.assertEquals(BeepScheduler.Side.CENTRE, BeepScheduler.sideOf(null));
    }

    @Test
    public void pansAndSwaps() {
        float[] left = BeepScheduler.gains(BeepScheduler.Side.LEFT, false, 1f);
        Assert.assertEquals(1f, left[0], 0f);
        Assert.assertEquals(0f, left[1], 0f);
        float[] swapped = BeepScheduler.gains(BeepScheduler.Side.LEFT, true, 1f);
        Assert.assertEquals(0f, swapped[0], 0f);
        Assert.assertEquals(1f, swapped[1], 0f);
        float[] centre = BeepScheduler.gains(BeepScheduler.Side.CENTRE, false, 1f);
        Assert.assertEquals(centre[0], centre[1], 0f);
        Assert.assertTrue(centre[0] > 0.5f && centre[0] < 1f);
    }

    @Test
    public void beepsOnlyWithin400m() {
        beeper.onGuidance("450 m", TurnIcon.QUITE_RIGHT);
        clock.advance(5000);
        Assert.assertTrue(beeps.isEmpty());
        beeper.onGuidance("400 m", TurnIcon.QUITE_RIGHT);
        clock.advance(0);
        Assert.assertEquals(1, beeps.size());
        Assert.assertEquals(0f, beeps.get(0)[0], 0f);
        Assert.assertEquals(1f, beeps.get(0)[1], 0f);
    }

    @Test
    public void intervalTightensAsTurnApproaches() {
        beeper.onGuidance("400 m", TurnIcon.QUITE_LEFT);
        clock.advance(1600);
        Assert.assertEquals(2, beeps.size());
        Assert.assertEquals(1600L, beepTimes.get(1) - beepTimes.get(0));
        beeper.onGuidance("0 m", TurnIcon.QUITE_LEFT);
        clock.advance(1600);
        int before = beeps.size();
        clock.advance(320);
        Assert.assertEquals(before + 2, beeps.size());
        Assert.assertEquals(160L, beepTimes.get(beepTimes.size() - 1) - beepTimes.get(beepTimes.size() - 2));
    }

    @Test
    public void ducksWhenStationary() {
        speed = 2f;
        beeper.configure(true, 50, false);
        beeper.onGuidance("100 m", TurnIcon.QUITE_LEFT);
        clock.advance(0);
        Assert.assertEquals(0.5f * BeepScheduler.DUCK_FACTOR, beeps.get(0)[0], 1e-6);
        speed = 30f;
        clock.advance(BeepScheduler.gapForMetres(100));
        Assert.assertEquals(0.5f, beeps.get(1)[0], 1e-6);
        speed = null;
        clock.advance(BeepScheduler.gapForMetres(100));
        Assert.assertEquals(0.5f, beeps.get(2)[0], 1e-6);
    }

    @Test
    public void stopsAfterTwentySecondsWithoutUpdates() {
        beeper.onGuidance("400 m", TurnIcon.GO_STRAIGHT);
        clock.advance(BeepScheduler.STALE_GUIDANCE_MS);
        Assert.assertTrue(beeper.isBeeping());
        int count = beeps.size();
        clock.advance(2000);
        Assert.assertFalse(beeper.isBeeping());
        Assert.assertTrue(beeps.size() <= count + 1);
        Assert.assertEquals(0, clock.pending());
        Assert.assertTrue(releases > 0);
    }

    @Test
    public void stopsOnEndedAndWhenDisabled() {
        beeper.onGuidance("200 m", TurnIcon.QUITE_LEFT);
        clock.advance(0);
        beeper.onGuidanceEnded();
        int count = beeps.size();
        clock.advance(10_000);
        Assert.assertEquals(count, beeps.size());

        beeper.configure(false, 60, false);
        beeper.onGuidance("200 m", TurnIcon.QUITE_LEFT);
        clock.advance(10_000);
        Assert.assertEquals(count, beeps.size());
    }

    @Test
    public void silentAtZeroVolume() {
        beeper.configure(true, 0, false);
        beeper.onGuidance("100 m", TurnIcon.QUITE_LEFT);
        clock.advance(3000);
        Assert.assertTrue(beeps.isEmpty());
    }

    @Test
    public void toneIsPannedPcm() {
        short[] pcm = BeepTone.stereoPcm(0f, 1f);
        Assert.assertEquals(BeepTone.frames() * 2, pcm.length);
        int leftPeak = 0;
        int rightPeak = 0;
        for (int i = 0; i < pcm.length; i += 2) {
            leftPeak = Math.max(leftPeak, Math.abs(pcm[i]));
            rightPeak = Math.max(rightPeak, Math.abs(pcm[i + 1]));
        }
        Assert.assertEquals(0, leftPeak);
        Assert.assertTrue(rightPeak > 30000);
        Assert.assertEquals(0, pcm[1]);
    }
}

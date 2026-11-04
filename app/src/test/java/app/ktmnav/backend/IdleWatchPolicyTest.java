package app.ktmnav.backend;

import org.junit.Assert;
import org.junit.Test;

public class IdleWatchPolicyTest {

    @Test
    public void floorCoversSettleFirstPairBudgetAndMargin() {
        Assert.assertTrue(IdleWatchPolicy.HANDSHAKE_FLOOR_MS >= 120_000L);
        Assert.assertTrue(IdleWatchPolicy.HANDSHAKE_FLOOR_MS
                >= BccuLink.CONNECT_SETTLE_MS + BccuLink.HANDSHAKE_TIMEOUT_FIRST_PAIR_MS);
    }

    @Test
    public void idleLinkSuspendsAtConfiguredDeadline() {
        Assert.assertEquals(0L, IdleWatchPolicy.deferralMs(30_000L, 30_000L, false));
    }

    @Test
    public void handshakeExtendsToFloor() {
        Assert.assertEquals(90_000L, IdleWatchPolicy.deferralMs(30_000L, 30_000L, true));
        Assert.assertEquals(1L, IdleWatchPolicy.deferralMs(30_000L, IdleWatchPolicy.HANDSHAKE_FLOOR_MS - 1, true));
    }

    @Test
    public void longConfiguredTimeoutIsKept() {
        Assert.assertEquals(100_000L, IdleWatchPolicy.deferralMs(200_000L, 100_000L, true));
    }

    @Test
    public void stillHandshakingAfterFloorRechecksUntilHardCap() {
        long floor = IdleWatchPolicy.HANDSHAKE_FLOOR_MS;
        Assert.assertEquals(IdleWatchPolicy.RECHECK_MS, IdleWatchPolicy.deferralMs(30_000L, floor, true));
        Assert.assertEquals(5_000L, IdleWatchPolicy.deferralMs(30_000L, IdleWatchPolicy.HARD_CAP_MS - 5_000L, true));
        Assert.assertEquals(0L, IdleWatchPolicy.deferralMs(30_000L, IdleWatchPolicy.HARD_CAP_MS, true));
    }

    @Test
    public void clampKeepsConfiguredRange() {
        Assert.assertEquals(15_000L, IdleWatchPolicy.clamp(1_000L));
        Assert.assertEquals(300_000L, IdleWatchPolicy.clamp(900_000L));
        Assert.assertEquals(45_000L, IdleWatchPolicy.clamp(45_000L));
    }
}

package app.ktmnav.backend.obd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.junit.Test;

public class ObdSessionTest {

    private static ObdSession.Options fastOptions() {
        ObdSession.Options o = new ObdSession.Options();
        o.commandTimeoutMs = 150L;
        o.resetTimeoutMs = 200L;
        o.minCommandIntervalMs = 0L;
        o.drainMs = 400L;
        o.noEcuRetryMs = 20L;
        return o;
    }

    private static FakeElm327 duke() {
        return new FakeElm327()
                .withPid(0x0C, "3E80")
                .withPid(0x0D, "28")
                .withPid(0x05, "82")
                .withPid(0x0F, "41")
                .withPid(0x11, "33")
                .withPid(0x1F, "02F2")
                .withPid(0x21, "0000")
                .withPid(0x2F, "66")
                .withPid(0x31, "0123")
                .withPid(0x42, "35E8")
                .withPid(0x5C, "96");
    }

    private static ObdSession attached(FakeElm327 fake, ObdSession.Options o) {
        ObdSession s = new ObdSession(fake, o, () -> 10_000L, null);
        s.attachWithoutWorker();
        return s;
    }

    private static ObdSession ready(FakeElm327 fake, ObdSession.Options o) throws ObdException {
        ObdSession s = attached(fake, o);
        assertTrue(s.initialize());
        assertTrue(s.discover());
        return s;
    }

    @Test
    public void initSequenceInOrderAndVersionRead() throws Exception {
        FakeElm327 fake = duke();
        ObdSession s = attached(fake, fastOptions());
        assertTrue(s.initialize());
        assertEquals(Arrays.asList("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0", "ATAT1"), fake.received);
        s.discover();
        assertEquals("ELM327 v1.5", s.getSnapshot().elmVersion);
    }

    @Test
    public void discoveryWalksAllThreeBitmasks() throws Exception {
        FakeElm327 fake = duke();
        ObdSession s = ready(fake, fastOptions());
        assertEquals(1, fake.count("0100"));
        assertEquals(1, fake.count("0120"));
        assertEquals(1, fake.count("0140"));
        assertEquals(1, fake.count("ATDPN"));
        List<Integer> supported = s.getSnapshot().supportedPids;
        for (int pid : new int[]{0x01, 0x05, 0x0C, 0x0D, 0x0F, 0x11, 0x1F, 0x21, 0x2F, 0x31, 0x42, 0x5C}) {
            assertTrue("pid " + Integer.toHexString(pid), supported.contains(pid));
        }
        assertEquals("A6", s.getSnapshot().protocol);
        assertEquals("ISO 15765-4 CAN 11-bit 500k (auto)", s.getSnapshot().protocolDescription);
    }

    @Test
    public void discoveryStopsWhenNoHigherRanges() throws Exception {
        FakeElm327 fake = new FakeElm327().withPid(0x0C, "1AF8").withPid(0x0D, "10");
        ObdSession s = ready(fake, fastOptions());
        assertEquals(0, fake.count("0120"));
        assertEquals(0, fake.count("0140"));
        assertEquals(Arrays.asList(0x01, 0x0C, 0x0D), s.getSnapshot().supportedPids);
    }

    @Test
    public void pollingRotatesFastEveryCycleAndSlowEveryFifth() throws Exception {
        FakeElm327 fake = duke();
        ObdSession s = ready(fake, fastOptions());
        for (int i = 0; i < 10; i++) {
            s.pollCycle();
        }
        assertEquals(10, fake.count("010C"));
        assertEquals(10, fake.count("010D"));
        assertEquals(2, fake.count("0105"));
        assertEquals(2, fake.count("0142"));
        assertEquals(2, fake.count("015C"));
        assertEquals(2, fake.count("0101"));
        assertEquals(0, fake.count("0146"));
        ObdSnapshot snap = s.getSnapshot();
        assertEquals(4000.0, snap.get(ObdPids.Pid.RPM).value, 1e-9);
        assertEquals(40.0, snap.get(ObdPids.Pid.SPEED).value, 1e-9);
        assertEquals(90.0, snap.get(ObdPids.Pid.COOLANT).value, 1e-9);
        assertEquals(13.8, snap.get(ObdPids.Pid.VOLTAGE).value, 1e-9);
        assertEquals(110.0, snap.get(ObdPids.Pid.OIL).value, 1e-9);
        assertEquals(754.0, snap.get(ObdPids.Pid.RUN_TIME).value, 1e-9);
        assertEquals("°C", snap.get(ObdPids.Pid.COOLANT).unit);
        assertEquals(10_000L, snap.get(ObdPids.Pid.RPM).timestampMs);
        assertEquals(Boolean.FALSE, snap.milOn);
        assertEquals(Integer.valueOf(4), snap.estimatedGear(GearEstimator.KTM_390_DUKE_GEN3, 10_500L, 3000L));
        assertNull(snap.estimatedGear(GearEstimator.KTM_390_DUKE_GEN3, 14_000L, 3000L));
        assertTrue(snap.connected);
    }

    @Test
    public void pidDroppedAfterTwoFailures() throws Exception {
        FakeElm327 fake = duke();
        fake.noDataPids.add(0x46);
        ObdSession.Options o = fastOptions();
        o.slowEvery = 1;
        ObdSession s = ready(fake, o);
        assertTrue(s.getSnapshot().supportedPids.contains(0x46));
        for (int i = 0; i < 6; i++) {
            s.pollCycle();
        }
        assertEquals(2, fake.count("0146"));
        assertEquals(6, fake.count("0105"));
        assertTrue(s.droppedPids().contains(0x46));
        assertEquals(Arrays.asList("46"), s.getSnapshot().droppedPids);
        assertNull(s.getSnapshot().get(ObdPids.Pid.AMBIENT));
    }

    @Test
    public void timedOutPidDroppedAndSessionContinues() throws Exception {
        FakeElm327 fake = duke();
        ObdSession s = ready(fake, fastOptions());
        fake.silentCommands.add("010D");
        for (int i = 0; i < 4; i++) {
            s.pollCycle();
        }
        assertEquals(2, fake.count("010D"));
        assertTrue(s.droppedPids().contains(0x0D));
        assertEquals(4, fake.count("010C"));
        assertEquals(4000.0, s.getSnapshot().get(ObdPids.Pid.RPM).value, 1e-9);
    }

    @Test
    public void consecutiveTimeoutsEndSession() throws Exception {
        FakeElm327 fake = duke();
        ObdSession s = ready(fake, fastOptions());
        fake.silentCommands.addAll(Arrays.asList("010C", "010D", "0101", "0105"));
        try {
            for (int i = 0; i < 5; i++) {
                s.pollCycle();
            }
            fail("expected timeout");
        } catch (ObdException e) {
            assertEquals(ObdException.Kind.TIMEOUT, e.kind);
        }
    }

    @Test
    public void atzRetriedOnceThenFails() throws Exception {
        FakeElm327 fake = duke();
        fake.silentCommands.add("ATZ");
        ObdSession s = attached(fake, fastOptions());
        assertFalse(s.initialize());
        assertEquals(2, fake.count("ATZ"));
    }

    @Test
    public void readsStoredAndPendingDtcsOverCanMultiFrame() throws Exception {
        FakeElm327 fake = duke();
        fake.milOn = true;
        fake.storedDtcs.addAll(Arrays.asList("P0133", "U0100", "P0171", "B1234", "P0133"));
        fake.pendingDtcs.add("P0300");
        ObdSession s = ready(fake, fastOptions());
        s.readDtcs();
        ObdSnapshot snap = s.getSnapshot();
        assertEquals(Arrays.asList("P0133", "U0100", "P0171", "B1234"), snap.storedDtcs);
        assertEquals(Arrays.asList("P0300"), snap.pendingDtcs);
        assertEquals(Boolean.TRUE, snap.milOn);
        assertEquals(Integer.valueOf(5), snap.dtcCount);
        assertEquals(10_000L, snap.dtcReadAtMs);
    }

    @Test
    public void readsDtcsOverKLine() throws Exception {
        FakeElm327 fake = duke();
        fake.can = false;
        fake.protocol = "A5";
        fake.storedDtcs.addAll(Arrays.asList("P0133", "P0244", "P0171", "C0123"));
        ObdSession s = ready(fake, fastOptions());
        s.readDtcs();
        assertEquals(Arrays.asList("P0133", "P0244", "P0171", "C0123"), s.getSnapshot().storedDtcs);
        assertTrue(s.getSnapshot().pendingDtcs.isEmpty());
        assertEquals("ISO 14230-4 KWP (fast init) (auto)", s.getSnapshot().protocolDescription);
    }

    @Test
    public void noEcuDiscoveryFails() throws Exception {
        FakeElm327 fake = duke();
        fake.ecuPresent = false;
        ObdSession s = attached(fake, fastOptions());
        assertTrue(s.initialize());
        assertFalse(s.discover());
    }

    @Test
    public void lateResponseIsDrainedBeforeNextCommand() throws Exception {
        FakeElm327 fake = duke();
        fake.delayMs = 250L;
        fake.searching = false;
        ElmChannel channel = new ElmChannel(fake, 600L);
        fake.open(new ObdLink.Listener() {
            @Override
            public void onOpened(String adapterName) {
            }

            @Override
            public void onBytes(byte[] data) {
                channel.onBytes(data);
            }

            @Override
            public void onClosed(String reason) {
                channel.onClosed(reason);
            }
        });
        try {
            channel.transact("010C", 100L);
            fail("expected timeout");
        } catch (ObdException e) {
            assertEquals(ObdException.Kind.TIMEOUT, e.kind);
        }
        fake.delayMs = 10L;
        String next = channel.transact("010D", 1000L);
        Elm327Protocol.Response r = Elm327Protocol.parse(next, "010D");
        assertNotNull(Elm327Protocol.findMode01Payload(r, 0x0D));
        assertNull(Elm327Protocol.findMode01Payload(r, 0x0C));
        fake.close();
    }

    @Test
    public void slowAdapterWithinTimeoutStillWorks() throws Exception {
        FakeElm327 fake = duke();
        fake.delayMs = 40L;
        fake.chunkSize = 3;
        ObdSession s = ready(fake, fastOptions());
        s.pollCycle();
        assertEquals(4000.0, s.getSnapshot().get(ObdPids.Pid.RPM).value, 1e-9);
        fake.close();
    }

    @Test
    public void channelRefusesForbiddenCommandsBeforeWriting() throws Exception {
        FakeElm327 fake = duke();
        ObdSession s = attached(fake, fastOptions());
        for (String cmd : new String[]{"04", "0400", "ATSH7E0", "2F0101", "1101", "08"}) {
            try {
                s.channel().transact(cmd, 100L);
                fail("transact accepted " + cmd);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("not permitted"));
            }
        }
        assertTrue(fake.received.isEmpty());
    }

    @Test
    public void endToEndThroughWorkerThread() throws Exception {
        FakeElm327 fake = duke();
        fake.echoAlways = true;
        fake.chunkSize = 7;
        fake.storedDtcs.add("P0133");
        CopyOnWriteArrayList<ObdSnapshot> snaps = new CopyOnWriteArrayList<>();
        CountDownLatch ended = new CountDownLatch(1);
        AtomicReference<String> endReason = new AtomicReference<>();
        ObdSession.Options o = fastOptions();
        o.minCommandIntervalMs = 2L;
        ObdSession s = new ObdSession(fake, o, System::currentTimeMillis, new ObdSession.Listener() {
            @Override
            public void onSnapshot(ObdSession session, ObdSnapshot snapshot) {
                snaps.add(snapshot);
            }

            @Override
            public void onEnded(ObdSession session, String reason, boolean reachedConnected) {
                endReason.set(reason + "|" + reachedConnected);
                ended.countDown();
            }
        });
        s.start();
        assertTrue(waitFor(snaps, x -> x.connected && x.get(ObdPids.Pid.SPEED) != null && x.get(ObdPids.Pid.OIL) != null));
        s.requestDtcRead();
        assertTrue(waitFor(snaps, x -> x.dtcReadAtMs > 0 && x.storedDtcs.contains("P0133")));
        s.stop();
        assertTrue(ended.await(2, TimeUnit.SECONDS));
        assertEquals("Stopped|true", endReason.get());
        assertFalse(s.isRunning());
        synchronized (fake.received) {
            for (String cmd : fake.received) {
                assertTrue("sent forbidden " + cmd, Elm327Protocol.isAllowed(cmd));
                assertFalse(cmd.startsWith("04"));
            }
        }
    }

    @Test
    public void linkLossEndsSession() throws Exception {
        FakeElm327 fake = duke();
        CountDownLatch ended = new CountDownLatch(1);
        AtomicReference<String> endReason = new AtomicReference<>();
        CopyOnWriteArrayList<ObdSnapshot> snaps = new CopyOnWriteArrayList<>();
        ObdSession.Options o = fastOptions();
        o.minCommandIntervalMs = 5L;
        ObdSession s = new ObdSession(fake, o, System::currentTimeMillis, new ObdSession.Listener() {
            @Override
            public void onSnapshot(ObdSession session, ObdSnapshot snapshot) {
                snaps.add(snapshot);
            }

            @Override
            public void onEnded(ObdSession session, String reason, boolean reachedConnected) {
                endReason.set(reason);
                ended.countDown();
            }
        });
        s.start();
        assertTrue(waitFor(snaps, x -> x.connected));
        fake.dropLink("Link lost");
        assertTrue(ended.await(2, TimeUnit.SECONDS));
        assertEquals("Link lost", endReason.get());
        assertEquals("Link lost", s.getSnapshot().state);
        assertFalse(s.getSnapshot().connected);
    }

    @Test
    public void noEcuReportedWhileRetrying() throws Exception {
        FakeElm327 fake = duke();
        fake.ecuPresent = false;
        CopyOnWriteArrayList<ObdSnapshot> snaps = new CopyOnWriteArrayList<>();
        ObdSession s = new ObdSession(fake, fastOptions(), System::currentTimeMillis, new ObdSession.Listener() {
            @Override
            public void onSnapshot(ObdSession session, ObdSnapshot snapshot) {
                snaps.add(snapshot);
            }

            @Override
            public void onEnded(ObdSession session, String reason, boolean reachedConnected) {
            }
        });
        s.start();
        assertTrue(waitFor(snaps, x -> x.state.startsWith("No ECU response")));
        assertTrue(waitFor(snaps, x -> fake.count("0100") >= 2));
        s.stop();
    }

    private static boolean waitFor(List<ObdSnapshot> snaps, Predicate<ObdSnapshot> p) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000L;
        while (System.currentTimeMillis() < deadline) {
            for (ObdSnapshot s : snaps) {
                if (p.test(s)) {
                    return true;
                }
            }
            Thread.sleep(10L);
        }
        return false;
    }
}

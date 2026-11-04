package app.ktmnav.backend;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class DashboardFeaturesRcmPrpcTest {

    private final List<UUID> writeIds = new ArrayList<>();
    private final List<byte[]> writeValues = new ArrayList<>();
    private final List<long[]> masks = new ArrayList<>();
    private DashboardFeatures features;
    private FakeDashScheduler clock;
    private final byte[] key = new byte[16];
    private final byte[] iv = new byte[16];
    private long uptime = 1000L;

    @Before
    public void setUp() {
        Arrays.fill(key, (byte) 0x33);
        Arrays.fill(iv, (byte) 0x44);
        clock = new FakeDashScheduler();
        features = DashboardFeatures.withScheduler(null, new DashboardFeatures.Transport() {
            @Override public void write(UUID id, byte[] value, boolean coalesce) { writeIds.add(id); writeValues.add(value); }
            @Override public void read(UUID id) { }
            @Override public void subscribe(UUID id, boolean indication) { }
            @Override public void requestRssi() { }
            @Override public void changed() { }
            @Override public void handlebarMask(int mask, long uptimeMs) { masks.add(new long[]{mask, uptimeMs}); }
        }, clock);
        features.setClocks(() -> 1700000000000L, () -> uptime);
        features.setTelemetrySchema(Arrays.asList(
                new Datapoint(202, "bCCU_Voltage", "T_FLOAT", "V", 5000, 1000, 60000, true),
                new Datapoint(303, "ECU_Engine_Rpm", "T_UINT16", "rpm", 1000, 200, 60000, true),
                new Datapoint(349, "DASH_Odo_Meter_Extended", "T_FLOAT", "km", 5000, 1000, 60000, true),
                new Datapoint(355, "DASH_Fuel_Level", "T_UINT8", "", 5000, 1000, 60000, true)));
    }

    private byte[] rcm(int active, int mask) {
        byte[] v = new byte[32];
        v[16] = (byte) active;
        v[17] = (byte) mask;
        return BccuCrypto.INSTANCE.encryptControl(v, key, iv);
    }

    @Test
    public void inactiveFrameDeliversMaskZeroAndNoPress() {
        features.onAuthenticated(key, iv, false);
        features.handleRcm(rcm(0x00, 0x0F));
        Assert.assertEquals(0L, features.getButtonEventSequence());
        Assert.assertEquals(1, masks.size());
        Assert.assertEquals(0L, masks.get(0)[0]);

        uptime = 1500L;
        features.handleRcm(rcm(0xFF, 0x0A));
        Assert.assertEquals(0x0A, masks.get(1)[0]);
        Assert.assertEquals(1500L, masks.get(1)[1]);
        Assert.assertEquals(2L, features.getButtonEventSequence());

        features.handleRcm(rcm(0x00, 0x0A));
        Assert.assertEquals(0L, masks.get(2)[0]);
    }

    @Test
    public void plaintextFramesAreNotAcceptedAsPresses() {
        features.onAuthenticated(key, iv, false);
        byte[] plaintext = new byte[32];
        plaintext[16] = (byte) 0xFF;
        plaintext[17] = 0x01;
        features.handleRcm(plaintext);
        Assert.assertEquals(0L, features.getButtonEventSequence());
    }

    @Test
    public void framesBeforeAuthenticationAreDropped() {
        features.handleRcm(rcm(0xFF, 0x01));
        Assert.assertTrue(masks.isEmpty());
    }

    private List<byte[]> prpcPlain() {
        List<byte[]> list = new ArrayList<>();
        for (int i = 0; i < writeIds.size(); i++) {
            if (BccuProtocol.PRPC_REQUEST.equals(writeIds.get(i))) {
                list.add(BccuCrypto.INSTANCE.decryptData(writeValues.get(i), key, iv));
            }
        }
        return list;
    }

    private void respond(byte[] plain) {
        features.onCharacteristic(BccuProtocol.PRPC_RESPONSE, BccuCrypto.INSTANCE.encryptData(plain, key, iv));
    }

    @Test
    public void timeSyncThenU16ConfigureFallsBackToU32OnError() {
        features.onAuthenticated(key, iv, true);
        List<byte[]> sent = prpcPlain();
        byte[] ts = sent.get(0);
        Assert.assertEquals(BccuProtocol.RPC_OPCODE_SET_VALUE, ts[0]);
        Assert.assertEquals(1700000000000L, ByteBuffer.wrap(ts, 6, 8).order(ByteOrder.LITTLE_ENDIAN).getLong());
        byte[] cfgRpm = null;
        for (byte[] r : sent) {
            if (r[0] == BccuProtocol.RPC_OPCODE_TELEMETRY_CONFIGURE) {
                Assert.assertEquals("u16 sample rate first", 4, r[3]);
                if ((r[4] & 0xFF) == (303 & 0xFF)) {
                    cfgRpm = r;
                }
            }
        }
        Assert.assertNotNull(cfgRpm);
        respond(new byte[]{2, 0, ts[2], 2, 0, 0});
        int before = sent.size();
        respond(new byte[]{3, 0, cfgRpm[2], 2, 0, 3});
        List<byte[]> after = prpcPlain().subList(before, prpcPlain().size());
        Assert.assertEquals(BccuProtocol.RPC_OPCODE_TELEMETRY_CONTROL, after.get(0)[0]);
        Assert.assertEquals(BccuProtocol.TELEMETRY_CMD_STOP, after.get(0)[4]);
        Assert.assertEquals(BccuProtocol.RPC_OPCODE_TELEMETRY_CONFIGURE, after.get(1)[0]);
        Assert.assertEquals("u32 retry", 6, after.get(1)[3]);
        byte[] last = after.get(after.size() - 1);
        Assert.assertEquals(BccuProtocol.RPC_OPCODE_TELEMETRY_CONTROL, last[0]);
        Assert.assertEquals(BccuProtocol.TELEMETRY_CMD_START, last[4]);

        int again = prpcPlain().size();
        respond(new byte[]{3, 0, cfgRpm[2], 2, 0, 3});
        Assert.assertEquals("fallback happens once", again, prpcPlain().size());

        String status = features.getTelemetryStatus();
        Assert.assertTrue(status, status.contains("TimeSync: accepted"));
        Assert.assertTrue(status, status.contains("u16 fallback"));
        Assert.assertTrue(status, status.contains("TELEMETRY_CONFIGURE_INVALID_SAMPLERATE"));
    }

    @Test
    public void getValueResultsOnlyStoreConsistentActualData() {
        features.onAuthenticated(key, iv, true);
        byte[] odoReq = null;
        byte[] fuelReq = null;
        for (byte[] r : prpcPlain()) {
            if (r[0] == BccuProtocol.RPC_OPCODE_GET_VALUE) {
                int dp = (r[4] & 0xFF) | ((r[5] & 0xFF) << 8);
                if (dp == 349) odoReq = r;
                if (dp == 355) fuelReq = r;
            }
        }
        Assert.assertNotNull(odoReq);
        Assert.assertNotNull(fuelReq);
        Assert.assertTrue(features.getTelemetry().isEmpty());

        ByteBuffer wrongDp = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        wrongDp.putShort((short) 1).put(odoReq[2]).put((byte) 8).put((byte) 0).put((byte) 0).putShort((short) 202).putFloat(13.9f);
        respond(wrongDp.array());
        Assert.assertTrue("a value for a different datapoint is ignored", features.getTelemetry().isEmpty());

        ByteBuffer err = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN);
        err.putShort((short) 1).put(fuelReq[2]).put((byte) 2).put((byte) 0).put((byte) 2);
        respond(err.array());
        Assert.assertNull(features.getTelemetry().get("DASH_Fuel_Level"));

        clock.advance(DashboardFeatures.VALUE_POLL_INTERVAL_MS);
        byte[] odo2 = null;
        for (byte[] r : prpcPlain()) {
            if (r[0] == BccuProtocol.RPC_OPCODE_GET_VALUE && (r[4] & 0xFF) == (349 & 0xFF) && r != odoReq) {
                odo2 = r;
            }
        }
        Assert.assertNotNull("getValue is polled every 10 s", odo2);
        ByteBuffer ok = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        ok.putShort((short) 1).put(odo2[2]).put((byte) 8).put((byte) 0).put((byte) 0).putShort((short) 349).putFloat(1234.5f);
        respond(ok.array());
        Assert.assertEquals("1234.5 km", features.getTelemetry().get("DASH_Odo_Meter_Extended"));
        Assert.assertTrue(features.getTelemetryStatus().contains("polled datapoints: 1"));

        for (byte[] r : prpcPlain()) {
            Assert.assertTrue("only setValue ever sent is TimeSync",
                    r[0] != BccuProtocol.RPC_OPCODE_SET_VALUE || ((r[4] & 0xFF) | ((r[5] & 0xFF) << 8)) == BccuProtocol.DP_TIME_SYNC);
        }
    }

    @Test
    public void bannerClearUsesLastValidIconAndOff() {
        features.onAuthenticated(key, iv, false);
        writeIds.clear();
        writeValues.clear();
        features.sendNotification("Reroute", BccuProtocol.NotificationIcon.NOTIFICATION_REROUTING);
        clock.advance(DashBannerArbiter.DEFAULT_HOLD_MS);
        Assert.assertArrayEquals(new byte[]{3, 1, 'R', 'e', 'r', 'o', 'u', 't', 'e'},
                BccuCrypto.INSTANCE.decryptData(writeValues.get(0), key, iv));
        Assert.assertArrayEquals(new byte[]{1, 1}, BccuCrypto.INSTANCE.decryptData(writeValues.get(1), key, iv));
    }

    @Test
    public void disconnectStopsPollsAndBanners() {
        features.onAuthenticated(key, iv, true);
        features.sendNotification("A message longer than sixteen", null);
        features.onDisconnected();
        int writes = writeIds.size();
        clock.advance(60000);
        Assert.assertEquals(writes, writeIds.size());
        Assert.assertNull(features.getTelemetryStatus());
    }
}

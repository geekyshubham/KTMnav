package app.ktmnav.backend;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * End-to-end JVM test of the Gen 3 link: a fake dash and a recording radio drive BccuLink through
 * first pairing, silent reconnect, stall recovery, guidance, banner and clear, asserting the exact
 * plaintext bytes written to each characteristic and the order of GATT operations.
 */
public class BccuLinkFakeDashTest {

    private static final String ADDR = "02:00:00:4B:54:4D";
    private static final UUID DIS_MANUFACTURER = DashboardFeatures.CHAR_MANUFACTURER_NAME;
    private static final UUID DIS_MODEL = DashboardFeatures.CHAR_MODEL_NUMBER;
    private static final UUID MYSTERY_0201 = UUID.fromString("71ced1ac-0201-44f5-9454-806ff70b3e02");

    static final class Op {
        final String kind;
        final UUID uuid;
        final byte[] value;
        final boolean flag;

        Op(String kind, UUID uuid, byte[] value, boolean flag) {
            this.kind = kind;
            this.uuid = uuid;
            this.value = value;
            this.flag = flag;
        }

        @Override
        public String toString() {
            String u = uuid == null ? "" : uuid.toString().startsWith("71ced1ac") ? uuid.toString().substring(9, 13) : uuid.toString().substring(4, 8);
            return kind + (uuid != null ? " " + u : "") + (flag ? "*" : "");
        }
    }

    static final class FakeRadio implements BccuLink.Radio {
        final List<Op> ops = new ArrayList<>();
        final Set<UUID> services = new LinkedHashSet<>();
        final Map<UUID, List<UUID>> chars = new HashMap<>();
        final Map<UUID, Integer> props = new HashMap<>();
        boolean scanAvailable = true;

        FakeRadio() {
            services.addAll(Arrays.asList(BccuProtocol.MAIN_SERVICE, BccuProtocol.PRPC_SERVICE, BccuProtocol.RCM_SERVICE,
                    BccuProtocol.BASE_SERVICE, BccuProtocol.DEVICE_INFO_SERVICE, BccuProtocol.MYSTERY_SERVICE_0200));
            chars.put(BccuProtocol.DEVICE_INFO_SERVICE, Arrays.asList(DIS_MANUFACTURER, DIS_MODEL));
            chars.put(BccuProtocol.MYSTERY_SERVICE_0200, List.of(MYSTERY_0201));
            props.put(MYSTERY_0201, BccuLink.PROPERTY_NOTIFY | BccuLink.PROPERTY_READ);
        }

        private void add(String kind, UUID uuid, byte[] value, boolean flag) {
            ops.add(new Op(kind, uuid, value, flag));
        }

        @Override public boolean startScan(String address) { add("SCAN", null, null, false); return scanAvailable; }
        @Override public void stopScan() { add("STOPSCAN", null, null, false); }
        @Override public boolean connectGatt(String address) { add("CONNECT", null, null, false); return true; }
        @Override public void closeGatt() { add("CLOSE", null, null, false); }
        @Override public boolean discoverServices() { add("DISCOVER", null, null, false); return true; }
        @Override public boolean requestMtu(int mtu) { add("MTU" + mtu, null, null, false); return true; }
        @Override public void requestHighPriority() { add("PRIORITY_HIGH", null, null, false); }
        @Override public void write(UUID c, byte[] v, boolean coalesce) { add("WRITE", c, v.clone(), coalesce); }
        @Override public void read(UUID c) { add("READ", c, null, false); }
        @Override public void subscribe(UUID c, boolean indication) { add(indication ? "INDICATE" : "NOTIFY", c, null, false); }
        @Override public void requestRssi() { add("RSSI", null, null, false); }
        @Override public Collection<UUID> services() { return services; }
        @Override public List<UUID> characteristics(UUID service) { return chars.getOrDefault(service, List.of()); }
        @Override public int properties(UUID c) { return props.getOrDefault(c, 0); }
        @Override public String describeGatt() { return "fake GATT " + services; }
        @Override public boolean refreshGattCache() { add("REFRESH", null, null, false); return true; }

        byte[] lastWrite(UUID c) {
            for (int i = ops.size() - 1; i >= 0; i--) {
                Op op = ops.get(i);
                if ("WRITE".equals(op.kind) && c.equals(op.uuid)) {
                    return op.value;
                }
            }
            throw new AssertionError("no write to " + c);
        }

        List<byte[]> writesSince(int from, UUID c) {
            List<byte[]> list = new ArrayList<>();
            for (int i = from; i < ops.size(); i++) {
                Op op = ops.get(i);
                if ("WRITE".equals(op.kind) && c.equals(op.uuid)) {
                    list.add(op.value);
                }
            }
            return list;
        }

        List<String> kindsSince(int from) {
            List<String> list = new ArrayList<>();
            for (int i = from; i < ops.size(); i++) {
                list.add(ops.get(i).toString());
            }
            return list;
        }
    }

    static final class MemStore implements BccuLink.Store {
        final Map<String, Boolean> paired = new HashMap<>();
        final Map<String, List<byte[]>> pools = new HashMap<>();

        @Override public boolean hasPairedBefore(String a) { return paired.getOrDefault(a, false); }
        @Override public void markPairedBefore(String a) { paired.put(a, true); }
        @Override public List<byte[]> loadKeyPool(String a) { return pools.get(a); }
        @Override public void storeKeyPool(String a, List<byte[]> k) { pools.put(a, k); }
        @Override public String userName() { return ""; }
    }

    private FakeDashScheduler clock;
    private FakeRadio radio;
    private MemStore store;
    private final List<String> states = new ArrayList<>();
    private BccuLink link;

    @Before
    public void setUp() {
        clock = new FakeDashScheduler();
        radio = new FakeRadio();
        store = new MemStore();
        link = newLink();
    }

    private BccuLink newLink() {
        BccuLink l = new BccuLink(null, radio, store, new BccuLink.Listener() {
            @Override public void onState(ConnectionState s, String detail) { states.add(s + ":" + detail); }
            @Override public void onFeaturesChanged() { }
            @Override public void onButton(String button) { }
            @Override public void onMask(int mask, long uptimeMs) { }
        }, clock);
        l.getFeatures().setTelemetrySchema(Arrays.asList(
                new Datapoint(202, "bCCU_Voltage", "T_FLOAT", "V", 5000, 1000, 60000, true),
                new Datapoint(349, "DASH_Odo_Meter_Extended", "T_FLOAT", "km", 5000, 1000, 60000, true)));
        l.getFeatures().setMarqueeEnabled(true);
        return l;
    }

    private static byte[] plain(byte[] encrypted, byte[] key, byte[] iv) {
        return BccuCrypto.INSTANCE.decryptData(encrypted, key, iv);
    }

    private static byte[] label(int visibility, String text) {
        byte[] t = text.getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[t.length + 1];
        out[0] = (byte) visibility;
        System.arraycopy(t, 0, out, 1, t.length);
        return out;
    }

    private static byte[] bytes(int... v) {
        byte[] out = new byte[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (byte) v[i];
        }
        return out;
    }

    /** Brings the link up to the point where the dash sends m1; returns the fake dash. */
    private FakeDash linkUpAndNonce() {
        link.onLinkUp();
        link.onServicesDiscovered(true);
        link.onMtuChanged(517);
        FakeDash dash = new FakeDash();
        link.onCharacteristicChanged(BccuProtocol.AUTH_REQUEST, dash.m1);
        dash.onM2(radio.lastWrite(BccuProtocol.AUTH_REPLY));
        return dash;
    }

    private void dashSends(FakeDash dash, int cmd, int decoy, int expectedReply) {
        link.onCharacteristicChanged(BccuProtocol.AUTH_REQUEST, dash.command(cmd, decoy));
        Assert.assertEquals(expectedReply, dash.replyCommand(radio.lastWrite(BccuProtocol.AUTH_REPLY)));
    }

    @Test
    public void firstPairingPostAuthSequenceGuidanceBannerAndClear() {
        link.start(ADDR, "390 Duke", BccuLink.Start.DIRECT, 0);
        Assert.assertEquals(List.of("CONNECT"), radio.kindsSince(0));

        FakeDash dash = linkUpAndNonce();
        Assert.assertEquals(List.of("CONNECT", "DISCOVER", "MTU517", "PRIORITY_HIGH", "INDICATE 0701", "WRITE 0702"),
                radio.kindsSince(0));
        Assert.assertTrue(states.get(states.size() - 1).startsWith("AUTHENTICATING"));

        dashSends(dash, BccuProtocol.CMD_HELLO, 0x11, 0);
        dashSends(dash, BccuProtocol.CMD_GENERATE_KEYS, 0x00, 2);
        int beforeSelect = radio.ops.size();
        dashSends(dash, 16 | 7, 0x01, 16 | 7);
        Assert.assertTrue(link.isAuthenticated());
        Assert.assertTrue(store.hasPairedBefore(ADDR));
        Assert.assertTrue(states.get(states.size() - 1).startsWith("CONNECTED"));

        Assert.assertEquals("only what the rider sees is queued right after authentication", Arrays.asList(
                "WRITE 0702",
                "INDICATE 0103", "NOTIFY 070b", "WRITE 0703",
                "WRITE 0704*", "WRITE 0707*", "WRITE 0705*"),
                radio.kindsSince(beforeSelect));
        int beforeProbe = radio.ops.size();
        clock.advance(BccuLink.POST_AUTH_PROBE_DELAY_MS - 1);
        Assert.assertEquals(List.of(), radio.kindsSince(beforeProbe));
        clock.advance(1);
        Assert.assertEquals("device information, VIN, unknown services and telemetry are probed later", Arrays.asList(
                "RSSI",
                "READ 2a29", "READ 2a24", "NOTIFY 0002", "WRITE 0001", "READ 0002",
                "NOTIFY 0201", "READ 0201",
                "NOTIFY 0603", "NOTIFY 0602",
                "WRITE 0601", "WRITE 0601", "WRITE 0601",
                "WRITE 0601", "WRITE 0601", "WRITE 0601", "WRITE 0601", "WRITE 0601", "WRITE 0601", "WRITE 0601"),
                radio.kindsSince(beforeProbe));

        byte[] key = dash.keys.get(7);
        byte[] iv = dash.tempIv;
        Assert.assertArrayEquals(bytes(0x03, 0xFF), plain(radio.writesSince(beforeSelect, BccuProtocol.NAVIGATION_STATE).get(0), key, iv));
        Assert.assertArrayEquals(bytes(0x03, 20), plain(radio.lastWrite(BccuProtocol.TURN_ICON), key, iv));
        Assert.assertArrayEquals(label(3, "Welcome to KTMnav"), plain(radio.lastWrite(BccuProtocol.TURN_ROAD), key, iv));
        Assert.assertArrayEquals(label(1, ""), plain(radio.lastWrite(BccuProtocol.TURN_DISTANCE), key, iv));
        Assert.assertArrayEquals(bytes(0x01), plain(radio.lastWrite(BccuProtocol.BASE_GET_VIN_REQUEST), key, iv));

        List<byte[]> prpc = radio.writesSince(beforeSelect, BccuProtocol.PRPC_REQUEST);
        byte[] timeSync = plain(prpc.get(0), key, iv);
        Assert.assertEquals(14, timeSync.length);
        ByteBuffer ts = ByteBuffer.wrap(timeSync).order(ByteOrder.LITTLE_ENDIAN);
        Assert.assertEquals(BccuProtocol.RPC_OPCODE_SET_VALUE, ts.getShort());
        ts.get();
        Assert.assertEquals(10, ts.get());
        Assert.assertEquals(BccuProtocol.DP_TIME_SYNC, ts.getShort());
        byte[] configure = plain(prpc.get(1), key, iv);
        Assert.assertArrayEquals(bytes(0x03, 0x00, configure[2] & 0xFF, 0x04, 0xCA, 0x00, 0x88, 0x13), configure);
        byte[] control = plain(prpc.get(2), key, iv);
        Assert.assertArrayEquals(bytes(0x04, 0x00, control[2] & 0xFF, 0x02, 0x01, 0x00), control);
        int[] polled = {349, 351, 355, 356, 202, 311, 312};
        for (int i = 0; i < polled.length; i++) {
            byte[] get = plain(prpc.get(3 + i), key, iv);
            Assert.assertArrayEquals(bytes(0x01, 0x00, get[2] & 0xFF, 0x02, polled[i] & 0xFF, polled[i] >> 8), get);
        }

        int beforeGuidance = radio.ops.size();
        link.sendGuidance("250 m", "Café Straße", "2:12 pm", "5.0 km");
        link.sendTurnIcon(TurnIcon.QUITE_RIGHT);
        Assert.assertEquals(Arrays.asList("WRITE 0705*", "WRITE 0707*", "WRITE 0708*", "WRITE 0709*", "WRITE 0704*"),
                radio.kindsSince(beforeGuidance));
        Assert.assertArrayEquals(label(3, "250 m"), plain(radio.lastWrite(BccuProtocol.TURN_DISTANCE), key, iv));
        Assert.assertArrayEquals(label(3, "Cafe Strasse"), plain(radio.lastWrite(BccuProtocol.TURN_ROAD), key, iv));
        Assert.assertArrayEquals(label(3, "14:12"), plain(radio.lastWrite(BccuProtocol.ETA), key, iv));
        Assert.assertArrayEquals(label(3, "5.0 km"), plain(radio.lastWrite(BccuProtocol.REMAINING_DISTANCE), key, iv));
        Assert.assertArrayEquals(bytes(0x03, 0x07), plain(radio.lastWrite(BccuProtocol.TURN_ICON), key, iv));

        link.sendGuidance(null, null, "KTMnav", null);
        Assert.assertArrayEquals("non-clock ETA is hidden, never sent as text", label(1, ""), plain(radio.lastWrite(BccuProtocol.ETA), key, iv));

        int beforeGreetingExpiry = radio.ops.size();
        clock.advance(BccuLink.GREETING_MS);
        Assert.assertTrue("guidance cancels the greeting clear", radio.writesSince(beforeGreetingExpiry, BccuProtocol.TURN_ROAD).isEmpty());

        int beforeBanner = radio.ops.size();
        Assert.assertTrue(link.showBanner("msg", "Hello rider", DashBannerArbiter.PRIORITY_MESSAGE, 0, BccuProtocol.NotificationIcon.INFORMATION));
        clock.advance(DashBannerArbiter.DEFAULT_HOLD_MS);
        List<byte[]> banner = radio.writesSince(beforeBanner, BccuProtocol.NOTIFICATION);
        Assert.assertEquals(2, banner.size());
        Assert.assertArrayEquals(bytes(0x03, 0x02, 'H', 'e', 'l', 'l', 'o', ' ', 'r', 'i', 'd', 'e', 'r'), plain(banner.get(0), key, iv));
        Assert.assertArrayEquals("clear keeps the last valid icon, OFF, empty text", bytes(0x01, 0x02), plain(banner.get(1), key, iv));

        int beforeClear = radio.ops.size();
        link.clearGuidance();
        clock.advance(BccuLink.GUIDANCE_CLEAR_DELAY_MS - 1);
        Assert.assertTrue(radio.writesSince(beforeClear, BccuProtocol.TURN_ICON).isEmpty());
        clock.advance(1);
        UUID[] order = {BccuProtocol.TURN_ICON, BccuProtocol.TURN_DISTANCE, BccuProtocol.TURN_INFO,
                BccuProtocol.TURN_ROAD, BccuProtocol.ETA, BccuProtocol.REMAINING_DISTANCE};
        List<UUID> written = new ArrayList<>();
        for (int i = beforeClear; i < radio.ops.size(); i++) {
            Op op = radio.ops.get(i);
            if ("WRITE".equals(op.kind) && Arrays.asList(order).contains(op.uuid)) {
                written.add(op.uuid);
            }
        }
        Assert.assertEquals(Arrays.asList(order), written);
        Assert.assertArrayEquals(bytes(0x01, 0x01), plain(radio.lastWrite(BccuProtocol.TURN_ICON), key, iv));
        for (int i = 1; i < order.length; i++) {
            Assert.assertArrayEquals(label(1, ""), plain(radio.lastWrite(order[i]), key, iv));
        }
        Assert.assertTrue("guidance state is never switched off",
                radio.writesSince(beforeSelect + 1, BccuProtocol.NAVIGATION_STATE).size() == 1);
    }

    @Test
    public void greetingClearsAfterSixSecondsWithoutGuidance() {
        link.start(ADDR, "390 Duke", BccuLink.Start.DIRECT, 0);
        FakeDash dash = linkUpAndNonce();
        dashSends(dash, 0, 0x11, 0);
        dashSends(dash, 1, 0x00, 2);
        dashSends(dash, 16, 0x01, 16);
        int before = radio.ops.size();
        clock.advance(BccuLink.GREETING_MS - 1);
        Assert.assertTrue(radio.writesSince(before, BccuProtocol.TURN_ROAD).isEmpty());
        clock.advance(1);
        Assert.assertArrayEquals(label(1, ""), plain(radio.lastWrite(BccuProtocol.TURN_ROAD), dash.keys.get(0), dash.tempIv));
    }

    private List<byte[]> pairOnce() {
        link.start(ADDR, "390 Duke", BccuLink.Start.DIRECT, 0);
        FakeDash dash = linkUpAndNonce();
        dashSends(dash, 0, 0x11, 0);
        dashSends(dash, 1, 0x00, 2);
        dashSends(dash, 16 | 2, 0x01, 16 | 2);
        link.onLinkDown(8);
        Assert.assertTrue("an authenticated drop first retries directly", states.get(states.size() - 1).startsWith("CONNECTING:Link lost"));
        Assert.assertTrue(link.isFastReconnecting());
        clock.advance(BccuLink.fastReconnectBudgetMs());
        Assert.assertTrue(states.get(states.size() - 1).startsWith("DISCONNECTED"));
        Assert.assertFalse(link.isFastReconnecting());
        return store.pools.get(ADDR);
    }

    @Test
    public void authenticatedDropReconnectsDirectlyWithoutScanOrSettle() {
        link.start(ADDR, "390 Duke", BccuLink.Start.DIRECT, 0);
        FakeDash dash = linkUpAndNonce();
        dashSends(dash, 0, 0x11, 0);
        dashSends(dash, 1, 0x00, 2);
        dashSends(dash, 16 | 2, 0x01, 16 | 2);
        List<byte[]> pool = store.pools.get(ADDR);
        int beforeDrop = radio.ops.size();
        link.onLinkDown(8);
        Assert.assertEquals(List.of("CLOSE"), radio.kindsSince(beforeDrop));
        Assert.assertEquals(BccuLink.Phase.RECONNECT_WAIT, link.getPhase());
        Assert.assertTrue(link.isHandshakeInProgress());
        clock.advance(BccuLink.FAST_RECONNECT_DELAYS_MS[0] - 1);
        Assert.assertEquals(List.of("CLOSE"), radio.kindsSince(beforeDrop));
        clock.advance(1);
        Assert.assertEquals("no SCAN and no settle before the first direct attempt", List.of("CLOSE", "CONNECT"), radio.kindsSince(beforeDrop));
        Assert.assertEquals(BccuLink.Phase.CONNECTING, link.getPhase());

        FakeDash again = linkUpAndNonce();
        dashSends(again, BccuProtocol.CMD_HELLO, 0x1F, BccuHandshake.REPLY_USE_KEY_POOL);
        dashSends(again, 16 | 5, 0x00, 16 | 5);
        Assert.assertTrue(link.isAuthenticated());
        Assert.assertFalse(link.isFastReconnecting());
        Assert.assertTrue(states.get(states.size() - 1).startsWith("CONNECTED:Reconnected to 390 Duke"));
        Assert.assertArrayEquals(bytes(0x03, 0xFF), plain(radio.lastWrite(BccuProtocol.NAVIGATION_STATE), pool.get(5), again.tempIv));
        for (String kind : radio.kindsSince(beforeDrop)) {
            Assert.assertNotEquals("SCAN", kind);
        }
    }

    @Test
    public void directReconnectLadderHandsOverToTheSearchWhenTheDashStaysSilent() {
        link.start(ADDR, "390 Duke", BccuLink.Start.DIRECT, 0);
        FakeDash dash = linkUpAndNonce();
        dashSends(dash, 0, 0x11, 0);
        dashSends(dash, 1, 0x00, 2);
        dashSends(dash, 16, 0x01, 16);
        int beforeDrop = radio.ops.size();
        link.onLinkDown(8);
        long elapsed = 0;
        for (int attempt = 1; attempt <= BccuLink.FAST_RECONNECT_DELAYS_MS.length; attempt++) {
            clock.advance(BccuLink.FAST_RECONNECT_DELAYS_MS[attempt - 1]);
            elapsed += BccuLink.FAST_RECONNECT_DELAYS_MS[attempt - 1];
            Assert.assertEquals(BccuLink.Phase.CONNECTING, link.getPhase());
            Assert.assertTrue(states.get(states.size() - 1).contains("attempt " + attempt + " of " + BccuLink.FAST_RECONNECT_DELAYS_MS.length));
            clock.advance(BccuLink.FAST_CONNECT_TIMEOUT_MS);
            elapsed += BccuLink.FAST_CONNECT_TIMEOUT_MS;
        }
        Assert.assertEquals(elapsed, BccuLink.fastReconnectBudgetMs());
        int connects = 0;
        for (String kind : radio.kindsSince(beforeDrop)) {
            if ("CONNECT".equals(kind)) connects++;
            Assert.assertNotEquals("the ladder never scans", "SCAN", kind);
        }
        Assert.assertEquals(BccuLink.FAST_RECONNECT_DELAYS_MS.length, connects);
        Assert.assertEquals(BccuLink.Phase.IDLE, link.getPhase());
        Assert.assertTrue("the caller's scan-and-settle path takes over from here", states.get(states.size() - 1).startsWith("DISCONNECTED:Disconnected from 390 Duke"));
        Assert.assertEquals(0, link.getHandshakeRecoveries());
    }

    @Test
    public void bikeSeenDuringReconnectWaitConnectsImmediately() {
        link.start(ADDR, "390 Duke", BccuLink.Start.DIRECT, 0);
        FakeDash dash = linkUpAndNonce();
        dashSends(dash, 0, 0x11, 0);
        dashSends(dash, 1, 0x00, 2);
        dashSends(dash, 16, 0x01, 16);
        link.onLinkDown(19);
        int before = radio.ops.size();
        clock.advance(100);
        link.onBikeSeen("Classic ACL connected");
        Assert.assertEquals(List.of("CONNECT"), radio.kindsSince(before));
        Assert.assertEquals(BccuLink.Phase.CONNECTING, link.getPhase());
    }

    @Test
    public void keepaliveKeyReselectionsAreAnsweredOncePerThrottleWindow() {
        link.start(ADDR, "390 Duke", BccuLink.Start.DIRECT, 0);
        FakeDash dash = linkUpAndNonce();
        dashSends(dash, 0, 0x11, 0);
        dashSends(dash, 1, 0x00, 2);
        dashSends(dash, 16 | 2, 0x01, 16 | 2);
        int before = radio.ops.size();
        link.onCharacteristicChanged(BccuProtocol.AUTH_REQUEST, dash.command(16 | 2, 0x05));
        link.onCharacteristicChanged(BccuProtocol.AUTH_REQUEST, dash.command(16 | 2, 0x06));
        Assert.assertEquals("a burst of identical keepalives gets one reply", 1, radio.writesSince(before, BccuProtocol.AUTH_REPLY).size());
        clock.advance(BccuLink.KEY_RESELECT_THROTTLE_MS);
        link.onCharacteristicChanged(BccuProtocol.AUTH_REQUEST, dash.command(16 | 3, 0x07));
        Assert.assertEquals(2, radio.writesSince(before, BccuProtocol.AUTH_REPLY).size());
        Assert.assertArrayEquals(label(3, "1 km"), plain(sendAndLast("1 km"), dash.keys.get(3), dash.tempIv));
    }

    private byte[] sendAndLast(String distance) {
        link.sendGuidance(distance, null, null, null);
        return radio.lastWrite(BccuProtocol.TURN_DISTANCE);
    }

    @Test
    public void displayWriteTimeoutsNeverDropTheLink() {
        link.start(ADDR, "390 Duke", BccuLink.Start.DIRECT, 0);
        FakeDash dash = linkUpAndNonce();
        dashSends(dash, 0, 0x11, 0);
        dashSends(dash, 1, 0x00, 2);
        dashSends(dash, 16, 0x01, 16);
        for (int i = 0; i < 6; i++) {
            link.onGattOperationTimeout(true, false);
        }
        Assert.assertTrue("slow dashboard text writes are tolerated", link.isAuthenticated());
        link.onGattOperationTimeout(true, true);
        link.onGattOperationTimeout(true, true);
        Assert.assertTrue(link.isAuthenticated());
        link.onGattOperationTimeout(false, true);
        Assert.assertFalse("three control timeouts in a row still tear the link down", link.isAuthenticated());
    }

    @Test
    public void silentReconnectThroughScanSettleWithStoredPool() {
        List<byte[]> pool = pairOnce();
        int start = radio.ops.size();
        link.start(ADDR, "390 Duke", BccuLink.Start.SCAN, 0);
        Assert.assertEquals(List.of("SCAN"), radio.kindsSince(start));
        link.onScanResult("AA:BB:CC:DD:EE:FF");
        Assert.assertEquals(BccuLink.Phase.SCANNING, link.getPhase());
        link.onScanResult(ADDR.toLowerCase());
        Assert.assertEquals(BccuLink.Phase.SETTLING, link.getPhase());
        clock.advance(BccuLink.CONNECT_SETTLE_MS - 1);
        Assert.assertEquals(List.of("SCAN", "STOPSCAN"), radio.kindsSince(start));
        clock.advance(1);
        Assert.assertEquals(List.of("SCAN", "STOPSCAN", "CONNECT"), radio.kindsSince(start));

        FakeDash dash = linkUpAndNonce();
        dashSends(dash, BccuProtocol.CMD_HELLO, 0x1F, BccuHandshake.REPLY_USE_KEY_POOL);
        int beforeSelect = radio.ops.size();
        dashSends(dash, 16 | 3, 0x00, 16 | 3);
        Assert.assertTrue(link.isAuthenticated());
        Assert.assertArrayEquals(bytes(0x03, 0xFF),
                plain(radio.writesSince(beforeSelect, BccuProtocol.NAVIGATION_STATE).get(0), pool.get(3), dash.tempIv));
    }

    @Test
    public void stallKeepsPairingThenRecoversWithHelloZero() {
        List<byte[]> pool = pairOnce();
        link.start(ADDR, "390 Duke", BccuLink.Start.DIRECT, 0);
        link.onLinkUp();
        link.onServicesDiscovered(true);
        link.onMtuChanged(517);
        clock.advance(BccuLink.HANDSHAKE_TIMEOUT_KNOWN_MS - 1);
        Assert.assertEquals(BccuLink.Phase.LINK_UP, link.getPhase());
        int beforeStall = radio.ops.size();
        clock.advance(1);
        Assert.assertEquals(BccuLink.Phase.COOLDOWN, link.getPhase());
        Assert.assertEquals(List.of("CLOSE"), radio.kindsSince(beforeStall));
        Assert.assertTrue("pairing is never cleared on a stall", store.hasPairedBefore(ADDR));
        Assert.assertSame(pool, store.pools.get(ADDR));
        Assert.assertEquals(1, link.getHandshakeRecoveries());
        Assert.assertTrue(states.get(states.size() - 1).startsWith("CONNECTING"));

        clock.advance(BccuLink.RETRY_COOLDOWN_FIRST_MS - 1);
        Assert.assertEquals(List.of("CLOSE"), radio.kindsSince(beforeStall));
        clock.advance(1);
        Assert.assertEquals(List.of("CLOSE", "SCAN"), radio.kindsSince(beforeStall));
        link.onScanResult(ADDR);
        clock.advance(BccuLink.CONNECT_SETTLE_MS);
        Assert.assertEquals(List.of("CLOSE", "SCAN", "STOPSCAN", "CONNECT"), radio.kindsSince(beforeStall));

        FakeDash dash = linkUpAndNonce();
        dashSends(dash, BccuProtocol.CMD_HELLO, 0x10, BccuHandshake.REPLY_HELLO);
        dashSends(dash, BccuProtocol.CMD_GENERATE_KEYS, 0x00, BccuHandshake.REPLY_KEYS_GENERATED);
        dashSends(dash, 16 | 1, 0x00, 16 | 1);
        Assert.assertTrue(link.isAuthenticated());
        Assert.assertEquals(0, link.getHandshakeRecoveries());
        Assert.assertArrayEquals(dash.keys.get(0), store.pools.get(ADDR).get(0));
    }

    @Test
    public void secondStallUsesLongerCooldownWithoutRetryCap() {
        pairOnce();
        link.start(ADDR, "390 Duke", BccuLink.Start.DIRECT, 0);
        for (int attempt = 1; attempt <= 4; attempt++) {
            link.onLinkUp();
            clock.advance(BccuLink.HANDSHAKE_TIMEOUT_KNOWN_MS);
            Assert.assertEquals(BccuLink.Phase.COOLDOWN, link.getPhase());
            Assert.assertEquals(attempt, link.getHandshakeRecoveries());
            long cooldown = attempt == 1 ? BccuLink.RETRY_COOLDOWN_FIRST_MS : BccuLink.RETRY_COOLDOWN_LATER_MS;
            clock.advance(cooldown - 1);
            Assert.assertEquals(BccuLink.Phase.COOLDOWN, link.getPhase());
            clock.advance(1);
            Assert.assertEquals(BccuLink.Phase.SCANNING, link.getPhase());
            link.onScanResult(ADDR);
            clock.advance(BccuLink.CONNECT_SETTLE_MS);
            Assert.assertEquals(BccuLink.Phase.CONNECTING, link.getPhase());
        }
        Assert.assertTrue(store.hasPairedBefore(ADDR));
    }

    @Test
    public void firstPairingUsesLongBudgetAndRetriesAfterEarlyDrop() {
        link.start(ADDR, "390 Duke", BccuLink.Start.DIRECT, 0);
        FakeDash dash = linkUpAndNonce();
        dashSends(dash, 0, 0x11, 0);
        clock.advance(9000);
        int before = radio.ops.size();
        link.onLinkDown(8);
        Assert.assertEquals(BccuLink.Phase.IDLE, link.getPhase());
        Assert.assertTrue(states.get(states.size() - 1).startsWith("CONNECTING"));
        clock.advance(BccuLink.LINK_LOSS_RESCAN_MS);
        Assert.assertEquals(List.of("CLOSE", "SCAN"), radio.kindsSince(before));
        Assert.assertEquals(0, link.getHandshakeRecoveries());

        link.onScanResult(ADDR);
        clock.advance(BccuLink.CONNECT_SETTLE_MS);
        link.onLinkUp();
        clock.advance(BccuLink.HANDSHAKE_TIMEOUT_KNOWN_MS + 1);
        Assert.assertEquals("first pairing gets the 75 s budget", BccuLink.Phase.LINK_UP, link.getPhase());
        clock.advance(BccuLink.HANDSHAKE_TIMEOUT_FIRST_PAIR_MS - BccuLink.HANDSHAKE_TIMEOUT_KNOWN_MS - 1);
        Assert.assertEquals(BccuLink.Phase.COOLDOWN, link.getPhase());
    }

    @Test
    public void keySelectWithoutPoolRepairsInPlaceWithoutCooldown() {
        store.paired.put(ADDR, true);
        link.start(ADDR, "390 Duke", BccuLink.Start.DIRECT, 0);
        FakeDash dash = linkUpAndNonce();
        dashSends(dash, 0, 0x11, 0);
        int before = radio.ops.size();
        dashSends(dash, 16 | 4, 0x00, BccuHandshake.REPLY_HELLO);
        Assert.assertEquals("the link stays up and lets the dash restart pairing", BccuLink.Phase.LINK_UP, link.getPhase());
        Assert.assertEquals(List.of("WRITE 0702"), radio.kindsSince(before));
        Assert.assertTrue(store.hasPairedBefore(ADDR));
        dashSends(dash, 0, 0x11, 0);
        dashSends(dash, 1, 0x00, 2);
        dashSends(dash, 16 | 4, 0x00, 16 | 4);
        Assert.assertTrue(link.isAuthenticated());
        Assert.assertEquals(0, link.getHandshakeRecoveries());
        Assert.assertArrayEquals(dash.keys.get(4), store.pools.get(ADDR).get(4));
    }

    @Test
    public void classicAclSkipsScanAndSettles() {
        link.start(ADDR, "390 Duke", BccuLink.Start.SCAN, 0);
        int before = radio.ops.size();
        link.onBikeSeen("Classic ACL connected");
        Assert.assertEquals(BccuLink.Phase.SETTLING, link.getPhase());
        clock.advance(BccuLink.CONNECT_SETTLE_MS);
        Assert.assertEquals(List.of("STOPSCAN", "CONNECT"), radio.kindsSince(before));

        BccuLink settleOnly = newLink();
        int b2 = radio.ops.size();
        settleOnly.start(ADDR, "390 Duke", BccuLink.Start.SETTLE, 0);
        clock.advance(BccuLink.CONNECT_SETTLE_MS - 1);
        Assert.assertFalse(radio.kindsSince(b2).contains("CONNECT"));
        clock.advance(1);
        Assert.assertEquals("CONNECT", radio.kindsSince(b2).get(radio.kindsSince(b2).size() - 1));
    }

    @Test
    public void adapterOffAndOnResumesSearch() {
        List<byte[]> pool = pairOnce();
        Assert.assertNotNull(pool);
        link.start(ADDR, "390 Duke", BccuLink.Start.SCAN, 0);
        link.onAdapterStateChanged(false);
        Assert.assertEquals(BccuLink.Phase.WAITING_FOR_ADAPTER, link.getPhase());
        int before = radio.ops.size();
        link.onAdapterStateChanged(true);
        Assert.assertEquals(List.of("SCAN"), radio.kindsSince(before));
    }

    @Test
    public void unsupportedDashReportsClearStatusAndDoesNotAuthenticate() {
        radio.services.remove(BccuProtocol.MAIN_SERVICE);
        link.start(ADDR, "1290", BccuLink.Start.DIRECT, 0);
        link.onLinkUp();
        int before = radio.ops.size();
        link.onServicesDiscovered(true);
        Assert.assertEquals(BccuLink.Phase.UNSUPPORTED, link.getPhase());
        Assert.assertEquals("ERROR:" + VehicleProfile.UNSUPPORTED_STATUS, states.get(states.size() - 1));
        Assert.assertEquals(List.of("READ 2a29", "READ 2a24"), radio.kindsSince(before));
        Assert.assertEquals(VehicleProfile.LABEL_PRPC_ONLY, link.getVehicleProfile().getLabel());
        clock.advance(BccuLink.HANDSHAKE_TIMEOUT_FIRST_PAIR_MS * 2);
        Assert.assertEquals(BccuLink.Phase.UNSUPPORTED, link.getPhase());
    }

    @Test
    public void symbolTestWritesRawIconsAndEndClears() {
        link.start(ADDR, "390 Duke", BccuLink.Start.DIRECT, 0);
        FakeDash dash = linkUpAndNonce();
        dashSends(dash, 0, 0x11, 0);
        dashSends(dash, 1, 0x00, 2);
        dashSends(dash, 16, 0x01, 16);
        byte[] key = dash.keys.get(0);
        Assert.assertTrue(link.sendSymbolTest(57));
        Assert.assertArrayEquals(bytes(0x03, 57), plain(radio.lastWrite(BccuProtocol.TURN_ICON), key, dash.tempIv));
        Assert.assertArrayEquals(label(3, "Icon 57"), plain(radio.lastWrite(BccuProtocol.TURN_ROAD), key, dash.tempIv));
        Assert.assertTrue(link.sendBannerIconTest(6));
        Assert.assertArrayEquals(bytes(0x03, 0x06, 'I', 'c', 'o', 'n', ' ', '6'), plain(radio.lastWrite(BccuProtocol.NOTIFICATION), key, dash.tempIv));
        link.endSymbolTest();
        Assert.assertArrayEquals(bytes(0x01, 0x01), plain(radio.lastWrite(BccuProtocol.TURN_ICON), key, dash.tempIv));
        Assert.assertArrayEquals("banner clear never uses the tested icon or 0", bytes(0x01, 0x02),
                plain(radio.lastWrite(BccuProtocol.NOTIFICATION), key, dash.tempIv));
    }

    @Test
    public void midSessionKeySelectAcksAndSwitchesSessionKey() {
        link.start(ADDR, "390 Duke", BccuLink.Start.DIRECT, 0);
        FakeDash dash = linkUpAndNonce();
        dashSends(dash, 0, 0x11, 0);
        dashSends(dash, 1, 0x00, 2);
        dashSends(dash, 16 | 2, 0x01, 16 | 2);
        int connectedReports = states.size();

        dashSends(dash, 16 | 9, 0x05, 16 | 9);
        Assert.assertTrue(link.isAuthenticated());
        Assert.assertEquals("re-key is silent: no new state report", connectedReports, states.size());

        link.sendGuidance("1 km", null, null, null);
        Assert.assertArrayEquals(label(3, "1 km"), plain(radio.lastWrite(BccuProtocol.TURN_DISTANCE), dash.keys.get(9), dash.tempIv));
        Assert.assertTrue(link.showBanner("msg", "Hi", DashBannerArbiter.PRIORITY_MESSAGE, 0, BccuProtocol.NotificationIcon.INFORMATION));
        Assert.assertArrayEquals(bytes(0x03, 0x02, 'H', 'i'), plain(radio.lastWrite(BccuProtocol.NOTIFICATION), dash.keys.get(9), dash.tempIv));
    }

    @Test
    public void missingNavServiceDecision() {
        Assert.assertEquals(BccuLink.MissingNavAction.UNSUPPORTED, BccuLink.missingNavAction(false, false));
        Assert.assertEquals(BccuLink.MissingNavAction.UNSUPPORTED, BccuLink.missingNavAction(false, true));
        Assert.assertEquals(BccuLink.MissingNavAction.RETRY_WITH_CACHE_REFRESH, BccuLink.missingNavAction(true, false));
        Assert.assertEquals(BccuLink.MissingNavAction.RETRY, BccuLink.missingNavAction(true, true));
    }

    @Test
    public void unsupportedDashClosesGattAfterDeviceInformationReads() {
        radio.services.remove(BccuProtocol.MAIN_SERVICE);
        link.start(ADDR, "1290", BccuLink.Start.DIRECT, 0);
        link.onLinkUp();
        int before = radio.ops.size();
        link.onServicesDiscovered(true);
        link.onCharacteristicRead(DIS_MANUFACTURER, "KTM".getBytes(StandardCharsets.US_ASCII));
        Assert.assertFalse(radio.kindsSince(before).contains("CLOSE"));
        link.onCharacteristicRead(DIS_MODEL, "1290".getBytes(StandardCharsets.US_ASCII));
        Assert.assertEquals(List.of("READ 2a29", "READ 2a24", "CLOSE"), radio.kindsSince(before));
        Assert.assertEquals(BccuLink.Phase.UNSUPPORTED, link.getPhase());
        link.onLinkDown(8);
        clock.advance(BccuLink.UNSUPPORTED_INFO_READ_MS * 10);
        Assert.assertEquals(List.of("READ 2a29", "READ 2a24", "CLOSE"), radio.kindsSince(before));
        Assert.assertEquals(BccuLink.Phase.UNSUPPORTED, link.getPhase());
    }

    @Test
    public void unsupportedDashClosesGattWhenReadsNeverArrive() {
        radio.services.remove(BccuProtocol.MAIN_SERVICE);
        link.start(ADDR, "1290", BccuLink.Start.DIRECT, 0);
        link.onLinkUp();
        int before = radio.ops.size();
        link.onServicesDiscovered(true);
        clock.advance(BccuLink.UNSUPPORTED_INFO_READ_MS - 1);
        Assert.assertFalse(radio.kindsSince(before).contains("CLOSE"));
        clock.advance(1);
        Assert.assertEquals("CLOSE", radio.kindsSince(before).get(radio.kindsSince(before).size() - 1));
    }

    @Test
    public void pairedDashMissingNavServiceRefreshesCacheOnceAndRetries() {
        pairOnce();
        radio.services.remove(BccuProtocol.MAIN_SERVICE);
        link.start(ADDR, "390 Duke", BccuLink.Start.DIRECT, 0);
        link.onLinkUp();
        int before = radio.ops.size();
        link.onServicesDiscovered(true);
        Assert.assertEquals(List.of("REFRESH", "CLOSE"), radio.kindsSince(before));
        Assert.assertEquals(BccuLink.Phase.COOLDOWN, link.getPhase());
        Assert.assertEquals(1, link.getHandshakeRecoveries());
        Assert.assertTrue(states.get(states.size() - 1).startsWith("CONNECTING"));

        clock.advance(BccuLink.RETRY_COOLDOWN_FIRST_MS);
        link.onScanResult(ADDR);
        clock.advance(BccuLink.CONNECT_SETTLE_MS);
        link.onLinkUp();
        int second = radio.ops.size();
        link.onServicesDiscovered(true);
        Assert.assertEquals("only one cache refresh per start", List.of("CLOSE"), radio.kindsSince(second));
        Assert.assertEquals(BccuLink.Phase.COOLDOWN, link.getPhase());

        radio.services.add(BccuProtocol.MAIN_SERVICE);
        clock.advance(BccuLink.RETRY_COOLDOWN_LATER_MS);
        link.onScanResult(ADDR);
        clock.advance(BccuLink.CONNECT_SETTLE_MS);
        FakeDash dash = linkUpAndNonce();
        dashSends(dash, BccuProtocol.CMD_HELLO, 0x11, BccuHandshake.REPLY_HELLO);
        dashSends(dash, BccuProtocol.CMD_GENERATE_KEYS, 0x00, BccuHandshake.REPLY_KEYS_GENERATED);
        dashSends(dash, 16 | 1, 0x00, 16 | 1);
        Assert.assertTrue(link.isAuthenticated());
    }

    @Test
    public void gattTimeoutTeardownPolicy() {
        Assert.assertTrue(BccuLink.shouldDropOnGattTimeout(BccuLink.Phase.LINK_UP, true, 1));
        Assert.assertFalse(BccuLink.shouldDropOnGattTimeout(BccuLink.Phase.LINK_UP, false, 1));
        Assert.assertFalse(BccuLink.shouldDropOnGattTimeout(BccuLink.Phase.AUTHENTICATED, true, 2));
        Assert.assertFalse(BccuLink.shouldDropOnGattTimeout(BccuLink.Phase.AUTHENTICATED, false, 2));
        Assert.assertTrue(BccuLink.shouldDropOnGattTimeout(BccuLink.Phase.AUTHENTICATED, false, 3));
    }

    @Test
    public void authenticatedLinkSurvivesIsolatedGattTimeouts() {
        link.start(ADDR, "390 Duke", BccuLink.Start.DIRECT, 0);
        FakeDash dash = linkUpAndNonce();
        dashSends(dash, 0, 0x11, 0);
        dashSends(dash, 1, 0x00, 2);
        dashSends(dash, 16, 0x01, 16);

        link.onGattOperationTimeout(false);
        link.onGattOperationTimeout(true);
        Assert.assertTrue(link.isAuthenticated());
        link.onGattOperationCompleted();
        link.onGattOperationTimeout(false);
        link.onGattOperationTimeout(false);
        Assert.assertTrue("a completed op resets the streak", link.isAuthenticated());
        link.onGattOperationTimeout(false);
        Assert.assertFalse(link.isAuthenticated());
        Assert.assertTrue("a torn-down session is retried directly first", states.get(states.size() - 1).startsWith("CONNECTING:Link lost"));
        Assert.assertTrue(link.isFastReconnecting());
    }

    @Test
    public void handshakeWriteTimeoutDropsImmediately() {
        link.start(ADDR, "390 Duke", BccuLink.Start.DIRECT, 0);
        linkUpAndNonce();
        link.onGattOperationTimeout(false);
        Assert.assertEquals(BccuLink.Phase.LINK_UP, link.getPhase());
        link.onGattOperationTimeout(true);
        Assert.assertEquals(BccuLink.Phase.IDLE, link.getPhase());
    }

    @Test
    public void handshakeInProgressCoversSettleLinkUpAndAuth() {
        link.start(ADDR, "390 Duke", BccuLink.Start.SETTLE, 0);
        Assert.assertTrue(link.isHandshakeInProgress());
        clock.advance(BccuLink.CONNECT_SETTLE_MS);
        Assert.assertTrue(link.isHandshakeInProgress());
        FakeDash dash = linkUpAndNonce();
        Assert.assertTrue(link.isHandshakeInProgress());
        dashSends(dash, 0, 0x11, 0);
        dashSends(dash, 1, 0x00, 2);
        dashSends(dash, 16, 0x01, 16);
        Assert.assertFalse(link.isHandshakeInProgress());
        link.onLinkDown(8);
        Assert.assertTrue("the direct reconnect ladder counts as in progress so background suspend waits", link.isHandshakeInProgress());
        clock.advance(BccuLink.fastReconnectBudgetMs());
        Assert.assertFalse(link.isHandshakeInProgress());
    }
}

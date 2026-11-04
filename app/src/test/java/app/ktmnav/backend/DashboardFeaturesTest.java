package app.ktmnav.backend;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class DashboardFeaturesTest {

    private static class MockTransport implements DashboardFeatures.Transport {
        final List<WriteOp> writes = new ArrayList<>();
        final List<UUID> reads = new ArrayList<>();
        final List<SubOp> subscriptions = new ArrayList<>();
        int rssiRequests = 0;
        int changedCount = 0;

        static class WriteOp {
            final UUID id;
            final byte[] value;
            final boolean coalesce;

            WriteOp(UUID id, byte[] value, boolean coalesce) {
                this.id = id;
                this.value = value != null ? value.clone() : null;
                this.coalesce = coalesce;
            }
        }

        static class SubOp {
            final UUID id;
            final boolean indication;

            SubOp(UUID id, boolean indication) {
                this.id = id;
                this.indication = indication;
            }
        }

        @Override
        public void write(UUID id, byte[] value, boolean coalesce) {
            writes.add(new WriteOp(id, value, coalesce));
        }

        @Override
        public void read(UUID id) {
            reads.add(id);
        }

        @Override
        public void subscribe(UUID id, boolean indication) {
            subscriptions.add(new SubOp(id, indication));
        }

        @Override
        public void requestRssi() {
            rssiRequests++;
        }

        @Override
        public void changed() {
            changedCount++;
        }
    }

    private MockTransport transport;
    private DashboardFeatures dashboard;
    private final byte[] testKey = new byte[16];
    private final byte[] testIv = new byte[16];

    @Before
    public void setUp() {
        transport = new MockTransport();
        dashboard = new DashboardFeatures(null, transport, null); // pure JVM mode without Android Looper
        Arrays.fill(testKey, (byte) 0x11);
        Arrays.fill(testIv, (byte) 0x22);

        // Preload schema with known test datapoints
        List<Datapoint> testSchema = Arrays.asList(
                new Datapoint(200, "ECU_Engine_Rpm", "T_UINT16", "rpm", 1000, 500, 5000, true),
                new Datapoint(202, "bCCU_Voltage", "T_FLOAT", "V", 2000, 1000, 10000, true),
                new Datapoint(300, "Non_Telemetry_Signal", "T_UINT8", null, 5000, 250, 60000, false)
        );
        dashboard.setTelemetrySchema(testSchema);
    }

    @Test
    public void testOnAuthenticatedInitializesServicesAndReads() {
        dashboard.onAuthenticated(testKey, testIv, true);

        // Verify RCM subscribed with indication = true
        boolean rcmSubscribed = false;
        for (MockTransport.SubOp sub : transport.subscriptions) {
            if (BccuProtocol.RCM_REMOTE_CONTROL.equals(sub.id) && sub.indication) {
                rcmSubscribed = true;
                break;
            }
        }
        Assert.assertTrue("RCM indication should be subscribed", rcmSubscribed);

        // Verify VIN subscribed (indication = false)
        boolean vinSubscribed = false;
        for (MockTransport.SubOp sub : transport.subscriptions) {
            if (BccuProtocol.BASE_VIN.equals(sub.id) && !sub.indication) {
                vinSubscribed = true;
                break;
            }
        }
        Assert.assertTrue("VIN notification should be subscribed", vinSubscribed);

        // Verify VIN read request written and characteristic read
        Assert.assertTrue("Should read BASE_VIN", transport.reads.contains(BccuProtocol.BASE_VIN));

        // Verify Device Info reads
        Assert.assertTrue("Should read Manufacturer Name", transport.reads.contains(DashboardFeatures.CHAR_MANUFACTURER_NAME));
        Assert.assertTrue("Should read Model Number", transport.reads.contains(DashboardFeatures.CHAR_MODEL_NUMBER));

        // Verify RSSI requested
        Assert.assertTrue("Initial RSSI request made", transport.rssiRequests > 0);

        // Verify PRPC subscriptions and configuration
        boolean prpcNotifSubscribed = false;
        for (MockTransport.SubOp sub : transport.subscriptions) {
            if (BccuProtocol.PRPC_NOTIFICATION.equals(sub.id)) {
                prpcNotifSubscribed = true;
                break;
            }
        }
        Assert.assertTrue("PRPC_NOTIFICATION should be subscribed", prpcNotifSubscribed);

        // Check PRPC request writes
        int prpcWrites = 0;
        for (MockTransport.WriteOp write : transport.writes) {
            if (BccuProtocol.PRPC_REQUEST.equals(write.id)) {
                prpcWrites++;
            }
        }
        // At least 2 config requests for ECU_Engine_Rpm and bCCU_Voltage + 1 start command
        Assert.assertTrue("PRPC requests sent: " + prpcWrites, prpcWrites >= 3);
    }

    @Test
    public void testOnAuthenticatedWithoutPrpc() {
        dashboard.onAuthenticated(testKey, testIv, false);

        for (MockTransport.SubOp sub : transport.subscriptions) {
            Assert.assertNotEquals(BccuProtocol.PRPC_NOTIFICATION, sub.id);
            Assert.assertNotEquals(BccuProtocol.PRPC_RESPONSE, sub.id);
        }
        for (MockTransport.WriteOp write : transport.writes) {
            Assert.assertNotEquals(BccuProtocol.PRPC_REQUEST, write.id);
        }
    }

    @Test
    public void testOnAuthenticatedRejectsInvalidKeyOrIv() {
        // Null key
        dashboard.onAuthenticated(null, testIv, true);
        Assert.assertTrue(transport.subscriptions.isEmpty());
        Assert.assertTrue(transport.reads.isEmpty());
        Assert.assertTrue(transport.writes.isEmpty());
        Assert.assertEquals(0, transport.rssiRequests);

        // Short key (15 bytes)
        dashboard.onAuthenticated(new byte[15], testIv, true);
        Assert.assertTrue(transport.subscriptions.isEmpty());

        // Null IV
        dashboard.onAuthenticated(testKey, null, true);
        Assert.assertTrue(transport.subscriptions.isEmpty());

        // Long IV (17 bytes)
        dashboard.onAuthenticated(testKey, new byte[17], true);
        Assert.assertTrue(transport.subscriptions.isEmpty());

        // Verify still unauthenticated: notifications dropped
        dashboard.sendNotification("Test", BccuProtocol.NotificationIcon.WARNING);
        Assert.assertTrue(transport.writes.isEmpty());
    }

    @Test
    public void testTelemetryNotificationDecoding() {
        dashboard.onAuthenticated(testKey, testIv, true);
        int initialChanged = transport.changedCount;

        // Build a raw telemetry notification:
        // Opcode 7, sid 1
        // Triple 1: id=200 (ECU_Engine_Rpm, T_UINT16), value = 3500 (LE)
        // Triple 2: id=202 (bCCU_Voltage, T_FLOAT), value = 14.2f (LE)
        ByteBuffer buf = ByteBuffer.allocate(3 + (10 + 2) + (10 + 4)).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) 7);
        buf.put((byte) 1);

        // Triple 1
        buf.putLong(System.currentTimeMillis());
        buf.putShort((short) 200);
        buf.putShort((short) 3500);

        // Triple 2
        buf.putLong(System.currentTimeMillis());
        buf.putShort((short) 202);
        buf.putFloat(14.2f);

        byte[] plainNotification = buf.array();
        byte[] encrypted = BccuCrypto.INSTANCE.encryptData(plainNotification, testKey, testIv);

        dashboard.onCharacteristic(BccuProtocol.PRPC_NOTIFICATION, encrypted);

        Map<String, String> telemetry = dashboard.getTelemetry();
        Assert.assertEquals("3500 rpm", telemetry.get("ECU_Engine_Rpm"));
        Assert.assertEquals("14.2 V", telemetry.get("bCCU_Voltage"));
        Assert.assertTrue(transport.changedCount > initialChanged);
    }

    @Test
    public void testVehicleInfoAndVinDecoding() {
        dashboard.onAuthenticated(testKey, testIv, true);

        // Manufacturer read
        dashboard.onCharacteristic(DashboardFeatures.CHAR_MANUFACTURER_NAME, "KTM AG\0".getBytes(StandardCharsets.UTF_8));
        Assert.assertEquals("KTM AG", dashboard.getVehicleInfo().get("Manufacturer"));

        // Model read
        dashboard.onCharacteristic(DashboardFeatures.CHAR_MODEL_NUMBER, "1390 SDR\0".getBytes(StandardCharsets.UTF_8));
        Assert.assertEquals("1390 SDR", dashboard.getVehicleInfo().get("ModelNumber"));

        // Encrypted VIN notification
        byte[] vinBytes = "VBK1234567890ABCD".getBytes(StandardCharsets.UTF_8);
        byte[] encryptedVin = BccuCrypto.INSTANCE.encryptData(vinBytes, testKey, testIv);
        dashboard.onCharacteristic(BccuProtocol.BASE_VIN, encryptedVin);
        Assert.assertEquals("VBK1234567890ABCD", dashboard.getVehicleInfo().get("VIN"));

        // Unsupported fields should remain missing (not fabricated)
        Assert.assertNull(dashboard.getVehicleInfo().get("SerialNumber"));
    }

    @Test
    public void testOnCharacteristicRawMysteryAndDeviceInfoExtra() {
        dashboard.onAuthenticated(testKey, testIv, true);

        // Mystery 0200 service characteristic
        UUID mysteryUuid = UUID.fromString("71ced1ac-0201-44f5-9454-806ff70b3e02");
        byte[] mysteryRaw = new byte[]{0x01, 0x02, (byte) 0xFE};
        dashboard.onCharacteristic(mysteryUuid, mysteryRaw);

        String mysteryKey = "Raw/Unknown:" + mysteryUuid;
        Assert.assertTrue(dashboard.getVehicleInfo().containsKey(mysteryKey));
        Assert.assertTrue(dashboard.getVehicleInfo().get(mysteryKey).contains("0102fe"));

        // Standard DeviceInfo extra characteristic (e.g. 0x2A23 System ID)
        UUID systemIdUuid = UUID.fromString("00002a23-0000-1000-8000-00805f9b34fb");
        byte[] systemIdRaw = new byte[]{0x11, 0x22, 0x33};
        dashboard.onCharacteristic(systemIdUuid, systemIdRaw);

        String systemIdKey = "Raw:" + systemIdUuid;
        Assert.assertTrue(dashboard.getVehicleInfo().containsKey(systemIdKey));
        Assert.assertTrue(dashboard.getVehicleInfo().get(systemIdKey).contains("112233"));
    }

    @Test
    public void testRssiUpdates() {
        Assert.assertNull(dashboard.getRssi());
        dashboard.onRssi(-72);
        Assert.assertEquals(Integer.valueOf(-72), dashboard.getRssi());
        dashboard.onRssi(-55);
        Assert.assertEquals(Integer.valueOf(-55), dashboard.getRssi());
    }

    @Test
    public void testHandleRcmRisingEdges() {
        dashboard.onAuthenticated(testKey, testIv, true);
        Assert.assertEquals(0L, dashboard.getButtonEventSequence());
        int changedBefore = transport.changedCount;

        // Packet 1: Button SET pressed (bit 0 = 1)
        byte[] p1 = new byte[32];
        p1[16] = (byte) 0xFF;
        p1[17] = 0x01; // SET pressed
        byte[] encP1 = BccuCrypto.INSTANCE.encryptControl(p1, testKey, testIv);

        dashboard.handleRcm(encP1);
        Assert.assertEquals("SET", dashboard.getLastButton());
        Assert.assertEquals(1L, dashboard.getButtonEventSequence());
        Assert.assertTrue(transport.changedCount > changedBefore);

        int changedAfterP1 = transport.changedCount;

        // Packet 2: SET still held -> no new rising edge
        dashboard.handleRcm(encP1);
        Assert.assertEquals("SET", dashboard.getLastButton());
        Assert.assertEquals(1L, dashboard.getButtonEventSequence());
        Assert.assertEquals(changedAfterP1, transport.changedCount);

        // Packet 3: SET released, UP pressed (bit 3 = 8)
        byte[] p3 = new byte[32];
        p3[16] = (byte) 0xFF;
        p3[17] = (byte) (1 << BccuProtocol.HandlebarButton.UP.getBitIndex());
        byte[] encP3 = BccuCrypto.INSTANCE.encryptControl(p3, testKey, testIv);

        dashboard.handleRcm(encP3);
        Assert.assertEquals("UP", dashboard.getLastButton());
        Assert.assertEquals(2L, dashboard.getButtonEventSequence());
        Assert.assertTrue(transport.changedCount > changedAfterP1);
    }

    @Test
    public void testButtonEventSequenceMonotonicAndBackBehavior() {
        dashboard.onAuthenticated(testKey, testIv, true);
        Assert.assertEquals(0L, dashboard.getButtonEventSequence());

        // Bit 1 = BACK (1 << 1 = 0x02)
        byte[] backPacket = new byte[32];
        backPacket[16] = (byte) 0xFF;
        backPacket[17] = (byte) (1 << BccuProtocol.HandlebarButton.BACK.getBitIndex());
        byte[] encBack = BccuCrypto.INSTANCE.encryptControl(backPacket, testKey, testIv);

        // 1. Initial press of BACK -> sequence increments to 1
        dashboard.handleRcm(encBack);
        Assert.assertEquals("BACK", dashboard.getLastButton());
        Assert.assertEquals(1L, dashboard.getButtonEventSequence());

        // 2. Held BACK repeated encrypted packet -> no increment
        dashboard.handleRcm(encBack);
        dashboard.handleRcm(encBack);
        Assert.assertEquals("BACK", dashboard.getLastButton());
        Assert.assertEquals(1L, dashboard.getButtonEventSequence());

        // 3. Telemetry and RSSI updates -> no increment
        dashboard.onRssi(-65);
        dashboard.onCharacteristic(DashboardFeatures.CHAR_MANUFACTURER_NAME, "KTM".getBytes(StandardCharsets.UTF_8));
        Assert.assertEquals(1L, dashboard.getButtonEventSequence());

        // 4. Release all buttons (bitmask 0)
        byte[] releasePacket = new byte[32];
        releasePacket[16] = (byte) 0xFF;
        releasePacket[17] = 0x00;
        byte[] encRelease = BccuCrypto.INSTANCE.encryptControl(releasePacket, testKey, testIv);
        dashboard.handleRcm(encRelease);
        Assert.assertEquals(1L, dashboard.getButtonEventSequence());

        // 5. Press BACK again -> sequence increments to 2
        dashboard.handleRcm(encBack);
        Assert.assertEquals("BACK", dashboard.getLastButton());
        Assert.assertEquals(2L, dashboard.getButtonEventSequence());

        // 6. On disconnect, sequence is preserved monotonically
        dashboard.onDisconnected();
        Assert.assertNull(dashboard.getLastButton());
        Assert.assertEquals(2L, dashboard.getButtonEventSequence());
    }

    @Test
    public void testSendNotificationWhenUnauthenticatedDoesNothing() {
        dashboard.sendNotification("Test Alert", BccuProtocol.NotificationIcon.WARNING);
        Assert.assertTrue("No writes allowed when unauthenticated", transport.writes.isEmpty());
    }

    @Test
    public void testSendNotificationWhenAuthenticated() {
        dashboard.onAuthenticated(testKey, testIv, true);
        transport.writes.clear();

        dashboard.setMarqueeEnabled(false);
        dashboard.sendNotification("Speed Alert", BccuProtocol.NotificationIcon.SPEED);

        Assert.assertEquals(1, transport.writes.size());
        MockTransport.WriteOp op = transport.writes.get(0);
        Assert.assertEquals(BccuProtocol.NOTIFICATION, op.id);
        Assert.assertTrue(op.coalesce);

        byte[] decrypted = BccuCrypto.INSTANCE.decryptData(op.value, testKey, testIv);
        Assert.assertEquals(BccuProtocol.Visibility.FULL.getBinary(), decrypted[0]);
        // Banner icons are coerced to WAYPOINT/REROUTING because other icons do not render on the Gen 3 banner
        Assert.assertEquals(BccuProtocol.NotificationIcon.NOTIFICATION_WAYPOINT.getBinary(), decrypted[1]);
        String text = new String(decrypted, 2, decrypted.length - 2, StandardCharsets.UTF_8);
        Assert.assertEquals("Speed Alert", text);
    }

    @Test
    public void testSettingsChangedDisablingActiveMarqueeClearsDisplay() {
        dashboard.onAuthenticated(testKey, testIv, true);
        transport.writes.clear();

        dashboard.setMarqueeEnabled(true);
        // Long notification triggers active notification / marquee
        dashboard.sendNotification("Attention! Route blocked ahead due to construction", BccuProtocol.NotificationIcon.WARNING);
        Assert.assertFalse("Initial frame written", transport.writes.isEmpty());

        transport.writes.clear();

        // Preference toggled off -> settingsChanged()
        dashboard.setMarqueeEnabled(false);
        dashboard.settingsChanged();

        Assert.assertEquals("Should emit clear notification frame (Visibility.OFF)", 1, transport.writes.size());
        MockTransport.WriteOp clearOp = transport.writes.get(0);
        Assert.assertEquals(BccuProtocol.NOTIFICATION, clearOp.id);
        byte[] decrypted = BccuCrypto.INSTANCE.decryptData(clearOp.value, testKey, testIv);
        Assert.assertEquals(BccuProtocol.Visibility.OFF.getBinary(), decrypted[0]);

        // Calling settingsChanged again when no notification is active should not emit duplicate clear frames
        transport.writes.clear();
        dashboard.settingsChanged();
        Assert.assertTrue("No duplicate clear frames when inactive", transport.writes.isEmpty());
    }

    @Test
    public void testOnDisconnectedResetsState() {
        dashboard.onAuthenticated(testKey, testIv, true);
        dashboard.onRssi(-60);
        dashboard.onCharacteristic(DashboardFeatures.CHAR_MANUFACTURER_NAME, "KTM".getBytes(StandardCharsets.UTF_8));

        dashboard.onDisconnected();

        Assert.assertNull(dashboard.getRssi());
        Assert.assertNull(dashboard.getLastButton());
        Assert.assertTrue(dashboard.getTelemetry().isEmpty());
        Assert.assertTrue(dashboard.getVehicleInfo().isEmpty());

        // Attempting to send notification after disconnect should fail
        transport.writes.clear();
        dashboard.sendNotification("After Disconnect", BccuProtocol.NotificationIcon.INFORMATION);
        Assert.assertTrue(transport.writes.isEmpty());
    }
}

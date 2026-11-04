package app.ktmnav.backend;

import org.junit.Assert;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

public class TelemetryProtocolTest {

    private final BccuProtocol proto = BccuProtocol.INSTANCE;

    @Test
    public void testBuildTelemetryConfigureRequest() {
        int sid = 42;
        int dpId = 202; // bCCU_Voltage
        int sampleRateMs = 1500;

        byte[] req = proto.buildTelemetryConfigureRequest(sid, dpId, sampleRateMs);
        Assert.assertNotNull(req);
        Assert.assertEquals(10, req.length);

        ByteBuffer buf = ByteBuffer.wrap(req).order(ByteOrder.LITTLE_ENDIAN);
        Assert.assertEquals(BccuProtocol.RPC_OPCODE_TELEMETRY_CONFIGURE, buf.getShort() & 0xFFFF);
        Assert.assertEquals((byte) sid, buf.get());
        Assert.assertEquals((byte) 6, buf.get());
        Assert.assertEquals(dpId, buf.getShort() & 0xFFFF);
        Assert.assertEquals(sampleRateMs, buf.getInt());
    }

    @Test
    public void testBuildTelemetryControlRequest() {
        int sid = 99;
        int command = BccuProtocol.TELEMETRY_CMD_START;

        byte[] req = proto.buildTelemetryControlRequest(sid, command);
        Assert.assertNotNull(req);
        Assert.assertEquals(6, req.length);

        ByteBuffer buf = ByteBuffer.wrap(req).order(ByteOrder.LITTLE_ENDIAN);
        Assert.assertEquals(BccuProtocol.RPC_OPCODE_TELEMETRY_CONTROL, buf.getShort() & 0xFFFF);
        Assert.assertEquals((byte) sid, buf.get());
        Assert.assertEquals((byte) 2, buf.get());
        Assert.assertEquals(command, buf.getShort() & 0xFFFF);
    }

    @Test
    public void testParseTelemetryNotificationMultiTriple() {
        // Build raw packet:
        // Header: opcode 7 (short LE), sid (byte) = 3 bytes total before triples
        // Triple 1: timestamp 1000L (8 bytes LE), id 10 (short LE), len 4 (float: 45.5f)
        // Triple 2: timestamp 1005L (8 bytes LE), id 20 (short LE), len 2 (short: 1234)
        ByteBuffer buf = ByteBuffer.allocate(3 + (10 + 4) + (10 + 2)).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) 7); // opcode
        buf.put((byte) 1);       // sid

        // Triple 1
        buf.putLong(1000L);
        buf.putShort((short) 10);
        buf.putFloat(45.5f);

        // Triple 2
        buf.putLong(1005L);
        buf.putShort((short) 20);
        buf.putShort((short) 1234);

        byte[] packet = buf.array();

        List<BccuProtocol.TelemetryTriple> triples = proto.parseTelemetryNotification(packet, id -> {
            if (id == 10) return 4;
            if (id == 20) return 2;
            return 0;
        });

        Assert.assertNotNull(triples);
        Assert.assertEquals(2, triples.size());

        BccuProtocol.TelemetryTriple t1 = triples.get(0);
        Assert.assertEquals(1000L, t1.getTimestampMs());
        Assert.assertEquals(10, t1.getDatapointId());
        ByteBuffer b1 = ByteBuffer.wrap(t1.getValue()).order(ByteOrder.LITTLE_ENDIAN);
        Assert.assertEquals(45.5f, b1.getFloat(), 0.001f);

        BccuProtocol.TelemetryTriple t2 = triples.get(1);
        Assert.assertEquals(1005L, t2.getTimestampMs());
        Assert.assertEquals(20, t2.getDatapointId());
        ByteBuffer b2 = ByteBuffer.wrap(t2.getValue()).order(ByteOrder.LITTLE_ENDIAN);
        Assert.assertEquals(1234, b2.getShort());
    }

    @Test
    public void testParseTelemetryNotificationTruncated() {
        // Triple 1 is complete, Triple 2 value is truncated
        ByteBuffer buf = ByteBuffer.allocate(3 + (10 + 4) + 10 + 1).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) 7);
        buf.put((byte) 1);

        // Triple 1 complete
        buf.putLong(2000L);
        buf.putShort((short) 100);
        buf.putInt(999);

        // Triple 2 truncated: length should be 4, but only 1 byte present
        buf.putLong(2001L);
        buf.putShort((short) 101);
        buf.put((byte) 0x55);

        List<BccuProtocol.TelemetryTriple> triples = proto.parseTelemetryNotification(buf.array(), id -> {
            if (id == 100) return 4;
            if (id == 101) return 4;
            return 0;
        });

        Assert.assertNotNull(triples);
        Assert.assertEquals(1, triples.size());
        Assert.assertEquals(100, triples.get(0).getDatapointId());
    }

    @Test
    public void testParseTelemetryNotificationWrongOpcodeOrTooShort() {
        Assert.assertTrue(proto.parseTelemetryNotification(new byte[0], id -> 4).isEmpty());
        Assert.assertTrue(proto.parseTelemetryNotification(new byte[]{0x07, 0x00}, id -> 4).isEmpty());

        byte[] wrongOpcode = new byte[]{0x06, 0x00, 0x01, 0x00};
        Assert.assertTrue(proto.parseTelemetryNotification(wrongOpcode, id -> 4).isEmpty());

        Assert.assertTrue(proto.parseTelemetryNotification(null, id -> 4).isEmpty());
    }

    @Test
    public void testParseRcmValue() {
        byte[] v = new byte[32];
        v[16] = (byte) 0xFF; // active
        // Bit 0: SET, Bit 1: BACK, Bit 2: DOWN, Bit 3: UP
        v[17] = (byte) ((1 << BccuProtocol.HandlebarButton.UP.getBitIndex()) |
                        (1 << BccuProtocol.HandlebarButton.SET.getBitIndex()));

        BccuProtocol.RcmState state = proto.parseRcmValue(v);
        Assert.assertNotNull(state);
        Assert.assertTrue(state.getActive());
        Assert.assertTrue(state.getPressed().get(BccuProtocol.HandlebarButton.UP));
        Assert.assertTrue(state.getPressed().get(BccuProtocol.HandlebarButton.SET));
        Assert.assertFalse(state.getPressed().get(BccuProtocol.HandlebarButton.BACK));
        Assert.assertFalse(state.getPressed().get(BccuProtocol.HandlebarButton.DOWN));
    }

    @Test
    public void testParseRcmValueInactiveOrShort() {
        byte[] shortVal = new byte[10];
        BccuProtocol.RcmState state1 = proto.parseRcmValue(shortVal);
        Assert.assertFalse(state1.getActive());
        for (Boolean pressed : state1.getPressed().values()) {
            Assert.assertFalse(pressed);
        }

        byte[] inactiveVal = new byte[32];
        inactiveVal[16] = 0x00;
        inactiveVal[17] = (byte) 0xFF;
        BccuProtocol.RcmState state2 = proto.parseRcmValue(inactiveVal);
        Assert.assertFalse(state2.getActive());
        Assert.assertTrue(state2.getPressed().get(BccuProtocol.HandlebarButton.SET));
    }

    @Test
    public void testTelemetryTripleImmutabilityAndEquals() {
        byte[] original = new byte[]{1, 2, 3};
        BccuProtocol.TelemetryTriple t1 = new BccuProtocol.TelemetryTriple(500L, 10, original);
        original[0] = 99; // modify original
        Assert.assertEquals(1, t1.getValue()[0]); // should remain 1

        t1.getValue()[0] = 88; // modify returned array
        Assert.assertEquals(1, t1.getValue()[0]); // should remain 1

        BccuProtocol.TelemetryTriple t2 = new BccuProtocol.TelemetryTriple(500L, 10, new byte[]{1, 2, 3});
        Assert.assertEquals(t1, t2);
        Assert.assertEquals(t1.hashCode(), t2.hashCode());
        Assert.assertTrue(t1.toString().contains("datapointId=10"));
    }
}

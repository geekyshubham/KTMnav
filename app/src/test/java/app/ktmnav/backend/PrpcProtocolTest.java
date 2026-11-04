package app.ktmnav.backend;

import org.junit.Assert;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class PrpcProtocolTest {

    private final BccuProtocol proto = BccuProtocol.INSTANCE;

    @Test
    public void configureU16AndU32Layouts() {
        Assert.assertArrayEquals(new byte[]{3, 0, 9, 4, (byte) 0xCA, 0, (byte) 0x88, 0x13},
                proto.buildTelemetryConfigureRequest(9, 202, 5000, true));
        Assert.assertArrayEquals(new byte[]{3, 0, 9, 6, (byte) 0xCA, 0, (byte) 0x88, 0x13, 0, 0},
                proto.buildTelemetryConfigureRequest(9, 202, 5000, false));
        Assert.assertArrayEquals(proto.buildTelemetryConfigureRequest(9, 202, 5000, false),
                proto.buildTelemetryConfigureRequest(9, 202, 5000));
        Assert.assertArrayEquals(new byte[]{3, 0, 1, 4, 1, 1, (byte) 0xFF, (byte) 0xFF},
                proto.buildTelemetryConfigureRequest(1, 257, 70000, true));
    }

    @Test
    public void getValueLayout() {
        Assert.assertArrayEquals(new byte[]{1, 0, 7, 2, 0x5D, 0x01}, proto.buildGetValueRequest(7, 349));
    }

    @Test
    public void timeSyncIsTheOnlyPermittedSetValue() {
        byte[] ts = proto.buildTimeSyncRequest(5, 0x0102030405060708L);
        Assert.assertArrayEquals(new byte[]{2, 0, 5, 10, (byte) 0xC8, 0, 8, 7, 6, 5, 4, 3, 2, 1}, ts);
        for (int dp = 0; dp <= 400; dp++) {
            if (dp == BccuProtocol.DP_TIME_SYNC) {
                continue;
            }
            try {
                proto.buildSetValueRequest(1, dp, new byte[8]);
                Assert.fail("setValue must be refused for dp " + dp);
            } catch (IllegalArgumentException expected) {
                Assert.assertTrue(expected.getMessage().contains("TimeSync"));
            }
        }
        for (int dp : new int[]{311, 312, 303, 314, 0xFFFF, -1}) {
            try {
                proto.buildSetValueRequest(1, dp, new byte[8]);
                Assert.fail("setValue must be refused for dp " + dp);
            } catch (IllegalArgumentException expected) {
                // refused
            }
        }
        try {
            proto.buildSetValueRequest(1, BccuProtocol.DP_TIME_SYNC, new byte[4]);
            Assert.fail("TimeSync requires a u64");
        } catch (IllegalArgumentException expected) {
            // refused
        }
    }

    @Test
    public void responseParsingAndErrorNames() {
        BccuProtocol.PrpcResponse ok = proto.parsePrpcResponse(new byte[]{3, 0, 4, 2, 0, 0});
        Assert.assertNotNull(ok);
        Assert.assertEquals(3, ok.opcode);
        Assert.assertEquals(4, ok.sid);
        Assert.assertTrue(ok.isSuccess());

        BccuProtocol.PrpcResponse rate = proto.parsePrpcResponse(new byte[]{3, 0, 4, 2, 0, 3});
        Assert.assertFalse(rate.isSuccess());
        Assert.assertEquals("telemetryConfigure sid=4: RPC_SUCCESS, TELEMETRY_CONFIGURE_INVALID_SAMPLERATE", rate.describe());

        BccuProtocol.PrpcResponse busy = proto.parsePrpcResponse(new byte[]{1, 0, 9, 1, 1});
        Assert.assertEquals("getValue sid=9: RPC_BUSYWORKER", busy.describe());

        ByteBuffer get = ByteBuffer.allocate(4 + 2 + 2 + 4).order(ByteOrder.LITTLE_ENDIAN);
        get.putShort((short) 1).put((byte) 2).put((byte) 8).put((byte) 0).put((byte) 0).putShort((short) 349).putFloat(1234.5f);
        BccuProtocol.PrpcResponse value = proto.parsePrpcResponse(get.array());
        Assert.assertEquals(6, value.returns.length);

        Assert.assertNull("length field must match", proto.parsePrpcResponse(new byte[]{3, 0, 4, 5, 0, 0}));
        Assert.assertNull("unknown opcode", proto.parsePrpcResponse(new byte[]{9, 0, 4, 2, 0, 0}));
        Assert.assertNull(proto.parsePrpcResponse(new byte[]{3, 0}));
        Assert.assertNull(proto.parsePrpcResponse(null));

        for (int code = 0; code <= 7; code++) {
            Assert.assertTrue(BccuProtocol.operationErrorName(BccuProtocol.RPC_OPCODE_TELEMETRY_CONFIGURE, code).startsWith("TELEMETRY_CONFIGURE_"));
        }
        Assert.assertEquals("TELEMETRY_CONFIGURE_TIME_SYNC_FAILURE", BccuProtocol.operationErrorName(3, 7));
        Assert.assertEquals("RPC_SUCCESS", BccuProtocol.rpcErrorName(0));
        Assert.assertEquals("RPC_BUSYWORKER", BccuProtocol.rpcErrorName(1));
        Assert.assertEquals("RPC_ENCRYPTION_FAIL", BccuProtocol.rpcErrorName(2));
        Assert.assertEquals("ERROR_8", BccuProtocol.operationErrorName(3, 8));
    }

    @Test
    public void rcmMaskOnlyCountsWhenActive() {
        byte[] active = new byte[32];
        active[16] = (byte) 0xFF;
        active[17] = (byte) 0xF9;
        Assert.assertEquals(0x09, BccuProtocol.rcmMask(active));
        byte[] inactive = active.clone();
        inactive[16] = 0x00;
        Assert.assertEquals(0, BccuProtocol.rcmMask(inactive));
        Assert.assertEquals(0, BccuProtocol.rcmMask(new byte[17]));
        Assert.assertEquals(0, BccuProtocol.rcmMask(null));
    }
}

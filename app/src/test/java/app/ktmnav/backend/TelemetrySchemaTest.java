package app.ktmnav.backend;

import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

public class TelemetrySchemaTest {

    private final TelemetrySchema schema = TelemetrySchema.INSTANCE;

    @Test
    public void testTypeLengths() {
        Assert.assertEquals(1, schema.typeLength("T_UINT8"));
        Assert.assertEquals(1, schema.typeLength("T_INT8"));
        Assert.assertEquals(1, schema.typeLength("T_CHAR"));
        Assert.assertEquals(2, schema.typeLength("T_UINT16"));
        Assert.assertEquals(2, schema.typeLength("T_INT16"));
        Assert.assertEquals(4, schema.typeLength("T_UINT32"));
        Assert.assertEquals(4, schema.typeLength("T_INT32"));
        Assert.assertEquals(8, schema.typeLength("T_UINT64"));
        Assert.assertEquals(8, schema.typeLength("T_INT64"));
        Assert.assertEquals(4, schema.typeLength("T_FLOAT"));
        Assert.assertEquals(0, schema.typeLength("T_UNKNOWN"));
        Assert.assertEquals(0, schema.typeLength(null));
    }

    @Test
    public void testDecodeScalars() {
        // T_UINT8 (unsigned: 250)
        Assert.assertEquals("250", schema.decodeValue("T_UINT8", new byte[]{(byte) 250}));

        // T_INT8 (signed: -5)
        Assert.assertEquals("-5", schema.decodeValue("T_INT8", new byte[]{(byte) -5}));

        // T_CHAR ('K')
        Assert.assertEquals("K", schema.decodeValue("T_CHAR", new byte[]{(byte) 'K'}));

        // T_UINT16 (unsigned 0x8001 -> 32769, Little Endian)
        byte[] u16 = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) 0x8001).array();
        Assert.assertEquals("32769", schema.decodeValue("T_UINT16", u16));

        // T_INT16 (signed -500, Little Endian)
        byte[] s16 = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) -500).array();
        Assert.assertEquals("-500", schema.decodeValue("T_INT16", s16));

        // T_UINT32 (unsigned 0xFFFFFFFFL -> 4294967295)
        byte[] u32 = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((int) 0xFFFFFFFFL).array();
        Assert.assertEquals("4294967295", schema.decodeValue("T_UINT32", u32));

        // T_INT32 (signed -123456)
        byte[] s32 = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(-123456).array();
        Assert.assertEquals("-123456", schema.decodeValue("T_INT32", s32));

        // T_UINT64 (unsigned 0xFFFFFFFFFFFFFFFF -> 18446744073709551615)
        byte[] u64 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(-1L).array();
        Assert.assertEquals("18446744073709551615", schema.decodeValue("T_UINT64", u64));

        // T_INT64 (signed -9876543210L)
        byte[] s64 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(-9876543210L).array();
        Assert.assertEquals("-9876543210", schema.decodeValue("T_INT64", s64));

        // T_FLOAT (14.25f)
        byte[] flt = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(14.25f).array();
        Assert.assertEquals("14.25", schema.decodeValue("T_FLOAT", flt));
    }

    @Test
    public void testDecodeEmptyAndMalformedBounds() {
        // Empty bytes
        Assert.assertEquals("(empty)", schema.decodeValue("T_FLOAT", new byte[0]));
        Assert.assertEquals("(empty)", schema.decodeValue("T_FLOAT", null));
        Assert.assertEquals("(empty)", schema.decodeValue(null, new byte[]{1, 2, 3}));

        // Malformed / truncated bounds: T_UINT16 expects 2 bytes, provide 1 byte
        String err1 = schema.decodeValue("T_UINT16", new byte[]{0x01});
        Assert.assertTrue("Should reject truncated bytes: " + err1, err1.startsWith("decode error"));

        // T_FLOAT expects 4 bytes, provide 2 bytes
        String err2 = schema.decodeValue("T_FLOAT", new byte[]{0x01, 0x02});
        Assert.assertTrue("Should reject truncated float: " + err2, err2.startsWith("decode error"));

        // T_UINT64 expects 8 bytes, provide 4 bytes
        String err3 = schema.decodeValue("T_UINT64", new byte[]{1, 2, 3, 4});
        Assert.assertTrue("Should reject truncated uint64: " + err3, err3.startsWith("decode error"));
    }

    @Test
    public void testDecodeUnknownTypeFallsBackToHex() {
        byte[] bytes = new byte[]{0x12, (byte) 0xAB, 0x34};
        String res = schema.decodeValue("T_BLOB", bytes);
        Assert.assertTrue(res.equalsIgnoreCase("12ab34") || res.equals("12 ab 34") || res.contains("12"));
    }

    @Test
    public void testParseJsonContract() throws Exception {
        String json = "{\n" +
                "  \"subjects\": [\n" +
                "    {\n" +
                "      \"name\": \"Engine\",\n" +
                "      \"datapoints\": [\n" +
                "        {\n" +
                "          \"id\": 200,\n" +
                "          \"name\": \"ECU_Engine_Rpm\",\n" +
                "          \"type\": \"T_UINT16\",\n" +
                "          \"unit\": \"rpm\",\n" +
                "          \"attributes\": [\"READABLE\", \"TELEMETRYABLE\"],\n" +
                "          \"sample_rates\": {\n" +
                "            \"sample_rate_default\": 1000,\n" +
                "            \"sample_rate_min\": 500,\n" +
                "            \"sample_rate_max\": 5000\n" +
                "          }\n" +
                "        },\n" +
                "        {\n" +
                "          \"id\": 201,\n" +
                "          \"name\": \"ECU_Status\",\n" +
                "          \"type\": \"T_UINT8\",\n" +
                "          \"attributes\": [\"READABLE\"]\n" +
                "        }\n" +
                "      ]\n" +
                "    }\n" +
                "  ]\n" +
                "}";

        List<Datapoint> datapoints = schema.parse(new JSONObject(json));
        Assert.assertNotNull(datapoints);
        Assert.assertEquals(2, datapoints.size());

        Datapoint dp1 = datapoints.get(0);
        Assert.assertEquals(200, dp1.getId());
        Assert.assertEquals("ECU_Engine_Rpm", dp1.getName());
        Assert.assertEquals("T_UINT16", dp1.getType());
        Assert.assertEquals("rpm", dp1.getUnit());
        Assert.assertTrue(dp1.getTelemetryable());
        Assert.assertEquals(1000, dp1.getSampleRateDefaultMs());
        Assert.assertEquals(500, dp1.getSampleRateMinMs());
        Assert.assertEquals(5000, dp1.getSampleRateMaxMs());

        Datapoint dp2 = datapoints.get(1);
        Assert.assertEquals(201, dp2.getId());
        Assert.assertEquals("ECU_Status", dp2.getName());
        Assert.assertEquals("T_UINT8", dp2.getType());
        Assert.assertNull(dp2.getUnit());
        Assert.assertFalse(dp2.getTelemetryable());
        // defaults
        Assert.assertEquals(5000, dp2.getSampleRateDefaultMs());
        Assert.assertEquals(200, dp2.getSampleRateMinMs());
        Assert.assertEquals(60000, dp2.getSampleRateMaxMs());
    }

    @Test
    public void testDatapointEqualsAndHashCode() {
        Datapoint d1 = new Datapoint(1, "RPM", "T_UINT16", "rpm", 1000, 500, 5000, true);
        Datapoint d2 = new Datapoint(1, "RPM", "T_UINT16", "rpm", 1000, 500, 5000, true);
        Datapoint d3 = new Datapoint(2, "RPM", "T_UINT16", "rpm", 1000, 500, 5000, true);

        Assert.assertEquals(d1, d2);
        Assert.assertEquals(d1.hashCode(), d2.hashCode());
        Assert.assertNotEquals(d1, d3);
        Assert.assertTrue(d1.toString().contains("RPM"));
    }
}

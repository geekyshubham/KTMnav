package app.ktmnav.backend;

import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public class Gen2FramesTest {

    @Test
    public void testFrameHeaderAndLength() throws Exception {
        JSONObject obj = new JSONObject();
        obj.put("test", true);
        obj.put("path", "a/b");

        byte[] frame = Gen2Frames.frame(obj);
        Assert.assertNotNull(frame);

        // Frame structure: 4 bytes BE length, 1 byte (0x01), UTF-8 JSON bytes
        byte[] jsonBytes = obj.toString().replace("\\/", "/").getBytes(StandardCharsets.UTF_8);
        int expectedTotalLength = jsonBytes.length + 5;
        Assert.assertEquals(expectedTotalLength, frame.length);

        ByteBuffer bb = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        int lengthField = bb.getInt();
        byte typeByte = bb.get();

        // 4-byte BE length equals payload + 1 (jsonBytes.length + 1)
        Assert.assertEquals(jsonBytes.length + 1, lengthField);
        Assert.assertEquals(0x01, typeByte);

        byte[] payloadBytes = new byte[frame.length - 5];
        bb.get(payloadBytes);
        JSONObject restored = new JSONObject(new String(payloadBytes, StandardCharsets.UTF_8));
        Assert.assertTrue(restored.getBoolean("test"));
        Assert.assertEquals("a/b", restored.getString("path"));
    }

    @Test
    public void testGuidanceJson() throws Exception {
        JSONObject guidance = Gen2Frames.guidance(
                42,
                "Main Street",
                "300",
                "m",
                "QUITE_RIGHT",
                "14:30",
                "12 km",
                "Exit 2",
                "Traffic slowdown"
        );

        Assert.assertNotNull(guidance);
        Assert.assertEquals("gon#42", guidance.getString("MsgId"));
        Assert.assertEquals("guidance", guidance.getString("UiContext"));

        JSONObject updateUi = guidance.getJSONObject("UpdateUI");
        Assert.assertNotNull(updateUi);

        JSONObject turnRoad = updateUi.getJSONObject("TurnRoad");
        Assert.assertEquals("Main Street", turnRoad.getString("Text"));
        Assert.assertEquals("full", turnRoad.getString("Visibility"));

        JSONObject turnDist = updateUi.getJSONObject("TurnDist");
        Assert.assertEquals("300", turnDist.getString("Text"));
        Assert.assertEquals("full", turnDist.getString("Visibility"));

        JSONObject turnDistUnit = updateUi.getJSONObject("TurnDistUnit");
        Assert.assertEquals("m", turnDistUnit.getString("Text"));
        Assert.assertEquals("full", turnDistUnit.getString("Visibility"));

        JSONObject turnIcon = updateUi.getJSONObject("TurnIcon");
        Assert.assertEquals("QUITE_RIGHT", turnIcon.getString("Image"));
        Assert.assertEquals("full", turnIcon.getString("Visibility"));

        JSONObject turnInfo = updateUi.getJSONObject("TurnInfo");
        Assert.assertEquals("Exit 2", turnInfo.getString("Text"));
        Assert.assertEquals("full", turnInfo.getString("Visibility"));

        JSONObject eta = updateUi.getJSONObject("ETA");
        Assert.assertEquals("14:30", eta.getString("Text"));
        Assert.assertEquals("full", eta.getString("Visibility"));

        JSONObject dist2Target = updateUi.getJSONObject("Dist2Target");
        Assert.assertEquals("12 km", dist2Target.getString("Text"));
        Assert.assertEquals("full", dist2Target.getString("Visibility"));

        JSONObject notifText = updateUi.getJSONObject("NotificationText");
        Assert.assertEquals("Traffic slowdown", notifText.getString("Text"));
        Assert.assertEquals("full", notifText.getString("Visibility"));

        JSONObject notifIcon = updateUi.getJSONObject("NotificationIcon");
        Assert.assertEquals("off", notifIcon.getString("Visibility"));

        JSONObject gpsIcon = updateUi.getJSONObject("GpsIcon");
        Assert.assertEquals("UNDEFINED", gpsIcon.getString("Image"));
        Assert.assertEquals("off", gpsIcon.getString("Visibility"));
    }

    @Test
    public void testGuidanceWithNullFields() throws Exception {
        JSONObject guidance = Gen2Frames.guidance(1, null, null, null, null, null, null, null, null);
        Assert.assertEquals("gon#1", guidance.getString("MsgId"));
        Assert.assertEquals("guidance", guidance.getString("UiContext"));

        JSONObject updateUi = guidance.getJSONObject("UpdateUI");
        Assert.assertEquals("UNDEFINED", updateUi.getJSONObject("TurnIcon").getString("Image"));
        Assert.assertEquals("off", updateUi.getJSONObject("TurnIcon").getString("Visibility"));
        Assert.assertEquals("", updateUi.getJSONObject("TurnDist").getString("Text"));
        Assert.assertEquals("off", updateUi.getJSONObject("TurnDist").getString("Visibility"));

        // In guidance mode, null ETA and Dist2Target keys are omitted
        Assert.assertFalse(updateUi.has("ETA"));
        Assert.assertFalse(updateUi.has("Dist2Target"));
    }

    @Test
    public void testRestoreJson() throws Exception {
        JSONObject restore = Gen2Frames.restore(3);
        Assert.assertNotNull(restore);
        Assert.assertEquals("Restore#3", restore.getString("MsgId"));
        Assert.assertEquals("default", restore.getString("UiContext"));

        JSONObject updateUi = restore.getJSONObject("UpdateUI");
        Assert.assertEquals("UNDEFINED", updateUi.getJSONObject("TurnIcon").getString("Image"));
        Assert.assertEquals("off", updateUi.getJSONObject("TurnIcon").getString("Visibility"));

        // In default mode, ETA and Dist2Target are present with empty string and off visibility
        Assert.assertTrue(updateUi.has("ETA"));
        Assert.assertEquals("", updateUi.getJSONObject("ETA").getString("Text"));
        Assert.assertEquals("off", updateUi.getJSONObject("ETA").getString("Visibility"));
    }

    @Test
    public void testSplitDistance() throws Exception {
        // Null distance returns null
        Assert.assertNull(Gen2Frames.splitDistance(null));

        // Metric
        String[] m = Gen2Frames.splitDistance("250 m");
        Assert.assertEquals("250", m[0]);
        Assert.assertEquals("m", m[1]);

        String[] km = Gen2Frames.splitDistance("12.4 km");
        Assert.assertEquals("12.4", km[0]);
        Assert.assertEquals("km", km[1]);

        // Imperial
        String[] mi = Gen2Frames.splitDistance("1.5 mi");
        Assert.assertEquals("1.5", mi[0]);
        Assert.assertEquals("mi", mi[1]);

        String[] ft = Gen2Frames.splitDistance("500 ft");
        Assert.assertEquals("500", ft[0]);
        Assert.assertEquals("ft", ft[1]);

        // Number only, no unit
        String[] noUnit = Gen2Frames.splitDistance("500");
        Assert.assertEquals("500", noUnit[0]);
        Assert.assertNull(noUnit[1]);

        // Non-letter unit suffix does not split
        String[] nonLetter = Gen2Frames.splitDistance("500 123");
        Assert.assertEquals("500 123", nonLetter[0]);
        Assert.assertNull(nonLetter[1]);

        // Extra whitespace
        String[] trimmed = Gen2Frames.splitDistance("   300   m   ");
        Assert.assertEquals("300", trimmed[0]);
        Assert.assertEquals("m", trimmed[1]);
    }
}

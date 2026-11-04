package app.ktmnav.backend;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Protocol frame builder for KTM Gen2 instrument clusters over RFCOMM socket.
 * Based on TurnIt Gen2Frames evidence.
 */
public final class Gen2Frames {

    public static final UUID NAV_UUID = UUID.fromString("cc4c1fb3-482e-4389-bdeb-57b7aac889ae");

    private Gen2Frames() {
    }

    public static byte[] frame(JSONObject jsonObject) {
        byte[] bytes = jsonObject.toString().replace("\\/", "/").getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(bytes.length + 5)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(bytes.length + 1)
                .put((byte) 1)
                .put(bytes)
                .array();
    }

    public static JSONObject restore(int msgId) throws JSONException {
        return updateUi("default", "Restore#" + msgId, null, null, null, null, null, null, null, null);
    }

    public static JSONObject guidance(int msgId, String road, String dist, String distUnit,
                                     String icon, String eta, String dist2Target,
                                     String turnInfo, String notifText) throws JSONException {
        return updateUi("guidance", "gon#" + msgId, road, dist, distUnit, icon, eta, dist2Target, turnInfo, notifText);
    }

    private static JSONObject updateUi(String uiContext, String msgId, String road, String dist,
                                       String distUnit, String icon, String eta, String dist2Target,
                                       String turnInfo, String notifText) throws JSONException {
        JSONObject updateUi = new JSONObject();
        updateUi.put("TurnIcon", field("Image", icon, "UNDEFINED"));
        updateUi.put("TurnDist", field("Text", clean(dist), ""));
        updateUi.put("TurnDistUnit", field("Text", clean(distUnit), ""));
        updateUi.put("TurnRoad", field("Text", clean(road), ""));
        updateUi.put("TurnInfo", field("Text", clean(turnInfo), ""));

        boolean isGuidance = "guidance".equals(uiContext);
        if (eta != null || !isGuidance) {
            updateUi.put("ETA", field("Text", clean(eta), ""));
        }
        if (dist2Target != null || !isGuidance) {
            updateUi.put("Dist2Target", field("Text", clean(dist2Target), ""));
        }
        String banner = notifText != null ? DashText.asciiOnly(notifText) : null;
        updateUi.put("NotificationText", field("Text", banner != null && !banner.trim().isEmpty() ? banner : null, ""));
        updateUi.put("NotificationIcon", new JSONObject().put("Visibility", "off"));
        updateUi.put("GpsIcon", field("Image", null, "UNDEFINED"));

        JSONObject root = new JSONObject();
        root.put("MsgId", msgId);
        root.put("UiContext", uiContext);
        root.put("UpdateUI", updateUi);
        return root;
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = DashText.asciiClean(value);
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static JSONObject field(String key, String value, String defaultValue) throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put(key, value != null ? value : defaultValue);
        obj.put("Visibility", value != null ? "full" : "off");
        return obj;
    }

    public static String[] splitDistance(String distance) {
        if (distance == null) {
            return null;
        }
        String trimmed = distance.trim();
        int spaceIndex = -1;
        for (int i = trimmed.length() - 1; i >= 0; i--) {
            char c = trimmed.charAt(i);
            if (c == ' ' || Character.isSpaceChar(c) || Character.isWhitespace(c)) {
                spaceIndex = i;
                break;
            }
        }
        if (spaceIndex > 0) {
            String unit = trimmed.substring(spaceIndex + 1);
            if (!unit.isEmpty() && isAllLetters(unit)) {
                return new String[]{trimmed.substring(0, spaceIndex).trim(), unit};
            }
        }
        return new String[]{trimmed, null};
    }

    private static boolean isAllLetters(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isLetter(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}

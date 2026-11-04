package app.ktmnav.backend;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Telemetry datapoint contract parser and binary value decoder.
 * Based on OpenDash TelemetrySchema (MIT License, Copyright (c) 2026 OpenDash contributors).
 */
public final class TelemetrySchema {

    public static final TelemetrySchema INSTANCE = new TelemetrySchema();

    private static final Map<String, Integer> TYPE_LENGTHS;

    static {
        Map<String, Integer> map = new HashMap<>();
        map.put("T_UINT8", 1);
        map.put("T_INT8", 1);
        map.put("T_CHAR", 1);
        map.put("T_UINT16", 2);
        map.put("T_INT16", 2);
        map.put("T_UINT32", 4);
        map.put("T_INT32", 4);
        map.put("T_UINT64", 8);
        map.put("T_INT64", 8);
        map.put("T_FLOAT", 4);
        TYPE_LENGTHS = Collections.unmodifiableMap(map);
    }

    private TelemetrySchema() {
    }

    public int typeLength(String type) {
        if (type == null) {
            return 0;
        }
        Integer len = TYPE_LENGTHS.get(type);
        return len != null ? len : 0;
    }

    public List<Datapoint> parse(String jsonString) throws JSONException {
        if (jsonString == null || jsonString.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return parse(new JSONObject(jsonString));
    }

    public List<Datapoint> parse(JSONObject json) throws JSONException {
        if (json == null) {
            return Collections.emptyList();
        }
        JSONArray subjects = json.optJSONArray("subjects");
        if (subjects == null) {
            return Collections.emptyList();
        }
        List<Datapoint> result = new ArrayList<>();
        int subjectsLen = subjects.length();
        for (int i = 0; i < subjectsLen; i++) {
            JSONObject subject = subjects.optJSONObject(i);
            if (subject == null) continue;
            JSONArray datapoints = subject.optJSONArray("datapoints");
            if (datapoints == null) continue;
            int datapointsLen = datapoints.length();
            for (int j = 0; j < datapointsLen; j++) {
                JSONObject dp = datapoints.optJSONObject(j);
                if (dp == null) continue;

                int id = dp.getInt("id");
                String name = dp.getString("name");
                String type = dp.getString("type");
                String unit = dp.has("unit") && !dp.isNull("unit") ? dp.getString("unit") : null;

                boolean telemetryable = false;
                JSONArray attributes = dp.optJSONArray("attributes");
                if (attributes != null) {
                    int attrLen = attributes.length();
                    for (int k = 0; k < attrLen; k++) {
                        if ("TELEMETRYABLE".equals(attributes.optString(k))) {
                            telemetryable = true;
                            break;
                        }
                    }
                }

                JSONObject sampleRates = dp.optJSONObject("sample_rates");
                int defaultRate = sampleRates != null ? sampleRates.optInt("sample_rate_default", 5000) : 5000;
                int minRate = sampleRates != null ? sampleRates.optInt("sample_rate_min", 200) : 200;
                int maxRate = sampleRates != null ? sampleRates.optInt("sample_rate_max", 60000) : 60000;

                result.add(new Datapoint(id, name, type, unit, defaultRate, minRate, maxRate, telemetryable));
            }
        }
        return Collections.unmodifiableList(result);
    }

    public List<Datapoint> load(Context context) throws IOException, JSONException {
        if (context == null) {
            return Collections.emptyList();
        }
        try (InputStream is = context.getAssets().open("bike_signals_contract.json");
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return parse(sb.toString());
        }
    }

    public String decodeValue(String type, byte[] bytes) {
        if (type == null || bytes == null || bytes.length == 0) {
            return "(empty)";
        }
        int expectedLen = typeLength(type);
        if (expectedLen > 0 && bytes.length < expectedLen) {
            return "decode error (BufferUnderflowException)";
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        try {
            switch (type) {
                case "T_UINT8":
                    return String.valueOf(bytes[0] & 0xFF);
                case "T_INT8":
                    return String.valueOf((int) bytes[0]);
                case "T_CHAR":
                    return String.valueOf((char) bytes[0]);
                case "T_UINT16":
                    return String.valueOf(buf.getShort() & 0xFFFF);
                case "T_INT16":
                    return String.valueOf((int) buf.getShort());
                case "T_UINT32":
                    return String.valueOf(((long) buf.getInt()) & 0xFFFFFFFFL);
                case "T_INT32":
                    return String.valueOf(buf.getInt());
                case "T_UINT64":
                    return Long.toUnsignedString(buf.getLong());
                case "T_INT64":
                    return String.valueOf(buf.getLong());
                case "T_FLOAT":
                    return String.valueOf(buf.getFloat());
                default:
                    return BccuCrypto.INSTANCE.hex(bytes);
            }
        } catch (Exception e) {
            return "decode error (" + e.getMessage() + ")";
        }
    }
}

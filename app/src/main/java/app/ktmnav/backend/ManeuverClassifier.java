package app.ktmnav.backend;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * On-device neural classifier for Google Maps notification arrow glyphs.
 * Uses local LiteRT / TensorFlow Lite model without Play Services dependencies.
 */
final class ManeuverClassifier {
    static final int INPUT_SIZE = 96;
    static final float MIN_CONFIDENCE = 0.4f;
    private static final String TAG = "KTMnav.Maneuver";

    private static volatile Object interpreter;
    private static volatile Method runMethod;
    private static volatile String[] labels;
    private static volatile boolean unavailable;
    private static final Object LOCK = new Object();
    private static final Map<Integer, TurnIcon> RESULT_CACHE = new ConcurrentHashMap<>();

    private ManeuverClassifier() {}

    static TurnIcon classify(Context context, int[] pixels) {
        if (pixels == null || pixels.length == 0) {
            return null;
        }
        int hash = ArrowHash.of(pixels);
        TurnIcon cached = RESULT_CACHE.get(hash);
        if (cached != null) {
            return cached;
        }

        Object model = ensureModel(context);
        String[] currentLabels = labels;
        Method method = runMethod;

        if (model != null && currentLabels != null && method != null) {
            try {
                ByteBuffer inputBuffer = preprocess(pixels, 48);
                float[][] output = (float[][]) Array.newInstance(float.class, 1, currentLabels.length);

                synchronized (LOCK) {
                    method.invoke(model, inputBuffer, output);
                }

                float[] scores = output[0];
                int bestIndex = 0;
                for (int i = 1; i < scores.length; i++) {
                    if (scores[i] > scores[bestIndex]) {
                        bestIndex = i;
                    }
                }

                float confidence = scores[bestIndex];
                if (confidence < MIN_CONFIDENCE) {
                    Log.d(TAG, String.format("No confident match (best=%s @ %.2f)", currentLabels[bestIndex], confidence));
                    return null;
                }

                TurnIcon icon = labelToIcon(currentLabels[bestIndex]);
                if (icon != null) {
                    Log.i(TAG, String.format("Classified %s = %s (%.2f)", currentLabels[bestIndex], icon, confidence));
                    RESULT_CACHE.put(hash, icon);
                }
                return icon;
            } catch (Throwable t) {
                Log.w(TAG, "Maneuver inference failed", t);
            }
        }
        return null;
    }

    static TurnIcon labelToIcon(String label) {
        if (label == null) {
            return null;
        }
        try {
            int binary = Integer.parseInt(label.trim());
            TurnIcon icon = TurnIcon.fromBinary(binary);
            return (icon == TurnIcon.UNKNOWN) ? null : icon;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static String[] parseLabels(InputStream inputStream) throws IOException {
        List<String> list = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    list.add(trimmed);
                }
            }
        }
        return list.toArray(new String[0]);
    }

    private static Object ensureModel(Context context) {
        Object current = interpreter;
        if (current != null) {
            return current;
        }
        if (unavailable) {
            return null;
        }

        synchronized (LOCK) {
            if (interpreter != null) {
                return interpreter;
            }
            try {
                int labelsResId = context.getResources().getIdentifier("maneuver_labels", "raw", context.getPackageName());
                int modelResId = context.getResources().getIdentifier("maneuver_model", "raw", context.getPackageName());

                if (labelsResId == 0 || modelResId == 0) {
                    Log.w(TAG, "Maneuver resources not found in raw");
                    unavailable = true;
                    return null;
                }

                try (InputStream labelsIn = context.getResources().openRawResource(labelsResId)) {
                    labels = parseLabels(labelsIn);
                }

                byte[] modelBytes;
                try (InputStream modelIn = context.getResources().openRawResource(modelResId)) {
                    modelBytes = readAll(modelIn);
                }

                ByteBuffer modelBuffer = ByteBuffer.allocateDirect(modelBytes.length).order(ByteOrder.nativeOrder());
                modelBuffer.put(modelBytes);
                modelBuffer.rewind();

                Class<?> interpreterClass = null;
                try {
                    interpreterClass = Class.forName("com.google.ai.edge.litert.Interpreter");
                } catch (ClassNotFoundException e) {
                    try {
                        interpreterClass = Class.forName("org.tensorflow.lite.Interpreter");
                    } catch (ClassNotFoundException ignored) {}
                }

                if (interpreterClass == null) {
                    Log.w(TAG, "Neither LiteRT nor TensorFlow Lite Interpreter found on classpath");
                    unavailable = true;
                    return null;
                }

                Constructor<?> constructor = interpreterClass.getConstructor(ByteBuffer.class);
                Object inst = constructor.newInstance(modelBuffer);
                runMethod = interpreterClass.getMethod("run", Object.class, Object.class);
                interpreter = inst;

                Log.i(TAG, "Model loaded: " + labels.length + " classes, " + modelBytes.length + " bytes via " + interpreterClass.getName());
                return interpreter;
            } catch (Throwable t) {
                Log.w(TAG, "Failed to load maneuver classifier", t);
                unavailable = true;
                return null;
            }
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = in.read(buffer)) != -1) {
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }

    private static ByteBuffer preprocess(int[] pixels, int size) {
        Bitmap src = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888);
        Bitmap blackBg = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(blackBg);
        canvas.drawColor(0xFF000000);
        canvas.drawBitmap(src, 0.0f, 0.0f, (Paint) null);

        Bitmap scaled = Bitmap.createScaledBitmap(blackBg, INPUT_SIZE, INPUT_SIZE, true);
        ByteBuffer buffer = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4).order(ByteOrder.nativeOrder());

        int[] scaledPixels = new int[INPUT_SIZE * INPUT_SIZE];
        scaled.getPixels(scaledPixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE);

        for (int p : scaledPixels) {
            buffer.putFloat(((p >> 16) & 0xFF) / 255.0f);
            buffer.putFloat(((p >> 8) & 0xFF) / 255.0f);
            buffer.putFloat((p & 0xFF) / 255.0f);
        }
        buffer.rewind();

        if (scaled != blackBg) {
            scaled.recycle();
        }
        blackBg.recycle();
        src.recycle();
        return buffer;
    }
}

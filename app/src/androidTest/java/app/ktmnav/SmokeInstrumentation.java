package app.ktmnav;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import app.ktmnav.backend.Bridge;
import app.ktmnav.backend.BccuCrypto;
import app.ktmnav.backend.BccuProtocol;
import app.ktmnav.backend.Gen2Frames;
import app.ktmnav.backend.Gen2Icons;
import app.ktmnav.backend.TurnIcon;
import app.ktmnav.backend.BikeManager;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.json.JSONObject;
import org.tensorflow.lite.Interpreter;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Exercises persisted bike management and a real Activity launch on Android. */
public final class SmokeInstrumentation extends Instrumentation {
    private boolean remappingOnly;
    private boolean remappingDenied;
    private boolean featuresOnly;
    private boolean screensOnly;
    @Override public void onCreate(Bundle args) { super.onCreate(args); remappingOnly=args!=null && "true".equals(args.getString("remapping_only")); remappingDenied=args!=null&&"true".equals(args.getString("remapping_denied")); featuresOnly=args!=null&&"true".equals(args.getString("features_only")); screensOnly=args!=null&&"true".equals(args.getString("screens_only")); start(); }
    private void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private long idleTimeout(Bridge bridge) {
        try {
            java.lang.reflect.Method method=Bridge.class.getDeclaredMethod("getIdleTimeoutMs");
            method.setAccessible(true); return ((Number) method.invoke(bridge)).longValue();
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        Bundle status = new Bundle();
        status.putString("class", SmokeInstrumentation.class.getName());
        status.putString("test", "testProtocolAndBikeLifecycle");
        status.putInt("numtests", 1);
        status.putInt("current", 1);
        sendStatus(1, status);
        if(screensOnly) {
            try {
                String report=new FeatureScreenChecks(this).run();
                result.putString("stream",report);sendStatus(0,status);finish(Activity.RESULT_OK,result);
            } catch(Throwable failure) {
                String report=android.util.Log.getStackTraceString(failure);
                result.putString("stream","FAIL: "+report);status.putString("stack",report);sendStatus(-2,status);finish(Activity.RESULT_CANCELED,result);
            }
            return;
        }
        if(featuresOnly) {
            try {
                String report=new FeatureRuntimeChecks(this,stage->{Bundle b=new Bundle();b.putString("stage",stage);sendStatus(2,b);}).run();
                result.putString("stream",report);sendStatus(0,status);finish(Activity.RESULT_OK,result);
            } catch(Throwable failure) {
                String report=android.util.Log.getStackTraceString(failure);
                result.putString("stream","FAIL: "+report);status.putString("stack",report);sendStatus(-2,status);finish(Activity.RESULT_CANCELED,result);
            }
            return;
        }
        if(remappingOnly) {
            try {
                HandlebarRuntimeChecks checks=new HandlebarRuntimeChecks(this);
                String report=remappingDenied?checks.denied():checks.run();
                result.putString("stream",report);sendStatus(0,status);finish(Activity.RESULT_OK,result);
            } catch(Throwable failure) {
                String report=android.util.Log.getStackTraceString(failure);
                result.putString("stream","FAIL: "+report);status.putString("stack",report);sendStatus(-2,status);finish(Activity.RESULT_CANCELED,result);
            }
            return;
        }
        String address = "02:00:00:4B:54:4D";
        String secondAddress = "02:00:00:4B:54:4E";
        try {
            byte[] modelBytes;
            try (InputStream in = getTargetContext().getResources().openRawResource(R.raw.maneuver_model);
                 ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                byte[] block = new byte[8192]; int count;
                while ((count = in.read(block)) != -1) buffer.write(block, 0, count);
                modelBytes = buffer.toByteArray();
            }
            ByteBuffer model = ByteBuffer.allocateDirect(modelBytes.length).order(ByteOrder.nativeOrder());
            model.put(modelBytes).rewind();
            try (Interpreter interpreter = new Interpreter(model)) {
                check(Arrays.equals(new int[]{1,96,96,3}, interpreter.getInputTensor(0).shape()), "Maneuver model input shape differs from recovered classifier");
                int classes = interpreter.getOutputTensor(0).shape()[1];
                float[][] output = new float[1][classes];
                interpreter.run(ByteBuffer.allocateDirect(96*96*3*4).order(ByteOrder.nativeOrder()), output);
                for (float confidence : output[0]) check(Float.isFinite(confidence), "Maneuver inference returned non-finite confidence");
            }
            Class<?> classifier = Class.forName("app.ktmnav.backend.ManeuverClassifier");
            java.lang.reflect.Method initialize = classifier.getDeclaredMethod("ensureModel", android.content.Context.class);
            initialize.setAccessible(true);
            check(initialize.invoke(null, getTargetContext()) != null, "Application classifier could not load its bundled model");
            Class<?> locusVersion = Class.forName("locus.api.android.objects.LocusVersion");
            Class<?> versionCode = Class.forName("locus.api.android.objects.VersionCode");
            check(versionCode.getField("UPDATE_13").get(null) != null, "Locus API version contract missing");
            Class.forName("locus.api.android.utils.LocusUtils").getMethod("getActiveVersion", android.content.Context.class, versionCode);
            Class.forName("locus.api.android.ActionBasics").getMethod("getUpdateContainer", android.content.Context.class, locusVersion);
            Class.forName("locus.api.android.features.periodicUpdates.UpdateContainer").getMethod("getGuideNavPoint1Action");
            BccuCrypto crypto = BccuCrypto.INSTANCE;
            byte[] key = crypto.unhex("2b7e151628aed2a6abf7158809cf4f3c");
            byte[] iv = crypto.unhex("000102030405060708090a0b0c0d0e0f");
            byte[] plaintext = crypto.unhex("6bc1bee22e409f96e93d7e117393172a");
            check("7649abac8119b246cee98e9b12e9197d".equals(crypto.hex(crypto.aes(plaintext, key, iv, true))), "NIST AES-CBC vector failed");
            byte[] message = "KTMnav protocol probe".getBytes(StandardCharsets.UTF_8);
            check(Arrays.equals(message, crypto.decryptData(crypto.encryptData(message, key, iv), key, iv)), "Encrypted data framing did not round trip");
            check(Arrays.equals(new byte[]{3,7}, BccuProtocol.INSTANCE.buildTurnIconPayload(TurnIcon.QUITE_RIGHT, BccuProtocol.Visibility.FULL)), "Gen 3 turn payload differs from evidence");
            check("RAB_SECT_4_RH".equals(Gen2Icons.gen2Name(TurnIcon.RAB_SECT_3_RH)), "Gen 2 roundabout mapping differs from evidence");
            JSONObject guidance = Gen2Frames.guidance(7, "Lakeside Road", "250", "m", "QUITE_RIGHT", "10:42", "12.4 km", null, null);
            byte[] frame = Gen2Frames.frame(guidance);
            check(ByteBuffer.wrap(frame).getInt() == frame.length - 4 && frame[4] == 1, "Gen 2 frame header is invalid");
            JSONObject decoded = new JSONObject(new String(frame, 5, frame.length - 5, StandardCharsets.UTF_8));
            check("guidance".equals(decoded.getString("UiContext")), "Gen 2 guidance context missing");
            check("Lakeside Road".equals(decoded.getJSONObject("UpdateUI").getJSONObject("TurnRoad").getString("Text")), "Gen 2 road field lost");
            Bridge b = Bridge.get(getTargetContext());
            b.getPrefs().edit().putBoolean("welcome_complete", true).commit();
            runOnMainSync(() -> { b.forgetBike(address); b.forgetBike(secondAddress); });
            Bridge.Bike previous = b.getActiveBike();
            runOnMainSync(() -> {
                b.saveBike(address, "Smoke test motorcycle", "GEN2");
                b.selectBike(address);
                check(b.getActiveBike() != null && address.equals(b.getActiveBike().address), "Saved bike was not selected");
                b.renameBike(address, "Renamed motorcycle");
                check("Renamed motorcycle".equals(b.getActiveBike().name), "Rename was not persisted");
                check("GEN2".equals(b.getActiveBike().generation), "Generation was lost");
                b.saveBike(secondAddress, "Second motorcycle", "GEN1");
                b.selectBike(secondAddress);
                check(secondAddress.equals(b.getActiveBike().address), "Second bike was not selected");
                check("GEN3".equals(b.getActiveBike().generation), "Legacy GEN1 did not normalize to actual Gen 3");
                b.getPrefs().edit().putString("active_bike_gen", "GEN1")
                    .putString("bike_gen_" + secondAddress, "GEN1")
                    .putString("session_keys_" + secondAddress, "00112233445566778899aabbccddeeff")
                    .putBoolean("paired_before_" + secondAddress, true).commit();
                BikeManager migrated = new BikeManager(getTargetContext());
                check("GEN3".equals(migrated.getActiveBike().generation), "Stored legacy generation migration failed");
                check("Second motorcycle".equals(migrated.getActiveBike().name), "Migration lost saved bike name");
                check(migrated.hasPairedBefore(secondAddress), "Migration lost pairing flag");
                check("00112233445566778899aabbccddeeff".equals(b.getPrefs().getString("session_keys_" + secondAddress, "")), "Migration changed session keys");
                b.selectBike(address);
                check("Renamed motorcycle".equals(b.getActiveBike().name), "Switching bikes lost the name");
                check("GEN2".equals(b.getActiveBike().generation), "Switching bikes lost the generation");
                BikeManager pending = new BikeManager(getTargetContext());
                pending.setProvisionalRestore(address, "Renamed motorcycle", "GEN2");
                pending.saveBike(secondAddress, "Provisional motorcycle", "GEN3");
                BikeManager recovered = new BikeManager(getTargetContext());
                check(address.equals(recovered.getActiveBike().address), "Interrupted pairing did not restore previous bike");
                check("GEN2".equals(recovered.getActiveBike().generation), "Pairing rollback lost previous generation");
                check(!recovered.hasProvisionalRestore(), "Pairing rollback retained pending marker");
            });
            Intent launch = new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Activity activity = startActivitySync(launch);
            waitForIdleSync();
            check(activity != null, "Activity failed to launch");
            runOnMainSync(() -> {
                check(!b.isConnected(), "Fresh bridge must not report a connection");
                b.sendTestGuidance();
                check(!b.isConnected(), "Test guidance faked a live connection");
                check(b.getTelemetry().isEmpty(), "Disconnected bridge fabricated telemetry");
                check(b.getSignalRssi() == null, "Disconnected bridge fabricated signal strength");
                boolean hadTimeout=b.getPrefs().contains("idle_timeout");
                long previousTimeout=b.getPrefs().getLong("idle_timeout",30000L);
                b.getPrefs().edit().putLong("idle_timeout",30000L).commit();
                check(idleTimeout(b)==30000L,"30-second background wait was not read in milliseconds");
                b.getPrefs().edit().putLong("idle_timeout",300000L).commit();
                check(idleTimeout(b)==300000L,"5-minute background wait was not read in milliseconds");
                b.getPrefs().edit().remove("idle_timeout").commit();
                check(idleTimeout(b)==30000L,"Default background wait differs from UI");
                if(hadTimeout)b.getPrefs().edit().putLong("idle_timeout",previousTimeout).commit();
                b.getPrefs().edit().putBoolean("desired_connect",true).putBoolean("keep_background",true).putBoolean("auto_connect",true).commit();
                b.suspendAndArmBackground();
                check(b.getPrefs().getBoolean("desired_connect",false),"Passive suspension lost user connection intent");
                check(address.equals(b.getActiveBike().address),"Passive suspension changed the active motorcycle");
                check(!b.isConnected(),"Passive suspension fabricated a connection");
                b.disconnect();
                check(!b.getPrefs().getBoolean("desired_connect",true),"Explicit disconnect retained automatic connection intent");
                b.forgetBike(address);
                check(b.getBikes().stream().noneMatch(bike -> address.equals(bike.address)), "Forgotten bike remained saved");
                check(b.getBikes().stream().anyMatch(bike -> secondAddress.equals(bike.address)), "Forgetting one bike removed another");
                b.forgetBike(secondAddress);
                if (previous != null) b.selectBike(previous.address);
                // finishInstrumentation terminates the app process; flush pending apply() writes first.
                check(b.getPrefs().edit().putLong("instrumentation_flush", System.currentTimeMillis()).commit(), "Bike preferences were not flushed to disk");
                check(b.getPrefs().edit().remove("instrumentation_flush").commit(), "Test cleanup was not persisted");
                activity.finish();
            });
            String previousLanguage = b.getPrefs().getString("app_language", "");
            for (String tag : new String[]{"en", "de", "es", "it", "fr", "pt-BR", "pt-PT"}) {
                b.getPrefs().edit().putString("app_language", tag).commit();
                check(tag.equals(UiText.language(getTargetContext())), "Language selection failed for " + tag);
                check(!UiText.translate(getTargetContext(), "Settings").isEmpty(), "Missing Settings translation for " + tag);
                try (InputStream in = getTargetContext().getAssets().open("i18n/" + tag + ".json")) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] bytes = new byte[4096]; int size;
                    while ((size = in.read(bytes)) != -1) out.write(bytes,0,size);
                    check(new JSONObject(out.toString("UTF-8")).length() > 50, "Language dictionary incomplete: " + tag);
                }
            }
            b.getPrefs().edit().putString("app_language", previousLanguage).commit();
            result.putString("stream", "PASS: bundled model inference, Locus SDK, NIST AES, Gen 3 payload, Gen 2 frame, saved bikes, GEN1-to-GEN3 migration preserving pairing keys, millisecond background wait, passive suspension preserves intent, explicit stop cancels intent, no fabricated telemetry, seven language dictionaries, Activity launch\n");
            sendStatus(0, status);
            finish(Activity.RESULT_OK, result);
        } catch (Throwable t) {
            try { runOnMainSync(() -> {
                Bridge b = Bridge.get(getTargetContext()); b.forgetBike(address); b.forgetBike(secondAddress);
                b.getPrefs().edit().putLong("instrumentation_flush", System.currentTimeMillis()).commit();
                b.getPrefs().edit().remove("instrumentation_flush").commit();
            }); } catch (Throwable ignored) {}
            result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(t));
            status.putString("stack", android.util.Log.getStackTraceString(t));
            sendStatus(-2, status);
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}

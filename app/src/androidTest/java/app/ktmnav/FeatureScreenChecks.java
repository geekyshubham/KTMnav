package app.ktmnav;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.json.JSONObject;

/** Opens every 1.3 feature screen in each app language and checks dictionary parity; never talks to a motorcycle. */
final class FeatureScreenChecks {
    private static final String[] LANGUAGES = {"en", "de", "es", "it", "fr", "pt-BR", "pt-PT"};
    private final Instrumentation runner;
    private final List<String> results = new ArrayList<>();

    FeatureScreenChecks(Instrumentation runner) {
        this.runner = runner;
    }

    private void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        results.add(message);
    }

    private JSONObject dictionary(Context context, String tag) throws Exception {
        try (InputStream in = context.getAssets().open("i18n/" + tag + ".json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return new JSONObject(out.toString("UTF-8"));
        }
    }

    private static Set<String> keys(JSONObject o) {
        Set<String> out = new TreeSet<>();
        for (Iterator<String> it = o.keys(); it.hasNext(); ) out.add(it.next());
        return out;
    }

    String run() throws Exception {
        Context context = runner.getTargetContext();
        Set<String> english = keys(dictionary(context, "en"));
        for (String tag : LANGUAGES) {
            JSONObject d = dictionary(context, tag);
            check(keys(d).equals(english), "Dictionary " + tag + " has the English key set");
            for (String key : english) check(!d.optString(key, "").trim().isEmpty(), "Dictionary " + tag + " value for " + key);
        }
        SharedPreferences prefs = context.getSharedPreferences("ktmnav", Context.MODE_PRIVATE);
        String previous = prefs.getString("app_language", "");
        try {
            for (String tag : LANGUAGES) {
                prefs.edit().putString("app_language", tag).commit();
                Activity activity = runner.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                runner.waitForIdleSync();
                MainActivity main = (MainActivity) activity;
                runner.runOnMainSync(() -> {
                    new MessagesScreen(main).show();
                    new CallsScreen(main).show();
                    new RideToolsScreen(main).show();
                    new RidesScreen(main).show();
                    new ObdScreen(main).show();
                    new SymbolTestScreen(main).show();
                    main.handlebarMappings();
                });
                runner.waitForIdleSync();
                Thread.sleep(1500);
                runner.runOnMainSync(activity::finish);
                runner.waitForIdleSync();
                results.add("Feature screens opened in " + tag);
            }
        } finally {
            prefs.edit().putString("app_language", previous).commit();
        }
        return "PASS: feature screens and dictionaries\n" + results.size() + " checks\n";
    }
}

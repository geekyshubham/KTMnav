package app.ktmnav;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.LocaleList;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONObject;

public final class UiText {
    private UiText() {}

    private static final String[] TAGS = {"", "en", "de", "es", "it", "fr", "pt-BR", "pt-PT"};
    private static final String[] NAMES = {
        "Phone language",
        "English",
        "Deutsch",
        "Español",
        "Italiano",
        "Français",
        "Português (Brasil)",
        "Português (Portugal)"
    };

    private static final Map<String, Map<String, String>> CACHE = new ConcurrentHashMap<>();

    private static final String[] PREFIXES = {
        "Wait before low-power scan: ",
        "Forward navigation from ",
        "Idle display: ",
        "Idle delay: ",
        "Theme: ",
        "Media: ",
        "Installed · ",
        "Dashboard direction: ",
        "Scan failed (",
        "Couldn't share logs: ",
        "Connection: ",
        "Signal: ",
        "Last handlebar event: ",
        "Navigation app: ",
        "Navigate to ",
        "Route imported: ",
        "Route not imported: ",
        "Started ",
        "Could not start ",
        "Open ",
        "Get "
    };

    public static String[] tags() {
        return TAGS.clone();
    }

    public static String[] names() {
        return NAMES.clone();
    }

    public static String language(Context context) {
        if (context == null) return "en";
        SharedPreferences prefs = context.getSharedPreferences("ktmnav", Context.MODE_PRIVATE);
        String pref = prefs != null ? prefs.getString("app_language", "") : "";
        if (pref != null && !pref.trim().isEmpty()) {
            for (String tag : TAGS) {
                if (!tag.isEmpty() && (tag.equalsIgnoreCase(pref) || tag.replace('-', '_').equalsIgnoreCase(pref.replace('-', '_')))) {
                    return tag;
                }
            }
            return "en";
        }

        Locale locale;
        if (Build.VERSION.SDK_INT >= 24) {
            LocaleList list = context.getResources().getConfiguration().getLocales();
            locale = (list != null && !list.isEmpty()) ? list.get(0) : Locale.getDefault();
        } else {
            locale = context.getResources().getConfiguration().locale;
        }
        if (locale == null) locale = Locale.getDefault();

        String lang = locale.getLanguage().toLowerCase(Locale.ROOT);
        String country = locale.getCountry().toUpperCase(Locale.ROOT);

        if ("pt".equals(lang)) {
            if ("PT".equals(country)) {
                return "pt-PT";
            }
            return "pt-BR";
        }
        if ("de".equals(lang)) return "de";
        if ("es".equals(lang)) return "es";
        if ("it".equals(lang)) return "it";
        if ("fr".equals(lang)) return "fr";
        if ("en".equals(lang)) return "en";

        return "en";
    }

    public static String translate(Context context, String english) {
        if (english == null) return null;
        if (context == null) return english;

        String lang = language(context);
        Map<String, String> dict = getDictionary(context, lang);
        if (dict != null) {
            String val = dict.get(english);
            if (val != null) return val;
        }

        Map<String, String> enDict = null;
        if (!"en".equals(lang)) {
            enDict = getDictionary(context, "en");
            if (enDict != null) {
                String enVal = enDict.get(english);
                if (enVal != null) return enVal;
            }
        }

        for (String pfx : PREFIXES) {
            if (english.startsWith(pfx)) {
                String sub = english.substring(pfx.length());
                String pfxTr = (dict != null && dict.containsKey(pfx)) ? dict.get(pfx)
                    : (enDict != null && enDict.containsKey(pfx)) ? enDict.get(pfx)
                    : pfx;
                String subTr = (dict != null && dict.containsKey(sub)) ? dict.get(sub)
                    : (enDict != null && enDict.containsKey(sub)) ? enDict.get(sub)
                    : sub;
                return pfxTr + subTr;
            }
        }

        return english;
    }

    public static String[] translate(Context context, String[] english) {
        if (english == null) return new String[0];
        String[] out = new String[english.length];
        for (int i = 0; i < english.length; i++) {
            out[i] = translate(context, english[i]);
        }
        return out;
    }

    private static Map<String, String> getDictionary(Context context, String lang) {
        if (lang == null || lang.isEmpty()) lang = "en";
        Map<String, String> cached = CACHE.get(lang);
        if (cached != null) return cached;

        synchronized (CACHE) {
            cached = CACHE.get(lang);
            if (cached != null) return cached;
            Map<String, String> map = loadAsset(context, lang);
            CACHE.put(lang, map);
            return map;
        }
    }

    private static Map<String, String> loadAsset(Context context, String lang) {
        Map<String, String> map = new HashMap<>();
        String path = "i18n/" + lang + ".json";
        try (InputStream in = context.getAssets().open(path);
             BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            JSONObject obj = new JSONObject(sb.toString());
            Iterator<String> it = obj.keys();
            while (it.hasNext()) {
                String k = it.next();
                map.put(k, obj.optString(k, ""));
            }
        } catch (Exception ignored) {}
        return map;
    }
}

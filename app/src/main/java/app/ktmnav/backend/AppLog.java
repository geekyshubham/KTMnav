package app.ktmnav.backend;

import android.util.Log;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * Thread-safe bounded in-memory log buffer exportable via Bridge.getLogs().
 */
public final class AppLog {

    private static final int MAX_LOG_LINES = 500;
    private static final ArrayDeque<String> BUFFER = new ArrayDeque<>(MAX_LOG_LINES);
    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT);

    private AppLog() {
    }

    private static void append(String level, String tag, String msg) {
        synchronized (BUFFER) {
            String timestamp = TIME_FORMAT.format(new Date());
            String line = timestamp + " [" + level + "/" + tag + "] " + msg;
            if (BUFFER.size() >= MAX_LOG_LINES) {
                BUFFER.pollFirst();
            }
            BUFFER.addLast(line);
        }
    }

    public static void i(String tag, String msg) {
        Log.i("KTMnav." + tag, msg);
        append("I", tag, msg);
    }

    public static void d(String tag, String msg) {
        Log.d("KTMnav." + tag, msg);
        append("D", tag, msg);
    }

    public static void w(String tag, String msg) {
        Log.w("KTMnav." + tag, msg);
        append("W", tag, msg);
    }

    public static void w(String tag, String msg, Throwable tr) {
        Log.w("KTMnav." + tag, msg, tr);
        append("W", tag, msg + (tr != null ? " (" + tr.getMessage() + ")" : ""));
    }

    public static void e(String tag, String msg) {
        Log.e("KTMnav." + tag, msg);
        append("E", tag, msg);
    }

    public static void e(String tag, String msg, Throwable tr) {
        Log.e("KTMnav." + tag, msg, tr);
        append("E", tag, msg + (tr != null ? " (" + tr.getMessage() + ")" : ""));
    }

    public static String getLogs() {
        StringBuilder sb = new StringBuilder();
        synchronized (BUFFER) {
            for (String line : BUFFER) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    public static void clear() {
        synchronized (BUFFER) {
            BUFFER.clear();
        }
    }
}

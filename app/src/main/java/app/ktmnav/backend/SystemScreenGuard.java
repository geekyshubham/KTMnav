package app.ktmnav.backend;

/** Refuses screen actions on system UI and permission dialogs so buttons can never grant access. */
public final class SystemScreenGuard {

    public static final String BLOCKED_RESULT = "Blocked on system screen";

    private static final String[] BLOCKED_PACKAGES = new String[]{
            "com.android.systemui",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            "android"
    };

    private SystemScreenGuard() {
    }

    public static boolean isBlocked(CharSequence packageName) {
        if (packageName == null) {
            return true;
        }
        String name = packageName.toString().trim();
        if (name.isEmpty()) {
            return true;
        }
        for (String blocked : BLOCKED_PACKAGES) {
            if (blocked.equals(name)) {
                return true;
            }
        }
        return false;
    }

    public static float[] centre(int left, int top, int right, int bottom) {
        if (right <= left || bottom <= top) {
            return null;
        }
        float x = (left + right) / 2f;
        float y = (top + bottom) / 2f;
        return x < 0f || y < 0f ? null : new float[]{x, y};
    }
}

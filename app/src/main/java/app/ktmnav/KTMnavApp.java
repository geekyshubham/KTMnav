package app.ktmnav;

import android.app.Application;
import app.ktmnav.backend.Bridge;

public final class KTMnavApp extends Application {
    @Override public void onCreate() { super.onCreate(); Bridge.get(this); }
}

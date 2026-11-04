package app.ktmnav.backend;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.telecom.TelecomManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;

/**
 * Feeds {@link CallControl} from TelephonyCallback (only when READ_PHONE_STATE is granted) and provides
 * the Android {@link CallControl.Telecom} implementation. Never reads or logs phone numbers.
 */
public final class CallStateMonitor {

    static final String PREFS = "ktmnav";
    static final String PREF_RING_MUTED = "call_ring_stream_muted";

    private final Context context;
    private final CallControl control;
    private TelephonyCallback callback;

    public CallStateMonitor(Context context, CallControl control) {
        this.context = context.getApplicationContext();
        this.control = control;
    }

    public static CallControl.Telecom telecom(Context context) {
        return new AndroidTelecom(context.getApplicationContext());
    }

    public static CallControl.State map(int telephonyState) {
        switch (telephonyState) {
            case TelephonyManager.CALL_STATE_RINGING:
                return CallControl.State.RINGING;
            case TelephonyManager.CALL_STATE_OFFHOOK:
                return CallControl.State.OFFHOOK;
            case TelephonyManager.CALL_STATE_IDLE:
                return CallControl.State.IDLE;
            default:
                return CallControl.State.UNKNOWN;
        }
    }

    public synchronized boolean isStarted() {
        return callback != null;
    }

    @SuppressLint("MissingPermission")
    public synchronized boolean start() {
        if (!control.isRinging()) {
            restorePersistedRingerMute(context);
        }
        if (callback != null) {
            return true;
        }
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        TelephonyManager tm = context.getSystemService(TelephonyManager.class);
        if (tm == null) {
            return false;
        }
        TelephonyCallback cb = new StateCallback(control);
        try {
            tm.registerTelephonyCallback(context.getMainExecutor(), cb);
        } catch (RuntimeException e) {
            return false;
        }
        callback = cb;
        return true;
    }

    public synchronized void stop() {
        TelephonyCallback cb = callback;
        callback = null;
        if (cb != null) {
            TelephonyManager tm = context.getSystemService(TelephonyManager.class);
            if (tm != null) {
                try {
                    tm.unregisterTelephonyCallback(cb);
                } catch (RuntimeException ignored) {
                }
            }
        }
        control.onCallStateChanged(CallControl.State.UNKNOWN);
    }

    /** Undoes a ring-stream mute left behind when the process died before the call stopped ringing. */
    static synchronized void restorePersistedRingerMute(Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(PREF_RING_MUTED, false)) {
            return;
        }
        prefs.edit().remove(PREF_RING_MUTED).apply();
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio == null) {
            return;
        }
        try {
            audio.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_UNMUTE, 0);
        } catch (RuntimeException ignored) {
        }
    }

    private static final class StateCallback extends TelephonyCallback implements TelephonyCallback.CallStateListener {
        private final CallControl control;

        StateCallback(CallControl control) {
            this.control = control;
        }

        @Override
        public void onCallStateChanged(int state) {
            control.onCallStateChanged(map(state));
        }
    }

    private static final class AndroidTelecom implements CallControl.Telecom {
        private final Context context;

        AndroidTelecom(Context context) {
            this.context = context;
        }

        @Override
        public boolean canReadPhoneState() {
            return context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED;
        }

        @Override
        public boolean canAnswerCalls() {
            return context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED;
        }

        @SuppressLint("MissingPermission")
        @SuppressWarnings("deprecation")
        @Override
        public boolean acceptRingingCall() {
            TelecomManager telecom = context.getSystemService(TelecomManager.class);
            if (telecom == null) {
                return false;
            }
            try {
                telecom.acceptRingingCall();
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        }

        @SuppressLint({"MissingPermission", "ApplySharedPref"})
        @Override
        public synchronized boolean silenceRinger() {
            TelecomManager telecom = context.getSystemService(TelecomManager.class);
            if (telecom != null) {
                try {
                    telecom.silenceRinger();
                    return true;
                } catch (RuntimeException ignored) {
                }
            }
            AudioManager audio = context.getSystemService(AudioManager.class);
            if (audio == null) {
                return false;
            }
            try {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF_RING_MUTED, true).commit();
                audio.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0);
                return true;
            } catch (RuntimeException e) {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(PREF_RING_MUTED).apply();
                return false;
            }
        }

        @Override
        public synchronized void restoreRinger() {
            restorePersistedRingerMute(context);
        }
    }
}

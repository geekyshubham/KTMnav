package app.ktmnav.backend;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Monitors active Android MediaSessions to extract track title and artist.
 * Stops observation on disconnect or permission revocation and resumes when restored.
 */
public final class NowPlayingSource {
    private static final String TAG = "KTMnav.NowPlaying";

    private final Context appContext;
    private final Listener listener;
    private final ComponentName listenerComponent;
    private final MediaSessionManager sessionManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<MediaController> controllers = new ArrayList<>();

    private NowPlaying last;
    private boolean started;

    public interface Listener {
        void onNowPlayingChanged(NowPlaying nowPlaying);
    }

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
                @Override
                public void onActiveSessionsChanged(List<MediaController> list) {
                    if (!started) {
                        return;
                    }
                    try {
                        attachControllers(list);
                        recompute();
                    } catch (SecurityException e) {
                        Log.w(TAG, "Permission lost on active sessions changed", e);
                        stop();
                    }
                }
            };

    private final MediaController.Callback controllerCallback =
            new MediaController.Callback() {
                @Override
                public void onPlaybackStateChanged(PlaybackState state) {
                    recompute();
                }

                @Override
                public void onMetadataChanged(MediaMetadata metadata) {
                    recompute();
                }

                @Override
                public void onSessionDestroyed() {
                    recompute();
                }
            };

    public NowPlayingSource(Context context, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
        this.sessionManager = (MediaSessionManager) appContext.getSystemService(Context.MEDIA_SESSION_SERVICE);
        this.listenerComponent = new ComponentName(appContext, NavListener.class);
    }

    public synchronized void start() {
        if (sessionManager == null) {
            return;
        }
        if (!started) {
            try {
                sessionManager.addOnActiveSessionsChangedListener(sessionsChanged, listenerComponent, handler);
                started = true;
            } catch (SecurityException e) {
                Log.w(TAG, "No notification listener permission - cannot observe media sessions", e);
                stop();
                return;
            } catch (Exception e) {
                Log.w(TAG, "Failed to register media session listener", e);
                stop();
                return;
            }
        }
        refreshControllers();
    }

    public synchronized void stop() {
        if (started) {
            started = false;
            if (sessionManager != null) {
                try {
                    sessionManager.removeOnActiveSessionsChangedListener(sessionsChanged);
                } catch (Exception ignored) {}
            }
        }
        detachControllers();
        if (last != null) {
            last = null;
            if (listener != null) {
                try {
                    listener.onNowPlayingChanged(null);
                } catch (Throwable t) {
                    Log.w(TAG, "Error notifying NowPlaying listener", t);
                }
            }
        }
    }

    public synchronized boolean isStarted() {
        return started;
    }

    public synchronized NowPlaying getCurrent() {
        return last;
    }

    private void refreshControllers() {
        if (sessionManager == null) return;
        try {
            List<MediaController> active = sessionManager.getActiveSessions(listenerComponent);
            attachControllers(active);
            recompute();
        } catch (SecurityException e) {
            Log.w(TAG, "Media session read denied (permission lost)", e);
            stop();
        } catch (Exception e) {
            Log.w(TAG, "Error getting active sessions", e);
            attachControllers(null);
            recompute();
        }
    }

    private void attachControllers(List<MediaController> list) {
        detachControllers();
        if (list != null) {
            for (MediaController mc : list) {
                try {
                    mc.registerCallback(controllerCallback, handler);
                    controllers.add(mc);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to register controller callback", e);
                }
            }
        }
    }

    private void detachControllers() {
        for (MediaController mc : controllers) {
            try {
                mc.unregisterCallback(controllerCallback);
            } catch (Exception ignored) {}
        }
        controllers.clear();
    }

    private void recompute() {
        if (!started) {
            return;
        }
        NowPlaying current = compute();
        if (Objects.equals(current, last)) {
            return;
        }
        last = current;
        if (listener != null) {
            try {
                listener.onNowPlayingChanged(current);
            } catch (Throwable t) {
                Log.w(TAG, "Error notifying NowPlaying listener", t);
            }
        }
    }

    private NowPlaying compute() {
        MediaController activeController = null;
        for (MediaController mc : controllers) {
            PlaybackState state = mc.getPlaybackState();
            if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                activeController = mc;
                break;
            }
        }

        if (activeController == null) {
            return null;
        }

        MediaMetadata metadata = activeController.getMetadata();
        if (metadata == null) {
            return null;
        }

        String title = firstNonEmpty(
                metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
                metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        );
        String artist = firstNonEmpty(
                metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
                metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        );

        if (title == null && artist == null) {
            return null;
        }
        return new NowPlaying(title, artist);
    }

    private static String firstNonEmpty(String... items) {
        if (items == null) return null;
        for (String item : items) {
            if (item != null && !item.trim().isEmpty()) {
                return item.trim();
            }
        }
        return null;
    }
}

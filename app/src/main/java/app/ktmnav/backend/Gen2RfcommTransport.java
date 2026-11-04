package app.ktmnav.backend;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Gen2 Bluetooth Classic RFCOMM transport for KTM/Husqvarna instrument clusters.
 * Based on TurnIt Gen2Frames and Gen2ConnectionService evidence.
 * Features prompt socket cancellation on disconnect, generation tokens, and worker lifecycle management.
 *
 * Physical cluster validation note: Protocol implemented per recovered specification;
 * final timing and cluster-specific idiosyncrasies remain subject to on-bike verification.
 */
public final class Gen2RfcommTransport implements BikeLink {

    private static final String TAG = "Gen2Rfcomm";
    private static final long MAX_RETRY_DELAY_MS = 10000L;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<ConnectionStateListener> listeners = new CopyOnWriteArrayList<>();

    private HandlerThread workerThread;
    private Handler workerHandler;

    private volatile ConnectionState state = ConnectionState.DISCONNECTED;
    private volatile String statusDetail = "Disconnected";

    private String targetAddress;
    private String targetName;

    private volatile BluetoothSocket socket;
    private volatile BluetoothSocket pendingSocket;
    private volatile OutputStream outputStream;
    private int msgId = 1;
    private int retryAttempt = 0;
    private volatile boolean stopping = false;
    private volatile boolean isShutdown = false;
    private volatile int connectionGeneration = 0;

    // Guidance cache
    private boolean guidanceActive = false;
    private String currentRoad;
    private String currentDist;
    private String currentDistUnit;
    private String currentIconName;
    private String currentEta;
    private String currentDist2Target;
    private String currentBanner;

    private final DashBannerArbiter bannerArbiter = new DashBannerArbiter(new DashBannerArbiter.Scheduler() {
        @Override
        public Object schedule(Runnable task, long delayMs) {
            Handler h = workerHandler;
            if (h == null) {
                return null;
            }
            h.postDelayed(task, delayMs);
            return task;
        }

        @Override
        public void cancel(Object token) {
            Handler h = workerHandler;
            if (h != null && token instanceof Runnable) {
                h.removeCallbacks((Runnable) token);
            }
        }
    }, new DashBannerArbiter.Sink() {
        @Override
        public void showFrame(String text, BccuProtocol.NotificationIcon icon) {
            postBanner(text);
        }

        @Override
        public void clearBanner() {
            postBanner(null);
        }
    });

    public Gen2RfcommTransport(Context context) {
        this.context = context.getApplicationContext();
        initWorkerThread();
    }

    public Gen2RfcommTransport(Context context, String address, String name) {
        this.context = context.getApplicationContext();
        this.targetAddress = (address != null && !address.trim().isEmpty()) ? address.trim() : null;
        this.targetName = (name != null && !name.trim().isEmpty()) ? name.trim() : this.targetAddress;
        initWorkerThread();
    }

    private synchronized void initWorkerThread() {
        if (workerThread == null || !workerThread.isAlive()) {
            workerThread = new HandlerThread("ktmnav-gen2");
            workerThread.start();
            workerHandler = new Handler(workerThread.getLooper());
        }
    }

    @Override
    public ConnectionState getConnectionState() {
        return state;
    }

    @Override
    public String getStatusDetail() {
        return statusDetail;
    }

    @Override
    public void addConnectionStateListener(ConnectionStateListener listener) {
        if (listener == null) return;
        listeners.add(listener);
        listener.onConnectionStateChanged(state, statusDetail);
    }

    @Override
    public void removeConnectionStateListener(ConnectionStateListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    private void updateState(ConnectionState newState, String detail) {
        if (isShutdown) {
            return;
        }
        this.state = newState;
        this.statusDetail = detail;
        AppLog.i(TAG, "State -> " + newState + " (" + detail + ")");
        mainHandler.post(() -> {
            if (isShutdown) {
                return;
            }
            for (ConnectionStateListener l : listeners) {
                l.onConnectionStateChanged(newState, detail);
            }
        });
    }

    public synchronized void connect() {
        if (this.targetAddress != null) {
            connect(this.targetAddress, this.targetName);
        }
    }

    @Override
    public synchronized void connect(String address, String name) {
        if (address == null || address.trim().isEmpty()) {
            return;
        }
        this.targetAddress = address.trim();
        this.targetName = (name != null && !name.trim().isEmpty()) ? name.trim() : targetAddress;
        this.stopping = false;
        this.retryAttempt = 0;
        final int gen = ++this.connectionGeneration;
        bannerArbiter.reset();

        // Promptly close any existing sockets
        closePendingSocket();
        closeSocket();

        initWorkerThread();
        if (workerHandler != null) {
            workerHandler.removeCallbacksAndMessages(null);
            workerHandler.post(() -> connectInternal(gen));
        }
    }

    @Override
    public synchronized void disconnect() {
        this.stopping = true;
        this.connectionGeneration++;
        bannerArbiter.reset();

        // Cancel pending in-flight connect promptly from the caller thread
        closePendingSocket();
        closeSocket();

        if (workerHandler != null) {
            workerHandler.removeCallbacksAndMessages(null);
        }
        updateState(ConnectionState.DISCONNECTED, "Disconnected");
    }

    public synchronized void shutdown() {
        this.isShutdown = true;
        disconnect();

        if (workerThread != null) {
            workerThread.quitSafely();
            workerThread = null;
            workerHandler = null;
        }
        listeners.clear();
        mainHandler.removeCallbacksAndMessages(null);
    }

    private void connectInternal(final int gen) {
        if (stopping || isShutdown || gen != connectionGeneration || socket != null) {
            return;
        }

        BluetoothAdapter adapter = null;
        try {
            BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            adapter = (bm != null) ? bm.getAdapter() : BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                updateState(ConnectionState.CONNECTING, "Waiting for Bluetooth to be enabled...");
                if (!stopping && !isShutdown && gen == connectionGeneration && workerHandler != null) {
                    workerHandler.postDelayed(() -> connectInternal(gen), MAX_RETRY_DELAY_MS);
                }
                return;
            }
        } catch (SecurityException e) {
            updateState(ConnectionState.ERROR, "BLUETOOTH permission missing: " + e.getMessage());
            return;
        }

        updateState(ConnectionState.CONNECTING, "Connecting to " + targetName + " (RFCOMM)...");

        BluetoothSocket s = null;
        try {
            BluetoothDevice device = adapter.getRemoteDevice(targetAddress);
            s = device.createInsecureRfcommSocketToServiceRecord(Gen2Frames.NAV_UUID);
            this.pendingSocket = s;

            if (stopping || isShutdown || gen != connectionGeneration) {
                closePendingSocket();
                return;
            }

            s.connect(); // Blocking connect call

            // Re-verify after unblocking
            if (stopping || isShutdown || gen != connectionGeneration) {
                this.pendingSocket = null;
                if (s != null) {
                    try { s.close(); } catch (IOException ignored) {}
                }
                return;
            }

            this.pendingSocket = null;
            this.socket = s;
            this.outputStream = s.getOutputStream();
            this.msgId = 1;

            // Handshake restore frames
            writeFrame(Gen2Frames.frame(Gen2Frames.restore(0)));
            writeFrame(Gen2Frames.frame(Gen2Frames.restore(msgId++)));

            if (guidanceActive) {
                sendCurrentGuidance();
            }

            startReaderThread(s, gen);
            retryAttempt = 0;
            updateState(ConnectionState.CONNECTED, "Connected to " + targetName);
            AppLog.i(TAG, "Connected to Gen2 bike: " + LogRedact.mac(targetAddress));
        } catch (SecurityException e) {
            this.pendingSocket = null;
            if (s != null) {
                try { s.close(); } catch (IOException ignored) {}
            }
            closeSocket();
            if (!stopping && !isShutdown && gen == connectionGeneration) {
                updateState(ConnectionState.ERROR, "BLUETOOTH_CONNECT permission missing: " + e.getMessage());
            }
        } catch (Exception e) {
            this.pendingSocket = null;
            if (s != null) {
                try { s.close(); } catch (IOException ignored) {}
            }
            closeSocket();
            if (stopping || isShutdown || gen != connectionGeneration) {
                return;
            }
            long delay = Math.min(MAX_RETRY_DELAY_MS, 2000L << Math.min(retryAttempt, 2));
            retryAttempt++;
            AppLog.w(TAG, "RFCOMM connect failed (" + e.getMessage() + ") - retrying in " + (delay / 1000) + "s");
            updateState(ConnectionState.CONNECTING, "Connect failed (" + e.getMessage() + "), retrying...");
            if (workerHandler != null) {
                workerHandler.postDelayed(() -> connectInternal(gen), delay);
            }
        }
    }

    private void startReaderThread(BluetoothSocket s, int gen) {
        Thread reader = new Thread(() -> {
            byte[] buf = new byte[256];
            try {
                InputStream in = s.getInputStream();
                while (in.read(buf) != -1) {
                    // Drain incoming data
                }
            } catch (IOException ignored) {
            }
            if (workerHandler != null) {
                workerHandler.post(() -> onLinkLost(s, gen));
            }
        }, "ktmnav-gen2-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private void onLinkLost(BluetoothSocket s, int gen) {
        if (this.socket != s || stopping || isShutdown || gen != connectionGeneration) {
            return;
        }
        AppLog.i(TAG, "RFCOMM link lost, scheduling reconnect...");
        closeSocket();
        updateState(ConnectionState.CONNECTING, "Link lost, reconnecting...");
        if (workerHandler != null) {
            workerHandler.removeCallbacksAndMessages(null);
            workerHandler.post(() -> connectInternal(gen));
        }
    }

    private void closePendingSocket() {
        BluetoothSocket p = this.pendingSocket;
        this.pendingSocket = null;
        if (p != null) {
            try {
                p.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void closeSocket() {
        BluetoothSocket s = this.socket;
        this.socket = null;
        this.outputStream = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public void sendGuidance(String distanceText, String roadText, String etaText, String remainingDistanceText) {
        if (workerHandler == null || isShutdown) return;
        workerHandler.post(() -> {
            if (isShutdown) return;
            guidanceActive = true;
            if (distanceText != null) {
                String[] split = Gen2Frames.splitDistance(distanceText);
                if (split != null && split.length >= 2) {
                    currentDist = split[0];
                    currentDistUnit = split[1];
                }
            }
            if (roadText != null) {
                currentRoad = roadText;
            }
            if (etaText != null) {
                currentEta = etaText;
            }
            if (remainingDistanceText != null) {
                currentDist2Target = remainingDistanceText;
            }
            sendCurrentGuidance();
        });
    }

    @Override
    public void sendTurnIcon(TurnIcon icon) {
        if (workerHandler == null || isShutdown) return;
        workerHandler.post(() -> {
            if (isShutdown) return;
            guidanceActive = true;
            currentIconName = Gen2Icons.gen2Name(icon);
            sendCurrentGuidance();
        });
    }

    @Override
    public void sendNotification(String text, BccuProtocol.NotificationIcon icon) {
        showBanner(DashboardFeatures.MANUAL_BANNER_OWNER, text, DashBannerArbiter.PRIORITY_MESSAGE, 0L);
    }

    public boolean showBanner(String owner, String text, int priority, long holdMs) {
        if (isShutdown || state != ConnectionState.CONNECTED) {
            return false;
        }
        return bannerArbiter.show(owner, text, priority, holdMs);
    }

    public void clearBanner(String owner) {
        bannerArbiter.clear(owner);
    }

    private void postBanner(String text) {
        Handler h = workerHandler;
        if (h == null || isShutdown) return;
        h.post(() -> {
            if (isShutdown) return;
            currentBanner = text;
            try {
                if (text == null && !guidanceActive) {
                    sendJson(Gen2Frames.restore(msgId++));
                } else if (guidanceActive) {
                    sendJson(Gen2Frames.guidance(msgId++, currentRoad, currentDist, currentDistUnit,
                            currentIconName, currentEta, currentDist2Target, null, text));
                } else {
                    sendJson(Gen2Frames.guidance(msgId++, null, null, null, null, null, null, null, text));
                }
            } catch (JSONException e) {
                AppLog.w(TAG, "Failed to build banner JSON", e);
            }
        });
    }

    @Override
    public void clearGuidance() {
        if (workerHandler == null || isShutdown) return;
        workerHandler.post(() -> {
            if (isShutdown) return;
            guidanceActive = false;
            currentRoad = null;
            currentDist = null;
            currentDistUnit = null;
            currentIconName = null;
            currentEta = null;
            currentDist2Target = null;
            try {
                if (currentBanner != null) {
                    sendJson(Gen2Frames.guidance(msgId++, null, null, null, null, null, null, null, currentBanner));
                    return;
                }
                sendJson(Gen2Frames.restore(msgId++));
            } catch (JSONException e) {
                AppLog.w(TAG, "Failed to build restore JSON", e);
            }
        });
    }

    private void sendCurrentGuidance() {
        try {
            JSONObject obj = Gen2Frames.guidance(msgId++, currentRoad, currentDist, currentDistUnit,
                    currentIconName, currentEta, currentDist2Target, null, currentBanner);
            sendJson(obj);
        } catch (JSONException e) {
            AppLog.w(TAG, "Failed to build guidance JSON", e);
        }
    }

    private void sendJson(JSONObject obj) {
        if (outputStream == null || isShutdown) {
            return;
        }
        try {
            writeFrame(Gen2Frames.frame(obj));
        } catch (IOException e) {
            AppLog.i(TAG, "Write failed - link lost");
            onLinkLost(socket, connectionGeneration);
        }
    }

    private void writeFrame(byte[] frame) throws IOException {
        OutputStream os = this.outputStream;
        if (os != null && !isShutdown) {
            os.write(frame);
            os.flush();
        }
    }
}

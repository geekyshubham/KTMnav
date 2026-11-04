package app.ktmnav.backend;

/**
 * Common interface for bike communication transports (Gen1 BLE / Gen2 RFCOMM).
 */
public interface BikeLink {

    interface ConnectionStateListener {
        void onConnectionStateChanged(ConnectionState state, String statusDetail);
    }

    void addConnectionStateListener(ConnectionStateListener listener);

    void removeConnectionStateListener(ConnectionStateListener listener);

    ConnectionState getConnectionState();

    String getStatusDetail();

    void connect(String address, String name);

    void disconnect();

    void sendGuidance(String distanceText, String roadText, String etaText, String remainingDistanceText);

    void sendTurnIcon(TurnIcon icon);

    void sendNotification(String text, BccuProtocol.NotificationIcon icon);

    void clearGuidance();
}

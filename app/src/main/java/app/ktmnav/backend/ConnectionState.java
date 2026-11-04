package app.ktmnav.backend;

/**
 * Connection states for bike link transports.
 */
public enum ConnectionState {
    DISCONNECTED("Disconnected"),
    CONNECTING("Connecting"),
    AUTHENTICATING("Authenticating"),
    CONNECTED("Connected"),
    ERROR("Error");

    private final String displayState;

    ConnectionState(String displayState) {
        this.displayState = displayState;
    }

    public String getDisplayState() {
        return displayState;
    }
}

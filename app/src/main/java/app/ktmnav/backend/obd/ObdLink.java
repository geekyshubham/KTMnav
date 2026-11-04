package app.ktmnav.backend.obd;

/**
 * Byte-stream link to an ELM327 adapter (BLE UART, Classic SPP or a test double).
 */
public interface ObdLink {

    interface Listener {
        void onOpened(String adapterName);

        void onBytes(byte[] data);

        void onClosed(String reason);
    }

    void open(Listener listener);

    boolean write(byte[] data);

    void close();

    String getName();
}

package app.ktmnav.backend.obd;

public final class ObdException extends Exception {

    public enum Kind { TIMEOUT, CLOSED, WRITE_FAILED, INTERRUPTED }

    public final Kind kind;

    public ObdException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }
}

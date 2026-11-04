package app.ktmnav.backend.obd;

import java.nio.charset.StandardCharsets;

/**
 * Serialises ELM327 commands over an {@link ObdLink}: one command in flight, response ends at the '>' prompt.
 * Every outgoing command passes the read-only whitelist in {@link Elm327Protocol#build(String)}.
 */
public final class ElmChannel {

    private final ObdLink link;
    private final long drainMs;
    private final Object lock = new Object();
    private final Object txLock = new Object();
    private final StringBuilder buffer = new StringBuilder();
    private boolean closed = false;
    private String closeReason = null;
    private boolean staleResponsePending = false;

    public ElmChannel(ObdLink link, long drainMs) {
        this.link = link;
        this.drainMs = drainMs;
    }

    public void onBytes(byte[] data) {
        if (data == null || data.length == 0) {
            return;
        }
        String s = new String(data, StandardCharsets.US_ASCII).replace("\u0000", "");
        synchronized (lock) {
            buffer.append(s);
            if (buffer.length() > 8192) {
                buffer.delete(0, buffer.length() - 4096);
            }
            lock.notifyAll();
        }
    }

    public void onClosed(String reason) {
        synchronized (lock) {
            if (!closed) {
                closed = true;
                closeReason = reason;
            }
            lock.notifyAll();
        }
    }

    public boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    public String transact(String command, long timeoutMs) throws ObdException {
        byte[] bytes = Elm327Protocol.buildBytes(command);
        synchronized (txLock) {
            synchronized (lock) {
                ensureOpen();
                if (staleResponsePending) {
                    awaitPrompt(drainMs);
                    staleResponsePending = false;
                }
                buffer.setLength(0);
            }
            if (!link.write(bytes)) {
                throw new ObdException(ObdException.Kind.WRITE_FAILED, "Write failed for " + Elm327Protocol.normalize(command));
            }
            synchronized (lock) {
                if (!awaitPrompt(timeoutMs)) {
                    ensureOpen();
                    staleResponsePending = true;
                    throw new ObdException(ObdException.Kind.TIMEOUT, "No prompt after " + timeoutMs + "ms for " + Elm327Protocol.normalize(command));
                }
                int idx = buffer.indexOf(String.valueOf(Elm327Protocol.PROMPT));
                String out = buffer.substring(0, idx);
                buffer.delete(0, idx + 1);
                return out;
            }
        }
    }

    private boolean awaitPrompt(long timeoutMs) throws ObdException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (buffer.indexOf(String.valueOf(Elm327Protocol.PROMPT)) < 0) {
            ensureOpen();
            long remainingMs = (deadline - System.nanoTime()) / 1_000_000L;
            if (remainingMs <= 0) {
                return false;
            }
            try {
                lock.wait(remainingMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ObdException(ObdException.Kind.INTERRUPTED, "Interrupted");
            }
        }
        return true;
    }

    private void ensureOpen() throws ObdException {
        if (closed) {
            throw new ObdException(ObdException.Kind.CLOSED, closeReason != null ? closeReason : "Link closed");
        }
    }
}

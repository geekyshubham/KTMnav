package app.ktmnav.backend;

import java.util.Arrays;
import java.util.List;

/**
 * Pure Gen 3 BCCU authentication state machine (no Android types), driven by AUTH_REQUEST values.
 * Bike-to-phone control blocks carry the command in byte [2]; replies are built by
 * BccuCrypto.buildControlMessage ([2]=0xFF, [4]=command, [6]=1).
 * HELLO reply rule (Navigator Gen3 v0.3.0, MIT, Copyright (c) 2026 OpenDash contributors):
 * reply 1 (use the persisted key pool) only for a known bike with a stored pool and no stall
 * recovery in this connection attempt; otherwise reply 0 and let the dash decide whether to prompt.
 */
public final class BccuHandshake {

    private static final String TAG = "Gen3Auth";

    public static final int REPLY_HELLO = 0;
    public static final int REPLY_USE_KEY_POOL = 1;
    public static final int REPLY_KEYS_GENERATED = 2;

    public interface Host {
        void writeAuthReply(byte[] value);

        boolean hasPairedBefore();

        List<byte[]> loadKeyPool();

        void storeKeyPool(List<byte[]> keys);
    }

    public enum Result {
        IGNORED,
        NONCE_SENT,
        HELLO_REPLIED,
        KEYS_GENERATED,
        AUTHENTICATED,
        KEY_POOL_MISSING
    }

    private final Host host;
    private final int handshakeRecoveries;

    private byte[] initialMessage;
    private byte[] tempIv;
    private byte[] tempSecret;
    private List<byte[]> sessionKeys;
    private byte[] activeSessionKey;
    private int activeKeyIndex = -1;
    private int lastCommand = -1;
    private int lastReply = -1;
    private int nonceRestarts;

    public BccuHandshake(Host host, int handshakeRecoveries) {
        this.host = host;
        this.handshakeRecoveries = Math.max(0, handshakeRecoveries);
    }

    public static boolean shouldUseKeyPool(boolean knownBike, boolean poolExists, int handshakeRecoveries) {
        return knownBike && poolExists && handshakeRecoveries == 0;
    }

    public synchronized Result onAuthRequest(byte[] value) {
        if (value == null) {
            return Result.IGNORED;
        }
        if (tempIv != null && activeSessionKey == null && initialMessage != null && value.length == 16
                && Arrays.equals(value, initialMessage)) {
            // The dash re-sent its opening nonce: it restarted the handshake on its side (RideConnect's
            // "ROUND 1 REPEAT"). Restart ours in place instead of waiting for the handshake budget to expire.
            nonceRestarts++;
            AppLog.i(TAG, "Dash re-sent m1; restarting the nonce exchange (" + nonceRestarts + ")");
            tempIv = null;
            tempSecret = null;
            sessionKeys = null;
            activeKeyIndex = -1;
        }
        if (tempIv == null || tempSecret == null) {
            if (value.length < 16) {
                return Result.IGNORED;
            }
            byte[] m1 = Arrays.copyOfRange(value, 0, 16);
            byte[] m2 = BccuCrypto.INSTANCE.randomNonce();
            BccuCrypto.KeyPair keyPair = BccuCrypto.INSTANCE.computeTempIvAndSecret(m1, m2);
            initialMessage = m1;
            tempIv = keyPair.iv;
            tempSecret = keyPair.secret;
            AppLog.i(TAG, "Received m1 (bike nonce); sending m2");
            host.writeAuthReply(m2);
            return Result.NONCE_SENT;
        }
        if (value.length < 16 || value.length % 16 != 0) {
            AppLog.w(TAG, "Ignoring control block of unexpected length " + value.length);
            return Result.IGNORED;
        }
        byte[] decrypted;
        try {
            decrypted = BccuCrypto.INSTANCE.decryptControl(value, tempSecret, tempIv);
        } catch (RuntimeException e) {
            AppLog.w(TAG, "Failed to decrypt auth control block", e);
            return Result.IGNORED;
        }
        int cmd = decrypted[2] & 0xFF;
        lastCommand = cmd;
        if (cmd == BccuProtocol.CMD_HELLO) {
            boolean known = host.hasPairedBefore();
            List<byte[]> pool = known ? host.loadKeyPool() : null;
            boolean poolValid = pool != null && pool.size() == 16;
            if (shouldUseKeyPool(known, poolValid, handshakeRecoveries)) {
                sessionKeys = pool;
                AppLog.i(TAG, "HELLO from known bike with stored key pool: reply 1 (silent reconnect)");
                reply(REPLY_USE_KEY_POOL);
            } else {
                String why = !known ? "new bike" : !poolValid ? "known bike, no stored key pool" : "known bike, retry after stall";
                AppLog.i(TAG, "HELLO (" + why + "): reply 0, the dash decides whether to prompt");
                reply(REPLY_HELLO);
            }
            return Result.HELLO_REPLIED;
        }
        if (cmd == BccuProtocol.CMD_GENERATE_KEYS) {
            AppLog.i(TAG, "GENERATE_KEYS: deriving 16 session keys");
            byte[] mirrored = BccuCrypto.INSTANCE.buildMirrored(decrypted);
            sessionKeys = BccuCrypto.INSTANCE.deriveSessionKeys(decrypted, mirrored, tempIv, tempSecret);
            host.storeKeyPool(sessionKeys);
            reply(REPLY_KEYS_GENERATED);
            return Result.KEYS_GENERATED;
        }
        if (cmd >= BccuProtocol.CMD_KEY_ACK_BASE && cmd < BccuProtocol.CMD_KEY_ACK_BASE + 16) {
            int keyIndex = cmd & 0x0F;
            if (sessionKeys == null && host.hasPairedBefore()) {
                sessionKeys = host.loadKeyPool();
            }
            if (sessionKeys == null || keyIndex >= sessionKeys.size()) {
                if (activeSessionKey == null) {
                    // Nothing to select from: tell the dash we have no keys (reply 0) so it re-runs HELLO and
                    // GENERATE_KEYS in this same connection instead of us dropping the link and cooling down.
                    AppLog.w(TAG, "Dash selected key index " + keyIndex + " but no key pool is available; asking it to re-pair");
                    reply(REPLY_HELLO);
                } else {
                    AppLog.w(TAG, "Dash selected key index " + keyIndex + " mid-session but no key pool is available; keeping the current key");
                }
                return Result.KEY_POOL_MISSING;
            }
            activeSessionKey = sessionKeys.get(keyIndex);
            activeKeyIndex = keyIndex;
            reply(BccuProtocol.CMD_KEY_ACK_BASE | keyIndex);
            AppLog.i(TAG, "Authenticated with session key " + keyIndex);
            return Result.AUTHENTICATED;
        }
        AppLog.d(TAG, "Ignoring unknown auth command " + cmd);
        return Result.IGNORED;
    }

    private void reply(int command) {
        lastReply = command;
        byte[] block = BccuCrypto.INSTANCE.buildControlMessage(command);
        host.writeAuthReply(BccuCrypto.INSTANCE.encryptControl(block, tempSecret, tempIv));
    }

    public synchronized byte[] getTempIv() {
        return tempIv != null ? tempIv.clone() : null;
    }

    public synchronized byte[] getTempSecret() {
        return tempSecret != null ? tempSecret.clone() : null;
    }

    public synchronized byte[] getActiveSessionKey() {
        return activeSessionKey != null ? activeSessionKey.clone() : null;
    }

    public synchronized int getActiveKeyIndex() {
        return activeKeyIndex;
    }

    public synchronized boolean isAuthenticated() {
        return activeSessionKey != null;
    }

    public synchronized int getLastCommand() {
        return lastCommand;
    }

    public synchronized int getLastReply() {
        return lastReply;
    }

    public int getHandshakeRecoveries() {
        return handshakeRecoveries;
    }

    public synchronized int getNonceRestarts() {
        return nonceRestarts;
    }
}

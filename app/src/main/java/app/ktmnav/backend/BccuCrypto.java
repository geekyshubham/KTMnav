package app.ktmnav.backend;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Pure Java 17 cryptographic routines for KTM BCCU Bluetooth Low Energy authentication and data encryption.
 * Based on OpenDash BccuCrypto (MIT License, Copyright (c) 2026 OpenDash contributors).
 */
public final class BccuCrypto {

    public static final BccuCrypto INSTANCE = new BccuCrypto();
    private static final SecureRandom RANDOM = new SecureRandom();

    public static final class KeyPair {
        public final byte[] iv;
        public final byte[] secret;

        public KeyPair(byte[] iv, byte[] secret) {
            this.iv = iv;
            this.secret = secret;
        }
    }

    private BccuCrypto() {
    }

    public byte[] aes(byte[] input, byte[] key, byte[] iv, boolean encrypt) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE,
                new SecretKeySpec(key, "AES"),
                new IvParameterSpec(iv));
        return cipher.doFinal(input);
    }

    public byte[] frame(byte[] data) {
        int padLen = 16 - (data.length % 16);
        int totalLen = data.length + 16 + padLen;
        byte[] framed = new byte[totalLen];

        byte[] prefixRandom = new byte[16];
        RANDOM.nextBytes(prefixRandom);
        System.arraycopy(prefixRandom, 0, framed, 0, 16);

        System.arraycopy(data, 0, framed, 16, data.length);

        int padStart = 16 + data.length;
        int padEnd = totalLen - 1;
        int randomPadBytes = padEnd - padStart;
        if (randomPadBytes > 0) {
            byte[] padRandom = new byte[randomPadBytes];
            RANDOM.nextBytes(padRandom);
            System.arraycopy(padRandom, 0, framed, padStart, randomPadBytes);
        }

        framed[totalLen - 1] = (byte) padLen;
        return framed;
    }

    public byte[] unframe(byte[] data) {
        if (data == null || data.length == 0) {
            return new byte[0];
        }
        int padLen = data[data.length - 1] & 0xFF;
        int payloadEnd = data.length - padLen;
        if (payloadEnd < 16) {
            return new byte[0];
        }
        return Arrays.copyOfRange(data, 16, payloadEnd);
    }

    public byte[] encryptData(byte[] payload, byte[] key, byte[] iv) {
        try {
            return aes(frame(payload), key, iv, true);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Failed to encrypt BCCU data payload", e);
        }
    }

    public byte[] decryptData(byte[] message, byte[] key, byte[] iv) {
        try {
            return unframe(aes(message, key, iv, false));
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Failed to decrypt BCCU data payload", e);
        }
    }

    public byte[] encryptControl(byte[] payload16, byte[] key, byte[] iv) {
        try {
            return aes(payload16, key, iv, true);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Failed to encrypt BCCU control message", e);
        }
    }

    public byte[] decryptControl(byte[] message, byte[] key, byte[] iv) {
        try {
            return aes(message, key, iv, false);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Failed to decrypt BCCU control message", e);
        }
    }

    public byte[] buildControlMessage(int command) {
        byte[] msg = new byte[16];
        RANDOM.nextBytes(msg);
        msg[2] = (byte) 0xFF;
        msg[4] = (byte) command;
        msg[6] = 1;
        return msg;
    }

    public KeyPair computeTempIvAndSecret(byte[] m1, byte[] m2) {
        byte[] tempIv = new byte[16];
        byte[] tempSecret = new byte[16];
        for (int i = 0; i < 8; i++) {
            int i2 = i + 8;
            tempIv[i] = m1[i2];
            tempIv[i2] = m2[i];
            tempSecret[i] = m2[i2];
            tempSecret[i2] = m1[i];
        }
        return new KeyPair(tempIv, tempSecret);
    }

    public byte[] buildMirrored(byte[] decryptedChallenge) {
        byte[] slice = Arrays.copyOfRange(decryptedChallenge, 8, 16);
        byte[] mirrored = new byte[16];
        for (int i = 0; i < 8; i++) {
            mirrored[i] = slice[i];
            mirrored[15 - i] = slice[i];
        }
        return mirrored;
    }

    public List<byte[]> deriveSessionKeys(byte[] decryptedChallenge, byte[] mirrored, byte[] tempIv, byte[] tempSecret) {
        List<byte[]> blocks = Arrays.asList(decryptedChallenge, mirrored, tempIv, tempSecret);
        List<byte[]> sessionKeys = new ArrayList<>(16);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            for (int i = 0; i < 4; i++) {
                byte[] chunk64 = new byte[64];
                for (int i2 = 0; i2 < 4; i2++) {
                    System.arraycopy(blocks.get((i2 + i) % 4), 0, chunk64, i2 * 16, 16);
                }
                byte[] hashed = digest.digest(chunk64);
                for (int i3 = 0; i3 < 4; i3++) {
                    int offset = i3 * 16;
                    sessionKeys.add(Arrays.copyOfRange(hashed, offset, offset + 16));
                }
            }
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-512 not available", e);
        }
        return sessionKeys;
    }

    public byte[] randomNonce() {
        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        return nonce;
    }

    public String hex(byte[] bytes) {
        if (bytes == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public byte[] unhex(String str) {
        if (str == null || str.isEmpty()) {
            return new byte[0];
        }
        int len = str.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(str.charAt(i), 16) << 4)
                    + Character.digit(str.charAt(i + 1), 16));
        }
        return data;
    }
}

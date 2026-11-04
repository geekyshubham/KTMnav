package app.ktmnav.backend;

import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

public class BccuCryptoTest {

    private final BccuCrypto crypto = BccuCrypto.INSTANCE;

    @Test
    public void testHexAndUnhex() {
        byte[] expected = new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF};
        String hex = crypto.hex(expected);
        Assert.assertEquals("deadbeef", hex.toLowerCase());

        byte[] decoded = crypto.unhex(hex);
        Assert.assertArrayEquals(expected, decoded);

        byte[] decodedUpper = crypto.unhex("DEADBEEF");
        Assert.assertArrayEquals(expected, decodedUpper);

        Assert.assertEquals("", crypto.hex(null));
        Assert.assertEquals("", crypto.hex(new byte[0]));
        Assert.assertEquals(0, crypto.unhex(null).length);
        Assert.assertEquals(0, crypto.unhex("").length);
    }

    @Test
    public void testNistAesCbcVector() throws Exception {
        byte[] key = crypto.unhex("2b7e151628aed2a6abf7158809cf4f3c");
        byte[] iv = crypto.unhex("000102030405060708090a0b0c0d0e0f");
        byte[] plaintext = crypto.unhex("6bc1bee22e409f96e93d7e117393172a");

        byte[] cipher = crypto.aes(plaintext, key, iv, true);
        Assert.assertEquals("7649abac8119b246cee98e9b12e9197d", crypto.hex(cipher).toLowerCase());

        byte[] decrypted = crypto.aes(cipher, key, iv, false);
        Assert.assertArrayEquals(plaintext, decrypted);
    }

    @Test
    public void testFrameAndUnframe() {
        byte[] payload = "KTMnav Pure Test Payload".getBytes(StandardCharsets.UTF_8);
        byte[] framed = crypto.frame(payload);

        Assert.assertNotNull(framed);
        Assert.assertEquals(0, framed.length % 16);
        Assert.assertTrue(framed.length >= payload.length + 17);

        byte[] unframed = crypto.unframe(framed);
        Assert.assertArrayEquals(payload, unframed);

        byte[] emptyPayload = new byte[0];
        byte[] framedEmpty = crypto.frame(emptyPayload);
        Assert.assertEquals(32, framedEmpty.length);
        Assert.assertArrayEquals(emptyPayload, crypto.unframe(framedEmpty));

        Assert.assertEquals(0, crypto.unframe(null).length);
        Assert.assertEquals(0, crypto.unframe(new byte[0]).length);
    }

    @Test
    public void testEncryptAndDecryptData() {
        byte[] key = crypto.unhex("000102030405060708090a0b0c0d0e0f");
        byte[] iv = crypto.unhex("101112131415161718191a1b1c1d1e1f");

        for (int len : new int[]{0, 1, 15, 16, 31, 32, 64}) {
            byte[] payload = new byte[len];
            for (int i = 0; i < len; i++) {
                payload[i] = (byte) (i & 0xFF);
            }
            byte[] encrypted = crypto.encryptData(payload, key, iv);
            Assert.assertNotNull(encrypted);
            Assert.assertEquals(0, encrypted.length % 16);

            byte[] decrypted = crypto.decryptData(encrypted, key, iv);
            Assert.assertArrayEquals("Mismatch for payload length " + len, payload, decrypted);
        }
    }

    @Test
    public void testComputeTempIvAndSecret() {
        byte[] m1 = new byte[16];
        byte[] m2 = new byte[16];
        for (int i = 0; i < 16; i++) {
            m1[i] = (byte) (i + 1);
            m2[i] = (byte) (i + 100);
        }

        BccuCrypto.KeyPair kp = crypto.computeTempIvAndSecret(m1, m2);
        Assert.assertNotNull(kp);
        Assert.assertEquals(16, kp.iv.length);
        Assert.assertEquals(16, kp.secret.length);

        for (int i = 0; i < 8; i++) {
            Assert.assertEquals(m1[i + 8], kp.iv[i]);
            Assert.assertEquals(m2[i], kp.iv[i + 8]);
            Assert.assertEquals(m2[i + 8], kp.secret[i]);
            Assert.assertEquals(m1[i], kp.secret[i + 8]);
        }
    }

    @Test
    public void testBuildMirrored() {
        byte[] challenge = new byte[16];
        for (int i = 0; i < 16; i++) {
            challenge[i] = (byte) (i * 3 + 1);
        }

        byte[] mirrored = crypto.buildMirrored(challenge);
        Assert.assertNotNull(mirrored);
        Assert.assertEquals(16, mirrored.length);

        for (int i = 0; i < 8; i++) {
            Assert.assertEquals(challenge[8 + i], mirrored[i]);
            Assert.assertEquals(challenge[8 + i], mirrored[15 - i]);
        }
    }

    @Test
    public void testDeriveSessionKeys() {
        byte[] challenge = new byte[16];
        Arrays.fill(challenge, (byte) 0x5A);
        byte[] m1 = new byte[16];
        Arrays.fill(m1, (byte) 0x11);
        byte[] m2 = new byte[16];
        Arrays.fill(m2, (byte) 0x22);

        BccuCrypto.KeyPair kp = crypto.computeTempIvAndSecret(m1, m2);
        byte[] mirrored = crypto.buildMirrored(challenge);

        List<byte[]> keys = crypto.deriveSessionKeys(challenge, mirrored, kp.iv, kp.secret);
        Assert.assertNotNull(keys);
        Assert.assertEquals(16, keys.size());

        for (byte[] k : keys) {
            Assert.assertNotNull(k);
            Assert.assertEquals(16, k.length);
        }

        // Must be deterministic
        List<byte[]> keys2 = crypto.deriveSessionKeys(challenge, mirrored, kp.iv, kp.secret);
        for (int i = 0; i < 16; i++) {
            Assert.assertArrayEquals(keys.get(i), keys2.get(i));
        }

        // Distinct consecutive session keys
        Assert.assertFalse(Arrays.equals(keys.get(0), keys.get(1)));
    }

    @Test
    public void testControlPayloadAndEncryption() {
        byte[] control = crypto.buildControlMessage(BccuProtocol.CMD_GENERATE_KEYS);
        Assert.assertNotNull(control);
        Assert.assertEquals(16, control.length);
        Assert.assertEquals((byte) 0xFF, control[2]);
        Assert.assertEquals((byte) BccuProtocol.CMD_GENERATE_KEYS, control[4]);
        Assert.assertEquals((byte) 1, control[6]);

        byte[] key = crypto.unhex("2b7e151628aed2a6abf7158809cf4f3c");
        byte[] iv = crypto.unhex("000102030405060708090a0b0c0d0e0f");

        byte[] enc = crypto.encryptControl(control, key, iv);
        Assert.assertNotNull(enc);
        Assert.assertEquals(16, enc.length);

        byte[] dec = crypto.decryptControl(enc, key, iv);
        Assert.assertArrayEquals(control, dec);
    }
}

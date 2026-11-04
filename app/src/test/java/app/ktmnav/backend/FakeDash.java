package app.ktmnav.backend;

import org.junit.Assert;

import java.security.SecureRandom;
import java.util.List;

/**
 * Dashboard side of the BCCU handshake for JVM tests. Control blocks are random 16 bytes with the
 * command at [2] and a deliberately misleading decoy at [0], encrypted under tempSecret/tempIv.
 */
final class FakeDash {

    private static final SecureRandom RANDOM = new SecureRandom();

    final byte[] m1 = new byte[16];
    byte[] tempIv;
    byte[] tempSecret;
    List<byte[]> keys;
    byte[] lastGenerateBlock;

    FakeDash() {
        RANDOM.nextBytes(m1);
    }

    void onM2(byte[] m2) {
        Assert.assertEquals("m2 is a clear 16-byte nonce", 16, m2.length);
        BccuCrypto.KeyPair kp = BccuCrypto.INSTANCE.computeTempIvAndSecret(m1, m2);
        tempIv = kp.iv;
        tempSecret = kp.secret;
    }

    byte[] command(int cmd, int decoy) {
        byte[] block = new byte[16];
        RANDOM.nextBytes(block);
        block[0] = (byte) decoy;
        block[2] = (byte) cmd;
        if (cmd == BccuProtocol.CMD_GENERATE_KEYS) {
            lastGenerateBlock = block.clone();
            keys = BccuCrypto.INSTANCE.deriveSessionKeys(block, BccuCrypto.INSTANCE.buildMirrored(block), tempIv, tempSecret);
        }
        return BccuCrypto.INSTANCE.encryptControl(block, tempSecret, tempIv);
    }

    int replyCommand(byte[] encryptedReply) {
        Assert.assertEquals(16, encryptedReply.length);
        byte[] d = BccuCrypto.INSTANCE.decryptControl(encryptedReply, tempSecret, tempIv);
        Assert.assertEquals("reply [2] marker", (byte) 0xFF, d[2]);
        Assert.assertEquals("reply [6] marker", 1, d[6]);
        return d[4] & 0xFF;
    }
}

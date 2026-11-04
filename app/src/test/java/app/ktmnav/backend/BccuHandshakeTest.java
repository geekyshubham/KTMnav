package app.ktmnav.backend;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class BccuHandshakeTest {

    private static final class Host implements BccuHandshake.Host {
        final List<byte[]> replies = new ArrayList<>();
        boolean paired;
        List<byte[]> pool;
        int stores;

        @Override
        public void writeAuthReply(byte[] value) {
            replies.add(value);
        }

        @Override
        public boolean hasPairedBefore() {
            return paired;
        }

        @Override
        public List<byte[]> loadKeyPool() {
            return pool;
        }

        @Override
        public void storeKeyPool(List<byte[]> keys) {
            pool = keys;
            stores++;
        }

        byte[] last() {
            return replies.get(replies.size() - 1);
        }
    }

    @Test
    public void helloDecisionTable() {
        Assert.assertTrue(BccuHandshake.shouldUseKeyPool(true, true, 0));
        Assert.assertFalse(BccuHandshake.shouldUseKeyPool(true, true, 1));
        Assert.assertFalse(BccuHandshake.shouldUseKeyPool(true, false, 0));
        Assert.assertFalse(BccuHandshake.shouldUseKeyPool(true, false, 2));
        Assert.assertFalse(BccuHandshake.shouldUseKeyPool(false, true, 0));
        Assert.assertFalse(BccuHandshake.shouldUseKeyPool(false, true, 1));
        Assert.assertFalse(BccuHandshake.shouldUseKeyPool(false, false, 0));
        Assert.assertFalse(BccuHandshake.shouldUseKeyPool(false, false, 3));
    }

    @Test
    public void firstPairingReadsCommandFromByteTwo() {
        Host host = new Host();
        FakeDash dash = new FakeDash();
        BccuHandshake hs = new BccuHandshake(host, 0);

        Assert.assertEquals(BccuHandshake.Result.NONCE_SENT, hs.onAuthRequest(dash.m1));
        dash.onM2(host.last());

        // Decoy 0x11 at [0] would read as "select key 1" if the command were taken from byte 0
        Assert.assertEquals(BccuHandshake.Result.HELLO_REPLIED, hs.onAuthRequest(dash.command(BccuProtocol.CMD_HELLO, 0x11)));
        Assert.assertEquals(0, dash.replyCommand(host.last()));

        Assert.assertEquals(BccuHandshake.Result.KEYS_GENERATED, hs.onAuthRequest(dash.command(BccuProtocol.CMD_GENERATE_KEYS, 0x00)));
        Assert.assertEquals(2, dash.replyCommand(host.last()));
        Assert.assertEquals(1, host.stores);
        for (int i = 0; i < 16; i++) {
            Assert.assertArrayEquals(dash.keys.get(i), host.pool.get(i));
        }

        Assert.assertEquals(BccuHandshake.Result.AUTHENTICATED, hs.onAuthRequest(dash.command(16 | 5, 0x01)));
        Assert.assertEquals(16 | 5, dash.replyCommand(host.last()));
        Assert.assertArrayEquals(dash.keys.get(5), hs.getActiveSessionKey());
        Assert.assertEquals(4, host.replies.size());
    }

    @Test
    public void silentReconnectRepliesOneAndUsesStoredPool() {
        Host host = new Host();
        FakeDash first = new FakeDash();
        BccuHandshake pairing = new BccuHandshake(host, 0);
        pairing.onAuthRequest(first.m1);
        first.onM2(host.last());
        pairing.onAuthRequest(first.command(0, 0x13));
        pairing.onAuthRequest(first.command(1, 0x00));
        pairing.onAuthRequest(first.command(16 | 2, 0x00));
        host.paired = true;
        List<byte[]> pool = host.pool;

        FakeDash dash = new FakeDash();
        BccuHandshake hs = new BccuHandshake(host, 0);
        hs.onAuthRequest(dash.m1);
        dash.onM2(host.last());
        hs.onAuthRequest(dash.command(BccuProtocol.CMD_HELLO, 0x1F));
        Assert.assertEquals(BccuHandshake.REPLY_USE_KEY_POOL, dash.replyCommand(host.last()));
        Assert.assertEquals(BccuHandshake.Result.AUTHENTICATED, hs.onAuthRequest(dash.command(16 | 9, 0x00)));
        Assert.assertEquals(16 | 9, dash.replyCommand(host.last()));
        Assert.assertArrayEquals(pool.get(9), hs.getActiveSessionKey());
        Assert.assertEquals("no re-derivation on silent reconnect", 1, host.stores);
    }

    @Test
    public void helloAfterStallRepliesZeroEvenWithPool() {
        Host host = new Host();
        host.paired = true;
        host.pool = BccuCrypto.INSTANCE.deriveSessionKeys(new byte[16], new byte[16], new byte[16], new byte[16]);
        FakeDash dash = new FakeDash();
        BccuHandshake hs = new BccuHandshake(host, 1);
        hs.onAuthRequest(dash.m1);
        dash.onM2(host.last());
        hs.onAuthRequest(dash.command(0, 0x10));
        Assert.assertEquals(0, dash.replyCommand(host.last()));
    }

    @Test
    public void knownBikeWithoutPoolRepliesZero() {
        Host host = new Host();
        host.paired = true;
        FakeDash dash = new FakeDash();
        BccuHandshake hs = new BccuHandshake(host, 0);
        hs.onAuthRequest(dash.m1);
        dash.onM2(host.last());
        hs.onAuthRequest(dash.command(0, 0x10));
        Assert.assertEquals(0, dash.replyCommand(host.last()));
    }

    @Test
    public void keySelectWithoutPoolAsksTheDashToRepair() {
        Host host = new Host();
        FakeDash dash = new FakeDash();
        BccuHandshake hs = new BccuHandshake(host, 0);
        hs.onAuthRequest(dash.m1);
        dash.onM2(host.last());
        int before = host.replies.size();
        Assert.assertEquals(BccuHandshake.Result.KEY_POOL_MISSING, hs.onAuthRequest(dash.command(16 | 4, 0x00)));
        Assert.assertEquals("reply 0 tells the dash to restart pairing in this connection", before + 1, host.replies.size());
        Assert.assertEquals(BccuHandshake.REPLY_HELLO, dash.replyCommand(host.last()));
        Assert.assertFalse(hs.isAuthenticated());
        // The dash then re-runs HELLO and key generation and the handshake completes normally.
        hs.onAuthRequest(dash.command(BccuProtocol.CMD_HELLO, 0x10));
        Assert.assertEquals(BccuHandshake.REPLY_HELLO, dash.replyCommand(host.last()));
        hs.onAuthRequest(dash.command(BccuProtocol.CMD_GENERATE_KEYS, 0x00));
        Assert.assertEquals(BccuHandshake.REPLY_KEYS_GENERATED, dash.replyCommand(host.last()));
        Assert.assertEquals(BccuHandshake.Result.AUTHENTICATED, hs.onAuthRequest(dash.command(16 | 4, 0x00)));
        Assert.assertTrue(hs.isAuthenticated());
    }

    @Test
    public void repeatedOpeningNonceRestartsTheExchange() {
        Host host = new Host();
        FakeDash dash = new FakeDash();
        BccuHandshake hs = new BccuHandshake(host, 0);
        hs.onAuthRequest(dash.m1);
        byte[] firstM2 = host.last();
        dash.onM2(firstM2);
        hs.onAuthRequest(dash.command(BccuProtocol.CMD_HELLO, 0x10));
        // The dash gives up and starts over by re-sending the same m1: the phone must answer with a fresh m2
        // instead of trying to decrypt the nonce as a control block.
        Assert.assertEquals(BccuHandshake.Result.NONCE_SENT, hs.onAuthRequest(dash.m1.clone()));
        Assert.assertEquals(1, hs.getNonceRestarts());
        byte[] secondM2 = host.last();
        Assert.assertEquals(16, secondM2.length);
        Assert.assertFalse(java.util.Arrays.equals(firstM2, secondM2));
        dash.onM2(secondM2);
        hs.onAuthRequest(dash.command(BccuProtocol.CMD_HELLO, 0x10));
        Assert.assertEquals(BccuHandshake.REPLY_HELLO, dash.replyCommand(host.last()));
        hs.onAuthRequest(dash.command(BccuProtocol.CMD_GENERATE_KEYS, 0x00));
        Assert.assertEquals(BccuHandshake.Result.AUTHENTICATED, hs.onAuthRequest(dash.command(16 | 1, 0x00)));
    }

    @Test
    public void malformedBlocksAreIgnored() {
        Host host = new Host();
        FakeDash dash = new FakeDash();
        BccuHandshake hs = new BccuHandshake(host, 0);
        Assert.assertEquals(BccuHandshake.Result.IGNORED, hs.onAuthRequest(new byte[8]));
        hs.onAuthRequest(dash.m1);
        Assert.assertEquals(BccuHandshake.Result.IGNORED, hs.onAuthRequest(new byte[10]));
    }
}

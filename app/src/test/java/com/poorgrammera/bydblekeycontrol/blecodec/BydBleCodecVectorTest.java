package com.poorgrammera.bydblekeycontrol.blecodec;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Synthetic deterministic regression vector. It contains no vehicle credential or capture data. */
public class BydBleCodecVectorTest {
    @Test
    public void authenticationFrameMatchesKnownWireVector() {
        byte[] appRandom = hex("1020304050607080");
        byte[] randomFrame = BydBleCodec.createRandomExchangeFrame((byte) 0, appRandom);
        assertArrayEquals(hex("5AA5D6102030405060708000FFFFFFFFFF3CF5FA"), randomFrame);

        byte[] responsePayload = hex("2AD601112233445566778801FFFFFFFF");
        assertTrue(BydBleCodec.parseRandomExchange(new BleRandomExchangeResult(), responsePayload));

        byte[] auth = BydBleCodec.createAuthenticationFrame("00112233445566778899AABBCCDDEEFF");
        assertArrayEquals(hex("5BB50396C4727C202D50B06A0477CF412BEBF5FA"), auth);
    }

    private static byte[] hex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }
}

package com.poorgrammera.bydblekeycontrol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.poorgrammera.bydblekeycontrol.blecodec.BleRandomExchangeResult;
import com.poorgrammera.bydblekeycontrol.blecodec.BydBleCodec;
import com.poorgrammera.bydblekeycontrol.blecodec.BydBleCrypto;

import org.junit.Test;

/** Regression tests for the Pure Java BLE codec wire format. */
public class BydPureJavaCodecTest {
    @Test
    public void aesCmacMatchesRfc4493Vector() {
        byte[] key = hex("2B7E151628AED2A6ABF7158809CF4F3C");
        byte[] expected = hex("BB1D6929E95937287FA37D129B756746");
        assertArrayEquals(expected, BydBleCrypto.aesCmac(key, new byte[0], 0));
    }

    @Test
    public void wakeAndRandomFramesHaveConfirmedWireShape() {
        assertArrayEquals(hex("5AA5D50000F5FA"), BydBleCodec.createWakeUpFrame());
        byte[] frame = BydBleCodec.createRandomExchangeFrame((byte) 3);
        assertEquals(20, frame.length);
        assertEquals(0x5A, frame[0] & 0xFF);
        assertEquals(0xA5, frame[1] & 0xFF);
        assertEquals(0xD6, frame[2] & 0xFF);
        assertEquals(3, frame[11] & 0xFF);
        assertEquals(BydBleCrypto.crc8(frame, 0, 16), frame[17]);
        assertEquals(0xF5, frame[18] & 0xFF);
        assertEquals(0xFA, frame[19] & 0xFF);
    }

    @Test
    public void crc8UsesInclusiveEndIndexContract() {
        // Synthetic deterministic packet; byte 17 is filled with the expected CRC below.
        byte[] captured = hex("5AA5D6010203040506070805FFFFFFFFFF6AF5FA");
        assertEquals(0x6A, BydBleCrypto.crc8(captured, 0, 16) & 0xFF);
        assertEquals(captured[17], BydBleCrypto.crc8(captured, 0, 16));
    }

    @Test
    public void dkeyAuthenticationAndControlUseEncrypted5bB5Envelope() {
        assertTrue(BydBleCodec.isValidDkey("0123456789abcdef0123456789abcdef"));
        assertFalse(BydBleCodec.isValidDkey("not-a-key"));
        byte[] auth = BydBleCodec.createAuthenticationFrame("0123456789abcdef0123456789abcdef");
        assertEncryptedEnvelope(auth);
        byte[] control = BydBleCodec.createControlFrame((byte) 0x05);
        assertEncryptedEnvelope(control);
        assertFalse(java.util.Arrays.equals(auth, control));
    }

    @Test
    public void parsesNewKeyRandomResponse() {
        byte[] response = hex("2AD601102030405060708001000000000000F5FA");
        assertEquals(0x2A, BydBleCodec.parseResponseType(response));
        BleRandomExchangeResult parsed = new BleRandomExchangeResult();
        assertTrue(BydBleCodec.parseRandomExchange(parsed, response));
        assertTrue(parsed.isValid());
        assertEquals(1, parsed.getKeyState() & 0xFF);
        assertEquals(1, parsed.getCrcCheckResult() & 0xFF);
        assertArrayEquals(hex("1020304050607080"), parsed.getVehicleRandom());
    }

    @Test
    public void functionIdsMatchDocumentedMap() {
        assertEquals(0x05, BydBleCodec.getControlCode(9001) & 0xFF);
        assertEquals(0x07, BydBleCodec.getControlCode(9002) & 0xFF);
        assertEquals(0x03, BydBleCodec.getControlCode(9003) & 0xFF);
        assertEquals(0x0A, BydBleCodec.getControlCode(9005) & 0xFF);
        assertEquals(0x0C, BydBleCodec.getControlCode(9007) & 0xFF);
        assertEquals(0x16, BydBleCodec.getControlCode(9010) & 0xFF);
        assertEquals(0x06, BydBleCodec.getControlCode(9011) & 0xFF);
        assertEquals(0x06, BydBleCodec.getControlCode(9015) & 0xFF);
        assertEquals(0x1A, BydBleCodec.getControlCode(9019) & 0xFF);
        assertEquals(0x18, BydBleCodec.getControlCode(9020) & 0xFF);
        assertEquals(0xFF, BydBleCodec.getControlCode(9004) & 0xFF);
    }

    @Test
    public void parsesVehicleControlAcknowledgementFields() {
        byte[] response = new byte[16];
        java.util.Arrays.fill(response, (byte) 0xFF);
        response[0] = (byte) BydBleCodec.RESPONSE_CONTROL;
        response[1] = (byte) BydBleCodec.CONTROL_COMMAND_TYPE;
        response[2] = BydBleCodec.getControlCode(9002);
        response[3] = 0x01;
        response[4] = (byte) 0xC0;

        assertEquals(0x24, BydBleCodec.parseResponseType(response));
        assertEquals(0xE5, BydBleCodec.parseResponseCommandType(response));
        assertEquals(0x07, BydBleCodec.parseControlCode(response));
        assertEquals(0x01, BydBleCodec.parseControlResult(response));
        assertEquals(0xC0, BydBleCodec.parseDoorStates(response));
    }

    @Test
    public void gattUuidsMatchVehicleServiceDiscoveryCapture() {
        assertEquals("42594420-4155-544F-E0A9-E50E24DCCA9E", BydBleCodec.SERVICE_UUID);
        assertEquals("42590002-4155-544F-E0A9-E50E24DCCA9E", BydBleCodec.SEND_CHARACTERISTIC_UUID);
        assertEquals("42590003-4155-544F-E0A9-E50E24DCCA9E", BydBleCodec.RECEIVE_CHARACTERISTIC_UUID);
    }

    private static void assertEncryptedEnvelope(byte[] frame) {
        assertEquals(20, frame.length);
        assertEquals(0x5B, frame[0] & 0xFF);
        assertEquals(0xB5, frame[1] & 0xFF);
        assertEquals(0xF5, frame[18] & 0xFF);
        assertEquals(0xFA, frame[19] & 0xFF);
    }

    private static byte[] hex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }
}

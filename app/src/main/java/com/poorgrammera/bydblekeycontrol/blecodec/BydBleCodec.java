package com.poorgrammera.bydblekeycontrol.blecodec;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/** Frame codec for the Korean BYD dkey BLE flow verified by this PoC. */
public final class BydBleCodec {
    public static final String SERVICE_UUID = "42594420-4155-544F-E0A9-E50E24DCCA9E";
    public static final String SEND_CHARACTERISTIC_UUID = "42590002-4155-544F-E0A9-E50E24DCCA9E";
    public static final String RECEIVE_CHARACTERISTIC_UUID = "42590003-4155-544F-E0A9-E50E24DCCA9E";

    public static final int RESPONSE_CONTROL = 0x24;
    public static final int RESPONSE_RANDOM_EXCHANGE = 0x2A;
    public static final int RESPONSE_AUTHENTICATION = 0x2B;
    public static final int CONTROL_COMMAND_TYPE = 0xE5;

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static volatile byte[] appRandom = new byte[8];
    private static volatile byte[] sessionIv = new byte[16];
    private static volatile byte[] aesKey = new byte[16];
    private static volatile byte[] cmacKey = new byte[16];

    private BydBleCodec() { }

    public static void clearSession() {
        Arrays.fill(appRandom, (byte) 0);
        Arrays.fill(sessionIv, (byte) 0);
        Arrays.fill(aesKey, (byte) 0);
        Arrays.fill(cmacKey, (byte) 0);
    }

    public static byte[] createWakeUpFrame() {
        return new byte[] {0x5A, (byte) 0xA5, (byte) 0xD5, 0x00, 0x00, (byte) 0xF5, (byte) 0xFA};
    }

    public static byte[] createRandomExchangeFrame(byte keyNumber) {
        return createRandomExchangeFrame(keyNumber, BydBleCrypto.randomBytes(8));
    }

    static byte[] createRandomExchangeFrame(byte keyNumber, byte[] random) {
        if (random == null || random.length != 8) {
            throw new IllegalArgumentException("App random must be 8 bytes");
        }
        byte[] frame = new byte[20];
        frame[0] = 0x5A;
        frame[1] = (byte) 0xA5;
        frame[2] = (byte) 0xD6;
        appRandom = Arrays.copyOf(random, random.length);
        System.arraycopy(random, 0, frame, 3, 8);
        frame[11] = keyNumber;
        Arrays.fill(frame, 12, 17, (byte) 0xFF);
        finishCrcFrame(frame);
        return frame;
    }

    public static boolean parseRandomExchange(BleRandomExchangeResult result, byte[] payload) {
        if (payload == null || payload.length < 12
                || payload[0] != RESPONSE_RANDOM_EXCHANGE || payload[1] != (byte) 0xD6) {
            return false;
        }
        byte[] vehicleRandom = Arrays.copyOfRange(payload, 3, 11);
        byte[] nextSessionIv = new byte[16];
        System.arraycopy(vehicleRandom, 0, nextSessionIv, 0, 8);
        System.arraycopy(appRandom, 0, nextSessionIv, 8, 8);
        sessionIv = nextSessionIv;
        if (result != null) {
            result.setKeyState(payload[2]);
            result.setVehicleRandom(vehicleRandom);
            result.setCrcCheckResult(payload[11]);
            result.setValid(true);
        }
        return true;
    }

    public static byte[] createAuthenticationFrame(String dkey) {
        if (dkey == null || dkey.trim().isEmpty()) {
            throw new IllegalArgumentException("dkey is required");
        }
        byte[] dkeyBytes = decodeHexDkey(dkey.trim());
        byte[] keyMaterial = new byte[dkeyBytes.length + sessionIv.length];
        System.arraycopy(dkeyBytes, 0, keyMaterial, 0, dkeyBytes.length);
        System.arraycopy(sessionIv, 0, keyMaterial, dkeyBytes.length, sessionIv.length);
        byte[] digest = BydBleCrypto.sha256(keyMaterial);
        aesKey = Arrays.copyOfRange(digest, 0, 16);
        cmacKey = Arrays.copyOfRange(digest, 16, 32);

        byte[] plain = new byte[16];
        plain[0] = (byte) 0xD8;
        Arrays.fill(plain, 1, 12, (byte) 0xFF);
        System.arraycopy(BydBleCrypto.aesCmac(cmacKey, plain, 12), 0, plain, 12, 4);
        return wrapEncrypted(plain);
    }

    public static boolean isValidDkey(String dkey) {
        if (dkey == null || dkey.trim().isEmpty()) return false;
        try {
            return decodeHexDkey(dkey.trim()).length > 0;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public static byte[] createControlFrame(byte controlCode) {
        byte[] plain = new byte[16];
        plain[0] = (byte) CONTROL_COMMAND_TYPE;
        plain[1] = controlCode;
        int sequence = SEQUENCE.updateAndGet(value -> value >= 0xFFFF ? 1 : value + 1);
        plain[2] = (byte) (sequence >>> 8);
        plain[3] = (byte) sequence;
        Arrays.fill(plain, 4, 12, (byte) 0xFF);
        if (controlCode == 0x03) plain[4] = 0x00;
        System.arraycopy(BydBleCrypto.aesCmac(cmacKey, plain, 12), 0, plain, 12, 4);
        return wrapEncrypted(plain);
    }

    public static byte parseAuthenticationResult(byte[] payload) {
        return payload != null && payload.length >= 3
                && payload[0] == RESPONSE_AUTHENTICATION && payload[1] == (byte) 0xD8
                ? payload[2] : (byte) 0xFF;
    }

    public static int parseResponseType(byte[] payload) {
        return payload != null && payload.length > 0 ? payload[0] & 0xFF : -1;
    }

    public static int parseResponseCommandType(byte[] payload) {
        return payload != null && payload.length > 1 ? payload[1] & 0xFF : -1;
    }

    public static int parseControlCode(byte[] payload) {
        return payload != null && payload.length > 2 ? payload[2] & 0xFF : -1;
    }

    public static int parseControlResult(byte[] payload) {
        return payload != null && payload.length > 3 ? payload[3] & 0xFF : -1;
    }

    public static int parseDoorStates(byte[] payload) {
        return payload != null && payload.length > 4 ? payload[4] & 0xFF : -1;
    }

    public static byte getControlCode(int functionId) {
        switch (functionId) {
            case 9001: return 0x05;
            case 9002: return 0x07;
            case 9003: return 0x03;
            case 9005: return 0x0A;
            case 9007: return 0x0C;
            case 9009: return 0x08;
            case 9010: return 0x16;
            case 9011:
            case 9015: return 0x06;
            case 9019: return 0x1A;
            case 9020: return 0x18;
            case -1: return 0x19;
            default: return (byte) 0xFF;
        }
    }

    private static byte[] wrapEncrypted(byte[] plain) {
        byte[] encrypted = BydBleCrypto.aesCbcEncryptNoPadding(plain, aesKey, sessionIv);
        byte[] frame = new byte[20];
        frame[0] = 0x5B;
        frame[1] = (byte) 0xB5;
        System.arraycopy(encrypted, 0, frame, 2, 16);
        frame[18] = (byte) 0xF5;
        frame[19] = (byte) 0xFA;
        return frame;
    }

    private static void finishCrcFrame(byte[] frame) {
        frame[17] = BydBleCrypto.crc8(frame, 0, 16);
        frame[18] = (byte) 0xF5;
        frame[19] = (byte) 0xFA;
    }

    private static byte[] decodeHexDkey(String value) {
        if ((value.length() & 1) != 0 || value.length() > 64) {
            throw new IllegalArgumentException("dkey must contain an even number of hex characters (maximum 64)");
        }
        byte[] output = new byte[value.length() / 2];
        for (int i = 0; i < output.length; i++) {
            int high = Character.digit(value.charAt(i * 2), 16);
            int low = Character.digit(value.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) throw new IllegalArgumentException("dkey must be hexadecimal");
            output[i] = (byte) ((high << 4) | low);
        }
        return output;
    }
}

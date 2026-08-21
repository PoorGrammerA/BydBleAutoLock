package com.poorgrammera.bydblekeycontrol.blecodec;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Cryptographic primitives used by the Korean dkey BLE protocol. */
public final class BydBleCrypto {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int CRC8_POLYNOMIAL = 0x07;

    private BydBleCrypto() { }

    /** Calculates CRC-8 over both supplied array indexes, inclusive. */
    public static byte crc8(byte[] data, int offset, int endInclusive) {
        if (data == null || offset < 0 || endInclusive < offset || endInclusive >= data.length) {
            return 0;
        }
        int crc = 0;
        for (int i = offset; i <= endInclusive; i++) {
            crc ^= data[i] & 0xFF;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x80) != 0
                        ? ((crc << 1) ^ CRC8_POLYNOMIAL)
                        : (crc << 1);
            }
        }
        return (byte) crc;
    }

    static byte[] randomBytes(int length) {
        byte[] value = new byte[length];
        RANDOM.nextBytes(value);
        return value;
    }

    static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    static byte[] aesCbcEncryptNoPadding(byte[] input, byte[] key, byte[] iv) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(input);
        } catch (Exception error) {
            throw new IllegalStateException("AES-CBC encryption failed", error);
        }
    }

    /** RFC 4493 AES-CMAC. */
    public static byte[] aesCmac(byte[] key, byte[] input, int length) {
        if (key == null || key.length != 16 || input == null || length < 0 || length > input.length) {
            throw new IllegalArgumentException("Invalid AES-CMAC input");
        }
        byte[] l = aesEcbEncrypt(new byte[16], key);
        byte[] k1 = leftShift(l);
        if ((l[0] & 0x80) != 0) k1[15] ^= (byte) 0x87;
        byte[] k2 = leftShift(k1);
        if ((k1[0] & 0x80) != 0) k2[15] ^= (byte) 0x87;
        int blocks = Math.max(1, (length + 15) / 16);
        boolean complete = length > 0 && length % 16 == 0;
        byte[] last = new byte[16];
        int lastStart = (blocks - 1) * 16;
        if (complete) {
            System.arraycopy(input, lastStart, last, 0, 16);
            xorInPlace(last, k1);
        } else {
            int remaining = length - lastStart;
            if (remaining > 0) System.arraycopy(input, lastStart, last, 0, remaining);
            last[remaining] = (byte) 0x80;
            xorInPlace(last, k2);
        }
        byte[] state = new byte[16];
        for (int block = 0; block < blocks - 1; block++) {
            byte[] value = Arrays.copyOfRange(input, block * 16, block * 16 + 16);
            xorInPlace(value, state);
            state = aesEcbEncrypt(value, key);
        }
        xorInPlace(last, state);
        return aesEcbEncrypt(last, key);
    }

    private static byte[] aesEcbEncrypt(byte[] input, byte[] key) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
            return cipher.doFinal(input);
        } catch (Exception error) {
            throw new IllegalStateException("AES-ECB encryption failed", error);
        }
    }

    private static byte[] leftShift(byte[] input) {
        byte[] output = new byte[16];
        int carry = 0;
        for (int i = 15; i >= 0; i--) {
            int value = input[i] & 0xFF;
            output[i] = (byte) ((value << 1) | carry);
            carry = value >>> 7;
        }
        return output;
    }

    private static void xorInPlace(byte[] target, byte[] value) {
        for (int i = 0; i < target.length; i++) target[i] ^= value[i];
    }
}

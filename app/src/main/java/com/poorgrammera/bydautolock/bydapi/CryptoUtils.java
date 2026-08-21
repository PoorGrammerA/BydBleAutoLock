package com.poorgrammera.bydautolock.bydapi;

import org.json.JSONObject;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Utility class providing AES encryption/decryption and hashing (MD5, SHA1)
 * required for BYD API integration.
 */
public class CryptoUtils {

    // AES-128-CBC Zero IV
    private static final byte[] ZERO_IV = new byte[16];

    /**
     * Hashes a string using MD5 and returns it as a lowercase hex string.
     */
    public static String md5Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hashInBytes = md.digest(value.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hashInBytes).toUpperCase();
        } catch (Exception e) {
            throw new RuntimeException("MD5 hashing failed", e);
        }
    }

    /**
     * Generates password key for login (MD5 hash of password hashed again with MD5).
     */
    public static String pwdLoginKey(String password) {
        return md5Hex(md5Hex(password));
    }

    /**
     * Hashing function for SHA1 mixed format used by BYD request headers.
     */
    public static String sha1Mixed(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < digest.length; i++) {
                String hexStr = Integer.toHexString(digest[i] & 0xFF);
                if (i % 2 == 0) {
                    sb.append(hexStr.toUpperCase(java.util.Locale.getDefault()));
                } else {
                    sb.append(hexStr.toLowerCase(java.util.Locale.getDefault()));
                }
            }
            
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA1 mixed hashing failed", e);
        }
    }

    /**
     * Computes the BYD request checkcode by re-ordering segments of the JSON payload's MD5 hash.
     */
    public static String computeCheckcode(String jsonStr) {
        String md5 = md5Hex(jsonStr).toLowerCase();
        
        return md5.substring(24, 32) + 
               md5.substring(8, 16) + 
               md5.substring(16, 24) + 
               md5.substring(0, 8);
    }

    /**
     * Encrypts plaintext using AES-128-CBC (Zero IV) and returns an uppercase hex string.
     * 
     * @param plaintext Plaintext to encrypt
     * @param keyHex Hex-encoded AES key
     */
    public static String aesEncryptHex(String plaintext, String keyHex) {
        try {
            byte[] keyBytes = hexToBytes(keyHex);
            SecretKeySpec secretKey = new SecretKeySpec(keyBytes, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(ZERO_IV);

            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec);

            byte[] encryptedBytes = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(encryptedBytes).toUpperCase();
        } catch (Exception e) {
            throw new RuntimeException("AES encryption failed", e);
        }
    }

    /**
     * Decrypts an uppercase hex ciphertext using AES-128-CBC (Zero IV).
     * 
     * @param cipherHex Hex-encoded ciphertext
     * @param keyHex Hex-encoded AES key
     */
    public static String aesDecryptUtf8(String cipherHex, String keyHex) {
        try {
            byte[] keyBytes = hexToBytes(keyHex);
            byte[] cipherBytes = hexToBytes(cipherHex);
            SecretKeySpec secretKey = new SecretKeySpec(keyBytes, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(ZERO_IV);

            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec);

            byte[] decryptedBytes = cipher.doFinal(cipherBytes);
            return new String(decryptedBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("AES decryption failed", e);
        }
    }

    /**
     * Builds the sign string from request query/body fields.
     */
    public static String buildSignString(java.util.Map<String, String> fields, String password) {
        java.util.List<String> sortedKeys = new java.util.ArrayList<>(fields.keySet());
        java.util.Collections.sort(sortedKeys);
        
        StringBuilder sb = new StringBuilder();
        for (String key : sortedKeys) {
            if (sb.length() > 0) sb.append("&");
            String val = fields.get(key);
            sb.append(key).append("=").append(val == null ? "null" : val);
        }
        sb.append("&password=").append(password);
        return sb.toString();
    }

    /**
     * Converts a Hex string to a byte array.
     */
    public static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                 + Character.digit(hex.charAt(i+1), 16));
        }
        return data;
    }

    /**
     * Converts a byte array to a Hex string.
     */
    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}

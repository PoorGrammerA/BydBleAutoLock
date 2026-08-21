package com.poorgrammera.bydautolock.storage;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Helper class that uses Android Keystore System to encrypt and decrypt sensitive strings
 * using AES-GCM-NoPadding (256-bit key).
 */
public class CryptoHelper {
    private static final String TAG = "CryptoHelper";
    private static final String KEY_PROVIDER = "AndroidKeyStore";
    private static final String KEY_ALIAS = "byd_autolock_secure_key";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12; // 12 bytes is standard for GCM
    private static final int GCM_TAG_LENGTH = 128; // bits

    private static synchronized SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEY_PROVIDER);
        keyStore.load(null);

        if (keyStore.containsAlias(KEY_ALIAS)) {
            KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry) keyStore.getEntry(KEY_ALIAS, null);
            if (entry != null) {
                return entry.getSecretKey();
            }
        }

        // Generate key if it doesn't exist yet
        KeyGenerator keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEY_PROVIDER);
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true) // require unique IV for every encryption
                .setKeySize(256)
                .build();
        keyGenerator.init(spec);
        return keyGenerator.generateKey();
    }

    /**
     * Encrypts the plain text using AES-GCM and prepends the 12-byte IV to the cipher text.
     * Returns a Base64 encoded string.
     */
    public static String encrypt(String plainText) {
        if (plainText == null || plainText.isEmpty()) {
            return plainText;
        }
        try {
            SecretKey key = getOrCreateKey();
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key);
            
            byte[] iv = cipher.getIV();
            byte[] cipherText = cipher.doFinal(plainText.getBytes("UTF-8"));
            
            // Combine IV and CipherText
            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);
            
            return Base64.encodeToString(combined, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "Encryption failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Decrypts the Base64 encoded encrypted text.
     * Extracts the IV from the first 12 bytes and decrypts the rest.
     */
    public static String decrypt(String encryptedText) {
        if (encryptedText == null || encryptedText.isEmpty()) {
            return encryptedText;
        }
        try {
            byte[] combined = Base64.decode(encryptedText, Base64.NO_WRAP);
            if (combined.length <= GCM_IV_LENGTH) {
                return null;
            }
            
            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] cipherText = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            System.arraycopy(combined, GCM_IV_LENGTH, cipherText, 0, cipherText.length);
            
            SecretKey key = getOrCreateKey();
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, key, spec);
            
            byte[] plainTextBytes = cipher.doFinal(cipherText);
            return new String(plainTextBytes, "UTF-8");
        } catch (Exception e) {
            // Log as debug or info to prevent cluttering logs with migration warnings
            Log.d(TAG, "Decryption failed (could be unencrypted legacy data): " + e.getMessage());
            return null;
        }
    }
}

package com.poorgrammera.bydautolock.bydapi;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import androidx.core.content.ContextCompat;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;

public final class DeviceInfoProvider {
    private static final String KEY_IMEI = "SP_IMEI";
    private final Context context;
    private final SharedPreferences prefs;

    public DeviceInfoProvider(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences("byd_control_prefs", 0);
    }

    /**
     * Returns a 32-character uppercase hexadecimal watchImei derived from Android's
     * per-device Secure.ANDROID_ID. This is not the phone's hardware IMEI and needs no
     * READ_PHONE_STATE permission. A stored random value is used only on devices that
     * unexpectedly provide no ANDROID_ID.
     */
    public String imeiMd5() {
        String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (!TextUtils.isEmpty(androidId)) {
            return md5Hex(androidId.trim().toUpperCase(Locale.US));
        }
        String stored = this.prefs.getString(KEY_IMEI, null);
        if (stored != null && !stored.trim().isEmpty() && stored.length() == 32) {
            return stored;
        }
        // Generate new: MD5 of a random UUID (no dashes, uppercase), then store
        String rawUuid = UUID.randomUUID().toString().replaceAll("-", "").toUpperCase(Locale.getDefault());
        String md5Result = md5Hex(rawUuid);
        this.prefs.edit().putString(KEY_IMEI, md5Result).apply();
        return md5Result;
    }

    /** Model name in uppercase, matching h.m() → Build.MODEL.toUpperCase() */
    public String model() {
        String str = Build.MODEL;
        return str == null ? "" : str.toUpperCase(Locale.getDefault());
    }

    /** Brand name in uppercase, matching h.l() → Build.BRAND.toUpperCase() */
    public String brand() {
        String str = Build.BRAND;
        return str == null ? "" : str.toUpperCase(Locale.getDefault());
    }

    public String deviceName() {
        BluetoothAdapter defaultAdapter = BluetoothAdapter.getDefaultAdapter();
        if (ContextCompat.checkSelfPermission(this.context, "android.permission.BLUETOOTH_CONNECT") == 0 
                && defaultAdapter != null) {
            String name = defaultAdapter.getName();
            if (name != null && !name.trim().isEmpty()) {
                return name;
            }
        }
        return "WATCH";
    }

    public String appVersionCodeString() {
        // Must match official BYD Watch app versionCode (341 = v3.4.1, confirmed via HTTP Toolkit 2026-07-21)
        return "341";
    }

    /**
     * MD5 hex string (uppercase, zero-padded), matching u.c(str) in the watch app.
     */
    private static String md5Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                String hex = Integer.toHexString(b & 0xFF);
                if (hex.length() == 1) {
                    hex = "0" + hex;
                }
                sb.append(hex.toUpperCase(Locale.getDefault()));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("MD5 failed", e);
        }
    }
}

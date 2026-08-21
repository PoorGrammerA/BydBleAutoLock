package com.poorgrammera.bydautolock.storage;

import android.content.Context;
import android.content.SharedPreferences;

public class StorageManager {

    private static final String PREFS_NAME = "BYDAutoLockPrefs";

    // Auth Keys
    private static final String KEY_USERNAME = "byd_username";
    private static final String KEY_PASSWORD = "byd_password";
    private static final String KEY_PIN = "byd_pin";
    private static final String KEY_REGION = "byd_region";
    private static final String KEY_USER_ID = "byd_user_id";
    private static final String KEY_SIGN_TOKEN = "byd_sign_token";
    private static final String KEY_ENCRY_TOKEN = "byd_encry_token";
    private static final String KEY_VINS = "byd_vins";
    private static final String KEY_SELECTED_VIN = "byd_selected_vin";
    private static final String KEY_HAS_CREDENTIALS = "has_credentials";

    // Bluetooth Keys
    private static final String KEY_DEVICE_MAC = "bt_device_mac";
    private static final String KEY_DEVICE_NAME = "bt_device_name";
    private static final String KEY_BLE_DK = "bt_ble_dk";
    private static final String KEY_BLE_PASSWORD = "bt_ble_password";

    // Watch Auth Token Keys
    private static final String KEY_WATCH_ENCRY_TOKEN = "watch_encry_token";
    private static final String KEY_WATCH_SIGN_TOKEN = "watch_sign_token";
    private static final String KEY_WATCH_CONTROL_PWD = "watch_control_pwd";
    private static final String KEY_WATCH_IDENTIFIER = "watch_identifier";
    private static final String KEY_WATCH_USER_TYPE = "watch_user_type";
    private static final String KEY_WATCH_QR_UUID = "watch_qr_uuid";
    private static final String KEY_BLE_MAC_ADDRESS = "ble_mac_address";
    private static final String KEY_BLE_KEY_NO = "ble_key_no";
    private static final String KEY_BLE_AUTH_PROTOCOL = "ble_auth_protocol";
    private static final String KEY_WATCH_VIN = "watch_vin";
    private static final String KEY_WATCH_VEHICLE_INFO_JSON = "watch_vehicle_info_json";
    private static final String KEY_EXPORTED_QR_URI = "exported_qr_uri";

    // Threshold Keys
    private static final String KEY_UNLOCK_RSSI = "unlock_rssi_threshold";
    private static final String KEY_LOCK_RSSI = "lock_rssi_threshold";
    private static final String KEY_RSSI_ALPHA = "rssi_smoothing_alpha";
    private static final int MIN_RSSI_THRESHOLD_DBM = -89;
    private static final int MAX_RSSI_THRESHOLD_DBM = -30;

    // Control Switches
    private static final String KEY_SERVICE_ENABLED = "service_enabled";
    private static final String KEY_AUTO_AC_ON_UNLOCK = "auto_ac_on_unlock";
    private static final String KEY_AUTO_AC_OFF_ON_LOCK = "auto_ac_off_on_lock";
    private static final String KEY_AC_TARGET_TEMP = "ac_target_temp";
    private static final String KEY_AC_WIND_LEVEL = "ac_wind_level";
    private static final String KEY_AC_CYCLE_MODE = "ac_cycle_mode";
    private static final String KEY_BLE_SCAN_MODE = "ble_scan_mode";
    private static final String KEY_GEOFENCING_ENABLED = "geofencing_enabled";
    private static final String KEY_AUTO_UNLOCK_ON_APPROACH = "auto_unlock_on_approach";
    private static final String KEY_AUTO_LOCK_ON_DEPARTURE = "auto_lock_on_departure";
    private static final String KEY_DEBUG_LOGGING_ENABLED = "debug_logging_enabled";
    private static final String KEY_DEVELOPER_MODE_ENABLED = "developer_mode_enabled";
    private static final String KEY_USE_BLE_KEY = "use_ble_key";

    // Location Keys
    private static final String KEY_LAST_VEHICLE_LAT = "last_vehicle_lat";
    private static final String KEY_LAST_VEHICLE_LNG = "last_vehicle_lng";
    private static final String KEY_LAST_VEHICLE_TIME = "last_vehicle_time";
    private static final String KEY_LAST_VEHICLE_SOURCE = "last_vehicle_source";

    // Watch Step Debug Logs
    private static final String KEY_WATCH_STEP_LOG_PREFIX = "watch_step_log_";

    private final SharedPreferences prefs;

    public StorageManager(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private String encrypt(String value) {
        return CryptoHelper.encrypt(value);
    }

    private String decrypt(String value) {
        if (value == null) return null;
        String decrypted = CryptoHelper.decrypt(value);
        return decrypted != null ? decrypted : value;
    }

    public String getUsername() {
        return decrypt(prefs.getString(KEY_USERNAME, null));
    }

    public void setUsername(String value) {
        prefs.edit().putString(KEY_USERNAME, encrypt(value)).apply();
    }

    public String getPassword() {
        return decrypt(prefs.getString(KEY_PASSWORD, null));
    }

    public void setPassword(String value) {
        prefs.edit().putString(KEY_PASSWORD, encrypt(value)).apply();
    }

    public String getPin() {
        return decrypt(prefs.getString(KEY_PIN, null));
    }

    public void setPin(String value) {
        prefs.edit().putString(KEY_PIN, encrypt(value)).apply();
    }

    public String getRegion() {
        return prefs.getString(KEY_REGION, "KR");
    }

    /** True once the user or the first-run regional suggestion has selected a server region. */
    public boolean hasRegion() {
        return prefs.contains(KEY_REGION);
    }

    public void setRegion(String value) {
        prefs.edit().putString(KEY_REGION, value).apply();
    }

    public String getUserId() {
        return decrypt(prefs.getString(KEY_USER_ID, null));
    }

    public void setUserId(String value) {
        prefs.edit().putString(KEY_USER_ID, encrypt(value)).apply();
    }

    public String getSignToken() {
        return decrypt(prefs.getString(KEY_SIGN_TOKEN, null));
    }

    public void setSignToken(String value) {
        prefs.edit().putString(KEY_SIGN_TOKEN, encrypt(value)).apply();
    }

    public String getEncryToken() {
        return decrypt(prefs.getString(KEY_ENCRY_TOKEN, null));
    }

    public void setEncryToken(String value) {
        prefs.edit().putString(KEY_ENCRY_TOKEN, encrypt(value)).apply();
    }

    public String getVins() {
        return decrypt(prefs.getString(KEY_VINS, null));
    }

    public void setVins(String value) {
        prefs.edit().putString(KEY_VINS, encrypt(value)).apply();
    }

    public String getSelectedVin() {
        return decrypt(prefs.getString(KEY_SELECTED_VIN, null));
    }

    public void setSelectedVin(String value) {
        prefs.edit().putString(KEY_SELECTED_VIN, encrypt(value)).apply();
    }

    public boolean hasCredentials() {
        return prefs.getBoolean(KEY_HAS_CREDENTIALS, false);
    }

    public void setHasCredentials(boolean value) {
        prefs.edit().putBoolean(KEY_HAS_CREDENTIALS, value).apply();
    }

    public String getDeviceMac() {
        return prefs.getString(KEY_DEVICE_MAC, null);
    }

    public void setDeviceMac(String value) {
        prefs.edit().putString(KEY_DEVICE_MAC, value).apply();
    }

    public String getDeviceName() {
        return prefs.getString(KEY_DEVICE_NAME, null);
    }

    public void setDeviceName(String value) {
        prefs.edit().putString(KEY_DEVICE_NAME, value).apply();
    }

    public int getUnlockRssi() {
        return clampRssiThreshold(prefs.getInt(KEY_UNLOCK_RSSI, -74));
    }

    public void setUnlockRssi(int value) {
        prefs.edit().putInt(KEY_UNLOCK_RSSI, clampRssiThreshold(value)).apply();
    }

    public int getLockRssi() {
        return clampRssiThreshold(prefs.getInt(KEY_LOCK_RSSI, -85));
    }

    public void setLockRssi(int value) {
        prefs.edit().putInt(KEY_LOCK_RSSI, clampRssiThreshold(value)).apply();
    }

    private int clampRssiThreshold(int value) {
        return Math.max(MIN_RSSI_THRESHOLD_DBM, Math.min(MAX_RSSI_THRESHOLD_DBM, value));
    }

    public float getRssiAlpha() {
        return prefs.getFloat(KEY_RSSI_ALPHA, 0.25f);
    }

    public void setRssiAlpha(float value) {
        prefs.edit().putFloat(KEY_RSSI_ALPHA, value).apply();
    }

    public boolean isServiceEnabled() {
        return prefs.getBoolean(KEY_SERVICE_ENABLED, true);
    }

    public void setServiceEnabled(boolean value) {
        prefs.edit().putBoolean(KEY_SERVICE_ENABLED, value).apply();
    }

    public boolean isAutoAcOnUnlock() {
        return prefs.getBoolean(KEY_AUTO_AC_ON_UNLOCK, false);
    }

    public void setAutoAcOnUnlock(boolean value) {
        prefs.edit().putBoolean(KEY_AUTO_AC_ON_UNLOCK, value).apply();
    }

    public boolean isAutoAcOffOnLock() {
        return prefs.getBoolean(KEY_AUTO_AC_OFF_ON_LOCK, false);
    }

    public void setAutoAcOffOnLock(boolean value) {
        prefs.edit().putBoolean(KEY_AUTO_AC_OFF_ON_LOCK, value).apply();
    }

    public float getAcTargetTemp() {
        return prefs.getFloat(KEY_AC_TARGET_TEMP, 22.0f);
    }

    public void setAcTargetTemp(float value) {
        prefs.edit().putFloat(KEY_AC_TARGET_TEMP, value).apply();
    }

    public int getAcWindLevel() {
        return prefs.getInt(KEY_AC_WIND_LEVEL, 0); // Default to 0 (Auto)
    }

    public void setAcWindLevel(int value) {
        prefs.edit().putInt(KEY_AC_WIND_LEVEL, value).apply();
    }

    public int getAcCycleMode() {
        return prefs.getInt(KEY_AC_CYCLE_MODE, 2); // Default to 2 (recirculate).
    }

    public void setAcCycleMode(int value) {
        prefs.edit().putInt(KEY_AC_CYCLE_MODE, value).apply();
    }

    // 0 = Balanced, 1 = Low Latency (default), 2 = Low Power
    public int getBleScanMode() {
        return prefs.getInt(KEY_BLE_SCAN_MODE, 1);
    }

    public void setBleScanMode(int value) {
        prefs.edit().putInt(KEY_BLE_SCAN_MODE, value).apply();
    }

    public double getLastVehicleLat() {
        long bits = prefs.getLong(KEY_LAST_VEHICLE_LAT, Double.doubleToRawLongBits(0.0));
        return Double.longBitsToDouble(bits);
    }

    public void setLastVehicleLat(double value) {
        prefs.edit().putLong(KEY_LAST_VEHICLE_LAT, Double.doubleToRawLongBits(value)).apply();
    }

    public double getLastVehicleLng() {
        long bits = prefs.getLong(KEY_LAST_VEHICLE_LNG, Double.doubleToRawLongBits(0.0));
        return Double.longBitsToDouble(bits);
    }

    public void setLastVehicleLng(double value) {
        prefs.edit().putLong(KEY_LAST_VEHICLE_LNG, Double.doubleToRawLongBits(value)).apply();
    }

    public long getLastVehicleTime() {
        return prefs.getLong(KEY_LAST_VEHICLE_TIME, 0L);
    }

    public void setLastVehicleTime(long value) {
        prefs.edit().putLong(KEY_LAST_VEHICLE_TIME, value).apply();
    }

    public String getLastVehicleSource() {
        return prefs.getString(KEY_LAST_VEHICLE_SOURCE, null);
    }

    public void setLastVehicleSource(String value) {
        prefs.edit().putString(KEY_LAST_VEHICLE_SOURCE, value).apply();
    }

    public boolean isGeofencingEnabled() {
        return prefs.getBoolean(KEY_GEOFENCING_ENABLED, false);
    }

    public void setGeofencingEnabled(boolean value) {
        prefs.edit().putBoolean(KEY_GEOFENCING_ENABLED, value).apply();
    }

    public boolean isAutoUnlockOnApproach() {
        return prefs.getBoolean(KEY_AUTO_UNLOCK_ON_APPROACH, true);
    }

    public void setAutoUnlockOnApproach(boolean value) {
        prefs.edit().putBoolean(KEY_AUTO_UNLOCK_ON_APPROACH, value).apply();
    }

    public boolean isAutoLockOnDeparture() {
        return prefs.getBoolean(KEY_AUTO_LOCK_ON_DEPARTURE, true);
    }

    public void setAutoLockOnDeparture(boolean value) {
        prefs.edit().putBoolean(KEY_AUTO_LOCK_ON_DEPARTURE, value).apply();
    }

    public boolean isDebugLoggingEnabled() {
        return prefs.getBoolean(KEY_DEBUG_LOGGING_ENABLED, false);
    }

    public void setDebugLoggingEnabled(boolean value) {
        prefs.edit().putBoolean(KEY_DEBUG_LOGGING_ENABLED, value).apply();
    }

    public boolean isDeveloperModeEnabled() {
        return prefs.getBoolean(KEY_DEVELOPER_MODE_ENABLED, false);
    }

    public void setDeveloperModeEnabled(boolean value) {
        prefs.edit().putBoolean(KEY_DEVELOPER_MODE_ENABLED, value).apply();
    }

    public boolean isUseBleKey() {
        return prefs.getBoolean(KEY_USE_BLE_KEY, true);
    }

    public void setUseBleKey(boolean value) {
        prefs.edit().putBoolean(KEY_USE_BLE_KEY, value).apply();
    }

    public void clearAuth() {
        prefs.edit()
                .remove(KEY_USERNAME)
                .remove(KEY_PASSWORD)
                .remove(KEY_PIN)
                .remove(KEY_USER_ID)
                .remove(KEY_SIGN_TOKEN)
                .remove(KEY_ENCRY_TOKEN)
                .remove(KEY_VINS)
                .remove(KEY_SELECTED_VIN)
                .putBoolean(KEY_HAS_CREDENTIALS, false)
                .apply();
    }

    public String getBleDk() {
        return decrypt(prefs.getString(KEY_BLE_DK, null));
    }

    public void setBleDk(String value) {
        prefs.edit().putString(KEY_BLE_DK, encrypt(value)).apply();
    }

    public String getBlePassword() {
        return decrypt(prefs.getString(KEY_BLE_PASSWORD, null));
    }

    public void setBlePassword(String value) {
        prefs.edit().putString(KEY_BLE_PASSWORD, encrypt(value)).apply();
    }

    // ── Watch Auth Token accessors ──────────────────────────────

    public String getWatchQrUuid() {
        return decrypt(prefs.getString(KEY_WATCH_QR_UUID, null));
    }

    public void setWatchQrUuid(String value) {
        prefs.edit().putString(KEY_WATCH_QR_UUID, encrypt(value)).apply();
    }

    public String getExportedQrUri() {
        return prefs.getString(KEY_EXPORTED_QR_URI, null);
    }

    public void setExportedQrUri(String value) {
        SharedPreferences.Editor editor = prefs.edit();
        if (value == null || value.trim().isEmpty()) editor.remove(KEY_EXPORTED_QR_URI);
        else editor.putString(KEY_EXPORTED_QR_URI, value);
        editor.apply();
    }

    public String getWatchEncryToken() {
        return decrypt(prefs.getString(KEY_WATCH_ENCRY_TOKEN, null));
    }

    public void setWatchEncryToken(String value) {
        prefs.edit().putString(KEY_WATCH_ENCRY_TOKEN, encrypt(value)).apply();
    }

    public String getWatchSignToken() {
        return decrypt(prefs.getString(KEY_WATCH_SIGN_TOKEN, null));
    }

    public void setWatchSignToken(String value) {
        prefs.edit().putString(KEY_WATCH_SIGN_TOKEN, encrypt(value)).apply();
    }

    public String getWatchControlPwd() {
        return decrypt(prefs.getString(KEY_WATCH_CONTROL_PWD, null));
    }

    public void setWatchControlPwd(String value) {
        prefs.edit().putString(KEY_WATCH_CONTROL_PWD, encrypt(value)).apply();
    }

    public String getWatchIdentifier() {
        return decrypt(prefs.getString(KEY_WATCH_IDENTIFIER, null));
    }

    public void setWatchIdentifier(String value) {
        prefs.edit().putString(KEY_WATCH_IDENTIFIER, encrypt(value)).apply();
    }

    public String getWatchUserType() {
        return prefs.getString(KEY_WATCH_USER_TYPE, null);
    }

    public void setWatchUserType(String value) {
        prefs.edit().putString(KEY_WATCH_USER_TYPE, value).apply();
    }

    public String getBleMacAddress() {
        return prefs.getString(KEY_BLE_MAC_ADDRESS, null);
    }

    public void setBleMacAddress(String value) {
        prefs.edit().putString(KEY_BLE_MAC_ADDRESS, value).apply();
    }

    public long getBleKeyNo() {
        return prefs.getLong(KEY_BLE_KEY_NO, -1);
    }

    public void setBleKeyNo(long value) {
        prefs.edit().putLong(KEY_BLE_KEY_NO, value).apply();
    }

    public int getBleAuthProtocol() {
        return prefs.getInt(KEY_BLE_AUTH_PROTOCOL, 0);
    }

    public void setBleAuthProtocol(int value) {
        prefs.edit().putInt(KEY_BLE_AUTH_PROTOCOL, value).apply();
    }

    public String getWatchVin() {
        return decrypt(prefs.getString(KEY_WATCH_VIN, null));
    }

    public void setWatchVin(String value) {
        prefs.edit().putString(KEY_WATCH_VIN, encrypt(value)).apply();
    }

    /** Last successful watch/login/gain/vehicle response.  It includes vehicle capabilities. */
    public String getWatchVehicleInfoJson() {
        return decrypt(prefs.getString(KEY_WATCH_VEHICLE_INFO_JSON, null));
    }

    public void setWatchVehicleInfoJson(String value) {
        prefs.edit().putString(KEY_WATCH_VEHICLE_INFO_JSON, encrypt(value)).apply();
    }

    public boolean hasWatchToken() {
        return getWatchEncryToken() != null && getWatchSignToken() != null;
    }

    public boolean hasBleKey() {
        // A digital key alone can authenticate BLE; the MAC can be configured separately.
        String dk = getBleDk();
        return dk != null && !dk.trim().isEmpty();
    }

    public void clearWatchAuth() {
        prefs.edit()
                .remove(KEY_WATCH_QR_UUID)
                .remove(KEY_WATCH_ENCRY_TOKEN)
                .remove(KEY_WATCH_SIGN_TOKEN)
                .remove(KEY_WATCH_CONTROL_PWD)
                .remove(KEY_WATCH_IDENTIFIER)
                .remove(KEY_WATCH_USER_TYPE)
                .remove(KEY_BLE_DK)
                .remove(KEY_BLE_PASSWORD)
                .remove(KEY_BLE_MAC_ADDRESS)
                .remove(KEY_BLE_KEY_NO)
                .remove(KEY_BLE_AUTH_PROTOCOL)
                .remove(KEY_WATCH_VIN)
                .remove(KEY_WATCH_VEHICLE_INFO_JSON)
                .apply();
    }

    /**
     * Removes every credential that can authenticate this device as a Bluetooth key.
     * The next launch will therefore enter the QR binding flow again.
     */
    public void clearBluetoothKeyAndWatchAuth() {
        clearWatchAuth();
        prefs.edit()
                .remove(KEY_DEVICE_MAC)
                .remove(KEY_DEVICE_NAME)
                .remove(KEY_SELECTED_VIN)
                .apply();
    }

    public String getWatchStepLog(int step) {
        return prefs.getString(KEY_WATCH_STEP_LOG_PREFIX + step, "");
    }

    public void setWatchStepLog(int step, String log) {
        prefs.edit().putString(KEY_WATCH_STEP_LOG_PREFIX + step, log).apply();
    }
}

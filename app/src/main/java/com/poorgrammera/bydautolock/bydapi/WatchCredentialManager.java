package com.poorgrammera.bydautolock.bydapi;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.poorgrammera.bydautolock.model.QrCodeState;
import com.poorgrammera.bydautolock.model.RemoteControlStartResponse;
import com.poorgrammera.bydautolock.model.TokenInfoBean;
import com.poorgrammera.bydautolock.model.WatchBlueToothKeyStatInfo;
import com.poorgrammera.bydautolock.storage.StorageManager;

/** Shared persistence and refresh steps for the Watch QR / BLE-key credentials. */
public final class WatchCredentialManager {
    private WatchCredentialManager() { }

    public interface Callback {
        void onSuccess(TokenInfoBean token);
        void onError(String message, Throwable error);
    }

    public static TokenInfoBean restoreToken(StorageManager storage) {
        if (!storage.hasWatchToken()
                || TextUtils.isEmpty(storage.getWatchIdentifier())
                || TextUtils.isEmpty(storage.getWatchUserType())
                || TextUtils.isEmpty(storage.getWatchVin())) {
            return null;
        }
        TokenInfoBean token = new TokenInfoBean();
        token.setEncryToken(storage.getWatchEncryToken());
        token.setSignToken(storage.getWatchSignToken());
        token.setControlPwd(storage.getWatchControlPwd());
        token.setIdentifier(storage.getWatchIdentifier());
        token.setUserType(storage.getWatchUserType());
        token.setVin(storage.getWatchVin());
        return token;
    }

    public static void saveToken(StorageManager storage, TokenInfoBean token) {
        if (token == null) return;
        storage.setWatchEncryToken(token.getEncryToken());
        storage.setWatchSignToken(token.getSignToken());
        storage.setWatchControlPwd(token.getControlPwd());
        storage.setWatchIdentifier(token.getIdentifier());
        storage.setWatchUserType(token.getUserType());
        storage.setWatchVin(token.getVin());
    }

    public static void saveVehicle(StorageManager storage, JsonObject vehicle) {
        if (vehicle == null) return;
        storage.setWatchVehicleInfoJson(vehicle.toString());
        JsonObject dto = objectAt(vehicle, "watchBluetoothDto");
        if (dto == null) dto = objectAt(objectAt(vehicle, "cfVechicle"), "watchBluetoothDto");
        if (dto == null) return;
        String mac = nullableStringAt(dto, "macAddress");
        if (!TextUtils.isEmpty(mac)) {
            storage.setBleMacAddress(mac);
            storage.setDeviceMac(mac);
        }
        JsonObject bluetoothInfo = objectAt(dto, "watchBluetoothInfo");
        if (bluetoothInfo != null) {
            String dkey = nullableStringAt(bluetoothInfo, "dkey");
            if (!TextUtils.isEmpty(dkey)) storage.setBleDk(dkey);
            if (bluetoothInfo.has("keyNumber")) storage.setBleKeyNo(bluetoothInfo.get("keyNumber").getAsLong());
        }
    }

    public static void saveBluetoothKey(StorageManager storage, WatchBlueToothKeyStatInfo key) {
        if (key == null) return;
        // Some regional APIs return dkey only from gain/vehicle. Do not erase that
        // value when gain/bluetooth omits fields and only supplies supplemental data.
        if (!TextUtils.isEmpty(key.getDk())) storage.setBleDk(key.getDk());
        if (!TextUtils.isEmpty(key.getBlueToothPassword())) storage.setBlePassword(key.getBlueToothPassword());
        if (key.getEmpowerBluetoothKeyNo() != null) storage.setBleKeyNo(key.getEmpowerBluetoothKeyNo());
        if (key.getAuthBluetoothProtocol() != null) storage.setBleAuthProtocol(key.getAuthBluetoothProtocol());
        if (!TextUtils.isEmpty(key.getBluetoothMacAddress())) {
            storage.setBleMacAddress(key.getBluetoothMacAddress());
            storage.setDeviceMac(key.getBluetoothMacAddress());
        }
        if (!TextUtils.isEmpty(key.getVin())) {
            storage.setWatchVin(key.getVin());
            storage.setSelectedVin(key.getVin());
        }
    }

    /** Runs Gain Token, Gain Vehicle and Gain Bluetooth Key using the last QR UUID. */
    public static void refreshFromSavedQr(Context context, Callback callback) {
        StorageManager storage = new StorageManager(context);
        String uuid = storage.getWatchQrUuid();
        if (TextUtils.isEmpty(uuid)) {
            callback.onError("No saved QR binding is available.", null);
            return;
        }
        BydWatchKeyService service = new BydWatchKeyService(context, BydConfig.fromRegion(storage.getRegion()));
        service.getToken(uuid, null, new BydWatchKeyService.Callback<TokenInfoBean>() {
            @Override public void onSuccess(TokenInfoBean token) {
                saveToken(storage, token);
                service.getVehicleConfig(token, new BydWatchKeyService.Callback<JsonObject>() {
                    @Override public void onSuccess(JsonObject vehicle) {
                        saveVehicle(storage, vehicle);
                        service.getWatchBlueInfo(token, new BydWatchKeyService.Callback<WatchBlueToothKeyStatInfo>() {
                            @Override public void onSuccess(WatchBlueToothKeyStatInfo key) {
                                // gain/bluetooth may omit dkey in Korea because gain/vehicle's
                                // watchBluetoothDto.watchBluetoothInfo already supplied it.
                                saveBluetoothKey(storage, key);
                                if (TextUtils.isEmpty(storage.getBleDk())) {
                                    callback.onError("Gain Vehicle and Gain Bluetooth Key returned no dkey.", null);
                                    return;
                                }
                                callback.onSuccess(token);
                            }
                            @Override public void onError(String message, Throwable error) { callback.onError(message, error); }
                        });
                    }
                    @Override public void onError(String message, Throwable error) { callback.onError(message, error); }
                });
            }
            @Override public void onError(String message, Throwable error) { callback.onError(message, error); }
        });
    }

    /** A successful real-time request/result pair proves that the refreshed REST token is usable. */
    public static void verifyRealtime(Context context, TokenInfoBean token, Callback callback) {
        StorageManager storage = new StorageManager(context);
        BydWatchKeyService service = new BydWatchKeyService(context, BydConfig.fromRegion(storage.getRegion()));
        service.requestVehicleRealtime(token, new BydWatchKeyService.Callback<RemoteControlStartResponse>() {
            @Override public void onSuccess(RemoteControlStartResponse result) {
                String serial = result == null ? null : result.getRequestSerial();
                if (TextUtils.isEmpty(serial)) {
                    callback.onError("Vehicle status request returned no serial.", null);
                    return;
                }
                new Handler(Looper.getMainLooper()).postDelayed(() -> service.getVehicleRealtimeResult(token, serial,
                        new BydWatchKeyService.Callback<JsonObject>() {
                            @Override public void onSuccess(JsonObject ignored) { callback.onSuccess(token); }
                            @Override public void onError(String message, Throwable error) { callback.onError(message, error); }
                        }), 2000);
            }
            @Override public void onError(String message, Throwable error) { callback.onError(message, error); }
        });
    }

    public static boolean isTokenExpiredError(String message) {
        return message != null && (message.contains("(1002,")
                || message.contains("(1010,")
                || message.contains("(9012,")
                || message.contains("(9013,"));
    }

    public static String vehicleField(StorageManager storage, String field) {
        String raw = storage.getWatchVehicleInfoJson();
        if (TextUtils.isEmpty(raw)) return "-";
        try { return stringAt(JsonParser.parseString(raw).getAsJsonObject(), field); }
        catch (Exception ignored) { return "-"; }
    }

    private static JsonObject objectAt(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonObject() ? object.getAsJsonObject(key) : null;
    }

    private static String stringAt(JsonObject object, String key) {
        String value = nullableStringAt(object, key);
        return value == null ? "-" : value;
    }

    private static String nullableStringAt(JsonObject object, String key) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull()
                    ? object.get(key).getAsString() : null;
        } catch (Exception ignored) { return null; }
    }
}

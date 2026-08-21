package com.poorgrammera.bydautolock.bydapi;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import com.poorgrammera.bydautolock.model.QrCodeInfo;
import com.poorgrammera.bydautolock.model.QrCodeState;
import com.poorgrammera.bydautolock.model.RemoteControlResult;
import com.poorgrammera.bydautolock.model.RemoteControlStartResponse;
import com.poorgrammera.bydautolock.model.TokenInfoBean;
import com.poorgrammera.bydautolock.model.TokenResponse;
import com.poorgrammera.bydautolock.model.WatchBlueToothKeyStatInfo;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * BYD Watch Key Service — handles Watch API calls for BLE key flow.
 * 
 * CRITICAL: The Watch API does NOT use Bangcle encryption for request/response wrapping!
 * (Confirmed via HTTP Toolkit analysis of actual Galaxy Watch traffic)
 * 
 * Protocol:
 * - Request: Send plain JSON directly as HTTP body (NOT wrapped in {"request": "..."})
 * - Response: Server returns {"response": "{\"code\":\"0\",...,\"respondData\":\"<aes_hex>\"}" }
 * - Only the inner encryData/respondData fields use AES-128-CBC encryption with MD5(key) 
 */
public class BydWatchKeyService {
    private static final String TAG = "BydWatchKeyService";
    
    // Spoofed Galaxy Watch device info
    private static final String WATCH_MODEL = "SM-R925N";
    private static final String WATCH_BRAND = "SAMSUNG";
    
    private final OkHttpClient httpClient;
    private final BydConfig config;
    private final DeviceInfoProvider deviceInfo;
    private final Gson gson;

    // Time difference (serverTime - localTime) for timestamp calibration
    private long timeDifference = 0;

    public BydWatchKeyService(Context context, BydConfig config) {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
        this.config = config;
        this.deviceInfo = new DeviceInfoProvider(context);
        this.gson = new Gson();
    }

    public interface Callback<T> {
        void onSuccess(T result);
        void onError(String msg, Throwable t);
    }

    // ── Public API methods ─────────────────────────────────────────────

    public void getServerCurrentTime(Callback<String> callback) {
        try {
            String countryCode = getCountryCode();
            String outerJson = buildUnLoginParamsJson(null, 5, null);
            String decryptKey = CryptoUtils.md5Hex(countryCode).toLowerCase();
            executeRequest("watch/login/getServerCurrentTime", outerJson, decryptKey, String.class, callback);
        } catch (Throwable t) {
            Log.e(TAG, "getServerCurrentTime error", t);
            callback.onError("Server time synchronization failed: " + t.getMessage(), t);
        }
    }

    public void createQrCode(Callback<QrCodeInfo> callback) {
        try {
            String countryCode = getCountryCode();
            String outerJson = buildUnLoginParamsJson(null, null, null);
            String decryptKey = CryptoUtils.md5Hex(countryCode).toLowerCase();
            executeRequest("watch/login/create/qrcode", outerJson, decryptKey, QrCodeInfo.class, callback);
        } catch (Throwable t) {
            Log.e(TAG, "createQrCode error", t);
            callback.onError("QR creation failed: " + t.getMessage(), t);
        }
    }

    public void getQrCodeStatus(String uuid, Callback<QrCodeState> callback) {
        try {
            String countryCode = getCountryCode();
            String outerJson = buildUnLoginParamsJson(null, 1, uuid);
            String decryptKey = CryptoUtils.md5Hex(countryCode).toLowerCase();
            executeRequest("watch/login/check/qrcode", outerJson, decryptKey, QrCodeState.class, callback);
        } catch (Throwable t) {
            Log.e(TAG, "getQrCodeStatus error", t);
            callback.onError("QR status request failed: " + t.getMessage(), t);
        }
    }

    /**
     * Step 4: gain/token — exchange uuid for tokens after QR scan confirmed (status==2).
     * Inner params: {timeStamp, timeZone:"Asia/Seoul", uuid}
     * No appChannel needed (confirmed via HTTP traffic capture).
     */
    public void getToken(String uuid, String appChannel, Callback<TokenInfoBean> callback) {
        try {
            String countryCode = getCountryCode();
            // appChannel is ignored — real traffic doesn't include it
            String outerJson = buildUnLoginParamsJson(null, 2, uuid);
            String decryptKey = CryptoUtils.md5Hex(countryCode).toLowerCase();
            // Response is wrapped: {"watchTokenInfo":{...}, "controlPwd":"..."}
            executeRequest("watch/login/gain/token", outerJson, decryptKey, TokenResponse.class, new Callback<TokenResponse>() {
                @Override
                public void onSuccess(TokenResponse result) {
                    if (result != null && result.getWatchTokenInfo() != null) {
                        TokenInfoBean tokenInfo = result.getWatchTokenInfo();
                        tokenInfo.setControlPwd(result.getControlPwd());
                        Log.d(TAG, "Token acquired: userId=" + tokenInfo.getIdentifier() + ", vin=" + tokenInfo.getVin());
                        callback.onSuccess(tokenInfo);
                    } else {
                        callback.onError("watchTokenInfo is missing from the token response", null);
                    }
                }
                @Override
                public void onError(String msg, Throwable t) {
                    callback.onError(msg, t);
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "getToken error", t);
            callback.onError("Token exchange failed: " + t.getMessage(), t);
        }
    }

    /**
     * Step 5: gain/vehicle — get vehicle config including BLE info.
     * Inner params: {appVersion:"2", deviceType:"0", random, timeStamp, vin}
     * Uses logged-in request format (identifier=userId, userType="0")
     */
    public void getVehicleConfig(TokenInfoBean tokenInfo, Callback<JsonObject> callback) {
        try {
            TreeMap<String, String> rawParams = new TreeMap<>();
            rawParams.put("appVersion", "2");
            rawParams.put("vin", tokenInfo.getVin());
            String outerJson = buildLoginParamsJson(rawParams, tokenInfo);
            String decryptKey = CryptoUtils.md5Hex(tokenInfo.getEncryToken()).toLowerCase();
            executeRequest("watch/login/gain/vehicle", outerJson, decryptKey, JsonObject.class, callback);
        } catch (Throwable t) {
            Log.e(TAG, "getVehicleConfig error", t);
            callback.onError("Vehicle information request failed: " + t.getMessage(), t);
        }
    }

    /**
     * Step 6: gain/bluetooth — get BLE key for vehicle.
     * Inner params: {appVersion:"2", deviceType:"0", random, timeStamp, vin}
     * Uses logged-in request format (identifier=userId, userType="0")
     */
    public void getWatchBlueInfo(TokenInfoBean tokenInfo, Callback<WatchBlueToothKeyStatInfo> callback) {
        try {
            TreeMap<String, String> rawParams = new TreeMap<>();
            rawParams.put("appVersion", "2");
            rawParams.put("vin", tokenInfo.getVin());
            String outerJson = buildLoginParamsJson(rawParams, tokenInfo);
            String decryptKey = CryptoUtils.md5Hex(tokenInfo.getEncryToken()).toLowerCase();
            executeRequest("watch/login/gain/bluetooth", outerJson, decryptKey, WatchBlueToothKeyStatInfo.class, callback);
        } catch (Throwable t) {
            Log.e(TAG, "getWatchBlueInfo error", t);
            callback.onError("Bluetooth key request failed: " + t.getMessage(), t);
        }
    }

    /**
     * Update time difference for timestamp calibration.
     */
    public void updateTimeDifference(long serverTime) {
        this.timeDifference = serverTime - System.currentTimeMillis();
        Log.d(TAG, "Time difference updated: " + timeDifference + "ms");
    }

    public long getTimeDifference() {
        return timeDifference;
    }

    // ── HTTP execution ─────────────────────────────────────────────────
    // Watch API protocol: plain JSON request body, plain JSON response.
    // NO Bangcle wrapping! (confirmed via Galaxy Watch HTTP traffic capture)

    /**
     * Sends a Watch remote-control command to BYD's server. This is independent from BLE.
     * The accepted request must be completed by querying getRemoteControlResult.
     */
    public void sendRemoteControl(TokenInfoBean tokenInfo, String commandType,
                                  Callback<RemoteControlStartResponse> callback) {
        sendRemoteControl(tokenInfo, commandType, null, callback);
    }

    /**
     * Sends a Watch remote-control command, optionally with the JSON-encoded control parameters
     * used by commands such as OPENAIR. The Watch server expects controlParamsMap as a string
     * inside the encrypted request payload.
     */
    public void sendRemoteControl(TokenInfoBean tokenInfo, String commandType, String controlParamsMap,
                                  Callback<RemoteControlStartResponse> callback) {
        try {
            if (tokenInfo == null || isBlank(tokenInfo.getVin()) || isBlank(tokenInfo.getControlPwd())) {
                callback.onError("Remote control requires a fresh Watch token with controlPwd. Run Gain Token again.", null);
                return;
            }
            TreeMap<String, String> rawParams = new TreeMap<>();
            rawParams.put("vin", tokenInfo.getVin());
            rawParams.put("commandType", commandType);
            rawParams.put("commandPwd", tokenInfo.getControlPwd());
            if (!isBlank(controlParamsMap)) {
                rawParams.put("controlParamsMap", controlParamsMap);
            }
            String outerJson = buildLoginParamsJson(rawParams, tokenInfo);
            String decryptKey = CryptoUtils.md5Hex(tokenInfo.getEncryToken()).toLowerCase();
            executeRequest("watch/control/vehicleControl", outerJson, decryptKey,
                    RemoteControlStartResponse.class, callback);
        } catch (Throwable t) {
            Log.e(TAG, "sendRemoteControl error", t);
            callback.onError("Remote-control request creation failed: " + t.getMessage(), t);
        }
    }

    /** Starts the Watch vehicle real-time status request. Query the matching result endpoint with its serial. */
    public void requestVehicleRealtime(TokenInfoBean tokenInfo, Callback<RemoteControlStartResponse> callback) {
        try {
            if (tokenInfo == null || isBlank(tokenInfo.getVin())) {
                callback.onError("Vehicle status request requires a Watch token with a VIN.", null);
                return;
            }
            TreeMap<String, String> rawParams = new TreeMap<>();
            rawParams.put("vin", tokenInfo.getVin());
            String outerJson = buildLoginParamsJson(rawParams, tokenInfo);
            String decryptKey = CryptoUtils.md5Hex(tokenInfo.getEncryToken()).toLowerCase();
            executeRequest("watch/vehicle/vehicleRealTimeRequest", outerJson, decryptKey,
                    RemoteControlStartResponse.class, callback);
        } catch (Throwable t) {
            Log.e(TAG, "requestVehicleRealtime error", t);
            callback.onError("Vehicle real-time request creation failed: " + t.getMessage(), t);
        }
    }

    /** Retrieves the decrypted data for a Watch vehicle real-time status request. */
    public void getVehicleRealtimeResult(TokenInfoBean tokenInfo, String requestSerial,
                                         Callback<JsonObject> callback) {
        try {
            if (tokenInfo == null || isBlank(tokenInfo.getVin()) || isBlank(requestSerial)) {
                callback.onError("Vehicle status result query is missing token, VIN, or request serial.", null);
                return;
            }
            TreeMap<String, String> rawParams = new TreeMap<>();
            rawParams.put("vin", tokenInfo.getVin());
            rawParams.put("requestSerial", requestSerial);
            String outerJson = buildLoginParamsJson(rawParams, tokenInfo);
            String decryptKey = CryptoUtils.md5Hex(tokenInfo.getEncryToken()).toLowerCase();
            executeRequest("watch/vehicle/vehicleRealTimeResult", outerJson, decryptKey,
                    JsonObject.class, callback);
        } catch (Throwable t) {
            Log.e(TAG, "getVehicleRealtimeResult error", t);
            callback.onError("Vehicle real-time result request creation failed: " + t.getMessage(), t);
        }
    }

    /** Reads current air-condition state through the endpoint declared by the original Watch app. */
    public void getAirConditionNow(TokenInfoBean tokenInfo, Callback<JsonObject> callback) {
        try {
            if (tokenInfo == null || isBlank(tokenInfo.getVin())) {
                callback.onError("Air-condition status request requires a Watch token with a VIN.", null);
                return;
            }
            TreeMap<String, String> rawParams = new TreeMap<>();
            rawParams.put("vin", tokenInfo.getVin());
            rawParams.put("airConditioningMode", "1");
            String outerJson = buildLoginParamsJson(rawParams, tokenInfo);
            String decryptKey = CryptoUtils.md5Hex(tokenInfo.getEncryToken()).toLowerCase();
            executeRequest("watch/control/getAirConditionNow", outerJson, decryptKey,
                    JsonObject.class, callback);
        } catch (Throwable t) {
            Log.e(TAG, "getAirConditionNow error", t);
            callback.onError("Air-condition status request creation failed: " + t.getMessage(), t);
        }
    }

    /** Queries the asynchronous result of a previously accepted remote-control command. */
    public void getRemoteControlResult(TokenInfoBean tokenInfo, String requestSerial, String commandType,
                                       Callback<RemoteControlResult> callback) {
        try {
            if (tokenInfo == null || isBlank(tokenInfo.getVin()) || isBlank(requestSerial)) {
                callback.onError("Remote-control result query is missing token, VIN, or request serial.", null);
                return;
            }
            TreeMap<String, String> rawParams = new TreeMap<>();
            rawParams.put("vin", tokenInfo.getVin());
            rawParams.put("requestSerial", requestSerial);
            rawParams.put("commandType", commandType);
            String outerJson = buildLoginParamsJson(rawParams, tokenInfo);
            String decryptKey = CryptoUtils.md5Hex(tokenInfo.getEncryToken()).toLowerCase();
            executeRequest("watch/control/vehicleControlResult", outerJson, decryptKey,
                    RemoteControlResult.class, callback);
        } catch (Throwable t) {
            Log.e(TAG, "getRemoteControlResult error", t);
            callback.onError("Remote-control result query creation failed: " + t.getMessage(), t);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private <T> void executeRequest(String endpoint, String outerJson, String decryptKey, Class<T> responseClass, Callback<T> callback) {
        try {
            // Send the outer JSON DIRECTLY as HTTP body (no Bangcle wrapping)
            RequestBody body = RequestBody.create(
                    outerJson,
                    MediaType.parse("application/json; charset=utf-8")
            );
            
            Request request = new Request.Builder()
                    .url(config.getBaseUrl() + "/" + endpoint)
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .header("User-Agent", "okhttp/4.12.0")
                    .post(body)
                    .build();
            
            Log.d(TAG, "[" + endpoint + "] Sending plain JSON request, length=" + outerJson.length());
            
            httpClient.newCall(request).enqueue(new okhttp3.Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    callback.onError("Network error", e);
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try {
                        String rawResp = response.body().string();
                        Log.d(TAG, "[" + endpoint + "] Response: " + rawResp.substring(0, Math.min(500, rawResp.length())));
                        JSONObject respJson = new JSONObject(rawResp);
                        
                        // Response format: {"response": "{\"code\":\"0\",...,\"respondData\":\"<aes_hex>\"}"}
                        String responseStr = respJson.optString("response");
                        if (responseStr.isEmpty()) {
                            responseStr = respJson.optString("respondData");
                        }
                        
                        if (responseStr.isEmpty()) {
                            callback.onError("Malformed server response (missing response field)", null);
                            return;
                        }
                        
                        // Parse the inner JSON (it's plain text, not encrypted!)
                        JSONObject envelopeJson = new JSONObject(responseStr);
                        String code = envelopeJson.optString("code", "-1");
                        String message = envelopeJson.optString("message", "");
                        
                        if (!"0".equals(code)) {
                            Log.e(TAG, "[" + endpoint + "] Server error: code=" + code + " message=" + message);
                            String classified = classifyErrorCode(code);
                            callback.onError("Server error: " + message + " (" + code + ", " + classified + ")", null);
                            return;
                        }
                        
                        // Extract and decrypt business data
                        String encryptedBusinessData = envelopeJson.optString("respondData");
                        if (encryptedBusinessData.isEmpty()) {
                            encryptedBusinessData = envelopeJson.optString("response");
                        }
                        
                        if (encryptedBusinessData.isEmpty()) {
                            callback.onSuccess(null);
                            return;
                        }
                        
                        String decryptedBusinessJson = CryptoUtils.aesDecryptUtf8(encryptedBusinessData, decryptKey);
                        logFullBusinessData(endpoint, decryptedBusinessJson);
                        
                        T result;
                        if (responseClass == String.class) {
                            String str = decryptedBusinessJson;
                            try {
                                JSONObject json = new JSONObject(str);
                                if (json.has("serverTime")) {
                                    str = String.valueOf(json.getLong("serverTime"));
                                }
                            } catch (Throwable ignored) {}
                            if (str.startsWith("\"") && str.endsWith("\"")) {
                                str = str.substring(1, str.length() - 1);
                            }
                            result = (T) str;
                        } else {
                            result = gson.fromJson(decryptedBusinessJson, responseClass);
                        }
                        callback.onSuccess(result);
                        
                    } catch (Throwable t) {
                        Log.e(TAG, "executeRequest response parsing error", t);
                        callback.onError("Response processing error: " + t.getMessage(), t);
                    }
                }
            });
            
        } catch (Throwable t) {
            Log.e(TAG, "executeRequest error", t);
            callback.onError("Request creation error: " + t.getMessage(), t);
        }
    }

    /** Debug build helper: Android truncates long individual Logcat lines, so split them safely. */
    private void logFullBusinessData(String endpoint, String data) {
        final int chunkSize = 3000;
        int chunks = Math.max(1, (data.length() + chunkSize - 1) / chunkSize);
        for (int index = 0; index < chunks; index++) {
            int start = index * chunkSize;
            int end = Math.min(data.length(), start + chunkSize);
            Log.d(TAG, "[" + endpoint + "] Decrypted business data (" + (index + 1) + "/" + chunks + "): "
                    + data.substring(start, end));
        }
    }

    // ── Request body building ──────────────────────────────────────────

    /**
     * Builds the WatchAccountRequest JSON for un-logged-in endpoints.
     * Exactly mirrors the official Galaxy Watch BYD app traffic (confirmed via HTTP Toolkit).
     * 
     * Protocol:
     * 1. Build inner params TreeMap (varies by endpoint)
     * 2. Encrypt inner params JSON → encryData using MD5(countryCode) as AES key
     * 3. Add outer fields for sign computation
     * 4. Compute sign = SHA1Mixed(sorted key=value& pairs + password=MD5(countryCode))
     * 5. Build final JSON
     */
    private String buildUnLoginParamsJson(Map<String, String> rawParams, Integer num, String uuid) throws Exception {
        String countryCode = getCountryCode();
        String reqTimestamp = String.valueOf(System.currentTimeMillis() + timeDifference);
        String randomUUID = generateRandom();
        String watchImei = deviceInfo.imeiMd5();
        String watchAppVersion = deviceInfo.appVersionCodeString();
        
        // Step 1: Build inner params based on endpoint type
        TreeMap<String, String> treeMapM;
        if (uuid != null) {
            if (num != null && num == 1) {
                // check/qrcode
                treeMapM = new TreeMap<>();
                treeMapM.put("timeStamp", reqTimestamp);
                treeMapM.put("random", randomUUID);
                treeMapM.put("networkType", "wifi");
                treeMapM.put("version", String.valueOf(watchAppVersion));
                treeMapM.put("uuid", uuid);
            } else {
                // gain/token (num=2) — confirmed via HTTP capture:
                // inner params = {timeStamp, timeZone:"Asia/Seoul", uuid}
                treeMapM = new TreeMap<>();
                treeMapM.put("timeStamp", reqTimestamp);
                treeMapM.put("uuid", uuid);
                // Use timezone ID like "Asia/Seoul" (NOT "GMT+9")
                treeMapM.put("timeZone", java.util.TimeZone.getDefault().getID());
            }
        } else if (num != null && num == 5) {
            // getServerCurrentTime - confirmed: no deviceType in real watch traffic
            treeMapM = new TreeMap<>();
            treeMapM.put("timeStamp", reqTimestamp);
            treeMapM.put("random", randomUUID);
            treeMapM.put("networkType", "wifi");
            treeMapM.put("version", String.valueOf(watchAppVersion));
        } else {
            // create/qrcode - confirmed via HTTP Toolkit capture
            treeMapM = new TreeMap<>();
            treeMapM.put("timeStamp", reqTimestamp);
            treeMapM.put("random", randomUUID);
            treeMapM.put("networkType", "wifi");
            treeMapM.put("version", String.valueOf(watchAppVersion));
        }
        
        // Merge additional raw params if any
        if (rawParams != null) {
            treeMapM.putAll(rawParams);
        }
        
        // Step 2: Encrypt inner params → encryData
        String md5CountryHex = CryptoUtils.md5Hex(countryCode); // uppercase
        String encryData = encryptTreeMap(treeMapM, md5CountryHex);
        
        // Step 3: Add outer fields to treeMapM for sign calculation
        treeMapM.put("identifier", countryCode);
        treeMapM.put("watchImei", watchImei);
        treeMapM.put("watchModel", WATCH_MODEL);
        treeMapM.put("watchName", WATCH_BRAND + WATCH_MODEL);
        treeMapM.put("watchBrand", WATCH_BRAND);
        treeMapM.put("watchAppVersion", String.valueOf(watchAppVersion));
        treeMapM.put("watchOs", "0");
        treeMapM.put("reqTimestamp", reqTimestamp);
        treeMapM.put("language", config.getLanguage() != null ? config.getLanguage() : "ko");
        treeMapM.put("countryCode", countryCode);
        
        // Step 4: Compute sign
        String sign = computeSign(treeMapM, countryCode, true);
        
        // Step 5: Build final JSON (field order matches captured traffic)
        JsonObject outerJson = new JsonObject();
        outerJson.addProperty("countryCode", countryCode);
        outerJson.addProperty("encryData", encryData);
        outerJson.addProperty("identifier", countryCode);
        outerJson.addProperty("language", config.getLanguage() != null ? config.getLanguage() : "ko");
        outerJson.addProperty("reqTimestamp", reqTimestamp);
        outerJson.addProperty("sign", sign);
        outerJson.addProperty("watchAppVersion", String.valueOf(watchAppVersion));
        outerJson.addProperty("watchBrand", WATCH_BRAND);
        outerJson.addProperty("watchImei", watchImei);
        outerJson.addProperty("watchModel", WATCH_MODEL);
        outerJson.addProperty("watchName", WATCH_BRAND + WATCH_MODEL);
        outerJson.addProperty("watchOs", "0");
        
        String result = outerJson.toString();
        Log.d(TAG, "buildUnLoginParamsJson result: " + result.substring(0, Math.min(300, result.length())) + "...");
        return result;
    }

    /**
     * Builds the WatchCommonRequest JSON for logged-in endpoints.
     * Mirrors j.a() in the official watch app.
     */
    private String buildLoginParamsJson(Map<String, String> rawParams, TokenInfoBean tokenInfo) throws Exception {
        String countryCode = getCountryCode();
        String reqTimestamp = String.valueOf(System.currentTimeMillis() + timeDifference);
        String randomUUID = generateRandom();
        String watchImei = deviceInfo.imeiMd5();
        String watchAppVersion = deviceInfo.appVersionCodeString();
        
        // Inner params for logged-in request
        TreeMap<String, String> treeMapM = new TreeMap<>();
        treeMapM.put("timeStamp", reqTimestamp);
        treeMapM.put("random", randomUUID);
        treeMapM.put("watchImei", watchImei);
        treeMapM.put("deviceType", "0");
        treeMapM.put("networkType", "wifi");
        
        if (rawParams != null) {
            treeMapM.putAll(rawParams);
        }
        
        // Encrypt with MD5(encryToken)
        String encryToken = tokenInfo.getEncryToken();
        String encryptKeyHex = CryptoUtils.md5Hex(encryToken); // uppercase hex
        String encryData = encryptTreeMap(treeMapM, encryptKeyHex);
        
        // Add outer fields
        String identifier = tokenInfo.getIdentifier() != null ? tokenInfo.getIdentifier() : countryCode;
        treeMapM.put("identifier", identifier);
        treeMapM.put("watchModel", WATCH_MODEL);
        treeMapM.put("watchName", WATCH_BRAND + WATCH_MODEL);
        treeMapM.put("watchBrand", WATCH_BRAND);
        treeMapM.put("watchAppVersion", String.valueOf(watchAppVersion));
        treeMapM.put("watchOs", "0");
        treeMapM.put("reqTimestamp", reqTimestamp);
        treeMapM.put("language", config.getLanguage() != null ? config.getLanguage() : "ko");
        treeMapM.put("countryCode", countryCode);
        String userType = tokenInfo.getUserType() != null ? tokenInfo.getUserType() : "";
        treeMapM.put("userType", userType);
        
        // Sign with signToken
        String signToken = tokenInfo.getSignToken();
        String sign = computeSign(treeMapM, signToken, true);
        
        JsonObject outerJson = new JsonObject();
        outerJson.addProperty("identifier", identifier);
        outerJson.addProperty("watchImei", watchImei);
        outerJson.addProperty("watchModel", WATCH_MODEL);
        outerJson.addProperty("watchName", WATCH_BRAND + WATCH_MODEL);
        outerJson.addProperty("watchBrand", WATCH_BRAND);
        outerJson.addProperty("watchAppVersion", String.valueOf(watchAppVersion));
        outerJson.addProperty("watchOs", "0");
        outerJson.addProperty("reqTimestamp", reqTimestamp);
        outerJson.addProperty("language", config.getLanguage() != null ? config.getLanguage() : "ko");
        outerJson.addProperty("countryCode", countryCode);
        outerJson.addProperty("userType", userType);
        outerJson.addProperty("encryData", encryData);
        outerJson.addProperty("sign", sign);
        
        return outerJson.toString();
    }

    // ── Crypto helpers matching official watch app ──────────────────────

    /**
     * Encrypts a TreeMap as JSON using AES-128-CBC (Zero IV).
     * Matches j.l() / j.g(): JsonObject from TreeMap → getBytes → AES encrypt → hex.
     * Key input is the MD5 hex string which gets converted to bytes via c.c().
     */
    private String encryptTreeMap(TreeMap<String, String> treeMap, String hexKey) {
        // Build JSON using Gson's JsonObject (matches official app's JsonObject usage)
        JsonObject jsonObject = new JsonObject();
        for (Map.Entry<String, String> entry : treeMap.entrySet()) {
            jsonObject.addProperty(entry.getKey(), entry.getValue());
        }
        String jsonStr = jsonObject.toString();
        Log.d(TAG, "encryptTreeMap input JSON: " + jsonStr);
        
        // AES encrypt: key = hexToBytes(hexKey), data = jsonStr.getBytes(UTF-8)
        return CryptoUtils.aesEncryptHex(jsonStr, hexKey.toLowerCase());
    }

    /**
     * Computes sign matching c.d() in the official watch app.
     * Iterates TreeMap entries, builds key=value& pairs, appends password.
     * When z5=true: password = a(b(str)) = MD5(str) as uppercase hex
     */
    private String computeSign(TreeMap<String, String> treeMap, String str, boolean z5) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : treeMap.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key != null && !key.isEmpty()) {
                if (value == null || value.isEmpty()) {
                    value = "";
                }
                sb.append(key).append("=").append(value).append("&");
            }
        }
        if (z5) {
            sb.append("password=").append(CryptoUtils.md5Hex(str)); // md5Hex already returns uppercase
        } else {
            sb.append("password=").append(str);
        }
        String signInput = sb.toString();
        Log.d(TAG, "computeSign input: " + signInput.substring(0, Math.min(200, signInput.length())) + "...");
        return CryptoUtils.sha1Mixed(signInput);
    }

    /**
     * Random UUID generation matching h.o():
     * UUID.randomUUID() → remove dashes → toUpperCase
     */
    private String generateRandom() {
        return UUID.randomUUID().toString().replaceAll("-", "").toUpperCase(Locale.getDefault());
    }

    /**
     * Classifies likely causes by error code for diagnostic logging.
     */
    private String classifyErrorCode(String code) {
        switch (code) {
            case "1002": return "Session expired";
            case "1005": return "Authentication failed/version rejected/signature error";
            case "1010": return "Invalid session";
            case "1001": return "Invalid parameter";
            case "1003": return "Insufficient permission";
            case "1004": return "Request rate limit exceeded";
            default: return "Unknown error";
        }
    }

    private String getCountryCode() {
        String cc = config.getCountryCode();
        return (cc != null && !cc.isEmpty()) ? cc : "KR";
    }

    /**
     * Builds the encrypted QR code content string for display.
     * Matches the official BYD Watch app's LoginViewModel.m7687y():
     *
     * 1. plaintext = "watchImei=<imei>&uuid=<uuid>&countryCode=<cc>"
     * 2. key = hexToBytes(MD5("watch.bydautolink").toUpperCase())
     * 3. encrypted = AES-CBC(key, plaintext.getBytes(UTF-8), zeroIV)
     * 4. result = "watchQRCode://" + bytesToHex(encrypted).toUpperCase()
     *
     * The BYD phone app scans this QR code to bind the watch.
     */
    public String buildQrCodeContent(String uuid, String watchImei) {
        String countryCode = getCountryCode();
        String plaintext = "watchImei=" + watchImei + "&uuid=" + uuid + "&countryCode=" + countryCode;
        Log.d(TAG, "buildQrCodeContent plaintext: " + plaintext);

        // AES key = MD5("watch.bydautolink") as raw bytes
        // CryptoUtils.md5Hex returns uppercase hex; we need the hex→bytes of that
        String qrKeyHex = CryptoUtils.md5Hex("watch.bydautolink"); // uppercase hex
        String encryptedHex = CryptoUtils.aesEncryptHex(plaintext, qrKeyHex.toLowerCase());

        String qrContent = "watchQRCode://" + encryptedHex.toUpperCase();
        Log.d(TAG, "buildQrCodeContent result: " + qrContent);
        return qrContent;
    }

    /**
     * Returns the watchImei used by this service (for QR code generation).
     */
    public String getWatchImei() {
        return deviceInfo.imeiMd5();
    }
}

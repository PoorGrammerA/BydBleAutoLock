package com.poorgrammera.bydblekeycontrol;

import android.Manifest;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.content.pm.PackageManager;
import android.view.Gravity;
import android.view.ViewGroup;
import android.util.Log;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.poorgrammera.bydautolock.bydapi.BydConfig;
import com.poorgrammera.bydautolock.bydapi.BydWatchKeyService;
import com.poorgrammera.bydautolock.bydapi.WatchBleKeyFlowManager;
import com.poorgrammera.bydautolock.model.QrCodeInfo;
import com.poorgrammera.bydautolock.model.QrCodeState;
import com.poorgrammera.bydautolock.model.RemoteControlResult;
import com.poorgrammera.bydautolock.model.RemoteControlStartResponse;
import com.poorgrammera.bydautolock.model.TokenInfoBean;
import com.poorgrammera.bydautolock.model.WatchBlueToothKeyStatInfo;
import com.poorgrammera.bydautolock.service.VehicleBleCommand;
import com.poorgrammera.bydautolock.storage.StorageManager;
import com.poorgrammera.bydblekeycontrol.blecodec.BydBleCodec;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** BLE key authentication controls only.  RSSI based lock/unlock logic is intentionally absent. */
/** Manual protocol and control test screen retained for development diagnostics. */
public class DevTestActivity extends AppCompatActivity {
    private static final String TAG = "BleKeyMain";
    private static final int REQUEST_BLE_CONNECT = 1001;
    private StorageManager storage;
    private BydWatchKeyService watchKeyService;
    private final TextView[] stepLogs = new TextView[6];
    private TextView selectedDevice;
    private TextView vehicleInfoView;
    private TextView remoteControlStatusView;
    private TextView remoteInfoView;
    private TextView nativeControlMapView;
    private LinearLayout vehicleFunctionContainer;
    private LinearLayout remoteControlContainer;
    private final List<Button> bleVehicleFunctionButtons = new ArrayList<>();
    private final List<Button> rawBleControlButtons = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WatchBleKeyFlowManager activeBleFlow;
    private boolean bleControlReady;
    private String currentVehicleMac;
    private String currentUuid;
    private TokenInfoBean currentToken;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "Starting version 1.0.1");
        storage = new StorageManager(this);
        restoreWatchFlowState();
        watchKeyService = new BydWatchKeyService(this, BydConfig.fromRegion(storage.getRegion()));
        setContentView(createContent());
        updateSelectedDevice();
        loadLogs();
        loadStoredVehicleConfiguration();
        requestBleConnectPermissionIfNeeded();
    }

    @Override protected void onDestroy() {
        stopActiveBleFlow();
        super.onDestroy();
    }

    private ViewGroup createContent() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(getColor(R.color.vehicle_background));
        int padding = dp(20);
        content.setPadding(padding, padding, padding, padding);
        scroll.addView(content);

        content.addView(title("BYD BLE Key Control", 24));
        content.addView(description("Run the Watch authentication flow in order. Step 4 automatically saves the vehicle BLE MAC provided by the server."));
        selectedDevice = description("");
        content.addView(selectedDevice);

        content.addView(title("Watch BLE Authentication Flow", 20));
        String[] labels = {
                "1. Create QR", "2. Check QR Status", "3. Gain Token",
                "4. Gain Vehicle", "5. Gain Bluetooth Key", "6. Connect & Authenticate BLE"
        };
        for (int i = 0; i < labels.length; i++) {
            Button action = button(labels[i]);
            final int step = i + 1;
            action.setOnClickListener(v -> runStep(step));
            content.addView(action);
            stepLogs[i] = description(step + ". " + stepName(step) + ": No data");
            stepLogs[i].setTextIsSelectable(true);
            content.addView(stepLogs[i]);
        }

        content.addView(title("Vehicle Information", 20));
        vehicleInfoView = description("Run step 4, Gain Vehicle, to save and display vehicle information here.");
        vehicleInfoView.setTextIsSelectable(true);
        content.addView(vehicleInfoView);
        content.addView(title("Vehicle Function Test", 20));
        content.addView(description("These fixed BLE control buttons send directly to the vehicle after authentication."));
        vehicleFunctionContainer = new LinearLayout(this);
        vehicleFunctionContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(vehicleFunctionContainer);
        renderFixedBleControlButtons();

        content.addView(title("Raw BLE function-ID test", 20));
        content.addView(description("Creates a direct BLE test button for every function ID in the Pure Java control-code table (9000-9100). Each command goes directly to the vehicle after Step 6 authentication. Test only while parked and clear of people or obstructions."));
        LinearLayout rawBleControlContainer = new LinearLayout(this);
        rawBleControlContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(rawBleControlContainer);
        renderRawBleControlButtons(rawBleControlContainer);

        content.addView(title("BYD Server Remote Control", 20));
        content.addView(description("Sends an actual online command to the BYD server, separately from BLE. Each command requires confirmation and its result is checked by REST polling."));
        remoteControlContainer = new LinearLayout(this);
        remoteControlContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(remoteControlContainer);
        remoteControlStatusView = description("Run Step 3 (Gain Token) after installing this version to obtain the remote-control credential.");
        remoteControlStatusView.setTextIsSelectable(true);
        content.addView(remoteControlStatusView);
        remoteInfoView = description("No additional server data read yet.");
        remoteInfoView.setTextIsSelectable(true);
        content.addView(remoteInfoView);
        renderRemoteControlButtons();

        content.addView(title("Pure Java BLE control-code diagnostic", 20));
        content.addView(description("Reads only the Pure Java function-ID-to-control-code table. It does not create a command frame, connect to Bluetooth, or send anything to the vehicle."));
        Button extractNativeControlMap = button("Extract Pure Java control-code map (9000-9100)");
        extractNativeControlMap.setOnClickListener(v -> extractNativeControlMap());
        content.addView(extractNativeControlMap);
        nativeControlMapView = description("No Pure Java control-code map extracted yet.");
        nativeControlMapView.setTextIsSelectable(true);
        content.addView(nativeControlMapView);

        return scroll;
    }

    /**
     * Diagnostic-only lookup. This deliberately does not create a frame or access BLE.
     */
    private void extractNativeControlMap() {
        final int firstFunctionId = 9000;
        final int lastFunctionId = 9100;
        StringBuilder result = new StringBuilder("Pure Java mapping (no BLE connection or transmission)\n");
        int supportedCount = 0;

        try {
            for (int functionId = firstFunctionId; functionId <= lastFunctionId; functionId++) {
                byte controlCode = BydBleCodec.getControlCode(functionId);
                if (controlCode == (byte) -1) continue;

                String entry = String.format(Locale.US, "%d -> 0x%02X", functionId, controlCode & 0xFF);
                result.append(entry).append('\n');
                Log.i(TAG, "Pure Java BLE control map: " + entry);
                supportedCount++;
            }
            if (supportedCount == 0) {
                result.append("No supported function ID was returned in 9000-9100.\n");
            }
            result.append("Entries: ").append(supportedCount);
            nativeControlMapView.setText(result.toString());
            Log.i(TAG, "Pure Java BLE control map extraction complete: entries=" + supportedCount);
            toast("Pure Java map extracted: " + supportedCount + " entries");
        } catch (Throwable error) {
            String message = "Pure Java control map extraction failed: " + error.getClass().getSimpleName()
                    + (isEmpty(error.getMessage()) ? "" : " - " + error.getMessage());
            Log.e(TAG, message, error);
            nativeControlMapView.setText(message);
            toast("Pure Java map extraction failed. Check Logcat.");
        }
    }

    private void renderRawBleControlButtons(LinearLayout container) {
        container.removeAllViews();
        rawBleControlButtons.clear();
        int supportedCount = 0;
        try {
            for (int functionId = 9000; functionId <= 9100; functionId++) {
                byte controlCode = BydBleCodec.getControlCode(functionId);
                if (controlCode == (byte) -1) continue;

                addRawBleControlButton(container, functionId, controlCode, rawBleFunctionName(functionId));
                supportedCount++;
            }
        } catch (Throwable error) {
            String message = "Unable to read the Pure Java control-code table: "
                    + error.getClass().getSimpleName();
            Log.e(TAG, message, error);
            container.addView(description(message));
        }
        if (supportedCount == 0) {
            container.addView(description("No Pure Java BLE function IDs were found in 9000-9100."));
        }
    }

    private void renderFixedBleControlButtons() {
        if (vehicleFunctionContainer == null) return;
        vehicleFunctionContainer.removeAllViews();
        bleVehicleFunctionButtons.clear();
        addFixedBleControlButton("Locking - BLE", VehicleBleCommand.LOCK_DOORS);
        addFixedBleControlButton("Unlocking - BLE", VehicleBleCommand.UNLOCK_ALL_DOORS);
        addFixedBleControlButton("Open the trunk - BLE", VehicleBleCommand.OPERATE_TRUNK);
        addFixedBleControlButton("only light - BLE", VehicleBleCommand.FLASH_LIGHTS);
        addFixedBleControlButton("light and honking - BLE", VehicleBleCommand.FIND_VEHICLE);
    }

    private void addFixedBleControlButton(String label, int functionId) {
        Button action = button(label);
        action.setEnabled(bleControlReady);
        action.setOnClickListener(v -> confirmAndSendBleCommand(functionId, label));
        bleVehicleFunctionButtons.add(action);
        vehicleFunctionContainer.addView(action);
    }

    private void addRawBleControlButton(LinearLayout container, int functionId, byte controlCode, String functionName) {
        Button action = button(String.format(Locale.US, "%d -> 0x%02X : %s - BLE direct",
                functionId, controlCode & 0xFF, functionName));
        action.setEnabled(bleControlReady);
        action.setOnClickListener(v -> confirmAndSendRawBleCommand(functionId, controlCode, functionName));
        rawBleControlButtons.add(action);
        container.addView(action);
    }

    private String rawBleFunctionName(int functionId) {
        switch (functionId) {
            case 9001: return "Unlock doors";
            case 9002: return "Lock doors";
            case 9005: return "Find vehicle";
            case 9010: return "Flash lights";
            case 9011:
            case 9015: return "Open electric rear door (confirmed)";
            case 9019: return "Open electric rear door (test)";
            case 9020: return "Close electric rear door (test)";
            default: return "Native mapping test";
        }
    }

    private void confirmAndSendRawBleCommand(int functionId, byte controlCode, String functionName) {
        String detail = String.format(Locale.US, "%d -> 0x%02X", functionId, controlCode & 0xFF);
        new AlertDialog.Builder(this)
                .setTitle("Raw BLE command test")
                .setMessage(functionName + " (" + detail + ") will be sent directly to the vehicle. "
                        + "The exact vehicle support is not guaranteed. Confirm the vehicle is parked and the area is clear. Send?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Send", (dialog, which) -> sendBleCommand(functionId,
                        "Raw BLE " + detail + " - " + functionName))
                .show();
    }

    private void renderRemoteControlButtons() {
        if (remoteControlContainer == null) return;
        remoteControlContainer.removeAllViews();
        addRemoteControlButton("Lock doors", "LOCKDOOR");
        addRemoteControlButton("Unlock doors", "OPENDOOR");
        addRemoteControlButton("Find vehicle", "FINDCAR");
        addRemoteControlButton("Open trunk", "OPENTRUNK");
        addRemoteControlButton("Close trunk", "CLOSETRUNK");
        addRemoteControlButton("Turn off engine", "TURNOFFENGINE");
        addRemoteControlButton("Start climate (22 C, 10 min)", "OPENAIR", buildDefaultAirControlParams());
        addRemoteControlButton("Stop climate", "CLOSEAIR");

        addRemoteControlButton("Open windows", "OPENWINDOW");
        addRemoteControlButton("Close windows", "CLOSEWINDOW");
        addRemoteControlButton("Flash lights, no horn", "FLASHLIGHTNOWHISTLE");
        addRemoteControlButton("Battery preheat on (not tested)", "BATTERYHEAT", buildBatteryHeatControlParams(true));
        addRemoteControlButton("Battery preheat off (not tested)", "BATTERYHEAT", buildBatteryHeatControlParams(false));

        remoteControlContainer.addView(description("Watch API data reads (no vehicle control):"));
        Button vehicleStatus = button("Read vehicle realtime status - Watch API");
        vehicleStatus.setOnClickListener(v -> requestVehicleRealtime());
        remoteControlContainer.addView(vehicleStatus);
        Button airStatus = button("Read air-condition status - Watch API");
        airStatus.setOnClickListener(v -> requestAirConditionStatus());
        remoteControlContainer.addView(airStatus);
    }

    private void addRemoteControlButton(String commandName, String commandType, String controlParamsMap) {
        Button action = button(commandName + " - server remote");
        action.setOnClickListener(v -> confirmAndSendRemoteControl(commandName, commandType, controlParamsMap));
        remoteControlContainer.addView(action);
    }

    private void addRemoteControlButton(String commandName, String commandType) {
        Button action = button(commandName + " · server remote");
        action.setOnClickListener(v -> confirmAndSendRemoteControl(commandName, commandType));
        remoteControlContainer.addView(action);
    }

    private void confirmAndSendRemoteControl(String commandName, String commandType, String controlParamsMap) {
        new AlertDialog.Builder(this)
                .setTitle("BYD server remote control")
                .setMessage(commandName + " will be sent to the BYD server and may operate the vehicle. Continue?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Send", (dialog, which) -> sendRemoteControl(commandName, commandType, controlParamsMap))
                .show();
    }

    private void confirmAndSendRemoteControl(String commandName, String commandType) {
        new AlertDialog.Builder(this)
                .setTitle("BYD server remote control")
                .setMessage(commandName + " will be sent to the BYD server and may operate the vehicle. Continue?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Send", (dialog, which) -> sendRemoteControl(commandName, commandType))
                .show();
    }

    private void sendRemoteControl(String commandName, String commandType) {
        if (!requireToken()) return;
        if (isEmpty(currentToken.getControlPwd())) {
            remoteControlStatusView.setText("Remote control is unavailable: run Step 3 (Gain Token) again to obtain controlPwd.");
            toast("Run Step 3 (Gain Token) again first.");
            return;
        }
        remoteControlStatusView.setText(commandName + " request is being sent to the BYD server...");
        Log.i(TAG, "Remote control requested: commandType=" + commandType);
        watchKeyService.sendRemoteControl(currentToken, commandType,
                new BydWatchKeyService.Callback<RemoteControlStartResponse>() {
                    @Override public void onSuccess(RemoteControlStartResponse result) {
                        runOnUiThread(() -> {
                            String requestSerial = result == null ? null : result.getRequestSerial();
                            if (isEmpty(requestSerial)) {
                                remoteControlStatusView.setText(commandName + " was not accepted: requestSerial is empty.");
                                Log.w(TAG, "Remote control accepted response has no requestSerial: " + commandType);
                                return;
                            }
                            remoteControlStatusView.setText(commandName + " accepted. Checking result... (serial="
                                    + abbreviate(requestSerial) + ")");
                            Log.i(TAG, "Remote control accepted: commandType=" + commandType
                                    + ", requestSerial=" + requestSerial);
                            pollRemoteControl(commandName, commandType, requestSerial, 1);
                        });
                    }

                    @Override public void onError(String message, Throwable error) {
                        runOnUiThread(() -> {
                            remoteControlStatusView.setText(commandName + " request failed: " + message);
                            Log.e(TAG, "Remote control request failed: " + commandType + " - " + message, error);
                        });
                    }
                });
    }

    private void sendRemoteControl(String commandName, String commandType, String controlParamsMap) {
        if (!requireToken()) return;
        if (isEmpty(currentToken.getControlPwd())) {
            remoteControlStatusView.setText("Remote control is unavailable: run Step 3 (Gain Token) again to obtain controlPwd.");
            toast("Run Step 3 (Gain Token) again first.");
            return;
        }
        remoteControlStatusView.setText(commandName + " request is being sent to the BYD server...");
        Log.i(TAG, "Remote control requested: commandType=" + commandType + ", params=" + controlParamsMap);
        watchKeyService.sendRemoteControl(currentToken, commandType, controlParamsMap,
                new BydWatchKeyService.Callback<RemoteControlStartResponse>() {
                    @Override public void onSuccess(RemoteControlStartResponse result) {
                        runOnUiThread(() -> {
                            String requestSerial = result == null ? null : result.getRequestSerial();
                            if (isEmpty(requestSerial)) {
                                remoteControlStatusView.setText(commandName + " was not accepted: requestSerial is empty.");
                                Log.w(TAG, "Remote control accepted response has no requestSerial: " + commandType);
                                return;
                            }
                            remoteControlStatusView.setText(commandName + " accepted. Checking result... (serial="
                                    + abbreviate(requestSerial) + ")");
                            Log.i(TAG, "Remote control accepted: commandType=" + commandType
                                    + ", requestSerial=" + requestSerial);
                            pollRemoteControl(commandName, commandType, requestSerial, 1);
                        });
                    }

                    @Override public void onError(String message, Throwable error) {
                        runOnUiThread(() -> {
                            remoteControlStatusView.setText(commandName + " request failed: " + message);
                            Log.e(TAG, "Remote control request failed: " + commandType + " - " + message, error);
                        });
                    }
                });
    }

    private void pollRemoteControl(String commandName, String commandType, String requestSerial, int attempt) {
        mainHandler.postDelayed(() -> {
            if (currentToken == null) return;
            remoteControlStatusView.setText(commandName + " result check " + attempt + "/10...");
            watchKeyService.getRemoteControlResult(currentToken, requestSerial, commandType,
                    new BydWatchKeyService.Callback<RemoteControlResult>() {
                        @Override public void onSuccess(RemoteControlResult result) {
                            runOnUiThread(() -> handleRemoteControlResult(commandName, commandType,
                                    requestSerial, attempt, result));
                        }

                        @Override public void onError(String message, Throwable error) {
                            runOnUiThread(() -> {
                                remoteControlStatusView.setText(commandName + " result check failed: " + message);
                                Log.e(TAG, "Remote control result check failed: " + commandType
                                        + ", attempt=" + attempt + " - " + message, error);
                            });
                        }
                    });
        }, remotePollDelayMillis(attempt));
    }

    private void handleRemoteControlResult(String commandName, String commandType, String requestSerial,
                                           int attempt, RemoteControlResult result) {
        int res = result == null ? -1 : result.getRes();
        String message = result == null || isEmpty(result.getMessage()) ? "(no message)" : result.getMessage();
        Log.i(TAG, "Remote control result: commandType=" + commandType + ", attempt=" + attempt
                + ", res=" + res + ", message=" + message);
        if (res == 2) {
            remoteControlStatusView.setText(commandName + " succeeded: " + message);
            toast(commandName + " succeeded");
        } else if (res == 1 && attempt < 10) {
            remoteControlStatusView.setText(commandName + " is pending. Retrying result check...");
            pollRemoteControl(commandName, commandType, requestSerial, attempt + 1);
        } else {
            remoteControlStatusView.setText(commandName + " failed (res=" + res + "): " + message);
            toast(commandName + " failed");
        }
    }

    private long remotePollDelayMillis(int attempt) {
        if (attempt == 1) return 1000L;
        if (attempt <= 3) return 500L;
        if (attempt <= 6) return 2000L;
        if (attempt <= 9) return 5000L;
        return 10000L;
    }

    private String buildDefaultAirControlParams() {
        JsonObject params = new JsonObject();
        params.add("airSet", com.google.gson.JsonNull.INSTANCE);
        params.addProperty("remoteMode", 4);
        params.addProperty("timeSpan", 1); // 10 minutes
        params.addProperty("mainSettingTemp", 11); // KR scale: 22 C
        params.addProperty("copilotSettingTemp", 11);
        params.addProperty("cycleMode", 2);
        params.addProperty("airAccuracy", 2);
        params.addProperty("airConditioningMode", 1); // auto
        return params.toString();
    }

    private String buildBatteryHeatControlParams(boolean enabled) {
        JsonObject params = new JsonObject();
        params.addProperty("batteryHeatSwitch", enabled ? 1 : 0);
        return params.toString();
    }

    private void requestVehicleRealtime() {
        if (!requireToken()) return;
        remoteInfoView.setText("Requesting vehicle realtime status from the Watch API...");
        watchKeyService.requestVehicleRealtime(currentToken,
                new BydWatchKeyService.Callback<RemoteControlStartResponse>() {
                    @Override public void onSuccess(RemoteControlStartResponse result) {
                        runOnUiThread(() -> {
                            String serial = result == null ? null : result.getRequestSerial();
                            if (isEmpty(serial)) {
                                remoteInfoView.setText("Vehicle realtime request was not accepted: requestSerial is empty.");
                                return;
                            }
                            remoteInfoView.setText("Vehicle realtime request accepted. Reading result... (serial="
                                    + abbreviate(serial) + ")");
                            pollVehicleRealtime(serial, 1);
                        });
                    }

                    @Override public void onError(String message, Throwable error) {
                        runOnUiThread(() -> {
                            remoteInfoView.setText("Vehicle realtime request failed: " + message);
                            Log.e(TAG, "Vehicle realtime request failed: " + message, error);
                        });
                    }
                });
    }

    private void pollVehicleRealtime(String requestSerial, int attempt) {
        mainHandler.postDelayed(() -> {
            if (currentToken == null) return;
            remoteInfoView.setText("Vehicle realtime result check " + attempt + "/4...");
            watchKeyService.getVehicleRealtimeResult(currentToken, requestSerial,
                    new BydWatchKeyService.Callback<JsonObject>() {
                        @Override public void onSuccess(JsonObject result) {
                            runOnUiThread(() -> {
                                boolean pending = result != null && result.has("res")
                                        && result.get("res").getAsInt() == 1;
                                if (pending && attempt < 4) {
                                    pollVehicleRealtime(requestSerial, attempt + 1);
                                    return;
                                }
                                String data = result == null ? "null" : result.toString();
                                remoteInfoView.setText("Vehicle realtime status:\n" + data);
                                Log.i(TAG, "Vehicle realtime status: " + data);
                            });
                        }

                        @Override public void onError(String message, Throwable error) {
                            runOnUiThread(() -> {
                                if (attempt < 4) {
                                    Log.w(TAG, "Vehicle realtime result check " + attempt + " failed; retrying: " + message);
                                    pollVehicleRealtime(requestSerial, attempt + 1);
                                } else {
                                    remoteInfoView.setText("Vehicle realtime result failed: " + message);
                                    Log.e(TAG, "Vehicle realtime result failed: " + message, error);
                                }
                            });
                        }
                    });
        }, attempt == 1 ? 1000L : 1500L);
    }

    private void requestAirConditionStatus() {
        if (!requireToken()) return;
        remoteInfoView.setText("Requesting air-condition status from the Watch API...");
        watchKeyService.getAirConditionNow(currentToken,
                new BydWatchKeyService.Callback<JsonObject>() {
                    @Override public void onSuccess(JsonObject result) {
                        runOnUiThread(() -> {
                            String data = result == null ? "null" : result.toString();
                            remoteInfoView.setText("Air-condition status:\n" + data);
                            Log.i(TAG, "Air-condition status: " + data);
                        });
                    }

                    @Override public void onError(String message, Throwable error) {
                        runOnUiThread(() -> {
                            remoteInfoView.setText("Air-condition status request failed: " + message);
                            Log.e(TAG, "Air-condition status request failed: " + message, error);
                        });
                    }
                });
    }

    private void runStep(int step) {
        switch (step) {
            case 1: createQr(); break;
            case 2: checkQrStatus(); break;
            case 3: gainToken(); break;
            case 4: gainVehicle(); break;
            case 5: gainBluetoothKey(); break;
            case 6: connectAndAuthenticate(); break;
            default: break;
        }
    }

    private void createQr() {
        updateLog(1, "Creating QR code...");
        watchKeyService.createQrCode(new BydWatchKeyService.Callback<QrCodeInfo>() {
            @Override public void onSuccess(QrCodeInfo result) {
                runOnUiThread(() -> {
                    if (result == null || result.getUuid() == null || result.getUuid().isEmpty()) {
                        updateLog(1, "Failed: empty UUID returned");
                        return;
                    }
                    currentUuid = result.getUuid();
                    storage.setWatchQrUuid(currentUuid);
                    updateLog(1, "Success: UUID=" + abbreviate(currentUuid));
                    showQrCode(currentUuid);
                });
            }
            @Override public void onError(String message, Throwable error) { runOnUiThread(() -> updateLog(1, "Error: " + message)); }
        });
    }

    private void checkQrStatus() {
        if (!requireUuid()) return;
        updateLog(2, "Checking QR status...");
        watchKeyService.getQrCodeStatus(currentUuid, new BydWatchKeyService.Callback<QrCodeState>() {
            @Override public void onSuccess(QrCodeState result) {
                runOnUiThread(() -> updateLog(2, "Result: status=" + (result == null ? "null" : result.getCodeStatus())));
            }
            @Override public void onError(String message, Throwable error) { runOnUiThread(() -> updateLog(2, "Error: " + message)); }
        });
    }

    private void gainToken() {
        if (!requireUuid()) return;
        updateLog(3, "Gaining token...");
        watchKeyService.getToken(currentUuid, null, new BydWatchKeyService.Callback<TokenInfoBean>() {
            @Override public void onSuccess(TokenInfoBean token) {
                runOnUiThread(() -> {
                    currentToken = token;
                    if (token == null) { updateLog(3, "Failed: empty token returned"); return; }
                    storage.setWatchEncryToken(token.getEncryToken());
                    storage.setWatchSignToken(token.getSignToken());
                    storage.setWatchControlPwd(token.getControlPwd());
                    storage.setWatchIdentifier(token.getIdentifier());
                    storage.setWatchUserType(token.getUserType());
                    if (token.getVin() != null) storage.setWatchVin(token.getVin());
                    updateLog(3, "Success: userId=" + token.getIdentifier() + ", VIN=" + token.getVin());
                });
            }
            @Override public void onError(String message, Throwable error) { runOnUiThread(() -> updateLog(3, "Error: " + message)); }
        });
    }

    private void gainVehicle() {
        if (!requireToken()) return;
        updateLog(4, "Gaining vehicle configuration...");
        watchKeyService.getVehicleConfig(currentToken, new BydWatchKeyService.Callback<com.google.gson.JsonObject>() {
            @Override public void onSuccess(com.google.gson.JsonObject result) {
                runOnUiThread(() -> {
                    String serverMac = cacheVehicleBluetoothInfo(result);
                    persistVehicleConfiguration(result);
                    Log.d(TAG, "Gain Vehicle full response: " + (result == null ? "null" : result.toString()));
                    updateLog(4, isEmpty(serverMac)
                            ? "Server BLE MAC was not provided. See Logcat for the full vehicle response."
                            : "Server BLE MAC saved: " + serverMac);
                    updateSelectedDevice();
                });
            }
            @Override public void onError(String message, Throwable error) { runOnUiThread(() -> updateLog(4, "Error: " + message)); }
        });
    }

    private void gainBluetoothKey() {
        if (!requireToken()) return;
        updateLog(5, "Gaining Bluetooth key...");
        watchKeyService.getWatchBlueInfo(currentToken, new BydWatchKeyService.Callback<WatchBlueToothKeyStatInfo>() {
            @Override public void onSuccess(WatchBlueToothKeyStatInfo key) {
                runOnUiThread(() -> {
                    if (key == null || key.getDk() == null) { updateLog(5, "Failed: empty BLE key returned"); return; }
                    storage.setBleDk(key.getDk());
                    storage.setBlePassword(key.getBlueToothPassword());
                    if (key.getEmpowerBluetoothKeyNo() != null) storage.setBleKeyNo(key.getEmpowerBluetoothKeyNo());
                    if (key.getAuthBluetoothProtocol() != null) storage.setBleAuthProtocol(key.getAuthBluetoothProtocol());
                    if (key.getBluetoothMacAddress() != null && !key.getBluetoothMacAddress().isEmpty()) storage.setBleMacAddress(key.getBluetoothMacAddress());
                    if (key.getVin() != null) storage.setSelectedVin(key.getVin());
                    updateLog(5, "Success: dkey=" + abbreviate(key.getDk()) + ", API MAC=" + key.getBluetoothMacAddress());
                });
            }
            @Override public void onError(String message, Throwable error) { runOnUiThread(() -> updateLog(5, "Error: " + message)); }
        });
    }

    private void connectAndAuthenticate() {
        if (!hasBleConnectPermission()) {
            requestBleConnectPermissionIfNeeded();
            toast("Allow the Nearby devices permission to connect to the vehicle.");
            return;
        }
        boolean hasBleKey = storage.hasBleKey();
        Log.i(TAG, "Step 6 clicked: hasBleKey=" + hasBleKey
                + ", memoryMac=" + currentVehicleMac
                + ", deviceMac=" + storage.getDeviceMac()
                + ", bleMac=" + storage.getBleMacAddress());
        if (!hasBleKey) { toast("Get the BLE key in step 5 first."); return; }
        String vehicleMac = resolveVehicleMac();
        if (isEmpty(vehicleMac)) {
            if (!requireToken()) return;
            Log.i(TAG, "Step 6 has no cached MAC; refreshing gain/vehicle automatically");
            updateLog(6, "Vehicle BLE MAC is not saved; loading vehicle information from the server again…");
            watchKeyService.getVehicleConfig(currentToken, new BydWatchKeyService.Callback<JsonObject>() {
                @Override public void onSuccess(JsonObject vehicle) {
                    runOnUiThread(() -> {
                        String refreshedMac = cacheVehicleBluetoothInfo(vehicle);
                        persistVehicleConfiguration(vehicle);
                        updateSelectedDevice();
                        Log.i(TAG, "Step 6 automatic Gain Vehicle result MAC=" + refreshedMac);
                        if (isEmpty(resolveVehicleMac())) {
                            Log.w(TAG, "Step 6 blocked: Gain Vehicle still supplied no BLE MAC");
                            updateLog(6, "Failed: vehicle BLE MAC is missing from server vehicle information.");
                            toast("Vehicle BLE MAC is missing from the server vehicle information. Check step 4 logs.");
                            return;
                        }
                        connectAndAuthenticate();
                    });
                }

                @Override public void onError(String message, Throwable error) {
                    runOnUiThread(() -> {
                        Log.e(TAG, "Step 6 automatic Gain Vehicle failed: " + message, error);
                        updateLog(6, "Automatic vehicle BLE MAC lookup failed: " + message);
                        toast("Could not automatically load the vehicle BLE MAC.");
                    });
                }
            });
            return;
        }
        Log.i(TAG, "Step 6 resolved vehicle BLE MAC=" + vehicleMac);
        setBleControlReady(false);
        updateLog(6, "Resolved vehicle BLE MAC: " + vehicleMac + "\nConnecting...");
        stopActiveBleFlow();
        WatchBleKeyFlowManager nextBleFlow = new WatchBleKeyFlowManager(this, BydConfig.fromRegion(storage.getRegion()), new WatchBleKeyFlowManager.FlowListener() {
            @Override public void onStateChanged(WatchBleKeyFlowManager.FlowState state, String message) {
                runOnUiThread(() -> {
                    setBleControlReady(state == WatchBleKeyFlowManager.FlowState.READY);
                    updateLog(6, "State: " + state + " - " + message);
                });
            }
            @Override public void onQrCodeReady(String uuid) { }
            @Override public void onBleKeyReady(WatchBlueToothKeyStatInfo info) { }
            @Override public void onFlowComplete(String message) { runOnUiThread(() -> updateLog(6, "Success: " + message)); }
            @Override public void onFlowError(WatchBleKeyFlowManager.FlowState state, String error) {
                runOnUiThread(() -> {
                    setBleControlReady(false);
                    updateLog(6, "Failed (" + state + "): " + error);
                });
            }
        });
        activeBleFlow = nextBleFlow;
        BleConnectionCoordinator.claim(BleConnectionCoordinator.Owner.DEVELOPER_TEST, activeBleFlow);
        activeBleFlow.resumeWithCachedKey();
    }

    private void stopActiveBleFlow() {
        if (activeBleFlow == null) return;
        BleConnectionCoordinator.release(BleConnectionCoordinator.Owner.DEVELOPER_TEST, activeBleFlow);
        activeBleFlow.cancel();
        activeBleFlow = null;
        bleControlReady = false;
    }

    private void sendBleCommand(int functionId, String commandName) {
        if (activeBleFlow == null || !bleControlReady) {
            toast("Complete BLE authentication in step 6 first.");
            return;
        }
        updateLog(6, "Sending command request: " + commandName + "…");
        activeBleFlow.sendCommand(functionId);
    }

    private boolean hasBleConnectPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestBleConnectPermissionIfNeeded() {
        if (!hasBleConnectPermission()) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_BLE_CONNECT);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_BLE_CONNECT) return;
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        Log.i(TAG, "BLUETOOTH_CONNECT permission result=" + granted);
        toast(granted ? "Vehicle BLE connection permission granted. Run step 6 again."
                : "Vehicle BLE connection permission was denied. Allow Nearby devices in app permissions.");
    }

    private void setBleControlReady(boolean ready) {
        bleControlReady = ready;
        for (Button button : bleVehicleFunctionButtons) {
            button.setEnabled(ready);
        }
        for (Button button : rawBleControlButtons) {
            button.setEnabled(ready);
        }
    }

    private void restoreWatchFlowState() {
        currentUuid = storage.getWatchQrUuid();
        currentToken = restoreWatchToken();
    }

    private TokenInfoBean restoreWatchToken() {
        String encryToken = storage.getWatchEncryToken();
        String signToken = storage.getWatchSignToken();
        String controlPwd = storage.getWatchControlPwd();
        String identifier = storage.getWatchIdentifier();
        String userType = storage.getWatchUserType();
        String vin = storage.getWatchVin();
        if (isEmpty(encryToken) || isEmpty(signToken) || isEmpty(identifier) || isEmpty(userType) || isEmpty(vin)) {
            return null;
        }
        TokenInfoBean token = new TokenInfoBean();
        token.setEncryToken(encryToken);
        token.setSignToken(signToken);
        token.setControlPwd(controlPwd);
        token.setIdentifier(identifier);
        token.setUserType(userType);
        token.setVin(vin);
        return token;
    }

    private boolean requireUuid() {
        if (isEmpty(currentUuid)) currentUuid = storage.getWatchQrUuid();
        if (!isEmpty(currentUuid)) return true;
        toast("Run step 1, Create QR, first.");
        return false;
    }

    private boolean requireToken() {
        if (currentToken == null) currentToken = restoreWatchToken();
        if (currentToken != null) return true;
        toast("Run step 3, Gain Token, first.");
        return false;
    }

    /** Extract the Korea API's nested watchBluetoothDto, which is absent from gain/bluetooth. */
    private String cacheVehicleBluetoothInfo(com.google.gson.JsonObject vehicle) {
        JsonObject dto = objectAt(vehicle, "watchBluetoothDto");
        if (dto == null) {
            // Korea returns it inside cfVechicle, not at the response root.
            dto = objectAt(objectAt(vehicle, "cfVechicle"), "watchBluetoothDto");
        }
        if (dto == null) {
            Log.w(TAG, "Gain Vehicle response has no watchBluetoothDto");
            return null;
        }
        String mac = dto.has("macAddress") && !dto.get("macAddress").isJsonNull() ? dto.get("macAddress").getAsString() : null;
        if (!isEmpty(mac)) {
            currentVehicleMac = mac;
            storage.setBleMacAddress(mac);
            storage.setDeviceMac(mac);
            Log.i(TAG, "Gain Vehicle saved BLE MAC=" + mac + " to memory/deviceMac/bleMac");
        } else {
            Log.w(TAG, "Gain Vehicle watchBluetoothDto has empty macAddress");
        }
        if (dto.has("watchBluetoothInfo") && dto.get("watchBluetoothInfo").isJsonObject()) {
            com.google.gson.JsonObject key = dto.getAsJsonObject("watchBluetoothInfo");
            if (key.has("dkey") && !key.get("dkey").isJsonNull()) storage.setBleDk(key.get("dkey").getAsString());
            if (key.has("keyNumber") && !key.get("keyNumber").isJsonNull()) storage.setBleKeyNo(key.get("keyNumber").getAsLong());
        }
        return mac;
    }

    /**
     * The Korean Watch API can supply the MAC through gain/vehicle only.  Keep all
     * compatible copies in sync and recover it from the saved response if needed.
     */
    private String resolveVehicleMac() {
        String mac = currentVehicleMac;
        String source = isEmpty(mac) ? "" : "memory";
        if (isEmpty(mac)) {
            mac = storage.getDeviceMac();
            source = isEmpty(mac) ? "" : "deviceMac";
        }
        if (isEmpty(mac)) {
            mac = storage.getBleMacAddress();
            source = isEmpty(mac) ? "" : "bleMac";
        }
        if (isEmpty(mac)) {
            String rawVehicle = storage.getWatchVehicleInfoJson();
            if (!isEmpty(rawVehicle)) {
                try {
                    JsonObject savedVehicle = JsonParser.parseString(rawVehicle).getAsJsonObject();
                    JsonObject dto = objectAt(savedVehicle, "watchBluetoothDto");
                    if (dto == null) dto = objectAt(objectAt(savedVehicle, "cfVechicle"), "watchBluetoothDto");
                    if (dto != null && dto.has("macAddress") && !dto.get("macAddress").isJsonNull()) {
                        mac = dto.get("macAddress").getAsString();
                        source = "savedVehicleJson";
                    }
                } catch (Exception ignored) {
                    // The UI will ask for a fresh Gain Vehicle response if the stored JSON is invalid.
                }
            }
        }
        if (!isEmpty(mac)) {
            currentVehicleMac = mac;
            storage.setDeviceMac(mac);
            storage.setBleMacAddress(mac);
        }
        Log.i(TAG, "resolveVehicleMac: source=" + (isEmpty(source) ? "none" : source)
                + ", result=" + mac);
        return mac;
    }

    /** Saves the full response because its function list is tied to this exact vehicle. */
    private void persistVehicleConfiguration(JsonObject vehicle) {
        if (vehicle == null) return;
        storage.setWatchVehicleInfoJson(vehicle.toString());
        renderVehicleConfiguration(vehicle);
    }

    private void loadStoredVehicleConfiguration() {
        String raw = storage.getWatchVehicleInfoJson();
        if (isEmpty(raw)) return;
        try {
            renderVehicleConfiguration(JsonParser.parseString(raw).getAsJsonObject());
        } catch (Exception error) {
            if (vehicleInfoView != null) {
                vehicleInfoView.setText("Could not read the saved vehicle information. Run step 4, Gain Vehicle, again.");
            }
        }
    }

    private void renderVehicleConfiguration(JsonObject vehicle) {
        if (vehicle == null || vehicleInfoView == null) return;

        vehicleInfoView.setText(
                "Model (modelNameOut): " + valueAt(vehicle, "modelNameOut")
                        + "\nLicense plate (autoPlate): " + valueAt(vehicle, "autoPlate")
                        + "\nVIN: " + (isEmpty(storage.getWatchVin()) ? valueAt(vehicle, "vin") : storage.getWatchVin()));

    }

    private String buildVehicleHealthText(JsonObject cfVehicle, JsonObject learnInfo) {
        StringBuilder text = new StringBuilder("Vehicle Health diagnostic items (display only)\n");
        JsonArray functions = arrayAt(cfVehicle, "cfFixedList");
        if (functions != null) {
            for (JsonElement element : functions) {
                if (!element.isJsonObject()) continue;
                JsonObject item = element.getAsJsonObject();
                if (!"Vehicle Health".equals(valueAt(item, "code"))) continue;
                JsonArray children = arrayAt(item, "cfFixedSecondLevelList");
                if (children != null) {
                    for (JsonElement child : children) {
                        if (!child.isJsonObject()) continue;
                        JsonObject health = child.getAsJsonObject();
                        text.append("• ").append(valueAt(health, "functionName"))
                                .append(" [").append(valueAt(health, "code")).append("]\n");
                    }
                }
            }
        }
        text.append("\nFunction support values (vehicleFunLearnInfo)\n");
        if (learnInfo == null || learnInfo.entrySet().isEmpty()) {
            text.append("No saved support information.");
        } else {
            for (Map.Entry<String, JsonElement> entry : learnInfo.entrySet()) {
                text.append("• ").append(entry.getKey()).append(" = ")
                        .append(entry.getValue().isJsonNull() ? "null" : entry.getValue().getAsString())
                        .append('\n');
            }
        }
        return text.toString();
    }

    private void renderVehicleFunctionButtons(JsonObject cfVehicle) {
        vehicleFunctionContainer.removeAllViews();
        bleVehicleFunctionButtons.clear();
        JsonArray functions = arrayAt(cfVehicle, "cfFixedList");
        if (functions == null || functions.size() == 0) {
            vehicleFunctionContainer.addView(description("No vehicle remote-function list (cfFixedList)."));
            return;
        }
        for (JsonElement element : functions) {
            if (!element.isJsonObject()) continue;
            JsonObject function = element.getAsJsonObject();
            JsonArray children = arrayAt(function, "cfFixedSecondLevelList");
            if (children != null && children.size() > 0) {
                vehicleFunctionContainer.addView(title(valueAt(function, "functionName") + " (" + valueAt(function, "code") + ")", 16));
                for (JsonElement child : children) {
                    if (child.isJsonObject()) addVehicleFunctionButton(child.getAsJsonObject());
                }
            } else {
                addVehicleFunctionButton(function);
            }
        }
    }

    private void addVehicleFunctionButton(JsonObject function) {
        String functionNo = valueAt(function, "functionNo");
        String code = valueAt(function, "code");
        String name = "None".equals(code) ? valueAt(function, "functionName") : code + " / " + valueAt(function, "functionName");
        int bleFunctionId = mapRemoteFunctionToBle(functionNo);
        if (bleFunctionId <= 0) return;
        Button action = button(name + " (" + functionNo + ")" + (bleFunctionId > 0 ? " · BLE" : " · BLE mapping not confirmed"));
        if (bleFunctionId <= 0) {
            action.setEnabled(false);
        } else {
            action.setEnabled(bleControlReady);
            action.setOnClickListener(v -> confirmAndSendBleCommand(bleFunctionId, name));
            bleVehicleFunctionButtons.add(action);
        }
        vehicleFunctionContainer.addView(action);
    }

    /** cfFixedList numbers are cloud feature identifiers, not BLE command values. */
    private int mapRemoteFunctionToBle(String functionNo) {
        if ("1005".equals(functionNo)) return VehicleBleCommand.LOCK_DOORS;
        if ("1006".equals(functionNo)) return VehicleBleCommand.UNLOCK_ALL_DOORS;
        if ("1007".equals(functionNo)) return VehicleBleCommand.FIND_VEHICLE;
        if ("1008".equals(functionNo)) return VehicleBleCommand.FLASH_LIGHTS;
        if ("1020".equals(functionNo)) return VehicleBleCommand.OPERATE_TRUNK;
        return -1;
    }

    private void confirmAndSendBleCommand(int functionId, String commandName) {
        new AlertDialog.Builder(this)
                .setTitle("Send Vehicle Command")
                .setMessage("Send " + commandName + " directly to the vehicle?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Send", (dialog, which) -> sendBleCommand(functionId, commandName))
                .show();
    }

    private JsonObject objectAt(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonObject()) return null;
        return object.getAsJsonObject(key);
    }

    private JsonArray arrayAt(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonArray()) return null;
        return object.getAsJsonArray(key);
    }

    private String valueAt(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return "None";
        try {
            return object.get(key).getAsString();
        } catch (Exception ignored) {
            return object.get(key).toString();
        }
    }

    private String rudderTypeLabel(String rudderType) {
        if ("1".equals(rudderType)) return "1 (left-hand drive)";
        return rudderType + " (manufacturer code)";
    }

    private void updateSelectedDevice() { if (selectedDevice != null) { String mac = resolveVehicleMac(); selectedDevice.setText(isEmpty(mac) ? "Vehicle BLE MAC: run step 4, Gain Vehicle" : "Vehicle BLE MAC (server-provided): " + mac); } }
    private void loadLogs() { for (int i = 1; i <= 6; i++) { String log = storage.getWatchStepLog(i); if (!log.isEmpty()) stepLogs[i - 1].setText(log); } }
    private void updateLog(int step, String message) { String text = step + ". " + stepName(step) + " [" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()) + "]\n   " + message; storage.setWatchStepLog(step, text); stepLogs[step - 1].setText(text); }
    private String stepName(int step) { String[] names = {"Create QR", "Check Status", "Gain Token", "Gain Vehicle", "Gain Bluetooth", "Connect BLE"}; return names[step - 1]; }
    private String abbreviate(String value) { return value == null ? "null" : value.substring(0, Math.min(value.length(), 12)) + (value.length() > 12 ? "..." : ""); }
    private boolean isEmpty(String value) { return value == null || value.trim().isEmpty(); }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }
    private TextView title(String text, int size) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(getColor(R.color.vehicle_text_primary));
        view.setTextSize(size);
        view.setGravity(Gravity.START);
        view.setPadding(0, dp(12), 0, dp(6));
        return view;
    }

    private TextView description(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(getColor(R.color.vehicle_text_secondary));
        view.setTextSize(14);
        view.setPadding(0, dp(4), 0, dp(12));
        return view;
    }

    private Button button(String text) {
        Button view = new Button(this);
        view.setText(text);
        view.setTextColor(getColor(R.color.vehicle_text_primary));
        view.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.vehicle_surface_elevated)));
        view.setAllCaps(false);
        view.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return view;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void showQrCode(String uuid) {
        try {
            String value = watchKeyService.buildQrCodeContent(uuid, watchKeyService.getWatchImei());
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix matrix = new QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 600, 600, hints);
            int[] pixels = new int[600 * 600];
            for (int y = 0; y < 600; y++) for (int x = 0; x < 600; x++) pixels[y * 600 + x] = matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
            Bitmap bitmap = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888);
            bitmap.setPixels(pixels, 0, 600, 0, 0, 600, 600);
            ImageView image = new ImageView(this); image.setImageBitmap(bitmap); image.setPadding(dp(16), dp(16), dp(16), dp(16));
            new AlertDialog.Builder(this).setTitle("Scan QR with BYD App").setMessage("Complete step 1 authentication, then check status in step 2.").setView(image).setPositiveButton("Close", null).show();
        } catch (Exception error) { updateLog(1, "QR rendering error: " + error.getMessage()); }
    }
}

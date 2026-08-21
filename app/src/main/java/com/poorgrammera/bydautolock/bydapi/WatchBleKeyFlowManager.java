package com.poorgrammera.bydautolock.bydapi;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.poorgrammera.bydautolock.model.QrCodeInfo;
import com.poorgrammera.bydautolock.model.QrCodeState;
import com.poorgrammera.bydautolock.model.TokenInfoBean;
import com.poorgrammera.bydautolock.model.WatchBlueToothKeyStatInfo;
import com.poorgrammera.bydautolock.service.BleAuthPhase;
import com.poorgrammera.bydautolock.service.BleConnectionStatus;
import com.poorgrammera.bydautolock.service.BleGattDataWriteChannel;
import com.poorgrammera.bydautolock.service.BleVehicleAuthSession;
import com.poorgrammera.bydautolock.service.BydWatchStyleBleManager;
import com.poorgrammera.bydautolock.service.GattSessionManager;
import com.poorgrammera.bydautolock.service.VehicleBleCommand;
import com.poorgrammera.bydautolock.service.WatchStyleBleFrameAssembler;
import com.poorgrammera.bydautolock.service.WatchStyleDataSendRecv;
import com.poorgrammera.bydautolock.storage.StorageManager;
import com.poorgrammera.bydblekeycontrol.blecodec.BydBleCodec;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * End-to-end orchestrator for the watch-style BLE door unlock flow:
 *
 * 1. getServerCurrentTime  — sync clock with BYD server
 * 2. createQrCode          — get QR code UUID
 * 3. (user scans QR)       — poll getQrCodeStatus every 3s
 * 4. getToken              — exchange UUID for auth tokens
 * 5. getWatchBlueInfo      — retrieve BLE key material (dk, MAC, password, keyNo)
 * 6. BLE connect + auth    — GATT connect → vehicle authenticate → send commands
 */
public class WatchBleKeyFlowManager {

    private static final String TAG = "WatchBleKeyFlow";
    private static final long QR_POLL_INTERVAL_MS = 3000;
    private static final long QR_POLL_TIMEOUT_MS = 150000; // 2.5 minutes
    private static final long COMMAND_RESPONSE_TIMEOUT_MS = 3000L;
    private static final int CONTROL_RESULT_SUCCESS = 0x01;

    public enum FlowState {
        IDLE,
        SYNCING_TIME,
        CREATING_QR,
        WAITING_QR_SCAN,
        GETTING_TOKEN,
        GETTING_BLE_KEY,
        CONNECTING_BLE,
        AUTHENTICATING,
        READY,
        SENDING_COMMAND,
        DONE,
        ERROR
    }

    public interface FlowListener {
        /** Called on every state transition */
        void onStateChanged(FlowState state, String message);

        /** Called when QR code UUID is received — UI should display QR */
        void onQrCodeReady(String uuid);

        /** Called when BLE key material is retrieved */
        void onBleKeyReady(WatchBlueToothKeyStatInfo info);

        /** Called when the entire flow completes successfully */
        void onFlowComplete(String message);

        /** Called on any unrecoverable error */
        void onFlowError(FlowState failedState, String error);
    }

    /** Reports the vehicle control response separately from a local GATT write failure or timeout. */
    public interface CommandCallback {
        void onVehicleConfirmed(int resultCode, int doorStates);
        void onVehicleRejected(int resultCode, int doorStates);
        void onResponseTimeout();
        void onWriteFailed();
    }

    /** Receives RSSI from the active authenticated GATT connection. */
    public interface ConnectedRssiListener {
        void onRssi(int rssi);
    }

    private final Context context;
    private final BydWatchKeyService watchService;
    private final StorageManager storage;
    private final Handler mainHandler;
    private final FlowListener listener;

    private volatile FlowState currentState = FlowState.IDLE;
    private String qrUuid;
    private TokenInfoBean tokenInfo;
    private WatchBlueToothKeyStatInfo bleKeyInfo;
    private long qrPollStartTime;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private Runnable qrPollRunnable;

    // BLE components
    private GattSessionManager gattSession;
    private BleVehicleAuthSession authSession;
    private WatchStyleBleFrameAssembler.BleFramePayloadListener commandResponseListener;
    private Runnable commandResponseTimeout;
    private long pendingCommandId;
    private long nextCommandId;
    private int syncTimeRetryCount = 0;
    private static final int MAX_SYNC_TIME_RETRIES = 1;

    public WatchBleKeyFlowManager(Context context, BydConfig config, FlowListener listener) {
        this.context = context.getApplicationContext();
        this.watchService = new BydWatchKeyService(context, config);
        this.storage = new StorageManager(context);
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.listener = listener;
    }

    public FlowState getCurrentState() {
        return currentState;
    }

    public void cancel() {
        cancelled.set(true);
        if (qrPollRunnable != null) {
            mainHandler.removeCallbacks(qrPollRunnable);
        }
        if (authSession != null) {
            authSession.cancel();
        }
        clearPendingCommandResponse();
        if (gattSession != null) {
            gattSession.disconnect();
        }
        setState(FlowState.IDLE, "Cancelled");
    }

    // ── Public API ──────────────────────────────────────────────

    /**
     * Start the full flow from step 2 (create QR).
     * (getServerCurrentTime is bypassed as it returns 1007 sign error, local clock is sufficient)
     */
    public void startFlow() {
        cancelled.set(false);
        Log.i(TAG, "=== Starting Watch BLE Key Flow ===");
        step2_createQr();
    }

    /**
     * Resume from step 6 if we already have cached BLE key material.
     */
    public void resumeWithCachedKey() {
        if (!storage.hasBleKey()) {
            listener.onFlowError(FlowState.IDLE, "No saved BLE key. Start full authentication.");
            return;
        }
        cancelled.set(false);
        Log.i(TAG, "=== Resuming with cached BLE key ===");

        // Reconstruct BLE key info from storage
        bleKeyInfo = new WatchBlueToothKeyStatInfo();
        bleKeyInfo.setDk(storage.getBleDk());
        bleKeyInfo.setBlueToothPassword(storage.getBlePassword());
        // Korea's gain/vehicle response supplies the MAC; fall back to a scanned device if absent.
        String cachedMac = storage.getBleMacAddress();
        if (cachedMac == null || cachedMac.isEmpty()) cachedMac = storage.getDeviceMac();
        bleKeyInfo.setBluetoothMacAddress(cachedMac);
        Log.i(TAG, "resumeWithCachedKey: dk=" + (storage.getBleDk() != null ? storage.getBleDk().substring(0, 8) + "..." : "null") + ", mac=" + cachedMac);
        if (storage.getBleKeyNo() >= 0) {
            bleKeyInfo.setEmpowerBluetoothKeyNo(storage.getBleKeyNo());
        }
        bleKeyInfo.setAuthBluetoothProtocol(storage.getBleAuthProtocol());

        // Reconstruct token info for potential re-auth
        if (storage.hasWatchToken()) {
            tokenInfo = new TokenInfoBean();
            tokenInfo.setEncryToken(storage.getWatchEncryToken());
            tokenInfo.setSignToken(storage.getWatchSignToken());
            tokenInfo.setIdentifier(storage.getWatchIdentifier());
            tokenInfo.setUserType(storage.getWatchUserType());
        }

        step6_connectBle();
    }

    /**
     * Send a BLE control command (e.g., 9001=unlock, 9002=lock).
     * Only callable when state == READY.
     */
    public void sendCommand(int functionId) {
        sendCommand(functionId, null);
    }

    /**
     * Vehicle BLE3 advertising can pause while a GATT connection is active. Use this
     * direct GATT RSSI read so proximity monitoring continues after authentication.
     */
    public boolean readConnectedRssi(ConnectedRssiListener listener) {
        if (gattSession == null || gattSession.getBleManager() == null) return false;
        return gattSession.getBleManager().enqueueReadRssi(rssi -> {
            if (listener != null) listener.onRssi(rssi);
        });
    }

    public void sendCommand(int functionId, CommandCallback commandCallback) {
        if (currentState != FlowState.READY) {
            listener.onFlowError(currentState, "BLE connection is not ready. Current state: " + currentState);
            if (commandCallback != null) commandCallback.onWriteFailed();
            return;
        }

        setState(FlowState.SENDING_COMMAND, "Sending command: " + functionId);

        byte[] frame = VehicleBleCommand.createFrame(functionId);
        if (frame == null) {
            setState(FlowState.ERROR, "Unknown function ID: " + functionId);
            listener.onFlowError(FlowState.SENDING_COMMAND, "Unknown function ID: " + functionId);
            if (commandCallback != null) commandCallback.onWriteFailed();
            return;
        }

        String functionName = getFunctionName(functionId);
        int expectedControlCode = BydBleCodec.getControlCode(functionId) & 0xFF;
        if (expectedControlCode == 0xFF) {
            setState(FlowState.ERROR, "Unknown control code for function ID: " + functionId);
            listener.onFlowError(FlowState.SENDING_COMMAND, "Unknown control code for function ID: " + functionId);
            if (commandCallback != null) commandCallback.onWriteFailed();
            return;
        }
        Log.i(TAG, "Sending BLE command: " + functionName + " (" + functionId + "), frame length=" + frame.length);

        clearPendingCommandResponse();
        long commandId = ++nextCommandId;
        pendingCommandId = commandId;
        commandResponseListener = payload -> mainHandler.post(() -> handleCommandResponse(
                commandId, payload, expectedControlCode, functionName, commandCallback));
        WatchStyleBleFrameAssembler.INSTANCE.addListener(commandResponseListener);

        WatchStyleDataSendRecv.INSTANCE.sendFrameData(frame, new WatchStyleDataSendRecv.OnWriteResultListener() {
            @Override
            public void onWriteSuccess() {
                mainHandler.post(() -> {
                    if (pendingCommandId != commandId) return;
                    Log.i(TAG, "BLE command write accepted; waiting for vehicle response: " + functionName);
                    commandResponseTimeout = () -> handleCommandResponseTimeout(
                            commandId, expectedControlCode, functionName, commandCallback);
                    mainHandler.postDelayed(commandResponseTimeout, COMMAND_RESPONSE_TIMEOUT_MS);
                });
            }

            @Override
            public void onWriteFailed() {
                Log.e(TAG, "BLE command send failed: " + functionName);
                mainHandler.post(() -> {
                    if (pendingCommandId != commandId) return;
                    clearPendingCommandResponse();
                    if (commandCallback != null) commandCallback.onWriteFailed();
                    setState(FlowState.ERROR, functionName + " command send failed");
                    listener.onFlowError(FlowState.SENDING_COMMAND, functionName + " send failed");
                });
            }
        });
    }

    private void handleCommandResponse(long commandId, byte[] payload, int expectedControlCode,
                                       String functionName, CommandCallback commandCallback) {
        if (pendingCommandId != commandId || currentState != FlowState.SENDING_COMMAND) return;

        int responseType = BydBleCodec.parseResponseType(payload);
        int commandType = BydBleCodec.parseResponseCommandType(payload);
        int controlCode = BydBleCodec.parseControlCode(payload);
        if (responseType != BydBleCodec.RESPONSE_CONTROL
                || commandType != BydBleCodec.CONTROL_COMMAND_TYPE
                || controlCode != expectedControlCode) {
            return;
        }

        int resultCode = BydBleCodec.parseControlResult(payload);
        int doorStates = BydBleCodec.parseDoorStates(payload);
        clearPendingCommandResponse();
        if (resultCode == CONTROL_RESULT_SUCCESS) {
            Log.i(TAG, "Vehicle confirmed BLE command: " + functionName
                    + " control=0x" + hexByte(controlCode)
                    + " result=0x" + hexByte(resultCode)
                    + " doorStates=0x" + hexByte(doorStates));
            setState(FlowState.READY, functionName + " confirmed by vehicle");
            if (commandCallback != null) commandCallback.onVehicleConfirmed(resultCode, doorStates);
            listener.onFlowComplete(functionName + " was confirmed by the vehicle.");
        } else {
            Log.w(TAG, "Vehicle rejected BLE command: " + functionName
                    + " control=0x" + hexByte(controlCode)
                    + " result=0x" + hexByte(resultCode)
                    + " doorStates=0x" + hexByte(doorStates));
            setState(FlowState.READY, functionName + " rejected by vehicle (result 0x" + hexByte(resultCode) + ")");
            if (commandCallback != null) commandCallback.onVehicleRejected(resultCode, doorStates);
        }
    }

    private void handleCommandResponseTimeout(long commandId, int expectedControlCode,
                                              String functionName, CommandCallback commandCallback) {
        if (pendingCommandId != commandId || currentState != FlowState.SENDING_COMMAND) return;
        clearPendingCommandResponse();
        Log.w(TAG, "Vehicle response timeout: " + functionName
                + " control=0x" + hexByte(expectedControlCode));
        setState(FlowState.READY, functionName + " response not received");
        if (commandCallback != null) commandCallback.onResponseTimeout();
    }

    private void clearPendingCommandResponse() {
        Runnable timeout = commandResponseTimeout;
        commandResponseTimeout = null;
        if (timeout != null) mainHandler.removeCallbacks(timeout);
        WatchStyleBleFrameAssembler.BleFramePayloadListener responseListener = commandResponseListener;
        commandResponseListener = null;
        if (responseListener != null) WatchStyleBleFrameAssembler.INSTANCE.removeListener(responseListener);
        pendingCommandId = 0L;
    }

    private static String hexByte(int value) {
        return String.format(java.util.Locale.ROOT, "%02X", value & 0xFF);
    }

    // ── Step 1: Sync server time ────────────────────────────────

    private void step1_syncTime() {
        setState(FlowState.SYNCING_TIME, "Synchronizing server time… (attempt " + (syncTimeRetryCount + 1) + ")");

        watchService.getServerCurrentTime(new BydWatchKeyService.Callback<String>() {
            @Override
            public void onSuccess(String serverTime) {
                if (cancelled.get()) return;
                Log.i(TAG, "Step 1 OK: serverTime=" + serverTime);
                syncTimeRetryCount = 0;

                // Update time difference for subsequent requests
                try {
                    long st = Long.parseLong(serverTime);
                    watchService.updateTimeDifference(st);
                } catch (NumberFormatException e) {
                    Log.w(TAG, "Failed to parse serverTime: " + serverTime);
                }

                mainHandler.post(() -> step2_createQr());
            }

            @Override
            public void onError(String msg, Throwable t) {
                if (cancelled.get()) return;
                Log.e(TAG, "Step 1 FAILED (attempt " + (syncTimeRetryCount + 1) + "): " + msg, t);

                // Reset timeDifference after the first failure, then retry once.
                if (syncTimeRetryCount < MAX_SYNC_TIME_RETRIES) {
                    syncTimeRetryCount++;
                    Log.i(TAG, "Step 1 retrying... resetting timeDifference to 0");
                    watchService.updateTimeDifference(System.currentTimeMillis());
                    mainHandler.postDelayed(() -> step1_syncTime(), 1000);
                    return;
                }

                syncTimeRetryCount = 0;
                String debugInfo = " [version=" + new DeviceInfoProvider(context).appVersionCodeString()
                        + ", ts=" + System.currentTimeMillis() + "]";
                mainHandler.post(() -> {
                    setState(FlowState.ERROR, "Time synchronization failed: " + msg + debugInfo);
                    listener.onFlowError(FlowState.SYNCING_TIME, msg + debugInfo);
                });
            }
        });
    }

    // ── Step 2: Create QR code ──────────────────────────────────

    private void step2_createQr() {
        setState(FlowState.CREATING_QR, "Creating QR code…");

        watchService.createQrCode(new BydWatchKeyService.Callback<QrCodeInfo>() {
            @Override
            public void onSuccess(QrCodeInfo info) {
                if (cancelled.get()) return;
                qrUuid = info != null ? info.getUuid() : null;
                if (qrUuid == null || qrUuid.isEmpty()) {
                    mainHandler.post(() -> {
                        setState(FlowState.ERROR, "QR UUID is empty.");
                        listener.onFlowError(FlowState.CREATING_QR, "QR UUID is empty.");
                    });
                    return;
                }

                Log.i(TAG, "Step 2 OK: uuid=" + qrUuid);
                mainHandler.post(() -> {
                    setState(FlowState.WAITING_QR_SCAN, "Scan the QR code with the official BYD app");
                    listener.onQrCodeReady(qrUuid);
                    step3_pollQrStatus();
                });
            }

            @Override
            public void onError(String msg, Throwable t) {
                if (cancelled.get()) return;
                Log.e(TAG, "Step 2 FAILED: " + msg, t);
                mainHandler.post(() -> {
                    setState(FlowState.ERROR, "QR creation failed: " + msg);
                    listener.onFlowError(FlowState.CREATING_QR, msg);
                });
            }
        });
    }

    // ── Step 3: Poll QR code scan status ────────────────────────

    private void step3_pollQrStatus() {
        qrPollStartTime = System.currentTimeMillis();
        scheduleQrPoll();
    }

    private void scheduleQrPoll() {
        qrPollRunnable = () -> {
            if (cancelled.get()) return;

            if (System.currentTimeMillis() - qrPollStartTime > QR_POLL_TIMEOUT_MS) {
                setState(FlowState.ERROR, "QR scan timed out (150 seconds)");
                listener.onFlowError(FlowState.WAITING_QR_SCAN, "QR code expired. Try again.");
                return;
            }

            watchService.getQrCodeStatus(qrUuid, new BydWatchKeyService.Callback<QrCodeState>() {
                @Override
                public void onSuccess(QrCodeState state) {
                    if (cancelled.get()) return;

                    String status = state != null ? state.getCodeStatus() : null;
                    Log.d(TAG, "Step 3 poll: codeStatus=" + status);

                    if ("2".equals(status)) {
                        // Scanned! Proceed to step 4
                        String appChannel = state.getAppChannel();
                        Log.i(TAG, "Step 3 OK: QR scanned, appChannel=" + appChannel);
                        mainHandler.post(() -> step4_getToken(appChannel));
                    } else if ("4".equals(status) || "3".equals(status)) {
                        // Expired or rejected
                        mainHandler.post(() -> {
                            setState(FlowState.ERROR, "QR code expired or was rejected.");
                            listener.onFlowError(FlowState.WAITING_QR_SCAN, "QR code expired.");
                        });
                    } else {
                        // Still waiting (status 0 or 1), poll again
                        mainHandler.postDelayed(() -> scheduleQrPoll(), QR_POLL_INTERVAL_MS);
                    }
                }

                @Override
                public void onError(String msg, Throwable t) {
                    if (cancelled.get()) return;
                    Log.w(TAG, "Step 3 poll error: " + msg);
                    // Retry on transient errors
                    mainHandler.postDelayed(() -> scheduleQrPoll(), QR_POLL_INTERVAL_MS);
                }
            });
        };
        mainHandler.post(qrPollRunnable);
    }

    // ── Step 4: Get auth token ──────────────────────────────────

    private void step4_getToken(String appChannel) {
        setState(FlowState.GETTING_TOKEN, "Getting authentication token…");

        watchService.getToken(qrUuid, appChannel, new BydWatchKeyService.Callback<TokenInfoBean>() {
            @Override
            public void onSuccess(TokenInfoBean token) {
                if (cancelled.get()) return;
                tokenInfo = token;

                if (tokenInfo == null || tokenInfo.getEncryToken() == null) {
                    mainHandler.post(() -> {
                        setState(FlowState.ERROR, "Token is empty.");
                        listener.onFlowError(FlowState.GETTING_TOKEN, "Token is empty.");
                    });
                    return;
                }

                Log.i(TAG, "Step 4 OK: encryToken=" +
                        tokenInfo.getEncryToken().substring(0, Math.min(8, tokenInfo.getEncryToken().length())) + "...");

                // Save tokens
                storage.setWatchEncryToken(tokenInfo.getEncryToken());
                storage.setWatchSignToken(tokenInfo.getSignToken());
                storage.setWatchIdentifier(tokenInfo.getIdentifier());
                storage.setWatchUserType(tokenInfo.getUserType());
                if (tokenInfo.getVin() != null) {
                    storage.setWatchVin(tokenInfo.getVin());
                }

                mainHandler.post(() -> step5a_getVehicleConfig());
            }

            @Override
            public void onError(String msg, Throwable t) {
                if (cancelled.get()) return;
                Log.e(TAG, "Step 4 FAILED: " + msg, t);
                mainHandler.post(() -> {
                    setState(FlowState.ERROR, "Token request failed: " + msg);
                    listener.onFlowError(FlowState.GETTING_TOKEN, msg);
                });
            }
        });
    }

    // ── Step 5a: Get vehicle config ─────────────────────────────

    private void step5a_getVehicleConfig() {
        setState(FlowState.GETTING_BLE_KEY, "Getting vehicle information (gain/vehicle)…");

        watchService.getVehicleConfig(tokenInfo, new BydWatchKeyService.Callback<com.google.gson.JsonObject>() {
            @Override
            public void onSuccess(com.google.gson.JsonObject result) {
                if (cancelled.get()) return;
                Log.i(TAG, "Step 5a (gain/vehicle) OK");
                mainHandler.post(() -> step5b_getBleKey());
            }

            @Override
            public void onError(String msg, Throwable t) {
                if (cancelled.get()) return;
                Log.w(TAG, "Step 5a (gain/vehicle) WARNING: " + msg + " (continuing to gain/bluetooth)");
                mainHandler.post(() -> step5b_getBleKey());
            }
        });
    }

    // ── Step 5b: Get BLE key material ────────────────────────────

    private void step5b_getBleKey() {
        setState(FlowState.GETTING_BLE_KEY, "Getting BLE key information (gain/bluetooth)…");

        watchService.getWatchBlueInfo(tokenInfo, new BydWatchKeyService.Callback<WatchBlueToothKeyStatInfo>() {
            @Override
            public void onSuccess(WatchBlueToothKeyStatInfo info) {
                if (cancelled.get()) return;
                bleKeyInfo = info;

                if (bleKeyInfo == null) {
                    mainHandler.post(() -> {
                        setState(FlowState.ERROR, "BLE key information is empty.");
                        listener.onFlowError(FlowState.GETTING_BLE_KEY, "BLE key information is empty.");
                    });
                    return;
                }

                Log.i(TAG, "Step 5 OK: dk=" +
                        (bleKeyInfo.getDk() != null ? bleKeyInfo.getDk().substring(0, Math.min(8, bleKeyInfo.getDk().length())) + "..." : "null") +
                        ", mac=" + bleKeyInfo.getBluetoothMacAddress() +
                        ", protocol=" + bleKeyInfo.getAuthBluetoothProtocol());

                // Save BLE key material
                storage.setBleDk(bleKeyInfo.getDk());
                storage.setBlePassword(bleKeyInfo.getBlueToothPassword());
                storage.setBleMacAddress(bleKeyInfo.getBluetoothMacAddress());
                if (bleKeyInfo.getEmpowerBluetoothKeyNo() != null) {
                    storage.setBleKeyNo(bleKeyInfo.getEmpowerBluetoothKeyNo());
                }
                if (bleKeyInfo.getAuthBluetoothProtocol() != null) {
                    storage.setBleAuthProtocol(bleKeyInfo.getAuthBluetoothProtocol());
                }
                if (bleKeyInfo.getVin() != null) {
                    storage.setWatchVin(bleKeyInfo.getVin());
                }

                // Also set device MAC for the main BLE service
                if (bleKeyInfo.getBluetoothMacAddress() != null) {
                    storage.setDeviceMac(bleKeyInfo.getBluetoothMacAddress());
                }

                mainHandler.post(() -> {
                    listener.onBleKeyReady(bleKeyInfo);
                    step6_connectBle();
                });
            }

            @Override
            public void onError(String msg, Throwable t) {
                if (cancelled.get()) return;
                Log.e(TAG, "Step 5 FAILED: " + msg, t);
                mainHandler.post(() -> {
                    setState(FlowState.ERROR, "BLE key request failed: " + msg);
                    listener.onFlowError(FlowState.GETTING_BLE_KEY, msg);
                });
            }
        });
    }

    // ── Step 6: BLE connect + authenticate ──────────────────────

    private void step6_connectBle() {
        // Korea's gain/vehicle response supplies the saved server MAC.
        // Priority: bleKeyInfo.mac → storage.getBleMacAddress() → storage.getDeviceMac() (set in BT settings)
        String mac = null;
        if (bleKeyInfo != null && bleKeyInfo.getBluetoothMacAddress() != null && !bleKeyInfo.getBluetoothMacAddress().isEmpty()) {
            mac = bleKeyInfo.getBluetoothMacAddress();
        }
        if (mac == null || mac.isEmpty()) mac = storage.getBleMacAddress();
        if (mac == null || mac.isEmpty()) mac = storage.getDeviceMac();

        if (mac == null || mac.isEmpty()) {
            setState(FlowState.ERROR, "No BLE MAC address. Register the vehicle Bluetooth MAC in settings.");
            listener.onFlowError(FlowState.CONNECTING_BLE, "No BLE MAC address. Register the vehicle Bluetooth MAC in settings.");
            return;
        }

        setState(FlowState.CONNECTING_BLE, "Connecting BLE: " + mac);

        String dk = bleKeyInfo != null ? bleKeyInfo.getDk() : storage.getBleDk();
        if (dk != null && !dk.isEmpty()) {
            Log.i(TAG, "Korean dkey material is available");
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            setState(FlowState.ERROR, "Bluetooth is disabled.");
            listener.onFlowError(FlowState.CONNECTING_BLE, "Turn on Bluetooth.");
            return;
        }

        // Create GATT session manager
        gattSession = new GattSessionManager(
                context,
                mainHandler,
                line -> Log.d(TAG, "[GATT] " + line),
                status -> {
                    Log.d(TAG, "[GATT] status: " + status);
                    listener.onStateChanged(currentState, "BLE: " + status);
                },
                bleManager -> {
                    // The authentication code sends frames through this shared channel.
                    // Register it before starting authentication, otherwise the first
                    // wake-up frame fails locally without reaching the vehicle.
                    WatchStyleDataSendRecv.INSTANCE.registerDataWriteChannel(
                            new BleGattDataWriteChannel(() -> gattSession != null ? gattSession.getBleManager() : null)
                    );
                    Log.i(TAG, "[GATT] ready, starting auth...");
                    startAuthentication();
                },
                endMsg -> {
                    Log.w(TAG, "[GATT] session ended: " + endMsg);
                    // A ready BLE session can be dropped when the vehicle goes out of range.
                    // Report it as an error so VehicleAccessService can recreate step 6 when
                    // the saved vehicle MAC is discovered again. cancel() moves to IDLE first,
                    // which deliberately avoids triggering an automatic reconnect.
                    if (currentState != FlowState.IDLE && !cancelled.get()) {
                        setState(FlowState.ERROR, "BLE disconnected: " + endMsg);
                        listener.onFlowError(FlowState.CONNECTING_BLE, "BLE disconnected: " + endMsg);
                    }
                },
                (name, addr) -> Log.d(TAG, "[GATT] device: " + name + " / " + addr),
                bondStatus -> Log.d(TAG, "[GATT] bond: " + bondStatus),
                data -> {
                    // Notification data → forward to frame assembler
                    WatchStyleBleFrameAssembler.INSTANCE.processReceiveFrames(data);
                }
        );

        gattSession.connect(adapter, mac);
    }

    private void startAuthentication() {
        setState(FlowState.AUTHENTICATING, "Authenticating vehicle…");

        authSession = new BleVehicleAuthSession(
                mainHandler,
                line -> Log.d(TAG, "[AUTH] " + line),
                (phase, detail) -> {
                    Log.d(TAG, "[AUTH] phase=" + phase + " detail=" + detail);
                    if (phase == BleAuthPhase.AUTH_PASS) {
                        setState(FlowState.READY, "Authentication complete. Ready to send commands.");
                        listener.onFlowComplete("BLE authentication succeeded. Vehicle control is available.");
                    } else if (phase == BleAuthPhase.FAILED) {
                        setState(FlowState.ERROR, "Authentication failed: " + detail);
                        listener.onFlowError(FlowState.AUTHENTICATING, "Authentication failed: " + detail);
                    }
                }
        );

        authSession.start(bleKeyInfo);
    }

    // ── Helpers ─────────────────────────────────────────────────

    private void setState(FlowState state, String message) {
        this.currentState = state;
        Log.i(TAG, "State → " + state + ": " + message);
        listener.onStateChanged(state, message);
    }

    private String getFunctionName(int functionId) {
        switch (functionId) {
            case VehicleBleCommand.UNLOCK_ALL_DOORS: return "Unlock all doors";
            case VehicleBleCommand.LOCK_DOORS: return "Lock doors";
            case VehicleBleCommand.START_CLIMATE: return "Turn on air conditioning";
            case VehicleBleCommand.STOP_CLIMATE: return "Turn off air conditioning";
            case VehicleBleCommand.FIND_VEHICLE: return "Find vehicle";
            case VehicleBleCommand.FLASH_LIGHTS: return "Flash lights";
            case VehicleBleCommand.UNLOCK_TRUNK: return "Open rear door";
            case VehicleBleCommand.OPEN_POWER_TRUNK: return "Open power trunk";
            case VehicleBleCommand.CLOSE_POWER_TRUNK: return "Close power trunk";
            case VehicleBleCommand.ONE_KEY_SHUTDOWN: return "One-key shutdown";
            default: return "Command " + functionId;
        }
    }
}

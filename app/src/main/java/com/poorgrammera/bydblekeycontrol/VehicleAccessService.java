package com.poorgrammera.bydblekeycontrol;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.poorgrammera.bydautolock.bydapi.BydConfig;
import com.poorgrammera.bydautolock.bydapi.BydWatchKeyService;
import com.poorgrammera.bydautolock.bydapi.WatchBleKeyFlowManager;
import com.poorgrammera.bydautolock.bydapi.WatchCredentialManager;
import com.poorgrammera.bydautolock.model.RemoteControlResult;
import com.poorgrammera.bydautolock.model.RemoteControlStartResponse;
import com.poorgrammera.bydautolock.model.TokenInfoBean;
import com.poorgrammera.bydautolock.model.WatchBlueToothKeyStatInfo;
import com.poorgrammera.bydautolock.service.VehicleBleCommand;
import com.poorgrammera.bydautolock.storage.StorageManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;
import java.text.SimpleDateFormat;

/** Foreground worker that monitors the saved vehicle BLE key and performs vehicle controls. */
public class VehicleAccessService extends Service {
    private static final String TAG = "VehicleAccessService";
    private static final String CHANNEL_ID = "vehicle_access";
    private static final int NOTIFICATION_ID = 41;
    private static final long AUTO_CONTROL_COOLDOWN_MS = 30_000L;
    private static final long AUTH_RETRY_MS = 15_000L;
    private static final long RECENT_VEHICLE_SIGHTING_MS = 15_000L;
    private static final long RECONNECT_SCAN_STALE_MS = 12_000L;
    private static final long RECONNECT_SCAN_WATCHDOG_MS = 4_000L;
    private static final long[] DIRECT_RECONNECT_DELAYS_MS = {1_000L, 2_000L, 5_000L, 10_000L};
    private static final int BLE_CONNECT_RSSI_MIN_DBM = -90;
    private static final int BLE_DISCONNECT_RSSI_MAX_DBM = -95;
    private static final long BLE_CONNECT_SCAN_FRESH_MS = 3_000L;
    private static final long BLE_DISCONNECT_DWELL_MS = 3_000L;
    private static final long BLE_DISTANCE_DISCONNECT_COOLDOWN_MS = 5_000L;
    // Diagnostic mode: 4 Hz is fast enough for proximity testing without flooding the GATT queue.
    private static final long CONNECTED_RSSI_POLL_MS = 250L;
    private static final long NOTIFICATION_UPDATE_INTERVAL_MS = 2_000L;
    private static final int RSSI_MEDIAN_WINDOW = 5;
    private static final float RSSI_EMA_ALPHA = 0.30f;
    private static final long UNLOCK_DWELL_MS = 1_000L;
    private static final long LOCK_DWELL_MS = 4_000L;
    private static final int MAX_CONTROL_LOG_ENTRIES = 20;
    private static final int MAX_REMOTE_RESULT_POLLS = 10;

    public static final String ACTION_START = "com.poorgrammera.bydblekeycontrol.START_VEHICLE_ACCESS";
    public static final String ACTION_STOP = "com.poorgrammera.bydblekeycontrol.STOP_VEHICLE_ACCESS";
    public static final String ACTION_AUTH_REQUIRED = "com.poorgrammera.bydblekeycontrol.AUTH_REQUIRED";
    public static final String EXTRA_COMMAND = "vehicle_command";
    public static final String EXTRA_DASHBOARD_VISIBLE = "dashboard_visible";
    public static final String COMMAND_LOCK = "LOCK";
    public static final String COMMAND_UNLOCK = "UNLOCK";
    public static final String COMMAND_OPEN_TRUNK = "OPEN_TRUNK";
    public static final String COMMAND_CLOSE_TRUNK = "CLOSE_TRUNK";
    public static final String COMMAND_OPEN_WINDOWS = "OPEN_WINDOWS";
    public static final String COMMAND_CLOSE_WINDOWS = "CLOSE_WINDOWS";
    public static final String COMMAND_START_CLIMATE = "START_CLIMATE";
    public static final String COMMAND_STOP_CLIMATE = "STOP_CLIMATE";
    public static final String COMMAND_FLASH = "FLASH";
    public static final String COMMAND_FIND = "FIND";

    public static final class Status {
        public final String scanner;
        public final String ble;
        public final String lastAction;
        public final int rssi;
        public final boolean scanning;
        public final String countdown;

        Status(String scanner, String ble, String lastAction, int rssi, boolean scanning) {
            this(scanner, ble, lastAction, rssi, scanning, "");
        }

        Status(String scanner, String ble, String lastAction, int rssi, boolean scanning, String countdown) {
            this.scanner = scanner;
            this.ble = ble;
            this.lastAction = lastAction;
            this.rssi = rssi;
            this.scanning = scanning;
            this.countdown = countdown;
        }
    }

    private static volatile Status status = new Status("Waiting for service to start", "Disconnected", "-", Integer.MIN_VALUE, false);
    private static volatile boolean serviceRunning;
    private static final Deque<String> controlLogEntries = new ArrayDeque<>();
    public static Status getStatus() { return status; }
    public static boolean isRunning() { return serviceRunning; }

    /** A short in-app audit trail; intentionally excludes payloads and credentials. */
    public static String getControlLogText() {
        synchronized (controlLogEntries) {
            if (controlLogEntries.isEmpty()) return "No control requests yet.";
            StringBuilder text = new StringBuilder();
            for (String entry : controlLogEntries) {
                if (text.length() > 0) text.append('\n');
                text.append(entry);
            }
            return text.toString();
        }
    }

    public static void start(Context context) {
        new StorageManager(context).setServiceEnabled(true);
        startForegroundService(context);
    }

    /** Restores monitoring without overriding a user-selected stopped state. */
    public static void startIfEnabled(Context context) {
        if (!new StorageManager(context).isServiceEnabled()) return;
        startForegroundService(context);
    }

    private static void startForegroundService(Context context) {
        Intent intent = new Intent(context, VehicleAccessService.class).setAction(ACTION_START);
        try {
            ContextCompat.startForegroundService(context, intent);
        } catch (RuntimeException error) {
            Log.e(TAG, "Unable to start foreground service", error);
        }
    }

    public static void sendCommand(Context context, String command) {
        Intent intent = new Intent(context, VehicleAccessService.class).setAction(ACTION_START);
        intent.putExtra(EXTRA_COMMAND, command);
        try {
            ContextCompat.startForegroundService(context, intent);
        } catch (RuntimeException error) {
            Log.e(TAG, "Unable to dispatch vehicle command", error);
        }
    }

    public static void stop(Context context) {
        new StorageManager(context).setServiceEnabled(false);
        Intent intent = new Intent(context, VehicleAccessService.class).setAction(ACTION_STOP);
        try {
            ContextCompat.startForegroundService(context, intent);
        } catch (RuntimeException error) {
            Log.e(TAG, "Unable to stop vehicle access service", error);
            context.stopService(new Intent(context, VehicleAccessService.class));
        }
    }

    /** Requests a scan refresh when the monitoring dashboard visibility changes. */
    public static void setDashboardVisible(Context context, boolean visible) {
        if (!serviceRunning) return;
        Intent intent = new Intent(context, VehicleAccessService.class).setAction(ACTION_START);
        intent.putExtra(EXTRA_DASHBOARD_VISIBLE, visible);
        try {
            ContextCompat.startForegroundService(context, intent);
        } catch (RuntimeException error) {
            Log.e(TAG, "Unable to update dashboard visibility", error);
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private StorageManager storage;
    private BluetoothLeScanner scanner;
    private boolean scanning;
    private long lastAutoControlAt;
    private long lastAuthAttemptAt;
    private long lastVehicleSeenAt;
    private long lastReconnectScanRestartAt;
    private long weakConnectedRssiAt;
    private long bleConnectionCooldownUntil;
    private float smoothedRssi = Float.NaN;
    private final Deque<Integer> recentRssiSamples = new ArrayDeque<>(RSSI_MEDIAN_WINDOW);
    private long unlockThresholdReachedAt;
    private long lockThresholdReachedAt;
    private String countdownText = "";
    private boolean nearVehicle;
    private boolean authInProgress;
    private boolean restRecoveryInProgress;
    private boolean reconnectPending;
    private boolean phoneConnectedToPower;
    private boolean batteryReceiverRegistered;
    private int directReconnectAttempt;
    private WatchBleKeyFlowManager bleFlow;
    private Runnable directReconnectRunnable;
    private Runnable distanceScanResumeRunnable;
    private String lastBleConnectionLog = "";
    private long lastNotificationUpdateAt;
    private String lastNotificationText = "";

    private final BroadcastReceiver batteryStateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            updatePowerConnectionState(intent);
        }
    };

    private final Runnable connectedRssiPoll = new Runnable() {
        @Override public void run() {
            if (shouldMonitorVehicle() && bleFlow != null
                    && bleFlow.getCurrentState() == WatchBleKeyFlowManager.FlowState.READY) {
                boolean requested = bleFlow.readConnectedRssi(rssi -> {
                    updateFilteredRssi(rssi, "GATT");
                    publish(getString(R.string.scanner_connected_rssi), currentBleText(), status.lastAction);
                    enforceConnectedRssiDisconnect();
                    processAutomaticThresholds();
                });
                if (!requested) Log.d(TAG, "GATT RSSI read was not available yet");
            }
            handler.postDelayed(this, CONNECTED_RSSI_POLL_MS);
        }
    };

    /** Recovers from Android BLE scans that appear active but stop delivering target advertisements after a disconnect. */
    private final Runnable reconnectScanWatchdog = new Runnable() {
        @Override public void run() {
            if (reconnectPending && shouldMonitorVehicle()) {
                long now = System.currentTimeMillis();
                if (!scanning) {
                    startScan();
                } else if (now - lastVehicleSeenAt >= RECONNECT_SCAN_STALE_MS
                        && now - lastReconnectScanRestartAt >= RECONNECT_SCAN_STALE_MS) {
                    lastReconnectScanRestartAt = now;
                    publish(getString(R.string.scanner_restarting), currentBleText(), status.lastAction);
                    stopScan();
                    startScan();
                }
            }
            handler.postDelayed(this, RECONNECT_SCAN_WATCHDOG_MS);
        }
    };

    private final Runnable countdownTicker = new Runnable() {
        @Override public void run() {
            if (phoneConnectedToPower) {
                setCountdown(getString(R.string.automatic_control_paused_charging));
            } else if (lastAutoControlAt > 0L) {
                long remaining = AUTO_CONTROL_COOLDOWN_MS - (System.currentTimeMillis() - lastAutoControlAt);
                if (remaining > 0L) setCountdown("Automatic-control cooldown: " + secondsCeil(remaining) + " seconds remaining");
                else if (!countdownText.isEmpty()) setCountdown("");
            }
            handler.postDelayed(this, 1_000L);
        }
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            if (result == null || result.getDevice() == null || !isSavedVehicle(result.getDevice().getAddress())) return;
            lastVehicleSeenAt = System.currentTimeMillis();
            int raw = result.getRssi();
            updateFilteredRssi(raw, "advertising");
            publish(getString(R.string.scanner_vehicle_detected), currentBleText(), status.lastAction);
            if (isBleConnectRssiEligible()) {
                if (reconnectPending) cancelScheduledReconnect();
                ensureBleAuthentication(reconnectPending);
            } else if (!isBleReady()) {
                publish(getString(R.string.scanner_signal_too_weak), currentBleText(), status.lastAction);
            }
            processAutomaticThresholds();
        }

        @Override public void onScanFailed(int errorCode) {
            scanning = false;
            publishImportant(getString(R.string.scanner_failed, errorCode), currentBleText(), status.lastAction,
                    getString(R.string.scanner_failed, errorCode));
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        serviceRunning = true;
        storage = new StorageManager(this);
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification(getString(R.string.notification_waiting)));
        Intent batteryState = ContextCompat.registerReceiver(this, batteryStateReceiver,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED);
        batteryReceiverRegistered = true;
        if (batteryState != null) updatePowerConnectionState(batteryState);
        handler.post(connectedRssiPoll);
        handler.post(countdownTicker);
        handler.post(reconnectScanWatchdog);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            storage.setServiceEnabled(false);
            disconnectAndStop();
            return START_NOT_STICKY;
        }
        String command = intent == null ? null : intent.getStringExtra(EXTRA_COMMAND);
        if (intent != null && intent.hasExtra(EXTRA_DASHBOARD_VISIBLE)) {
            startScan();
        }
        if (!TextUtils.isEmpty(command)) executeCommand(command, false);
        else if (!storage.hasBleKey() || !storage.isServiceEnabled()) stopSelf();
        else {
            startScan();
        }
        return START_STICKY;
    }

    /** Explicit service-stop path: end the active GATT connection before destroying the service. */
    private void disconnectAndStop() {
        reconnectPending = false;
        directReconnectAttempt = 0;
        cancelScheduledReconnect();
        stopScan();
        if (bleFlow != null) {
            BleConnectionCoordinator.release(BleConnectionCoordinator.Owner.VEHICLE_ACCESS_SERVICE, bleFlow);
            bleFlow.cancel(); // Cancels the flow and calls GattSessionManager.disconnect().
            bleFlow = null;
        }
        stopSelf();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        serviceRunning = false;
        handler.removeCallbacksAndMessages(null);
        if (batteryReceiverRegistered) {
            unregisterReceiver(batteryStateReceiver);
            batteryReceiverRegistered = false;
        }
        stopScan();
        if (bleFlow != null) {
            BleConnectionCoordinator.release(BleConnectionCoordinator.Owner.VEHICLE_ACCESS_SERVICE, bleFlow);
            bleFlow.cancel();
        }
        super.onDestroy();
    }

    private boolean hasBlePermissions() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED);
    }

    private void startScan() {
        if (scanning || !storage.hasBleKey()) return;
        String vehicleMac = savedVehicleMac();
        if (TextUtils.isEmpty(vehicleMac) || !BluetoothAdapter.checkBluetoothAddress(vehicleMac)) {
            Log.w(TAG, "BLE scan not started: saved vehicle MAC is missing or invalid");
            return;
        }
        if (!hasBlePermissions()) {
            recordBleConnectionEvent(getString(R.string.ble_permission_missing));
            publishImportant(getString(R.string.scanner_permission_required), currentBleText(), status.lastAction,
                    getString(R.string.notification_permission_required));
            return;
        }
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            recordBleConnectionEvent(getString(R.string.ble_disabled));
            publishImportant(getString(R.string.scanner_bluetooth_disabled), currentBleText(), status.lastAction,
                    getString(R.string.notification_bluetooth_disabled));
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) return;
        try {
            // Test app: use LOW_LATENCY and the exact saved vehicle MAC so scanning continues with the screen off.
            ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            ScanFilter vehicleFilter = new ScanFilter.Builder().setDeviceAddress(vehicleMac).build();
            scanner.startScan(Collections.singletonList(vehicleFilter), settings, scanCallback);
            scanning = true;
            publish(getString(R.string.scanner_scanning), currentBleText(), status.lastAction);
        } catch (SecurityException error) {
            recordBleConnectionEvent(getString(R.string.scanner_permission_error));
            publishImportant(getString(R.string.scanner_permission_error), currentBleText(), status.lastAction,
                    getString(R.string.scanner_permission_error));
        }
    }

    private void stopScan() {
        if (!scanning || scanner == null) return;
        try { scanner.stopScan(scanCallback); } catch (SecurityException ignored) { }
        scanning = false;
    }

    private boolean isSavedVehicle(String address) {
        String mac = savedVehicleMac();
        return !TextUtils.isEmpty(mac) && mac.equalsIgnoreCase(address);
    }

    private String savedVehicleMac() {
        String mac = storage.getBleMacAddress();
        return TextUtils.isEmpty(mac) ? storage.getDeviceMac() : mac;
    }

    private boolean shouldMonitorVehicle() {
        return serviceRunning && storage != null && storage.hasBleKey();
    }

    /** Starts step 6 only from a fresh target advertisement at or above the connection threshold. */
    private boolean isBleConnectRssiEligible() {
        return recentRssiSamples.size() >= RSSI_MEDIAN_WINDOW
                && !Float.isNaN(smoothedRssi)
                && smoothedRssi >= BLE_CONNECT_RSSI_MIN_DBM
                && System.currentTimeMillis() >= bleConnectionCooldownUntil
                && System.currentTimeMillis() - lastVehicleSeenAt <= BLE_CONNECT_SCAN_FRESH_MS;
    }

    private void ensureBleAuthentication() {
        ensureBleAuthentication(false);
    }

    /** A forced attempt is reserved for reconnect recovery after a freshly detected vehicle advertisement. */
    private void ensureBleAuthentication(boolean force) {
        if (bleFlow != null) {
            WatchBleKeyFlowManager.FlowState state = bleFlow.getCurrentState();
            if (state == WatchBleKeyFlowManager.FlowState.READY
                    || state == WatchBleKeyFlowManager.FlowState.CONNECTING_BLE
                    || state == WatchBleKeyFlowManager.FlowState.AUTHENTICATING
                    || state == WatchBleKeyFlowManager.FlowState.SENDING_COMMAND) return;
        }
        long now = System.currentTimeMillis();
        if (authInProgress || (!force && now - lastAuthAttemptAt < AUTH_RETRY_MS)) return;
        authInProgress = true;
        lastAuthAttemptAt = now;
        recordBleConnectionEvent(getString(R.string.ble_connection_start));
        publishImportant(status.scanner, getString(R.string.ble_authenticating), status.lastAction,
                getString(R.string.notification_ble_auth_start));
        if (bleFlow != null) {
            BleConnectionCoordinator.release(BleConnectionCoordinator.Owner.VEHICLE_ACCESS_SERVICE, bleFlow);
            bleFlow.cancel();
        }
        WatchBleKeyFlowManager nextBleFlow = new WatchBleKeyFlowManager(this, BydConfig.fromRegion(storage.getRegion()), new WatchBleKeyFlowManager.FlowListener() {
            @Override public void onStateChanged(WatchBleKeyFlowManager.FlowState state, String message) {
                recordBleConnectionEvent(state + ": " + message);
                if (state == WatchBleKeyFlowManager.FlowState.READY || state == WatchBleKeyFlowManager.FlowState.ERROR) authInProgress = false;
                if (state == WatchBleKeyFlowManager.FlowState.READY) {
                    reconnectPending = false;
                    directReconnectAttempt = 0;
                    cancelScheduledReconnect();
                    publishImportant(status.scanner, state + ": " + message, status.lastAction, getString(R.string.notification_ble_auth_complete));
                } else if (state == WatchBleKeyFlowManager.FlowState.ERROR) {
                    publishImportant(status.scanner, state + ": " + message, status.lastAction, getString(R.string.notification_ble_auth_failed));
                    if (message != null && message.startsWith("BLE disconnected:")) beginBleReconnectRecovery();
                } else {
                    publish(status.scanner, state + ": " + message, status.lastAction);
                }
            }
            @Override public void onQrCodeReady(String uuid) { }
            @Override public void onBleKeyReady(WatchBlueToothKeyStatInfo info) { }
            @Override public void onFlowComplete(String message) { publish(status.scanner, currentBleText(), message); }
            @Override public void onFlowError(WatchBleKeyFlowManager.FlowState state, String error) {
                authInProgress = false;
                recordBleConnectionEvent(getString(R.string.ble_connection_error, error));
                publishImportant(status.scanner, getString(R.string.ble_auth_failed, error), status.lastAction, getString(R.string.notification_ble_auth_failed));
            }
        });
        bleFlow = nextBleFlow;
        BleConnectionCoordinator.claim(BleConnectionCoordinator.Owner.VEHICLE_ACCESS_SERVICE, bleFlow);
        bleFlow.resumeWithCachedKey();
    }

    /**
     * A vehicle can stop advertising while a GATT session is active. After an unexpected disconnect,
     * try the saved MAC directly a few times and keep scan recovery active as a fallback.
     */
    private void beginBleReconnectRecovery() {
        if (!storage.hasBleKey()) return;
        reconnectPending = true;
        resetRssiAfterDisconnect();
        startScan();
        recordBleConnectionEvent(getString(R.string.ble_unexpected_disconnect));
        publishImportant(getString(R.string.ble_reconnecting), getString(R.string.ble_disconnected),
                status.lastAction, getString(R.string.ble_reconnecting));
        scheduleDirectBleReconnect();
    }

    private void scheduleDirectBleReconnect() {
        if (!reconnectPending || authInProgress || isBleReady()) return;
        // A retry still requires a strong, fresh vehicle advertisement.
        if (!isBleConnectRssiEligible()) {
            recordBleConnectionEvent(getString(R.string.scanner_signal_too_weak));
            publish(getString(R.string.scanner_signal_too_weak), currentBleText(), status.lastAction);
            return;
        }
        if (directReconnectRunnable != null || directReconnectAttempt >= DIRECT_RECONNECT_DELAYS_MS.length) return;
        long delay = DIRECT_RECONNECT_DELAYS_MS[directReconnectAttempt];
        int attempt = ++directReconnectAttempt;
        directReconnectRunnable = () -> {
            directReconnectRunnable = null;
            if (!reconnectPending || authInProgress || isBleReady()) return;
            recordBleConnectionEvent(getString(R.string.ble_reconnect_attempt, attempt, DIRECT_RECONNECT_DELAYS_MS.length));
            publish(getString(R.string.ble_reconnect_attempt, attempt, DIRECT_RECONNECT_DELAYS_MS.length),
                    currentBleText(), status.lastAction);
            ensureBleAuthentication(true);
        };
        handler.postDelayed(directReconnectRunnable, delay);
    }

    private boolean isBleReady() {
        return bleFlow != null && bleFlow.getCurrentState() == WatchBleKeyFlowManager.FlowState.READY;
    }

    private void cancelScheduledReconnect() {
        if (directReconnectRunnable != null) handler.removeCallbacks(directReconnectRunnable);
        directReconnectRunnable = null;
    }

    private void resetRssiAfterDisconnect() {
        smoothedRssi = Float.NaN;
        recentRssiSamples.clear();
        unlockThresholdReachedAt = 0L;
        lockThresholdReachedAt = 0L;
        weakConnectedRssiAt = 0L;
    }

    /** Gracefully ends the GATT session when connected RSSI remains below the exit threshold. */
    private void enforceConnectedRssiDisconnect() {
        if (!isBleReady() || Float.isNaN(smoothedRssi)) {
            weakConnectedRssiAt = 0L;
            return;
        }
        long now = System.currentTimeMillis();
        if (smoothedRssi <= BLE_DISCONNECT_RSSI_MAX_DBM) {
            if (weakConnectedRssiAt == 0L) weakConnectedRssiAt = now;
            if (now - weakConnectedRssiAt >= BLE_DISCONNECT_DWELL_MS) disconnectBleForDistance();
        } else {
            weakConnectedRssiAt = 0L;
        }
    }

    private void disconnectBleForDistance() {
        // This is an intentional GATT disconnect, not a transport failure. Wait for a new strong scan.
        reconnectPending = false;
        directReconnectAttempt = 0;
        cancelScheduledReconnect();
        resetRssiAfterDisconnect();
        bleConnectionCooldownUntil = System.currentTimeMillis() + BLE_DISTANCE_DISCONNECT_COOLDOWN_MS;
        stopScan();
        recordBleConnectionEvent(getString(R.string.ble_disconnected_for_distance));
        publishImportant(getString(R.string.ble_distance_cooldown,
                        BLE_DISTANCE_DISCONNECT_COOLDOWN_MS / 1_000L),
                getString(R.string.ble_disconnected_for_distance), status.lastAction,
                getString(R.string.ble_disconnected_for_distance));
        if (bleFlow != null) {
            BleConnectionCoordinator.release(BleConnectionCoordinator.Owner.VEHICLE_ACCESS_SERVICE, bleFlow);
            bleFlow.cancel();
        }
        scheduleDistanceScanResume();
    }

    private void scheduleDistanceScanResume() {
        if (distanceScanResumeRunnable != null) handler.removeCallbacks(distanceScanResumeRunnable);
        distanceScanResumeRunnable = () -> {
            distanceScanResumeRunnable = null;
            if (System.currentTimeMillis() < bleConnectionCooldownUntil) {
                scheduleDistanceScanResume();
                return;
            }
            if (shouldMonitorVehicle()) {
                startScan();
                recordBleConnectionEvent(getString(R.string.ble_scan_resumed));
                publish(getString(R.string.scanner_scanning), currentBleText(), status.lastAction);
            }
        };
        handler.postDelayed(distanceScanResumeRunnable, BLE_DISTANCE_DISCONNECT_COOLDOWN_MS);
    }

    private void processAutomaticThresholds() {
        if (phoneConnectedToPower) {
            resetAutomaticThresholdDwell();
            setCountdown(getString(R.string.automatic_control_paused_charging));
            return;
        }
        if (recentRssiSamples.size() < RSSI_MEDIAN_WINDOW || Float.isNaN(smoothedRssi)) return;
        long now = System.currentTimeMillis();
        long cooldownRemaining = AUTO_CONTROL_COOLDOWN_MS - (now - lastAutoControlAt);
        if (lastAutoControlAt > 0L && cooldownRemaining > 0L) {
            unlockThresholdReachedAt = 0L;
            lockThresholdReachedAt = 0L;
            setCountdown("Automatic-control cooldown: " + secondsCeil(cooldownRemaining) + " seconds remaining");
            return;
        }
        int unlock = storage.getUnlockRssi();
        int lock = storage.getLockRssi();
        if (!nearVehicle) {
            lockThresholdReachedAt = 0L;
            if (smoothedRssi >= unlock) {
                if (unlockThresholdReachedAt == 0L) unlockThresholdReachedAt = now;
                if (now - unlockThresholdReachedAt >= UNLOCK_DWELL_MS) {
                    nearVehicle = true;
                    unlockThresholdReachedAt = 0L;
                    lastAutoControlAt = now;
                    setCountdown("Automatic-control cooldown: 30 seconds remaining");
                    executeCommand(COMMAND_UNLOCK, true);
                    if (storage.isAutoAcOnUnlock()) executeRest(COMMAND_START_CLIMATE, true, true);
                } else {
                    setCountdown("Unlock condition: " + secondsCeil(UNLOCK_DWELL_MS - (now - unlockThresholdReachedAt)) + " seconds remaining");
                }
            } else {
                unlockThresholdReachedAt = 0L;
                setCountdown("");
            }
        } else {
            unlockThresholdReachedAt = 0L;
            if (smoothedRssi <= lock) {
                if (lockThresholdReachedAt == 0L) lockThresholdReachedAt = now;
                if (now - lockThresholdReachedAt >= LOCK_DWELL_MS) {
                    nearVehicle = false;
                    lockThresholdReachedAt = 0L;
                    lastAutoControlAt = now;
                    setCountdown("Automatic-control cooldown: 30 seconds remaining");
                    executeCommand(COMMAND_LOCK, true);
                } else {
                    setCountdown("Lock condition: " + secondsCeil(LOCK_DWELL_MS - (now - lockThresholdReachedAt)) + " seconds remaining");
                }
            } else {
                lockThresholdReachedAt = 0L;
                setCountdown("");
            }
        }
    }

    private long secondsCeil(long millis) {
        return Math.max(1L, (millis + 999L) / 1_000L);
    }

    private void updatePowerConnectionState(Intent batteryState) {
        if (batteryState == null) return;
        int plugged = batteryState.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        int batteryStatus = batteryState.getIntExtra(BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_UNKNOWN);
        boolean connected = plugged != 0 || batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING;
        if (phoneConnectedToPower == connected) return;

        phoneConnectedToPower = connected;
        resetAutomaticThresholdDwell();
        if (connected) {
            String message = getString(R.string.automatic_control_paused_charging);
            setCountdown(message);
            recordControlEvent("AUTO", getString(R.string.control_ignored), message);
        } else {
            setCountdown("");
        }
    }

    private void resetAutomaticThresholdDwell() {
        unlockThresholdReachedAt = 0L;
        lockThresholdReachedAt = 0L;
    }

    private boolean ignoreAutomaticCommandWhileCharging(String command, boolean automatic) {
        if (!automatic || !phoneConnectedToPower) return false;
        String message = getString(R.string.automatic_command_ignored_charging, label(command));
        recordControlEvent("AUTO", getString(R.string.control_ignored), message);
        publishImportant(status.scanner, currentBleText(), message,
                getString(R.string.automatic_control_paused_charging));
        return true;
    }

    private void setCountdown(String text) {
        if (text.equals(countdownText)) return;
        countdownText = text;
        publish(status.scanner, currentBleText(), status.lastAction);
    }

    /** Removes one-off RSSI spikes with a 5-sample median before applying EMA α=0.3. */
    private void updateFilteredRssi(int rawRssi, String source) {
        if (recentRssiSamples.size() == RSSI_MEDIAN_WINDOW) recentRssiSamples.removeFirst();
        recentRssiSamples.addLast(rawRssi);
        ArrayList<Integer> sorted = new ArrayList<>(recentRssiSamples);
        Collections.sort(sorted);
        int median = sorted.get(sorted.size() / 2);
        smoothedRssi = Float.isNaN(smoothedRssi) ? median
                : RSSI_EMA_ALPHA * median + (1f - RSSI_EMA_ALPHA) * smoothedRssi;
        Log.d(TAG, "Vehicle " + source + " RSSI: raw=" + rawRssi + " dBm, median=" + median
                + " dBm, EMA=" + Math.round(smoothedRssi) + " dBm, samples=" + sorted.size());
    }

    private void recordControlEvent(String channel, String result, String detail) {
        String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(new Date());
        String entry = timestamp + " | " + channel + " | " + result + " | " + detail;
        synchronized (controlLogEntries) {
            if (controlLogEntries.size() == MAX_CONTROL_LOG_ENTRIES) controlLogEntries.removeFirst();
            controlLogEntries.addLast(entry);
        }
        Log.i(TAG, "Control event: " + entry);
    }

    /** Adds meaningful BLE lifecycle changes to the dashboard log without recording high-rate RSSI samples. */
    private void recordBleConnectionEvent(String detail) {
        if (TextUtils.isEmpty(detail) || detail.equals(lastBleConnectionLog)) return;
        lastBleConnectionLog = detail;
        recordControlEvent("BLE", getString(R.string.control_connection), detail);
    }

    private void showControlResultToast(String message) {
        handler.post(() -> Toast.makeText(VehicleAccessService.this, message, Toast.LENGTH_LONG).show());
    }

    private void executeCommand(String command, boolean automatic) {
        if (ignoreAutomaticCommandWhileCharging(command, automatic)) return;
        Integer bleFunction = bleFunctionFor(command);
        if (bleFunction != null && bleFlow != null && bleFlow.getCurrentState() == WatchBleKeyFlowManager.FlowState.READY) {
            recordControlEvent("BLE", getString(R.string.control_attempt), label(command));
            publishImportant(status.scanner, currentBleText(), getString(R.string.ble_sending, label(command)),
                    getString(R.string.ble_sending, label(command)));
            bleFlow.sendCommand(bleFunction, new WatchBleKeyFlowManager.CommandCallback() {
                @Override public void onVehicleConfirmed(int resultCode, int doorStates) {
                    playAutomaticDoorHaptic(command, automatic);
                    String message = getString(R.string.ble_command_confirmed, label(command));
                    recordControlEvent("BLE", getString(R.string.control_success), message);
                    showControlResultToast(message);
                    publishImportant(status.scanner, currentBleText(), message, message);
                }
                @Override public void onVehicleRejected(int resultCode, int doorStates) {
                    String result = String.format(Locale.ROOT, "0x%02X", resultCode & 0xFF);
                    String message = getString(R.string.ble_command_rejected_retry_rest, label(command), result);
                    recordControlEvent("BLE", getString(R.string.control_failed), message);
                    showControlResultToast(message);
                    publishImportant(status.scanner, currentBleText(), message, message);
                    executeRest(command, true, automatic);
                }
                @Override public void onResponseTimeout() {
                    String message = getString(R.string.ble_command_response_timeout, label(command));
                    recordControlEvent("BLE", getString(R.string.control_unconfirmed), message);
                    showControlResultToast(message);
                    publishImportant(status.scanner, currentBleText(), message, message);
                }
                @Override public void onWriteFailed() {
                    recordControlEvent("BLE", getString(R.string.control_failed), getString(R.string.ble_command_failed_retry_rest, label(command)));
                    showControlResultToast(getString(R.string.ble_command_failed, label(command)));
                    publishImportant(status.scanner, currentBleText(), getString(R.string.ble_command_failed_retry_rest, label(command)),
                            getString(R.string.ble_command_failed_retry_rest, label(command)));
                    executeRest(command, true, automatic);
                }
            });
        } else {
            executeRest(command, true, automatic);
        }
    }

    private Integer bleFunctionFor(String command) {
        if (COMMAND_LOCK.equals(command)) return VehicleBleCommand.LOCK_DOORS;
        if (COMMAND_UNLOCK.equals(command)) return VehicleBleCommand.UNLOCK_ALL_DOORS;
        if (COMMAND_OPEN_TRUNK.equals(command)) return VehicleBleCommand.OPERATE_TRUNK;
        if (COMMAND_FLASH.equals(command)) return VehicleBleCommand.FLASH_LIGHTS;
        if (COMMAND_FIND.equals(command)) return VehicleBleCommand.FIND_VEHICLE;
        return null;
    }

    private void executeRest(String command, boolean retryAfterRefresh, boolean automatic) {
        if (ignoreAutomaticCommandWhileCharging(command, automatic)) return;
        TokenInfoBean token = WatchCredentialManager.restoreToken(storage);
        if (token == null || TextUtils.isEmpty(token.getControlPwd())) {
            handleRestFailure("REST token is missing.", command, retryAfterRefresh, automatic);
            return;
        }
        String commandType = restCommandFor(command);
        String params = COMMAND_START_CLIMATE.equals(command) ? buildAirControlParams() : null;
        if (commandType == null) return;
        recordControlEvent("REST", getString(R.string.control_attempt), label(command));
        publishImportant(status.scanner, currentBleText(), getString(R.string.rest_requesting, label(command)),
                getString(R.string.rest_request_sent, label(command)));
        BydWatchKeyService api = new BydWatchKeyService(this, BydConfig.fromRegion(storage.getRegion()));
        api.sendRemoteControl(token, commandType, params, new BydWatchKeyService.Callback<RemoteControlStartResponse>() {
            @Override public void onSuccess(RemoteControlStartResponse result) {
                String serial = result == null ? null : result.getRequestSerial();
                if (TextUtils.isEmpty(serial)) {
                    handleRestFailure("REST response has no request serial.", command, retryAfterRefresh, automatic);
                    return;
                }
                pollRestControlResult(api, token, serial, commandType, command, retryAfterRefresh, automatic, 1);
            }
            @Override public void onError(String message, Throwable error) { handleRestFailure(message, command, retryAfterRefresh, automatic); }
        });
    }

    /** The server returns res=1 while the vehicle is still processing an accepted remote command. */
    private void pollRestControlResult(BydWatchKeyService api, TokenInfoBean token, String requestSerial,
                                       String commandType, String command, boolean retryAfterRefresh, boolean automatic, int attempt) {
        handler.postDelayed(() -> api.getRemoteControlResult(token, requestSerial, commandType,
                new BydWatchKeyService.Callback<RemoteControlResult>() {
                    @Override public void onSuccess(RemoteControlResult response) {
                        int res = response == null ? -1 : response.getRes();
                        String message = response == null || TextUtils.isEmpty(response.getMessage())
                                ? getString(R.string.server_no_response_message) : response.getMessage();
                        if (res == 2) {
                            playAutomaticDoorHaptic(command, automatic);
                            recordControlEvent("REST", getString(R.string.control_success), message);
                            showControlResultToast(message);
                            publishImportant(status.scanner, currentBleText(), getString(R.string.rest_result, label(command), res),
                                    getString(R.string.rest_result, label(command), res));
                        } else if (res == 1 && attempt < MAX_REMOTE_RESULT_POLLS) {
                            recordControlEvent("REST", getString(R.string.control_pending), getString(R.string.rest_processing, label(command), attempt, MAX_REMOTE_RESULT_POLLS));
                            publish(status.scanner, currentBleText(), getString(R.string.rest_processing, label(command), attempt, MAX_REMOTE_RESULT_POLLS));
                            pollRestControlResult(api, token, requestSerial, commandType, command,
                                    retryAfterRefresh, automatic, attempt + 1);
                        } else {
                            recordControlEvent("REST", getString(R.string.control_failed), "res=" + res + ", " + message);
                            showControlResultToast(getString(R.string.rest_failed, label(command), message));
                            publishImportant(status.scanner, currentBleText(), getString(R.string.rest_result, label(command), res),
                                    getString(R.string.rest_result, label(command), res));
                        }
                    }

                    @Override public void onError(String message, Throwable error) {
                        handleRestFailure(message, command, retryAfterRefresh, automatic);
                    }
                }), remoteControlResultDelayMillis(attempt));
    }

    private long remoteControlResultDelayMillis(int attempt) {
        if (attempt == 1) return 2_000L;
        if (attempt <= 3) return 500L;
        if (attempt <= 6) return 2_000L;
        if (attempt <= 9) return 5_000L;
        return 10_000L;
    }

    private void handleRestFailure(String message, String command, boolean retryAfterRefresh, boolean automatic) {
        String code = extractErrorCode(message);
        String detail = label(command) + (TextUtils.isEmpty(code) ? "" : " (" + code + ")") + ": " + message;
        recordControlEvent("REST", getString(R.string.control_failed), detail);
        showControlResultToast(getString(R.string.rest_request_failed, label(command)) + (TextUtils.isEmpty(code) ? "" : " (" + code + ")"));
        if (WatchCredentialManager.isTokenExpiredError(message) && !restRecoveryInProgress && retryAfterRefresh) {
            recoverRestCredentials(command, automatic);
        } else {
            publishImportant(status.scanner, currentBleText(), getString(R.string.rest_failed, label(command), message),
                    getString(R.string.rest_request_failed, label(command)));
        }
    }

    private String extractErrorCode(String message) {
        if (TextUtils.isEmpty(message)) return "";
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\((\\d{4}),").matcher(message);
        return matcher.find() ? matcher.group(1) : "";
    }

    /** On token expiry, retry steps 3–5 then prove the token with a real-time status read. */
    private void recoverRestCredentials(String pendingCommand, boolean automatic) {
        restRecoveryInProgress = true;
        publishImportant(status.scanner, currentBleText(), getString(R.string.rest_key_recovery),
                getString(R.string.notification_rest_recovery));
        WatchCredentialManager.refreshFromSavedQr(this, new WatchCredentialManager.Callback() {
            @Override public void onSuccess(TokenInfoBean token) {
                WatchCredentialManager.verifyRealtime(VehicleAccessService.this, token, new WatchCredentialManager.Callback() {
                    @Override public void onSuccess(TokenInfoBean verified) {
                        restRecoveryInProgress = false;
                        publishImportant(status.scanner, currentBleText(), getString(R.string.rest_key_recovered),
                                getString(R.string.notification_rest_recovered));
                        executeRest(pendingCommand, false, automatic);
                    }
                    @Override public void onError(String message, Throwable error) { requireQrReauthentication(message); }
                });
            }
            @Override public void onError(String message, Throwable error) { requireQrReauthentication(message); }
        });
    }

    private void requireQrReauthentication(String message) {
        restRecoveryInProgress = false;
        storage.clearBluetoothKeyAndWatchAuth();
        publishImportant(status.scanner, getString(R.string.reauthentication_required), getString(R.string.rest_recovery_failed, message),
                getString(R.string.notification_qr_reauth));
        sendBroadcast(new Intent(ACTION_AUTH_REQUIRED).setPackage(getPackageName()));
        stopSelf();
    }

    /** Gives a concise on-device confirmation only for proximity-triggered door controls. */
    private void playAutomaticDoorHaptic(String command, boolean automatic) {
        if (!automatic || (!COMMAND_UNLOCK.equals(command) && !COMMAND_LOCK.equals(command))) return;
        Vibrator vibrator = getSystemService(Vibrator.class);
        if (vibrator == null || !vibrator.hasVibrator()) return;
        long[] pattern = COMMAND_UNLOCK.equals(command)
                ? new long[]{0L, 100L}
                : new long[]{0L, 100L, 110L, 100L};
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
    }

    private String restCommandFor(String command) {
        if (COMMAND_LOCK.equals(command)) return "LOCKDOOR";
        if (COMMAND_UNLOCK.equals(command)) return "OPENDOOR";
        if (COMMAND_OPEN_TRUNK.equals(command)) return "OPENTRUNK";
        if (COMMAND_CLOSE_TRUNK.equals(command)) return "CLOSETRUNK";
        if (COMMAND_OPEN_WINDOWS.equals(command)) return "OPENWINDOW";
        if (COMMAND_CLOSE_WINDOWS.equals(command)) return "CLOSEWINDOW";
        if (COMMAND_START_CLIMATE.equals(command)) return "OPENAIR";
        if (COMMAND_STOP_CLIMATE.equals(command)) return "CLOSEAIR";
        if (COMMAND_FLASH.equals(command)) return "FLASHLIGHTNOWHISTLE";
        if (COMMAND_FIND.equals(command)) return "FINDCAR";
        return null;
    }

    private String buildAirControlParams() {
        JsonObject params = new JsonObject();
        params.add("airSet", JsonNull.INSTANCE);
        params.addProperty("remoteMode", 4);
        params.addProperty("timeSpan", 1);
        int scale = celsiusToScaleKR(storage.getAcTargetTemp());
        params.addProperty("mainSettingTemp", scale);
        params.addProperty("copilotSettingTemp", scale);
        params.addProperty("cycleMode", 2);
        params.addProperty("airAccuracy", 2);
        params.addProperty("airConditioningMode", 1);
        return params.toString();
    }

    private int celsiusToScaleKR(double tempC) {
        // Korean BYD app scale: Celsius = 16.5 + (code / 2.0), range 1 = 17°C through 21 = 27°C.
        int scale = (int) Math.round((tempC - 16.5) * 2.0);
        return Math.max(1, Math.min(21, scale));
    }

    private String label(String command) {
        if (COMMAND_LOCK.equals(command)) return getString(R.string.command_lock);
        if (COMMAND_UNLOCK.equals(command)) return getString(R.string.command_unlock);
        if (COMMAND_OPEN_TRUNK.equals(command)) return getString(R.string.command_open_trunk);
        if (COMMAND_CLOSE_TRUNK.equals(command)) return getString(R.string.command_close_trunk);
        if (COMMAND_OPEN_WINDOWS.equals(command)) return getString(R.string.command_open_windows);
        if (COMMAND_CLOSE_WINDOWS.equals(command)) return getString(R.string.command_close_windows);
        if (COMMAND_START_CLIMATE.equals(command)) return getString(R.string.command_start_climate);
        if (COMMAND_STOP_CLIMATE.equals(command)) return getString(R.string.command_stop_climate);
        if (COMMAND_FLASH.equals(command)) return getString(R.string.command_flash_lights);
        if (COMMAND_FIND.equals(command)) return getString(R.string.command_find_vehicle);
        return command == null ? getString(R.string.command_unknown) : command.replace('_', ' ');
    }
    private String currentBleText() {
        if (bleFlow == null) return getString(R.string.ble_disconnected);
        return bleFlow.getCurrentState() == WatchBleKeyFlowManager.FlowState.READY ? getString(R.string.ble_authenticated) : bleFlow.getCurrentState().name();
    }

    private void publish(String scannerText, String bleText, String actionText) {
        int rssi = Float.isNaN(smoothedRssi) ? Integer.MIN_VALUE : Math.round(smoothedRssi);
        status = new Status(scannerText, bleText, actionText, rssi, scanning, countdownText);
    }

    /** Notification updates are intentionally reserved for user-relevant state changes. */
    private void publishImportant(String scannerText, String bleText, String actionText, String notificationText) {
        publish(scannerText, bleText, actionText);
        updateNotification(notificationText);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification createNotification(String text) {
        Class<?> destination = storage != null && storage.hasBleKey()
                ? VehicleControlActivity.class : AuthActivity.class;
        Intent launchIntent = new Intent(this, destination)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        long now = System.currentTimeMillis();
        if (text.equals(lastNotificationText)) return;
        if (now - lastNotificationUpdateAt < NOTIFICATION_UPDATE_INTERVAL_MS) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            lastNotificationText = text;
            lastNotificationUpdateAt = now;
            manager.notify(NOTIFICATION_ID, createNotification(text));
        }
    }
}

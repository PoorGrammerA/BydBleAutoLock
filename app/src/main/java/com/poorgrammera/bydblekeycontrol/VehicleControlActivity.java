package com.poorgrammera.bydblekeycontrol;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.poorgrammera.bydautolock.bydapi.WatchCredentialManager;
import com.poorgrammera.bydautolock.storage.StorageManager;

/** Monitoring dashboard for the foreground vehicle-access service. */
public class VehicleControlActivity extends AppCompatActivity {
    private static final int REQUEST_BLUETOOTH_PERMISSIONS = 3001;
    private static final int RSSI_MIN = -89;
    private static final int RSSI_MAX = -30;
    private static final int HYSTERESIS_DBM = 10;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private StorageManager storage;
    private TextView vehicleInfo;
    private TextView vehicleAccessTitle;
    private TextView bleStatus;
    private TextView serviceStatus;
    private TextView controlLogView;
    private TextView unlockLabel;
    private TextView lockLabel;
    private TextView autoControlCooldownLabel;
    private TextView thresholdWarning;
    private TextView autoControlCountdown;
    private TextView tempLabel;
    private TextView batteryMessage;
    private View batteryOptimizationBanner;
    private Button batteryButton;
    private Button serviceToggleButton;
    private View devTestButton;
    private int vehicleAccessTitleTapCount;
    private Button bluetoothPermissionButton;
    private TextView bluetoothPermissionMessage;
    private View bluetoothPermissionBanner;
    private CheckBox autoClimate;
    private CheckBox pauseAutoControlWhileCharging;
    private SeekBar unlockSeek;
    private SeekBar lockSeek;
    private SeekBar autoControlCooldownSeek;

    private final Runnable statusUpdater = new Runnable() {
        @Override public void run() {
            renderServiceStatus();
            handler.postDelayed(this, 1000L);
        }
    };

    private final BroadcastReceiver authRequiredReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            Toast.makeText(VehicleControlActivity.this, R.string.notification_qr_reauth, Toast.LENGTH_LONG).show();
            startActivity(new Intent(VehicleControlActivity.this, AuthActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        storage = new StorageManager(this);
        if (!storage.hasBleKey()) {
            startActivity(new Intent(this, AuthActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.activity_vehicle_control);
        bindViews();
        bindControls();
        renderVehicle();
        renderThresholds();
        renderTemperature();
        renderBatteryOptimizationState();
        renderBluetoothPermissionState();
        VehicleAccessService.startIfEnabled(this);
    }

    @Override protected void onStart() {
        super.onStart();
        ContextCompat.registerReceiver(this, authRequiredReceiver,
                new IntentFilter(VehicleAccessService.ACTION_AUTH_REQUIRED), ContextCompat.RECEIVER_NOT_EXPORTED);
        handler.post(statusUpdater);
        VehicleAccessService.setDashboardVisible(this, true);
    }

    @Override protected void onStop() {
        handler.removeCallbacks(statusUpdater);
        VehicleAccessService.setDashboardVisible(this, false);
        try { unregisterReceiver(authRequiredReceiver); } catch (IllegalArgumentException ignored) { }
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        renderBatteryOptimizationState();
        renderBluetoothPermissionState();
    }

    private void bindViews() {
        vehicleInfo = findViewById(R.id.vehicleInfo);
        vehicleAccessTitle = findViewById(R.id.vehicleAccessTitle);
        bleStatus = findViewById(R.id.bleStatus);
        serviceStatus = findViewById(R.id.serviceStatus);
        controlLogView = findViewById(R.id.controlLogView);
        unlockLabel = findViewById(R.id.unlockThresholdLabel);
        lockLabel = findViewById(R.id.lockThresholdLabel);
        autoControlCooldownLabel = findViewById(R.id.autoControlCooldownLabel);
        thresholdWarning = findViewById(R.id.thresholdWarning);
        autoControlCountdown = findViewById(R.id.autoControlCountdown);
        tempLabel = findViewById(R.id.tempLabel);
        batteryMessage = findViewById(R.id.batteryOptimizationMessage);
        batteryOptimizationBanner = findViewById(R.id.batteryOptimizationBanner);
        batteryButton = findViewById(R.id.requestBatteryOptimizationButton);
        serviceToggleButton = findViewById(R.id.serviceToggleButton);
        devTestButton = findViewById(R.id.devTestButton);
        bluetoothPermissionButton = findViewById(R.id.requestBluetoothPermissionButton);
        bluetoothPermissionMessage = findViewById(R.id.bluetoothPermissionMessage);
        bluetoothPermissionBanner = findViewById(R.id.bluetoothPermissionBanner);
        autoClimate = findViewById(R.id.autoClimateCheck);
        pauseAutoControlWhileCharging = findViewById(R.id.pauseAutoControlWhileChargingCheck);
        unlockSeek = findViewById(R.id.unlockThresholdSeek);
        lockSeek = findViewById(R.id.lockThresholdSeek);
        autoControlCooldownSeek = findViewById(R.id.autoControlCooldownSeek);
    }

    private void bindControls() {
        unlockSeek.setMax(RSSI_MAX - RSSI_MIN);
        lockSeek.setMax(RSSI_MAX - RSSI_MIN);
        unlockSeek.setProgress(toProgress(storage.getUnlockRssi()));
        lockSeek.setProgress(toProgress(storage.getLockRssi()));
        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (seekBar == unlockSeek) storage.setUnlockRssi(fromProgress(progress));
                else storage.setLockRssi(fromProgress(progress));
                renderThresholds();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        };
        unlockSeek.setOnSeekBarChangeListener(listener);
        lockSeek.setOnSeekBarChangeListener(listener);

        autoControlCooldownSeek.setMax(StorageManager.MAX_AUTO_CONTROL_COOLDOWN_SECONDS
                - StorageManager.MIN_AUTO_CONTROL_COOLDOWN_SECONDS);
        autoControlCooldownSeek.setProgress(storage.getAutoControlCooldownSeconds()
                - StorageManager.MIN_AUTO_CONTROL_COOLDOWN_SECONDS);
        autoControlCooldownSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                storage.setAutoControlCooldownSeconds(progress
                        + StorageManager.MIN_AUTO_CONTROL_COOLDOWN_SECONDS);
                renderAutoControlCooldown();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        renderAutoControlCooldown();

        pauseAutoControlWhileCharging.setChecked(storage.isPauseAutoControlWhileCharging());
        pauseAutoControlWhileCharging.setOnCheckedChangeListener((buttonView, checked) ->
                storage.setPauseAutoControlWhileCharging(checked));

        autoClimate.setChecked(storage.isAutoAcOnUnlock());
        autoClimate.setOnCheckedChangeListener((buttonView, checked) -> storage.setAutoAcOnUnlock(checked));
        findViewById(R.id.tempDownButton).setOnClickListener(v -> changeTemperature(-0.5f));
        findViewById(R.id.tempUpButton).setOnClickListener(v -> changeTemperature(0.5f));
        batteryButton.setOnClickListener(v -> requestBatteryOptimizationExemption());
        serviceToggleButton.setOnClickListener(v -> toggleVehicleAccessService());
        bluetoothPermissionButton.setOnClickListener(v -> requestBluetoothPermissions());
        findViewById(R.id.devTestButton).setOnClickListener(v -> startActivity(new Intent(this, DevTestActivity.class)));
        vehicleAccessTitle.setOnClickListener(v -> enableDeveloperModeAfterFiveTaps());
        renderDeveloperMode();

        bindCommand(R.id.lockButton, getString(R.string.command_lock), VehicleAccessService.COMMAND_LOCK);
        bindCommand(R.id.unlockButton, getString(R.string.command_unlock), VehicleAccessService.COMMAND_UNLOCK);
        bindCommand(R.id.openTrunkButton, getString(R.string.command_open_trunk), VehicleAccessService.COMMAND_OPEN_TRUNK);
        bindCommand(R.id.closeTrunkButton, getString(R.string.command_close_trunk), VehicleAccessService.COMMAND_CLOSE_TRUNK);
        bindCommand(R.id.openWindowsButton, getString(R.string.command_open_windows), VehicleAccessService.COMMAND_OPEN_WINDOWS);
        bindCommand(R.id.closeWindowsButton, getString(R.string.command_close_windows), VehicleAccessService.COMMAND_CLOSE_WINDOWS);
        bindCommand(R.id.startClimateButton, getString(R.string.command_start_climate), VehicleAccessService.COMMAND_START_CLIMATE);
        bindCommand(R.id.stopClimateButton, getString(R.string.command_stop_climate), VehicleAccessService.COMMAND_STOP_CLIMATE);
        bindCommand(R.id.flashButton, getString(R.string.command_flash_lights), VehicleAccessService.COMMAND_FLASH);
        bindCommand(R.id.findButton, getString(R.string.command_find_vehicle), VehicleAccessService.COMMAND_FIND);
    }

    private void bindCommand(int id, String label, String command) {
        findViewById(id).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(R.string.vehicle_control_dialog_title)
                .setMessage(getString(R.string.vehicle_control_dialog_message, label))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.send, (dialog, which) -> VehicleAccessService.sendCommand(this, command))
                .show());
    }

    private void renderVehicle() {
        String model = WatchCredentialManager.vehicleField(storage, "modelNameOut");
        String plate = WatchCredentialManager.vehicleField(storage, "autoPlate");
        String vin = storage.getWatchVin();
        String mac = storage.getBleMacAddress();
        vehicleInfo.setText(getString(R.string.vehicle_info, model, plate, vin == null ? "-" : vin, mac == null ? "-" : mac));
    }

    private void renderServiceStatus() {
        VehicleAccessService.Status status = VehicleAccessService.getStatus();
        String rssi = status.rssi == Integer.MIN_VALUE ? getString(R.string.not_received) : status.rssi + " dBm";
        bleStatus.setText(getString(R.string.ble_status, status.ble, status.scanner, rssi, status.lastAction));
        if (status.countdown == null || status.countdown.isEmpty()) {
            autoControlCountdown.setVisibility(View.GONE);
        } else {
            autoControlCountdown.setVisibility(View.VISIBLE);
            autoControlCountdown.setText(status.countdown);
        }
        boolean running = VehicleAccessService.isRunning();
        serviceStatus.setText(running ? "Vehicle access service: running" : "Vehicle access service: stopped");
        serviceToggleButton.setText(running ? R.string.stop_service : R.string.start_service);
        controlLogView.setText(VehicleAccessService.getControlLogText());
    }

    private void toggleVehicleAccessService() {
        if (VehicleAccessService.isRunning()) {
            VehicleAccessService.setDashboardVisible(this, false);
            VehicleAccessService.stop(this);
            Toast.makeText(this, R.string.service_stopped, Toast.LENGTH_SHORT).show();
        } else {
            VehicleAccessService.start(this);
            VehicleAccessService.setDashboardVisible(this, true);
            Toast.makeText(this, R.string.service_started, Toast.LENGTH_SHORT).show();
        }
    }

    private void enableDeveloperModeAfterFiveTaps() {
        if (storage.isDeveloperModeEnabled()) return;
        vehicleAccessTitleTapCount++;
        if (vehicleAccessTitleTapCount < 5) return;
        storage.setDeveloperModeEnabled(true);
        renderDeveloperMode();
        Toast.makeText(this, R.string.developer_mode_enabled, Toast.LENGTH_LONG).show();
    }

    private void renderDeveloperMode() {
        devTestButton.setVisibility(storage.isDeveloperModeEnabled() ? View.VISIBLE : View.GONE);
    }

    private void renderThresholds() {
        int unlock = storage.getUnlockRssi();
        int lock = storage.getLockRssi();
        unlockLabel.setText(getString(R.string.unlock_threshold, unlock));
        lockLabel.setText(getString(R.string.lock_threshold, lock));
        if (unlock <= lock + HYSTERESIS_DBM) {
            thresholdWarning.setVisibility(View.VISIBLE);
            thresholdWarning.setText(getString(R.string.threshold_warning, HYSTERESIS_DBM));
        } else {
            thresholdWarning.setVisibility(View.GONE);
        }
    }

    private void renderAutoControlCooldown() {
        autoControlCooldownLabel.setText(getString(R.string.auto_control_cooldown,
                storage.getAutoControlCooldownSeconds()));
    }

    private void changeTemperature(float delta) {
        float next = Math.max(17f, Math.min(27f, storage.getAcTargetTemp() + delta));
        storage.setAcTargetTemp(next);
        renderTemperature();
    }

    private void renderTemperature() {
        tempLabel.setText(getString(R.string.climate_temperature, storage.getAcTargetTemp()));
    }

    private int toProgress(int rssi) { return Math.max(0, Math.min(RSSI_MAX - RSSI_MIN, rssi - RSSI_MIN)); }
    private int fromProgress(int progress) { return RSSI_MIN + progress; }

    private void renderBatteryOptimizationState() {
        PowerManager power = getSystemService(PowerManager.class);
        boolean exempt = power != null && power.isIgnoringBatteryOptimizations(getPackageName());
        if (exempt) {
            batteryMessage.setText(R.string.battery_optimization_allowed);
            batteryButton.setVisibility(View.GONE);
            batteryOptimizationBanner.setVisibility(View.GONE);
        } else {
            batteryMessage.setText(R.string.battery_optimization_needed);
            batteryButton.setVisibility(View.VISIBLE);
            batteryOptimizationBanner.setVisibility(View.VISIBLE);
        }
    }

    private boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        boolean scan = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        boolean connect = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        boolean notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        return scan && connect && notifications;
    }

    private void renderBluetoothPermissionState() {
        if (hasBluetoothPermissions()) {
            bluetoothPermissionMessage.setText(R.string.bluetooth_permission_allowed);
            bluetoothPermissionButton.setVisibility(View.GONE);
            bluetoothPermissionBanner.setVisibility(View.GONE);
        } else {
            bluetoothPermissionMessage.setText(R.string.bluetooth_permission_needed);
            bluetoothPermissionButton.setVisibility(View.VISIBLE);
            bluetoothPermissionBanner.setVisibility(View.VISIBLE);
        }
    }

    private void requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        java.util.ArrayList<String> permissions = new java.util.ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.BLUETOOTH_SCAN);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!permissions.isEmpty()) requestPermissions(permissions.toArray(new String[0]), REQUEST_BLUETOOTH_PERMISSIONS);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_BLUETOOTH_PERMISSIONS) return;
        renderBluetoothPermissionState();
        if (hasBluetoothPermissions()) {
            Toast.makeText(this, R.string.bluetooth_permission_started, Toast.LENGTH_LONG).show();
            VehicleAccessService.start(this);
        } else {
            Toast.makeText(this, R.string.bluetooth_permission_denied, Toast.LENGTH_LONG).show();
        }
    }

    private void requestBatteryOptimizationExemption() {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception error) {
            Toast.makeText(this, R.string.battery_optimization_settings_needed, Toast.LENGTH_LONG).show();
        }
    }
}

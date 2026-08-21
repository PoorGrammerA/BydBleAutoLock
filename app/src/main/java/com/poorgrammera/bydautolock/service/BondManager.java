package com.poorgrammera.bydautolock.service;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
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
import android.os.Handler;
import android.util.Log;
import androidx.core.content.ContextCompat;
import java.util.List;
import java.util.function.Consumer;

public final class BondManager {
    private static final long BOND_POLL_INTERVAL_MS = 500;
    private static final long BOND_WAIT_TIMEOUT_MS = 90000;
    private static final long SCAN_TIMEOUT_MS = 3000;

    private final Context context;
    private final Handler mainHandler;
    private final Consumer<String> log;
    
    private Callback callback;
    private BluetoothLeScanner leScanner;
    private String pendingScanMac;
    private BluetoothDevice pollDevice;
    private boolean removeBeforeBond;
    private ScanCallback scanCallback;
    private BroadcastReceiver stateReceiver;
    private String watchAddress;

    public interface Callback {
        void onBonded(BluetoothDevice device);
        void onBondFailed(String reason);
        void onBondTimeout();
    }

    private final Runnable timeoutRunnable = new Runnable() {
        @Override
        public void run() {
            onTimeout();
        }
    };

    private final Runnable scanTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            onScanTimeout();
        }
    };

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            BluetoothDevice device = pollDevice;
            if (device == null || stateReceiver == null) {
                return;
            }
            int state = BluetoothDevice.BOND_NONE;
            try {
                state = device.getBondState();
            } catch (SecurityException e) {
                log.accept("poll getBondState error: " + e.getMessage());
            }
            String address = watchAddress != null ? watchAddress : device.getAddress();
            log.accept("bondPoll: bondState=" + bondLabel(state) + " mac=" + address);

            if (state == BluetoothDevice.BOND_NONE) {
                if (removeBeforeBond) {
                    removeBeforeBond = false;
                    stopPolling();
                    log.accept("poll 确认旧绑定已移除，开始 BLE 扫描");
                    if (address != null) {
                        startBleScan(address);
                    }
                    return;
                }
                mainHandler.postDelayed(this, BOND_POLL_INTERVAL_MS);
            } else if (state == BluetoothDevice.BOND_BONDING) {
                mainHandler.postDelayed(this, BOND_POLL_INTERVAL_MS);
            } else if (state == BluetoothDevice.BOND_BONDED) {
                stopPolling();
                mainHandler.removeCallbacks(timeoutRunnable);
                unregisterReceiverOnly();
                if (callback != null) {
                    callback.onBonded(device);
                }
            }
        }
    };

    public BondManager(Context context, Handler mainHandler, Consumer<String> log) {
        this.context = context;
        this.mainHandler = mainHandler;
        this.log = log;
    }

    private BluetoothAdapter getBluetoothAdapter() {
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        return bm != null ? bm.getAdapter() : null;
    }

    public boolean startBonding(BluetoothDevice device, Callback cb) {
        return startBonding(device, cb, BOND_WAIT_TIMEOUT_MS);
    }

    public boolean startBonding(BluetoothDevice device, Callback cb, long timeoutMs) {
        cancelBonding();
        this.callback = cb;
        logBondState(device);
        
        int state = BluetoothDevice.BOND_NONE;
        try {
            state = device.getBondState();
        } catch (SecurityException e) {
            log.accept("读取 bondState 失败: " + e.getMessage());
        }

        if (state == BluetoothDevice.BOND_BONDING) {
            log.accept("设备已在 BOND_BONDING，监听直至 BOND_BONDED 或失败");
            if (!registerReceiver(device)) {
                this.callback = null;
                return false;
            }
            armTimeout(timeoutMs);
            startPolling(device);
        } else if (state == BluetoothDevice.BOND_BONDED) {
            log.accept("bondState=BONDED，先 removeBond 清除旧绑定");
            if (!registerReceiver(device)) {
                this.callback = null;
                return false;
            }
            armTimeout(timeoutMs);
            this.removeBeforeBond = true;
            boolean removeResult = false;
            try {
                removeResult = (Boolean) device.getClass().getMethod("removeBond").invoke(device);
            } catch (Exception e) {
                log.accept("removeBond 反射异常: " + e.getMessage());
            }
            log.accept("removeBond 返回=" + removeResult + "，等待 BOND_NONE 后扫描");
            startPolling(device);
        } else {
            if (!registerReceiver(device)) {
                this.callback = null;
                return false;
            }
            armTimeout(timeoutMs);
            log.accept("bondState=BOND_NONE，先 BLE 扫描确认车在附近");
            String address = device.getAddress();
            startBleScan(address);
        }
        return true;
    }

    public void cancelBonding() {
        this.removeBeforeBond = false;
        this.mainHandler.removeCallbacks(this.timeoutRunnable);
        stopPolling();
        stopBleScan();
        unregisterReceiverOnly();
        this.callback = null;
    }

    private void startBleScan(final String mac) {
        stopBleScan();
        BluetoothAdapter adapter = getBluetoothAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            log.accept("BLE 扫描失败: 蓝牙适配器不可用");
            Callback cb = this.callback;
            cancelBonding();
            if (cb != null) cb.onBondFailed("蓝牙未开启");
            return;
        }
        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            log.accept("BLE 扫描失败: 设备不支持 BLE");
            Callback cb = this.callback;
            cancelBonding();
            if (cb != null) cb.onBondFailed("设备不支持 BLE");
            return;
        }
        if (ContextCompat.checkSelfPermission(this.context, android.Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            log.accept("BLE 扫描失败: 缺少 BLUETOOTH_SCAN 权限");
            Callback cb = this.callback;
            cancelBonding();
            if (cb != null) cb.onBondFailed("缺少蓝牙扫描权限");
            return;
        }
        this.pendingScanMac = mac;
        this.leScanner = scanner;
        ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        
        this.scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                BluetoothDevice dev = result.getDevice();
                if (dev != null && mac.equalsIgnoreCase(dev.getAddress())) {
                    log.accept("BLE 扫描匹配成功: " + dev.getAddress());
                    onScanDeviceFound(dev);
                }
            }

            @Override
            public void onBatchScanResults(List<ScanResult> results) {
                for (ScanResult result : results) {
                    BluetoothDevice dev = result.getDevice();
                    if (dev != null && mac.equalsIgnoreCase(dev.getAddress())) {
                        log.accept("BLE 扫描(Batch)匹配成功: " + dev.getAddress());
                        onScanDeviceFound(dev);
                        break;
                    }
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                log.accept("BLE 扫描失败, errorCode=" + errorCode);
            }
        };

        this.mainHandler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS);
        try {
            scanner.startScan(null, settings, this.scanCallback);
            log.accept("BLE 扫描已启动 mac=" + mac + " 超时=" + SCAN_TIMEOUT_MS + "ms");
        } catch (SecurityException e) {
            log.accept("startScan SecurityException: " + e.getMessage());
            Callback cb = this.callback;
            cancelBonding();
            if (cb != null) cb.onBondFailed("缺少蓝牙权限");
        }
    }

    private void onScanDeviceFound(BluetoothDevice device) {
        stopBleScan();
        log.accept("扫描到设备 → createBond(" + device.getAddress() + ")");
        boolean bondResult = false;
        try {
            bondResult = device.createBond();
        } catch (SecurityException e) {
            log.accept("createBond SecurityException: " + e.getMessage());
        }
        if (bondResult) {
            log.accept("createBond()=true，等待系统配对弹窗");
            startPolling(device);
        } else {
            Callback cb = this.callback;
            cancelBonding();
            if (cb != null) {
                cb.onBondFailed("无法发起系统配对（createBond 返回 false）");
            }
        }
    }

    private void onScanTimeout() {
        String mac = this.pendingScanMac;
        if (mac == null) {
            return;
        }
        log.accept("BLE 扫描超时，未找到设备 " + mac + " → 直接 createBond 兜底");
        stopBleScan();
        tryCreateBondDirect(mac);
    }

    private void tryCreateBondDirect(String mac) {
        BluetoothAdapter adapter = getBluetoothAdapter();
        if (adapter == null) {
            Callback cb = this.callback;
            cancelBonding();
            if (cb != null) cb.onBondFailed("蓝牙适配器不可用");
            return;
        }
        BluetoothDevice device = adapter.getRemoteDevice(mac);
        if (device == null) {
            Callback cb = this.callback;
            cancelBonding();
            if (cb != null) cb.onBondFailed("无法解析设备 MAC");
            return;
        }
        log.accept("扫描失败兜底: 直接 createBond(" + mac + ")");
        boolean result = false;
        try {
            result = device.createBond();
        } catch (SecurityException e) {
            log.accept("tryCreateBondDirect SecurityException: " + e.getMessage());
        }
        if (result) {
            startPolling(device);
        } else {
            Callback cb = this.callback;
            cancelBonding();
            if (cb != null) {
                cb.onBondFailed("无法发起系统配对");
            }
        }
    }

    private void stopBleScan() {
        this.pendingScanMac = null;
        this.mainHandler.removeCallbacks(this.scanTimeoutRunnable);
        if (this.leScanner != null && this.scanCallback != null) {
            try {
                this.leScanner.stopScan(this.scanCallback);
            } catch (SecurityException e) {
                // ignore
            }
        }
        this.leScanner = null;
        this.scanCallback = null;
    }

    private boolean registerReceiver(BluetoothDevice device) {
        unregisterReceiverOnly();
        this.watchAddress = device.getAddress();
        this.stateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("android.bluetooth.device.action.BOND_STATE_CHANGED".equals(intent.getAction())) {
                    BluetoothDevice dev = intent.getParcelableExtra("android.bluetooth.device.extra.DEVICE");
                    if (dev == null || !dev.getAddress().equalsIgnoreCase(watchAddress)) {
                        return;
                    }
                    int bondState = intent.getIntExtra("android.bluetooth.device.extra.BOND_STATE", BluetoothDevice.BOND_NONE);
                    int prevBondState = intent.getIntExtra("android.bluetooth.device.extra.PREVIOUS_BOND_STATE", BluetoothDevice.BOND_NONE);
                    log.accept("BroadcastReceiver: " + dev.getAddress() + " state: " + bondLabel(prevBondState) + " → " + bondLabel(bondState));
                    
                    if (bondState == BluetoothDevice.BOND_BONDED) {
                        stopPolling();
                        mainHandler.removeCallbacks(timeoutRunnable);
                        unregisterReceiverOnly();
                        if (callback != null) {
                            callback.onBonded(dev);
                        }
                    } else if (bondState == BluetoothDevice.BOND_NONE && prevBondState == BluetoothDevice.BOND_BONDING) {
                        int reason = intent.getIntExtra("android.bluetooth.device.extra.REASON", 0);
                        log.accept("BroadcastReceiver: 配对失败 reason=" + reason);
                        Callback cb = callback;
                        cancelBonding();
                        if (cb != null) {
                            cb.onBondFailed("配对失败 reason=" + reason);
                        }
                    }
                }
            }
        };

        try {
            ContextCompat.registerReceiver(this.context, this.stateReceiver, 
                    new IntentFilter("android.bluetooth.device.action.BOND_STATE_CHANGED"), ContextCompat.RECEIVER_EXPORTED);
            log.accept("已注册 BOND_STATE_CHANGED 监听");
            return true;
        } catch (Exception e) {
            this.watchAddress = null;
            this.stateReceiver = null;
            log.accept("注册配对广播失败: " + e.getMessage());
            return false;
        }
    }

    private void armTimeout(long timeoutMs) {
        this.mainHandler.removeCallbacks(this.timeoutRunnable);
        this.mainHandler.postDelayed(this.timeoutRunnable, timeoutMs);
    }

    private void startPolling(BluetoothDevice device) {
        this.pollDevice = device;
        this.mainHandler.removeCallbacks(this.pollRunnable);
        this.mainHandler.postDelayed(this.pollRunnable, BOND_POLL_INTERVAL_MS);
    }

    private void stopPolling() {
        this.pollDevice = null;
        this.mainHandler.removeCallbacks(this.pollRunnable);
    }

    private void onTimeout() {
        if (this.stateReceiver == null) {
            return;
        }
        log.accept("配对等待超时 (90000ms)");
        Callback cb = this.callback;
        cancelBonding();
        if (cb != null) {
            cb.onBondTimeout();
        }
    }

    private void unregisterReceiverOnly() {
        BroadcastReceiver receiver = this.stateReceiver;
        if (receiver == null) {
            return;
        }
        this.stateReceiver = null;
        this.watchAddress = null;
        try {
            this.context.unregisterReceiver(receiver);
            log.accept("已注销 BOND_STATE_CHANGED 监听");
        } catch (Exception e) {
            log.accept("注销配对广播: " + e.getMessage());
        }
    }

    private void logBondState(BluetoothDevice device) {
        int state = BluetoothDevice.BOND_NONE;
        try {
            state = device.getBondState();
        } catch (SecurityException e) {
            log.accept("读取 bondState 异常: " + e.getMessage());
        }
        log.accept("配对状态 bondState=" + bondLabel(state));
    }

    private String bondLabel(int bond) {
        switch (bond) {
            case BluetoothDevice.BOND_NONE:
                return "NONE";
            case BluetoothDevice.BOND_BONDING:
                return "BONDING";
            case BluetoothDevice.BOND_BONDED:
                return "BONDED";
            default:
                return "other=" + bond;
        }
    }
}

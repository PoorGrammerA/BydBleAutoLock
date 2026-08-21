package com.poorgrammera.bydautolock.service;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.os.Handler;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import no.nordicsemi.android.ble.callback.FailCallback;
import no.nordicsemi.android.ble.callback.SuccessCallback;
import no.nordicsemi.android.ble.observer.ConnectionObserver;

public final class GattSessionManager {
    private static final int MAX_RETRIES = 3;
    private static final long PRE_CONNECT_DELAY_MS = 500;
    private static final long RETRY_DELAY_MS = 3000;

    private final Context appContext;
    private BydWatchStyleBleManager bleManager;
    private boolean bondConfirmed;
    private final BondManager bondManager;
    private long lastCycleEndMs;
    private final Consumer<String> log;
    private final Handler mainHandler;
    private boolean manualDisconnect;
    private final BydWatchStyleBleManager.NotificationListener notifyHandler;
    private final Consumer<BleBondStatus> onBondStatus;
    private final BiConsumer<String, String> onDeviceInfo;
    private final Consumer<BydWatchStyleBleManager> onGattReady;
    private final Consumer<String> onSessionEnd;
    private final Consumer<BleConnectionStatus> onStatusChanged;
    private Runnable preConnectRunnable;
    private int retryCount;
    private Runnable retryRunnable;
    private String sessionMac;
    private volatile ConnectionState state;

    public GattSessionManager(
            Context appContext, 
            Handler mainHandler, 
            Consumer<String> log, 
            Consumer<BleConnectionStatus> onStatusChanged, 
            Consumer<BydWatchStyleBleManager> onGattReady, 
            Consumer<String> onSessionEnd, 
            BiConsumer<String, String> onDeviceInfo, 
            Consumer<BleBondStatus> onBondStatus, 
            BydWatchStyleBleManager.NotificationListener notifyHandler) {
        this.appContext = appContext;
        this.mainHandler = mainHandler;
        this.log = log;
        this.onStatusChanged = onStatusChanged;
        this.onGattReady = onGattReady;
        this.onSessionEnd = onSessionEnd;
        this.onDeviceInfo = onDeviceInfo;
        this.onBondStatus = onBondStatus;
        this.notifyHandler = notifyHandler;
        this.state = ConnectionState.IDLE;
        this.bondManager = new BondManager(appContext, mainHandler, log);
    }

    public BydWatchStyleBleManager getBleManager() {
        return this.bleManager;
    }

    public ConnectionState getState() {
        return this.state;
    }

    public boolean isIdle() {
        return this.state == ConnectionState.IDLE;
    }

    public boolean isDisconnected() {
        return this.state == ConnectionState.DISCONNECTED;
    }

    public boolean connect(BluetoothAdapter adapter, String mac) {
        if (this.state == ConnectionState.IDLE || this.state == ConnectionState.DISCONNECTED) {
            startGattOnlyCycle(adapter, mac);
            return true;
        }
        if (this.state == ConnectionState.CONNECTING) {
            this.log.accept("GattSession: 已在 CONNECTING 状态（重试中），跳过");
            return false;
        }
        this.log.accept("GattSession: 已在 state=" + this.state + "，强制重置后重新 GATT 建链");
        releaseBleManager();
        cancelAllDelays();
        this.bondManager.cancelBonding();
        this.manualDisconnect = false;
        this.lastCycleEndMs = 0L;
        startGattOnlyCycle(adapter, mac);
        return true;
    }

    public boolean pairOnly(BluetoothAdapter adapter, String mac) {
        if (this.state != ConnectionState.IDLE && this.state != ConnectionState.DISCONNECTED) {
            this.log.accept("GattSession: pairOnly 忽略，当前 state=" + this.state);
            return false;
        }
        startCycle(adapter, mac, false);
        return true;
    }

    public void disconnect() {
        this.log.accept("GattSession: disconnect() 主动断开");
        this.manualDisconnect = true;
        this.bondManager.cancelBonding();
        cancelAllDelays();
        BydWatchStyleBleManager manager = this.bleManager;
        if (manager != null && manager.isConnected()) {
            try {
                manager.disconnect().enqueue();
            } catch (Throwable th) {
                // ignore
            }
        }
        releaseBleManager();
        endCycle("用户主动断开");
    }

    public void cancelBondSession() {
        this.bondManager.cancelBonding();
    }

    public void notifyAuthStarted() {
        if (this.state == ConnectionState.READY) {
            transitionTo(ConnectionState.AUTH);
        }
    }

    public void notifyAuthPassed() {
        if (this.state == ConnectionState.AUTH) {
            transitionTo(ConnectionState.PASSED);
        }
    }

    public void notifyAuthFailed() {
        if (this.state == ConnectionState.AUTH) {
            transitionTo(ConnectionState.READY);
        }
    }

    private void startGattOnlyCycle(BluetoothAdapter adapter, String mac) {
        this.retryCount = 0;
        this.manualDisconnect = false;
        this.bondConfirmed = false;
        this.sessionMac = mac;
        BluetoothDevice remoteDevice = adapter.getRemoteDevice(mac);
        int bondState = BluetoothDevice.BOND_NONE;
        try {
            bondState = remoteDevice.getBondState();
        } catch (SecurityException e) {
            // ignore
        }
        BleBondStatus status = bondState == BluetoothDevice.BOND_BONDED ? BleBondStatus.BONDED : BleBondStatus.NOT_BONDED;
        this.onBondStatus.accept(status);
        this.log.accept("GattSession: connect() 跳过配对检查（bond=" + status.name() + "），直接 GATT 建链");
        scheduleGattConnect(adapter, remoteDevice);
    }

    private void startCycle(BluetoothAdapter adapter, String mac, boolean proceedToGatt) {
        this.retryCount = 0;
        this.manualDisconnect = false;
        this.bondConfirmed = false;
        this.sessionMac = mac;
        beginWithBondCheck(adapter, mac, proceedToGatt);
    }

    private void beginWithBondCheck(BluetoothAdapter adapter, String mac, final boolean proceedToGatt) {
        BluetoothDevice remoteDevice = adapter.getRemoteDevice(mac);
        logBondState(remoteDevice);
        transitionTo(ConnectionState.BONDING);
        this.onBondStatus.accept(BleBondStatus.BONDING);
        this.bondManager.startBonding(remoteDevice, new BondManager.Callback() {
            @Override
            public void onBonded(BluetoothDevice dev) {
                if (state != ConnectionState.BONDING) {
                    log.accept("GattSession: 忽略过期的 onBonded (state=" + state + ")");
                    return;
                }
                bondConfirmed = true;
                onBondStatus.accept(BleBondStatus.BONDED);
                if (proceedToGatt) {
                    BluetoothAdapter adapter2 = getAdapter();
                    if (adapter2 == null) {
                        endCycle("配对完成但蓝牙适配器不可用");
                    } else {
                        scheduleGattConnect(adapter2, dev);
                    }
                } else {
                    log.accept("GattSession: pairOnly 配对完成，不进入 GATT");
                    transitionTo(ConnectionState.IDLE);
                    lastCycleEndMs = System.currentTimeMillis();
                }
            }

            @Override
            public void onBondFailed(String reason) {
                if (state != ConnectionState.BONDING) {
                    return;
                }
                onBondStatus.accept(BleBondStatus.NOT_BONDED);
                endCycle("配对失败: " + reason);
            }

            @Override
            public void onBondTimeout() {
                if (state != ConnectionState.BONDING) {
                    return;
                }
                onBondStatus.accept(BleBondStatus.NOT_BONDED);
                endCycle("配对超时");
            }
        });
    }

    private void scheduleGattConnect(BluetoothAdapter adapter, BluetoothDevice device) {
        cancelPreConnectDelay();
        transitionTo(ConnectionState.CONNECTING);
        final String address = device.getAddress();
        this.log.accept("GattSession: 500ms 后再 Nordic.connect（mac=" + address + "）");
        this.preConnectRunnable = new Runnable() {
            @Override
            public void run() {
                preConnectRunnable = null;
                if (state != ConnectionState.CONNECTING) {
                    return;
                }
                BluetoothAdapter adapter2 = getAdapter();
                if (adapter2 == null) {
                    endCycle("延迟建链时蓝牙适配器不可用");
                    return;
                }
                BluetoothDevice dev = null;
                try {
                    dev = adapter2.getRemoteDevice(address);
                } catch (Exception e) {
                    // ignore
                }
                if (dev == null) {
                    endCycle("延迟建链时无法解析设备 MAC");
                } else {
                    startGattSession(adapter2, dev);
                }
            }
        };
        this.mainHandler.postDelayed(this.preConnectRunnable, PRE_CONNECT_DELAY_MS);
    }

    private void startGattSession(BluetoothAdapter adapter, BluetoothDevice device) {
        this.log.accept("GattSession: 开始 Nordic BleManager.connect → " + device.getAddress());
        releaseBleManager();
        transitionTo(ConnectionState.CONNECTING);
        BydWatchStyleBleManager manager = new BydWatchStyleBleManager(this.appContext, this.notifyHandler);
        this.bleManager = manager;
        manager.setConnectionObserver(createObserver());
        manager.connect(device)
                .useAutoConnect(false)
                .timeout(60000L)
                .done(new SuccessCallback() {
                    @Override
                    public void onRequestCompleted(BluetoothDevice dev) {
                        log.accept("ConnectRequest.done（初始化队列完成）");
                        mainHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                BydWatchStyleBleManager current = bleManager;
                                if (current != null && current.isReady()) {
                                    log.accept("GattSession: done callback isReady");
                                    onGattReadyInternal(current);
                                } else {
                                    log.accept("done callback completed with isReady=false; waiting for ConnectionObserver");
                                }
                            }
                        }, 120L);
                    }
                })
                .fail(new FailCallback() {
                    @Override
                    public void onRequestFailed(BluetoothDevice dev, int status) {
                        log.accept("ConnectRequest.fail status=" + status);
                        endCycle("GATT connection failed status=" + status);
                    }
                })
                .enqueue();
    }

    private ConnectionObserver createObserver() {
        return new ConnectionObserver() {
            @Override
            public void onDeviceConnecting(BluetoothDevice device) {
                log.accept("Nordic onDeviceConnecting " + device.getAddress());
                transitionTo(ConnectionState.CONNECTING);
            }

            @Override
            public void onDeviceConnected(BluetoothDevice device) {
                String name = "";
                try {
                    name = device.getName();
                } catch (SecurityException e) {
                    // ignore
                }
                if (name == null) name = "";
                log.accept("Nordic onDeviceConnected " + device.getAddress() + " name=" + (name.isEmpty() ? "(none)" : name));
                bondManager.cancelBonding();
                onDeviceInfo.accept(device.getAddress() != null ? device.getAddress() : "", name);
                transitionTo(ConnectionState.CONNECTED);
            }

            @Override
            public void onDeviceReady(BluetoothDevice device) {
                log.accept("Nordic onDeviceReady (handshake complete)");
                retryCount = 0;
                BydWatchStyleBleManager current = bleManager;
                if (current != null) {
                    onGattReadyInternal(current);
                }
            }

            @Override
            public void onDeviceDisconnecting(BluetoothDevice device) {
                log.accept("Nordic onDeviceDisconnecting");
            }

            @Override
            public void onDeviceFailedToConnect(BluetoothDevice device, int reason) {
                log.accept("Nordic onDeviceFailedToConnect reason=" + reason);
                releaseBleManager();
                handleRetry(device.getAddress(), "GATT connection failed");
            }

            @Override
            public void onDeviceDisconnected(BluetoothDevice device, int reason) {
                String desc = describeReason(reason);
                log.accept("Nordic onDeviceDisconnected reason=" + reason + " (" + desc + ")");
                if (state == ConnectionState.IDLE || state == ConnectionState.DISCONNECTED) {
                    return;
                }
                if (manualDisconnect) {
                    manualDisconnect = false;
                    log.accept("GattSession: manually disconnected by user; skipping automatic retry");
                    endCycle("用户主动断开");
                } else {
                    if (state == ConnectionState.READY || state == ConnectionState.AUTH || state == ConnectionState.PASSED) {
                        endCycle("已就绪后断链 reason=" + reason + " (" + desc + ")");
                        return;
                    }
                    releaseBleManager();
                    handleRetry(device.getAddress(), "GATT disconnected");
                }
            }
        };
    }

    private void handleRetry(final String mac, String reason) {
        if (this.retryCount < MAX_RETRIES) {
            this.retryCount++;
            this.log.accept("GattSession: " + reason + ", retry " + this.retryCount + " after 3000ms (bondConfirmed=" + this.bondConfirmed + ")");
            cancelRetryDelay();
            this.retryRunnable = new Runnable() {
                @Override
                public void run() {
                    retryRunnable = null;
                    if (state != ConnectionState.CONNECTING) {
                        return;
                    }
                    BluetoothAdapter adapter = getAdapter();
                    if (adapter == null) {
                        endCycle("重试时蓝牙适配器不可用");
                        return;
                    }
                    BluetoothDevice dev = null;
                    try {
                        dev = adapter.getRemoteDevice(mac);
                    } catch (Exception e) {
                        // ignore
                    }
                    if (dev == null) {
                        endCycle("重试时无法解析设备 MAC");
                    } else {
                        scheduleGattConnect(adapter, dev);
                    }
                }
            };
            this.mainHandler.postDelayed(this.retryRunnable, RETRY_DELAY_MS);
        } else {
            endCycle(reason + " (exceeded 3 retries)");
        }
    }

    private void onGattReadyInternal(BydWatchStyleBleManager m) {
        if (this.state == ConnectionState.READY || this.state == ConnectionState.AUTH || this.state == ConnectionState.PASSED) {
            this.log.accept("GattSession: onGattReady 但已处于 state=" + this.state + "，跳过重复");
        } else {
            transitionTo(ConnectionState.READY);
            this.onGattReady.accept(m);
        }
    }

    private void transitionTo(ConnectionState newState) {
        ConnectionState oldState = this.state;
        this.state = newState;
        this.log.accept("GattSession: " + oldState + " → " + newState);
        this.onStatusChanged.accept(mapStateToStatus(newState));
    }

    private void endCycle(String reason) {
        releaseBleManager();
        cancelAllDelays();
        this.bondManager.cancelBonding();
        this.lastCycleEndMs = System.currentTimeMillis();
        this.onSessionEnd.accept(reason);
        transitionTo(ConnectionState.DISCONNECTED);
        this.log.accept("GattSession: 周期结束: " + reason);
    }

    private void releaseBleManager() {
        cancelPreConnectDelay();
        BydWatchStyleBleManager current = this.bleManager;
        if (current == null) {
            return;
        }
        this.bleManager = null;
        WatchStyleBleFrameAssembler.INSTANCE.resetProcess();
        try {
            current.close();
        } catch (Throwable th) {
            // ignore
        }
    }

    private void cancelAllDelays() {
        cancelPreConnectDelay();
        cancelRetryDelay();
    }

    private void cancelPreConnectDelay() {
        if (this.preConnectRunnable != null) {
            this.mainHandler.removeCallbacks(this.preConnectRunnable);
        }
        this.preConnectRunnable = null;
    }

    private void cancelRetryDelay() {
        if (this.retryRunnable != null) {
            this.mainHandler.removeCallbacks(this.retryRunnable);
        }
        this.retryRunnable = null;
    }

    private BluetoothAdapter getAdapter() {
        BluetoothManager bm = (BluetoothManager) this.appContext.getSystemService(Context.BLUETOOTH_SERVICE);
        return bm != null ? bm.getAdapter() : null;
    }

    private void logBondState(BluetoothDevice device) {
        int bondState = BluetoothDevice.BOND_NONE;
        try {
            bondState = device.getBondState();
        } catch (SecurityException e) {
            // ignore
        }
        String label = "NONE";
        if (bondState == BluetoothDevice.BOND_BONDING) label = "BONDING";
        else if (bondState == BluetoothDevice.BOND_BONDED) label = "BONDED";
        this.log.accept("配对状态 bondState=" + label);
    }

    private BleConnectionStatus mapStateToStatus(ConnectionState state) {
        switch (state) {
            case IDLE:
            case DISCONNECTED:
                return BleConnectionStatus.DISCONNECTED;
            case BONDING:
            case CONNECTING:
                return BleConnectionStatus.CONNECTING;
            case CONNECTED:
                return BleConnectionStatus.CONNECTED;
            case READY:
            case AUTH:
            case PASSED:
                return BleConnectionStatus.READY;
            default:
                return BleConnectionStatus.DISCONNECTED;
        }
    }

    private String describeReason(int reason) {
        switch (reason) {
            case ConnectionObserver.REASON_TIMEOUT:
                return "REASON_TIMEOUT";
            case ConnectionObserver.REASON_TERMINATE_LOCAL_HOST:
                return "REASON_TERMINATE_LOCAL_HOST";
            case ConnectionObserver.REASON_TERMINATE_PEER_USER:
                return "REASON_TERMINATE_PEER_USER";
            case ConnectionObserver.REASON_LINK_LOSS:
                return "REASON_LINK_LOSS";
            case ConnectionObserver.REASON_NOT_SUPPORTED:
                return "REASON_NOT_SUPPORTED(车端无所需服务)";
            case ConnectionObserver.REASON_CANCELLED:
                return "REASON_CANCELLED";
            case -1:
                return "REASON_UNKNOWN";
            case 0:
                return "REASON_SUCCESS";
            default:
                return "reason=" + reason;
        }
    }
}

package com.poorgrammera.bydautolock.service;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.Context;
import android.util.Log;
import androidx.annotation.NonNull;
import com.poorgrammera.bydblekeycontrol.blecodec.BydBleCodec;
import no.nordicsemi.android.ble.BleManager;
import no.nordicsemi.android.ble.data.Data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class BydWatchStyleBleManager extends BleManager {
    private static final String TAG = "BydWatchStyleBle";
    
    public interface NotificationListener {
        void onNotify(byte[] data);
    }

    public interface DiagCallback {
        void onDiag(String msg);
    }

    private static volatile DiagCallback diagCallback;
    static volatile UUID receiveCharacteristicUuid;
    static volatile UUID receiveServiceUuid;
    static volatile UUID sendCharacteristicUuid;
    static volatile UUID sendServiceUuid;
    
    private volatile int lastServiceCount;
    private volatile List<String> lastServiceUuidSample = Collections.emptyList();
    private volatile List<String> lastWritableUuids = Collections.emptyList();
    private final NotificationListener notificationListener;
    private volatile BluetoothGattCharacteristic receiveCharacteristic;
    private volatile BluetoothGattCharacteristic sendCharacteristic;

    public BydWatchStyleBleManager(@NonNull Context context, @NonNull NotificationListener notificationListener) {
        super(context.getApplicationContext());
        this.notificationListener = notificationListener;
        initBleUuidConfigIfNeeded();
    }

    public BluetoothGattCharacteristic getSendCharacteristic() {
        return this.sendCharacteristic;
    }

    public BluetoothGattCharacteristic getReceiveCharacteristic() {
        return this.receiveCharacteristic;
    }

    public int getLastServiceCount() {
        return this.lastServiceCount;
    }

    public List<String> getLastServiceUuidSample() {
        return this.lastServiceUuidSample;
    }

    public List<String> getLastWritableUuids() {
        return this.lastWritableUuids;
    }

    @Override
    protected boolean isRequiredServiceSupported(@NonNull BluetoothGatt gatt) {
        List<BluetoothGattService> services = gatt.getServices();
        if (services == null) {
            services = Collections.emptyList();
        }
        this.lastServiceCount = services.size();
        
        List<BluetoothGattService> takeList = services.subList(0, Math.min(services.size(), 12));
        ArrayList<String> uuidSampleList = new ArrayList<>();
        for (BluetoothGattService s : takeList) {
            uuidSampleList.add(s.getUuid().toString());
        }
        this.lastServiceUuidSample = uuidSampleList;
        this.lastWritableUuids = collectWritableUuids(services);

        initBleUuidConfigIfNeeded();

        UUID sendSvcUuid = sendServiceUuid;
        UUID sendCharUuid = sendCharacteristicUuid;
        UUID recvSvcUuid = receiveServiceUuid;
        UUID recvCharUuid = receiveCharacteristicUuid;

        if (sendSvcUuid == null || sendCharUuid == null || recvSvcUuid == null || recvCharUuid == null) {
            return false;
        }

        BluetoothGattService sendService = gatt.getService(sendSvcUuid);
        if (sendService == null) {
            return false;
        }

        BluetoothGattCharacteristic sendChar = sendService.getCharacteristic(sendCharUuid);
        BluetoothGattService recvService = gatt.getService(recvSvcUuid);
        if (recvService == null) {
            return false;
        }

        BluetoothGattCharacteristic recvChar = recvService.getCharacteristic(recvCharUuid);
        
        this.sendCharacteristic = sendChar;
        this.receiveCharacteristic = recvChar;

        return sendChar != null && recvChar != null;
    }

    @Override
    protected void initialize() {
        BluetoothGattCharacteristic charToNotify = this.receiveCharacteristic;
        if (charToNotify == null) {
            return;
        }
        setNotificationCallback(charToNotify).with((device, data) -> {
            byte[] value = data.getValue();
            if (value == null) {
                value = new byte[0];
            }
            Log.d(TAG, "Notify received: uuid=" + charToNotify.getUuid()
                    + " len=" + value.length + " hex=" + toHex(value));
            notificationListener.onNotify(value);
        });
        enableNotifications(charToNotify)
                .done(device -> Log.d(TAG, "Notifications enabled: uuid=" + charToNotify.getUuid()))
                .fail((device, status) -> Log.e(TAG, "Notifications enable failed: uuid="
                        + charToNotify.getUuid() + " status=" + status))
                .enqueue();
    }

    public boolean sendPayload(byte[] data) {
        if (data == null) return false;
        BluetoothGattCharacteristic sendChar = this.sendCharacteristic;
        if (sendChar == null || !isReady()) {
            return false;
        }
        // Preserve the write type advertised by the vehicle characteristic.
        // BYD BLE3 rejects the first wake-up frame when it is forced to
        // WRITE_TYPE_NO_RESPONSE; the reference Watch flow uses the default.
        writeCharacteristic(sendChar, data).enqueue();
        return true;
    }

    public interface WriteCallback {
        void onSuccess();
        void onFailure();
    }

    public boolean enqueueWritePayload(byte[] data, WriteCallback callback) {
        if (data == null) return false;
        BluetoothGattCharacteristic sendChar = this.sendCharacteristic;
        if (sendChar == null || !isReady()) {
            Log.w(TAG, "Write rejected locally: characteristic=" + (sendChar != null)
                    + " ready=" + isReady() + " len=" + data.length);
            return false;
        }
        Log.d(TAG, "Write enqueue: uuid=" + sendChar.getUuid()
                + " properties=0x" + Integer.toHexString(sendChar.getProperties())
                + " writeType=" + sendChar.getWriteType()
                + " len=" + data.length);
        writeCharacteristic(sendChar, data)
                .done(device -> {
                    Log.d(TAG, "Write complete: uuid=" + sendChar.getUuid() + " len=" + data.length);
                    if (callback != null) callback.onSuccess();
                })
                .fail((device, status) -> {
                    Log.e(TAG, "Write failed: uuid=" + sendChar.getUuid()
                            + " status=" + status + " len=" + data.length);
                    if (callback != null) callback.onFailure();
                })
                .enqueue();
        return true;
    }

    public interface RssiListener {
        void onRssi(int rssi);
    }

    public boolean enqueueReadRssi(RssiListener listener) {
        if (!isConnected()) {
            return false;
        }
        readRssi().with((device, rssi) -> {
            if (listener != null) listener.onRssi(rssi);
        }).enqueue();
        return true;
    }

    private List<String> collectWritableUuids(List<BluetoothGattService> services) {
        ArrayList<String> list = new ArrayList<>();
        for (BluetoothGattService service : services) {
            List<BluetoothGattCharacteristic> characteristics = service.getCharacteristics();
            if (characteristics == null) {
                characteristics = Collections.emptyList();
            }
            for (BluetoothGattCharacteristic chr : characteristics) {
                int properties = chr.getProperties();
                boolean writable = (properties & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0;
                boolean writableNoResp = (properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
                if (writable || writableNoResp) {
                    list.add("S=" + service.getUuid() + " C=" + chr.getUuid());
                }
            }
        }
        return list;
    }

    private static String toHex(byte[] data) {
        StringBuilder output = new StringBuilder(data.length * 2);
        for (byte value : data) output.append(String.format(java.util.Locale.ROOT, "%02X", value & 0xFF));
        return output.toString();
    }

    public static void initBleUuidConfigIfNeeded() {
        if (sendServiceUuid != null) {
            return;
        }
        synchronized (BydWatchStyleBleManager.class) {
            if (sendServiceUuid != null) {
                return;
            }
            loadCodecUuidsLocked();
        }
    }

    private static void loadCodecUuidsLocked() {
        try {
            String serviceUuidValue = BydBleCodec.SERVICE_UUID;
            String sendUuidValue = BydBleCodec.SEND_CHARACTERISTIC_UUID;
            String receiveUuidValue = BydBleCodec.RECEIVE_CHARACTERISTIC_UUID;

            if (diagCallback != null) {
                diagCallback.onDiag("Codec UUIDs: service='" + serviceUuidValue
                        + "' send='" + sendUuidValue + "' receive='" + receiveUuidValue + "'");
            }

            sendServiceUuid = UUID.fromString(serviceUuidValue);
            receiveServiceUuid = UUID.fromString(serviceUuidValue);
            sendCharacteristicUuid = UUID.fromString(sendUuidValue);
            receiveCharacteristicUuid = UUID.fromString(receiveUuidValue);
        } catch (Throwable th) {
            Log.e("BydWatchStyleBleManager", "Error loading BLE UUIDs", th);
        }
    }

    public static boolean isUuidConfigComplete() {
        return sendServiceUuid != null && sendCharacteristicUuid != null 
                && receiveServiceUuid != null && receiveCharacteristicUuid != null;
    }

    public static DiagCallback getDiagCallback() {
        return diagCallback;
    }

    public static void setDiagCallback(DiagCallback callback) {
        diagCallback = callback;
    }
}

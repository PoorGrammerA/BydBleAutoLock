package com.poorgrammera.bydautolock.service;

import android.util.Log;

import java.util.function.Supplier;

public final class BleGattDataWriteChannel implements WatchStyleDataSendRecv.IDataWriteChannel {
    private static final String TAG = "BleGattWriteChannel";
    private final Supplier<BydWatchStyleBleManager> managerProvider;

    public BleGattDataWriteChannel(Supplier<BydWatchStyleBleManager> managerProvider) {
        this.managerProvider = managerProvider;
    }

    @Override
    public void writeData(byte[] bArr, final WatchStyleDataSendRecv.OnWriteResultListener listener) {
        if (bArr == null) {
            Log.w(TAG, "writeData rejected: payload is null");
            if (listener != null) listener.onWriteFailed();
            return;
        }
        BydWatchStyleBleManager manager = this.managerProvider.get();
        if (manager == null) {
            Log.w(TAG, "writeData rejected: BLE manager is null");
            if (listener != null) listener.onWriteFailed();
        } else {
            boolean enqueued = manager.enqueueWritePayload(bArr, new BydWatchStyleBleManager.WriteCallback() {
                @Override
                public void onSuccess() {
                    if (listener != null) listener.onWriteSuccess();
                }

                @Override
                public void onFailure() {
                    if (listener != null) listener.onWriteFailed();
                }
            });
            if (!enqueued && listener != null) {
                Log.w(TAG, "writeData was not enqueued by BLE manager");
                listener.onWriteFailed();
            }
        }
    }

    @Override
    public void writeDataV2(byte[] bArr, WatchStyleDataSendRecv.OnWriteResultListener listener) {
        writeData(bArr, listener);
    }
}

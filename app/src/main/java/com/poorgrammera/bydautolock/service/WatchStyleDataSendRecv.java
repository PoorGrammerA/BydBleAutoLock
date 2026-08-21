package com.poorgrammera.bydautolock.service;

import android.util.Log;

public final class WatchStyleDataSendRecv {
    private static final String TAG = "WatchStyleDataSendRecv";
    public static final WatchStyleDataSendRecv INSTANCE = new WatchStyleDataSendRecv();
    private static volatile IDataWriteChannel writeChannel;

    public interface IDataWriteChannel {
        void writeData(byte[] bArr, OnWriteResultListener listener);
        void writeDataV2(byte[] bArr, OnWriteResultListener listener);
    }

    public interface OnWriteResultListener {
        void onWriteFailed();
        void onWriteSuccess();
    }

    private WatchStyleDataSendRecv() {
    }

    public void registerDataWriteChannel(IDataWriteChannel channel) {
        writeChannel = channel;
        Log.d(TAG, "Write channel " + (channel == null ? "cleared" : "registered"));
    }

    public void sendFrameData(byte[] bArr, OnWriteResultListener listener) {
        IDataWriteChannel iDataWriteChannel = writeChannel;
        if (iDataWriteChannel == null) {
            Log.e(TAG, "sendFrameData failed: no write channel, len=" + (bArr == null ? -1 : bArr.length));
            if (listener != null) {
                listener.onWriteFailed();
            }
        } else if (bArr != null) {
            iDataWriteChannel.writeData(bArr, listener);
        } else if (listener != null) {
            listener.onWriteFailed();
        }
    }

    public void sendFrameDataV2(byte[] bArr, OnWriteResultListener listener) {
        IDataWriteChannel iDataWriteChannel = writeChannel;
        if (iDataWriteChannel == null) {
            if (listener != null) {
                listener.onWriteFailed();
            }
        } else if (bArr != null) {
            iDataWriteChannel.writeDataV2(bArr, listener);
        } else if (listener != null) {
            listener.onWriteFailed();
        }
    }
}

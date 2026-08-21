package com.poorgrammera.bydautolock.service;

import com.poorgrammera.bydblekeycontrol.blecodec.BydBleCrypto;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class WatchStyleBleFrameAssembler {
    private static final int COMPACT_AFTER_READ = 8000;
    private static final int FRAME_LEN = 20;
    private static final short HEADER_A = -23206;
    private static final short HEADER_B = -19109;
    private static final short TAIL_MAGIC = -2566;
    private static volatile Thread processThread;
    private static int readerIndex;
    private static int writerIndex;
    public static final WatchStyleBleFrameAssembler INSTANCE = new WatchStyleBleFrameAssembler();
    private static final Object lock = new Object();
    private static final int CAPACITY = 12000;
    private static final byte[] slab = new byte[CAPACITY];
    private static final ExecutorService appendExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ble-frame-append");
        thread.setDaemon(true);
        return thread;
    });
    private static final CopyOnWriteArrayList<BleFramePayloadListener> listeners = new CopyOnWriteArrayList<>();

    public interface BleFramePayloadListener {
        void onAssembledPayload(byte[] payload16);
    }

    private WatchStyleBleFrameAssembler() {
    }

    public void addListener(BleFramePayloadListener listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    public void removeListener(BleFramePayloadListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    public void resetProcess() {
        synchronized (lock) {
            readerIndex = 0;
            writerIndex = 0;
        }
    }

    public void processReceiveFrames(final byte[] chunk) {
        if (chunk == null || chunk.length == 0) {
            return;
        }
        appendExecutor.execute(() -> {
            synchronized (lock) {
                int length = chunk.length;
                int i = CAPACITY - writerIndex;
                if (length > i) {
                    compactLocked();
                    i = CAPACITY - writerIndex;
                }
                if (length <= i) {
                    System.arraycopy(chunk, 0, slab, writerIndex, length);
                    writerIndex += length;
                    lock.notifyAll();
                }
            }
        });
        ensureProcessThread();
    }

    private void ensureProcessThread() {
        if (processThread != null) {
            return;
        }
        synchronized (lock) {
            if (processThread != null) {
                return;
            }
            Thread thread = new Thread(this::processLoop, "ble-frame-process");
            thread.setDaemon(true);
            processThread = thread;
            thread.start();
        }
    }

    private void processLoop() {
        byte[] bArrExtractOneFrameLocked;
        while (true) {
            ArrayList<byte[]> arrayList = new ArrayList<>(4);
            synchronized (lock) {
                while (writerIndex - readerIndex < FRAME_LEN) {
                    try {
                        lock.wait();
                    } catch (InterruptedException unused) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                while (writerIndex - readerIndex >= FRAME_LEN && (bArrExtractOneFrameLocked = extractOneFrameLocked()) != null) {
                    arrayList.add(bArrExtractOneFrameLocked);
                }
                if (readerIndex >= COMPACT_AFTER_READ) {
                    compactLocked();
                }
            }
            if (arrayList.isEmpty()) {
                Thread.yield();
            } else {
                for (byte[] bArr : arrayList) {
                    for (BleFramePayloadListener listener : listeners) {
                        try {
                            listener.onAssembledPayload(bArr);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }
    }

    private byte[] extractOneFrameLocked() {
        int i = readerIndex;
        if (writerIndex - i < FRAME_LEN) {
            return null;
        }
        short shortBe = readShortBe(i);
        if (shortBe != HEADER_A && shortBe != HEADER_B) {
            readerIndex = i + 1;
            return null;
        }
        int i2 = i + 18;
        if (i + FRAME_LEN > writerIndex) {
            readerIndex = i;
            return null;
        }
        if (readShortBe(i2) != TAIL_MAGIC) {
            readerIndex = i + 1;
            return null;
        }
        byte[] bArr = new byte[FRAME_LEN];
        System.arraycopy(slab, i, bArr, 0, FRAME_LEN);
        if (BydBleCrypto.crc8(bArr, 0, 16) != bArr[17]) {
            readerIndex = i + 1;
            return null;
        }
        byte[] bArr2 = new byte[16];
        System.arraycopy(bArr, 2, bArr2, 0, 16);
        readerIndex = i + FRAME_LEN;
        return bArr2;
    }

    private short readShortBe(int offset) {
        byte[] bArr = slab;
        return (short) ((bArr[offset + 1] & 0xFF) | ((bArr[offset] & 0xFF) << 8));
    }

    private void compactLocked() {
        int i = writerIndex;
        int i2 = readerIndex;
        int i3 = i - i2;
        if (i3 <= 0) {
            readerIndex = 0;
            writerIndex = 0;
        } else if (i2 > 0) {
            byte[] bArr = slab;
            System.arraycopy(bArr, i2, bArr, 0, i3);
            readerIndex = 0;
            writerIndex = i3;
        }
    }
}

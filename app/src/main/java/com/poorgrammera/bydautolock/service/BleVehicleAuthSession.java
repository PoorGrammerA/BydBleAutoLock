package com.poorgrammera.bydautolock.service;

import android.os.Handler;
import android.util.Log;

import com.poorgrammera.bydautolock.model.WatchBlueToothKeyStatInfo;
import com.poorgrammera.bydblekeycontrol.blecodec.BleRandomExchangeResult;
import com.poorgrammera.bydblekeycontrol.blecodec.BydBleCodec;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Runs the Korean dkey BLE authentication sequence verified by this PoC. */
public final class BleVehicleAuthSession {
    private static final String TAG = "BleVehicleAuth";
    private static final long RANDOM_FRAME_DELAY_MS = 200L;
    private static final long AUTH_FRAME_DELAY_MS = 180L;

    private final Consumer<String> appendDiagnostic;
    private final Handler mainHandler;
    private final BiConsumer<BleAuthPhase, String> onPhase;
    private final AtomicInteger sessionId = new AtomicInteger();

    private volatile boolean cancelled;
    private WatchStyleBleFrameAssembler.BleFramePayloadListener responseListener;

    public BleVehicleAuthSession(
            Handler mainHandler,
            Consumer<String> appendDiagnostic,
            BiConsumer<BleAuthPhase, String> onPhase) {
        this.mainHandler = mainHandler;
        this.appendDiagnostic = appendDiagnostic;
        this.onPhase = onPhase;
    }

    public void start(WatchBlueToothKeyStatInfo info) {
        cancel();
        cancelled = false;
        final int currentSession = sessionId.get();

        if (info == null) {
            fail("BLE key information is missing");
            return;
        }
        String dkey = info.getDk();
        if (!BydBleCodec.isValidDkey(dkey)) {
            fail("Korean dkey authentication requires a hexadecimal dkey");
            return;
        }

        BydBleCodec.clearSession();
        phase(BleAuthPhase.RUNNING, "Starting Korean dkey authentication");
        byte keyNumber = keyNumber(info);
        diagnostic("Sending wake frame; keyNumber=" + (keyNumber & 0xFF));

        sendFrame(BydBleCodec.createWakeUpFrame(), currentSession,
                () -> mainHandler.postDelayed(
                        () -> sendRandomExchange(info, keyNumber, currentSession),
                        RANDOM_FRAME_DELAY_MS),
                "Wake frame write failed");
    }

    public void cancel() {
        cancelled = true;
        sessionId.incrementAndGet();
        unregisterResponseListener();
    }

    private void sendRandomExchange(
            WatchBlueToothKeyStatInfo info,
            byte keyNumber,
            int currentSession) {
        if (!isActive(currentSession)) return;

        final byte[] randomFrame;
        try {
            randomFrame = BydBleCodec.createRandomExchangeFrame(keyNumber);
        } catch (RuntimeException error) {
            fail("Could not create random-exchange frame: " + safeMessage(error));
            return;
        }

        unregisterResponseListener();
        responseListener = payload -> mainHandler.post(
                () -> handleRandomExchangeResponse(info, payload, currentSession));
        WatchStyleBleFrameAssembler.INSTANCE.addListener(responseListener);
        diagnostic("Sending random-exchange frame (" + randomFrame.length + " bytes)");
        sendFrame(randomFrame, currentSession,
                () -> diagnostic("Random-exchange frame written; waiting for vehicle response"),
                "Random-exchange frame write failed");
    }

    private void handleRandomExchangeResponse(
            WatchBlueToothKeyStatInfo info,
            byte[] payload,
            int currentSession) {
        if (!isActive(currentSession)
                || BydBleCodec.parseResponseType(payload) != BydBleCodec.RESPONSE_RANDOM_EXCHANGE) {
            return;
        }

        BleRandomExchangeResult result = new BleRandomExchangeResult();
        boolean parsed = BydBleCodec.parseRandomExchange(result, payload);
        diagnostic("Random-exchange response: parsed=" + parsed + ", " + result);
        if (!parsed || result.getCrcCheckResult() != 1) {
            unregisterResponseListener();
            fail("Random-exchange response was invalid");
            return;
        }
        if (result.getKeyState() != 1) {
            unregisterResponseListener();
            fail("BLE key is not active; keyState=" + (result.getKeyState() & 0xFF));
            return;
        }

        unregisterResponseListener();
        phase(BleAuthPhase.STEP_RANDOM_EXCHANGE_OK, "Random exchange complete");
        mainHandler.postDelayed(
                () -> sendAuthenticationFrame(info.getDk(), currentSession),
                AUTH_FRAME_DELAY_MS);
    }

    private void sendAuthenticationFrame(String dkey, int currentSession) {
        if (!isActive(currentSession)) return;

        final byte[] authenticationFrame;
        try {
            authenticationFrame = BydBleCodec.createAuthenticationFrame(dkey);
        } catch (RuntimeException error) {
            fail("Could not create authentication frame: " + safeMessage(error));
            return;
        }

        unregisterResponseListener();
        responseListener = payload -> mainHandler.post(
                () -> handleAuthenticationResponse(payload, currentSession));
        WatchStyleBleFrameAssembler.INSTANCE.addListener(responseListener);
        diagnostic("Sending dkey authentication frame (" + authenticationFrame.length + " bytes)");
        sendFrame(authenticationFrame, currentSession,
                () -> diagnostic("Authentication frame written; waiting for vehicle response"),
                "Authentication frame write failed");
    }

    private void handleAuthenticationResponse(byte[] payload, int currentSession) {
        if (!isActive(currentSession)
                || BydBleCodec.parseResponseType(payload) != BydBleCodec.RESPONSE_AUTHENTICATION) {
            return;
        }

        int result = BydBleCodec.parseAuthenticationResult(payload) & 0xFF;
        unregisterResponseListener();
        if (result != 1) {
            fail("Vehicle rejected dkey authentication; result=" + result);
            return;
        }
        phase(BleAuthPhase.STEP_NEW_KEY_AUTH_OK, "Vehicle accepted dkey authentication");
        phase(BleAuthPhase.AUTH_PASS, "Korean dkey authentication complete");
    }

    private void sendFrame(
            byte[] frame,
            int expectedSession,
            Runnable onSuccess,
            String failureMessage) {
        WatchStyleDataSendRecv.INSTANCE.sendFrameData(
                frame,
                new WatchStyleDataSendRecv.OnWriteResultListener() {
                    @Override
                    public void onWriteSuccess() {
                        if (isActive(expectedSession)) onSuccess.run();
                    }

                    @Override
                    public void onWriteFailed() {
                        if (!isActive(expectedSession)) return;
                        unregisterResponseListener();
                        fail(failureMessage);
                    }
                });
    }

    private byte keyNumber(WatchBlueToothKeyStatInfo info) {
        Long value = info.getEmpowerBluetoothKeyNo();
        long number = value == null ? 0L : value;
        return (byte) Math.max(0L, Math.min(255L, number));
    }

    private boolean isActive(int expectedSession) {
        return !cancelled && sessionId.get() == expectedSession;
    }

    private void unregisterResponseListener() {
        WatchStyleBleFrameAssembler.BleFramePayloadListener listener = responseListener;
        if (listener != null) {
            WatchStyleBleFrameAssembler.INSTANCE.removeListener(listener);
            responseListener = null;
        }
    }

    private void diagnostic(String message) {
        mainHandler.post(() -> appendDiagnostic.accept(message));
    }

    private void phase(BleAuthPhase phase, String detail) {
        Log.i(TAG, "[Auth] phase=" + phase + " " + detail);
        mainHandler.post(() -> onPhase.accept(phase, detail));
    }

    private void fail(String detail) {
        phase(BleAuthPhase.FAILED, detail);
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message;
    }
}

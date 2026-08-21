package com.poorgrammera.bydblekeycontrol.blecodec;

/** Parsed result of the vehicle/app random exchange. */
public final class BleRandomExchangeResult {
    private boolean valid;
    private byte[] vehicleRandom;
    private byte crcCheckResult = (byte) 0xFF;
    private byte keyState = (byte) 0xFF;

    public boolean isValid() {
        return valid;
    }

    void setValid(boolean valid) {
        this.valid = valid;
    }

    public byte[] getVehicleRandom() {
        return vehicleRandom == null ? null : vehicleRandom.clone();
    }

    void setVehicleRandom(byte[] vehicleRandom) {
        this.vehicleRandom = vehicleRandom == null ? null : vehicleRandom.clone();
    }

    public byte getCrcCheckResult() {
        return crcCheckResult;
    }

    void setCrcCheckResult(byte crcCheckResult) {
        this.crcCheckResult = crcCheckResult;
    }

    public byte getKeyState() {
        return keyState;
    }

    void setKeyState(byte keyState) {
        this.keyState = keyState;
    }

    @Override
    public String toString() {
        return "BleRandomExchangeResult{valid=" + valid
                + ", keyState=" + (keyState & 0xFF)
                + ", crcCheckResult=" + (crcCheckResult & 0xFF) + '}';
    }
}

package com.poorgrammera.bydautolock.service;

import com.poorgrammera.bydblekeycontrol.blecodec.BydBleCodec;

/** Function identifiers and frame creation for BLE vehicle commands. */
public final class VehicleBleCommand {
    public static final int CLOSE_POWER_TRUNK = 9020;
    public static final int FLASH_LIGHTS = 9010;
    public static final int LOCK_DOORS = 9002;
    public static final int OPEN_POWER_TRUNK = 9019;
    public static final int OPERATE_TRUNK = 9015;
    public static final int CLOSE_WINDOWS = 9007;
    public static final int FIND_VEHICLE = 9005;
    public static final int STOP_CLIMATE = 9009;
    public static final int START_CLIMATE = 9003;
    public static final int UNLOCK_ALL_DOORS = 9001;
    public static final int UNLOCK_TRUNK = 9011;
    public static final int ONE_KEY_SHUTDOWN = 9008;

    private VehicleBleCommand() { }

    public static byte[] createFrame(int functionId) {
        byte controlCode = BydBleCodec.getControlCode(functionId);
        return controlCode == (byte) 0xFF ? null : BydBleCodec.createControlFrame(controlCode);
    }
}

package com.poorgrammera.bydautolock.model;

import com.google.gson.annotations.SerializedName;

/** Decrypted response from watch/control/vehicleControlResult. */
public class RemoteControlResult {
    @SerializedName("res")
    private int res;

    @SerializedName("message")
    private String message;

    public int getRes() {
        return res;
    }

    public String getMessage() {
        return message;
    }
}

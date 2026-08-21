package com.poorgrammera.bydautolock.model;

import com.google.gson.annotations.SerializedName;

/** Response to watch/control/vehicleControl after the server accepts the command. */
public class RemoteControlStartResponse {
    @SerializedName("requestSerial")
    private String requestSerial;

    public String getRequestSerial() {
        return requestSerial;
    }
}

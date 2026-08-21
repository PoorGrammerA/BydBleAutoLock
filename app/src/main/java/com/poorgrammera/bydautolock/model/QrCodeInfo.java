package com.poorgrammera.bydautolock.model;

import com.google.gson.annotations.SerializedName;

public class QrCodeInfo {
    @SerializedName("uuid")
    private String uuid;

    @SerializedName("watchImei")
    private String watchImei;

    public QrCodeInfo(String uuid, String watchImei) {
        this.uuid = uuid;
        this.watchImei = watchImei;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public String getWatchImei() {
        return watchImei;
    }

    public void setWatchImei(String watchImei) {
        this.watchImei = watchImei;
    }
}

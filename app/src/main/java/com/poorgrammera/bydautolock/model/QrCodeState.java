package com.poorgrammera.bydautolock.model;

import com.google.gson.annotations.SerializedName;

public class QrCodeState {
    @SerializedName("appChannel")
    private String appChannel;

    @SerializedName(value = "codeStatus", alternate = {"status"})
    private String codeStatus;

    @SerializedName("uuid")
    private String uuid;

    @SerializedName("watchImei")
    private String watchImei;

    public QrCodeState(String codeStatus, String uuid, String watchImei, String appChannel) {
        this.codeStatus = codeStatus;
        this.uuid = uuid;
        this.watchImei = watchImei;
        this.appChannel = appChannel;
    }

    public String getAppChannel() {
        return appChannel;
    }

    public void setAppChannel(String appChannel) {
        this.appChannel = appChannel;
    }

    public String getCodeStatus() {
        return codeStatus;
    }

    public void setCodeStatus(String codeStatus) {
        this.codeStatus = codeStatus;
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

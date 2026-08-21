package com.poorgrammera.bydautolock.model;

import com.google.gson.annotations.SerializedName;

public class TokenInfoBean {
    @SerializedName("encryToken")
    private String encryToken;

    @SerializedName("signToken")
    private String signToken;

    @SerializedName("uuid")
    private String uuid;

    @SerializedName("timeStamp")
    private String timeStamp;

    @SerializedName("acquireTokenTimestamp")
    private String acquireTokenTimestamp;

    @SerializedName("identifier")
    private String identifier;

    @SerializedName("identifierType")
    private String identifierType;

    @SerializedName("objective")
    private String objective;

    @SerializedName("passWord")
    private String passWord;

    @SerializedName("softType")
    private String softType;

    @SerializedName("appVersion")
    private String appVersion;

    @SerializedName("appChannel")
    private String appChannel;

    @SerializedName("terminalType")
    private String terminalType;

    @SerializedName("vehicleBrand")
    private String vehicleBrand;

    @SerializedName("watchImei")
    private String watchImei;

    @SerializedName("support")
    private String support;

    @SerializedName("vin")
    private String vin;

    @SerializedName("userType")
    private String userType;

    @SerializedName("userId")
    private String userId;

    @SerializedName("accountStatus")
    private int accountStatus;

    /** Server-issued remote-control credential; this is not the BYD account password. */
    private String controlPwd;

    @SerializedName("language")
    private String language;

    public String getEncryToken() {
        return encryToken;
    }

    public void setEncryToken(String encryToken) {
        this.encryToken = encryToken;
    }

    public String getSignToken() {
        return signToken;
    }

    public void setSignToken(String signToken) {
        this.signToken = signToken;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public String getTimeStamp() {
        return timeStamp;
    }

    public void setTimeStamp(String timeStamp) {
        this.timeStamp = timeStamp;
    }

    public String getAcquireTokenTimestamp() {
        return acquireTokenTimestamp;
    }

    public void setAcquireTokenTimestamp(String acquireTokenTimestamp) {
        this.acquireTokenTimestamp = acquireTokenTimestamp;
    }

    public String getIdentifier() {
        // API response uses userId as the identifier for logged-in requests
        return identifier != null ? identifier : userId;
    }

    public void setIdentifier(String identifier) {
        this.identifier = identifier;
    }

    public String getIdentifierType() {
        return identifierType;
    }

    public void setIdentifierType(String identifierType) {
        this.identifierType = identifierType;
    }

    public String getObjective() {
        return objective;
    }

    public void setObjective(String objective) {
        this.objective = objective;
    }

    public String getPassWord() {
        return passWord;
    }

    public void setPassWord(String passWord) {
        this.passWord = passWord;
    }

    public String getSoftType() {
        return softType;
    }

    public void setSoftType(String softType) {
        this.softType = softType;
    }

    public String getAppVersion() {
        return appVersion;
    }

    public void setAppVersion(String appVersion) {
        this.appVersion = appVersion;
    }

    public String getAppChannel() {
        return appChannel;
    }

    public void setAppChannel(String appChannel) {
        this.appChannel = appChannel;
    }

    public String getTerminalType() {
        return terminalType;
    }

    public void setTerminalType(String terminalType) {
        this.terminalType = terminalType;
    }

    public String getVehicleBrand() {
        return vehicleBrand;
    }

    public void setVehicleBrand(String vehicleBrand) {
        this.vehicleBrand = vehicleBrand;
    }

    public String getWatchImei() {
        return watchImei;
    }

    public void setWatchImei(String watchImei) {
        this.watchImei = watchImei;
    }

    public String getSupport() {
        return support;
    }

    public void setSupport(String support) {
        this.support = support;
    }

    public String getVin() {
        return vin;
    }

    public void setVin(String vin) {
        this.vin = vin;
    }

    public String getUserType() {
        return userType;
    }

    public void setUserType(String userType) {
        this.userType = userType;
    }

    public String getControlPwd() {
        return controlPwd;
    }

    public void setControlPwd(String controlPwd) {
        this.controlPwd = controlPwd;
    }
}

package com.poorgrammera.bydautolock.model;

import com.google.gson.annotations.SerializedName;

public class WatchBlueToothKeyStatInfo {

    @SerializedName("vin")
    private String vin;

    @SerializedName(value = "blueToothPassword", alternate = {"bluetoothPassword", "BluetoothPassword", "blue_tooth_password"})
    private String blueToothPassword;

    @SerializedName(value = "dk", alternate = {"DK", "dK", "dkey", "DKEY"})
    private String dk;

    @SerializedName(value = "bluetoothMacAddress", alternate = {"bluetoothMACAddress", "bluetooth_mac_address", "macAddress", "mac"})
    private String bluetoothMacAddress;

    @SerializedName("bluetoothActivationState")
    private Integer bluetoothActivationState;

    @SerializedName("bluetoothAvaliableState")
    private Integer bluetoothAvaliableState;

    @SerializedName("bluetoothAvaliableSynState")
    private Integer bluetoothAvaliableSynState;

    @SerializedName("bluetoothAvaliableSynTime")
    private Long bluetoothAvaliableSynTime;

    @SerializedName("bluetoothSynState")
    private Integer bluetoothSynState;

    @SerializedName("bluetoothKeySwitch")
    private Integer bluetoothKeySwitch;

    @SerializedName("authBluetoothProtocol")
    private Integer authBluetoothProtocol;

    @SerializedName("authBluetoothProtocolState")
    private Integer authBluetoothProtocolState;

    @SerializedName("authNumberKey")
    private Integer authNumberKey;

    @SerializedName("empowerBluetooth")
    private Boolean empowerBluetooth;

    @SerializedName(value = "empowerBluetoothKeyNo", alternate = {"keyNumber", "keyNo"})
    private Long empowerBluetoothKeyNo;

    @SerializedName("empowerExpireTime")
    private Long empowerExpireTime;

    @SerializedName("teleoperateDrivingConfig")
    private Integer teleoperateDrivingConfig;

    @SerializedName("ikeyBluetoothVersion")
    private String ikeyBluetoothVersion;

    @SerializedName("userinfoSycState")
    private Integer userinfoSycState;

    @SerializedName("userinfoSycTime")
    private Long userinfoSycTime;

    @SerializedName("creditEquipmentSynState")
    private Integer creditEquipmentSynState;

    @SerializedName("creditEquipmentSynTime")
    private Long creditEquipmentSynTime;

    // Getters and Setters

    public String getVin() {
        return vin;
    }

    public void setVin(String vin) {
        this.vin = vin;
    }

    public String getBlueToothPassword() {
        return blueToothPassword;
    }

    public void setBlueToothPassword(String blueToothPassword) {
        this.blueToothPassword = blueToothPassword;
    }

    public String getDk() {
        return dk;
    }

    public void setDk(String dk) {
        this.dk = dk;
    }

    public String getBluetoothMacAddress() {
        return bluetoothMacAddress;
    }

    public void setBluetoothMacAddress(String bluetoothMacAddress) {
        this.bluetoothMacAddress = bluetoothMacAddress;
    }

    public Integer getBluetoothActivationState() {
        return bluetoothActivationState;
    }

    public void setBluetoothActivationState(Integer bluetoothActivationState) {
        this.bluetoothActivationState = bluetoothActivationState;
    }

    public Integer getBluetoothAvaliableState() {
        return bluetoothAvaliableState;
    }

    public void setBluetoothAvaliableState(Integer bluetoothAvaliableState) {
        this.bluetoothAvaliableState = bluetoothAvaliableState;
    }

    public Integer getBluetoothAvaliableSynState() {
        return bluetoothAvaliableSynState;
    }

    public void setBluetoothAvaliableSynState(Integer bluetoothAvaliableSynState) {
        this.bluetoothAvaliableSynState = bluetoothAvaliableSynState;
    }

    public Long getBluetoothAvaliableSynTime() {
        return bluetoothAvaliableSynTime;
    }

    public void setBluetoothAvaliableSynTime(Long bluetoothAvaliableSynTime) {
        this.bluetoothAvaliableSynTime = bluetoothAvaliableSynTime;
    }

    public Integer getBluetoothSynState() {
        return bluetoothSynState;
    }

    public void setBluetoothSynState(Integer bluetoothSynState) {
        this.bluetoothSynState = bluetoothSynState;
    }

    public Integer getBluetoothKeySwitch() {
        return bluetoothKeySwitch;
    }

    public void setBluetoothKeySwitch(Integer bluetoothKeySwitch) {
        this.bluetoothKeySwitch = bluetoothKeySwitch;
    }

    public Integer getAuthBluetoothProtocol() {
        return authBluetoothProtocol;
    }

    public void setAuthBluetoothProtocol(Integer authBluetoothProtocol) {
        this.authBluetoothProtocol = authBluetoothProtocol;
    }

    public Integer getAuthBluetoothProtocolState() {
        return authBluetoothProtocolState;
    }

    public void setAuthBluetoothProtocolState(Integer authBluetoothProtocolState) {
        this.authBluetoothProtocolState = authBluetoothProtocolState;
    }

    public Integer getAuthNumberKey() {
        return authNumberKey;
    }

    public void setAuthNumberKey(Integer authNumberKey) {
        this.authNumberKey = authNumberKey;
    }

    public Boolean getEmpowerBluetooth() {
        return empowerBluetooth;
    }

    public void setEmpowerBluetooth(Boolean empowerBluetooth) {
        this.empowerBluetooth = empowerBluetooth;
    }

    public Long getEmpowerBluetoothKeyNo() {
        return empowerBluetoothKeyNo;
    }

    public void setEmpowerBluetoothKeyNo(Long empowerBluetoothKeyNo) {
        this.empowerBluetoothKeyNo = empowerBluetoothKeyNo;
    }

    public Long getEmpowerExpireTime() {
        return empowerExpireTime;
    }

    public void setEmpowerExpireTime(Long empowerExpireTime) {
        this.empowerExpireTime = empowerExpireTime;
    }

    public Integer getTeleoperateDrivingConfig() {
        return teleoperateDrivingConfig;
    }

    public void setTeleoperateDrivingConfig(Integer teleoperateDrivingConfig) {
        this.teleoperateDrivingConfig = teleoperateDrivingConfig;
    }

    public String getIkeyBluetoothVersion() {
        return ikeyBluetoothVersion;
    }

    public void setIkeyBluetoothVersion(String ikeyBluetoothVersion) {
        this.ikeyBluetoothVersion = ikeyBluetoothVersion;
    }

    public Integer getUserinfoSycState() {
        return userinfoSycState;
    }

    public void setUserinfoSycState(Integer userinfoSycState) {
        this.userinfoSycState = userinfoSycState;
    }

    public Long getUserinfoSycTime() {
        return userinfoSycTime;
    }

    public void setUserinfoSycTime(Long userinfoSycTime) {
        this.userinfoSycTime = userinfoSycTime;
    }

    public Integer getCreditEquipmentSynState() {
        return creditEquipmentSynState;
    }

    public void setCreditEquipmentSynState(Integer creditEquipmentSynState) {
        this.creditEquipmentSynState = creditEquipmentSynState;
    }

    public Long getCreditEquipmentSynTime() {
        return creditEquipmentSynTime;
    }

    public void setCreditEquipmentSynTime(Long creditEquipmentSynTime) {
        this.creditEquipmentSynTime = creditEquipmentSynTime;
    }
}

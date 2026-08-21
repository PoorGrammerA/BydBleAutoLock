package com.poorgrammera.bydautolock.model;

import com.google.gson.annotations.SerializedName;

/**
 * Wrapper for gain/token API response.
 * The actual response structure is:
 * {
 *   "watchTokenInfo": { "encryToken": "...", "signToken": "...", "userId": "<USER_ID>", ... },
 *   "controlPwd": "<SERVER_ISSUED_CONTROL_CREDENTIAL>"
 * }
 */
public class TokenResponse {
    @SerializedName("watchTokenInfo")
    private TokenInfoBean watchTokenInfo;

    @SerializedName("controlPwd")
    private String controlPwd;

    public TokenInfoBean getWatchTokenInfo() {
        return watchTokenInfo;
    }

    public void setWatchTokenInfo(TokenInfoBean watchTokenInfo) {
        this.watchTokenInfo = watchTokenInfo;
    }

    public String getControlPwd() {
        return controlPwd;
    }

    public void setControlPwd(String controlPwd) {
        this.controlPwd = controlPwd;
    }
}

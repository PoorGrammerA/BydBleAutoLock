package com.poorgrammera.bydautolock.service;

public enum BleAuthPhase {
    IDLE,
    RUNNING,
    STEP_RANDOM_EXCHANGE_OK,
    STEP_NEW_KEY_AUTH_OK,
    AUTH_PASS,
    FAILED
}

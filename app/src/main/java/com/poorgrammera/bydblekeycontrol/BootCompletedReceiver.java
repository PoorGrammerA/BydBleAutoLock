package com.poorgrammera.bydblekeycontrol;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.poorgrammera.bydautolock.storage.StorageManager;

/** Restarts monitoring after a completed device boot only when the encrypted BLE key is present. */
public class BootCompletedReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        StorageManager storage = new StorageManager(context);
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                && storage.hasBleKey() && storage.isServiceEnabled()) {
            VehicleAccessService.startIfEnabled(context);
        }
    }
}

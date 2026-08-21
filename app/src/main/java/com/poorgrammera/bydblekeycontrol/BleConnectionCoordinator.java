package com.poorgrammera.bydblekeycontrol;

import com.poorgrammera.bydautolock.bydapi.WatchBleKeyFlowManager;

/** Keeps a single in-process owner for the vehicle GATT connection. */
final class BleConnectionCoordinator {
    enum Owner { VEHICLE_ACCESS_SERVICE, DEVELOPER_TEST }

    private static Owner activeOwner;
    private static WatchBleKeyFlowManager activeFlow;

    private BleConnectionCoordinator() { }

    static synchronized void claim(Owner owner, WatchBleKeyFlowManager flow) {
        if (activeFlow != null && activeFlow != flow) activeFlow.cancel();
        activeOwner = owner;
        activeFlow = flow;
    }

    static synchronized void release(Owner owner, WatchBleKeyFlowManager flow) {
        if (activeOwner == owner && activeFlow == flow) {
            activeOwner = null;
            activeFlow = null;
        }
    }
}

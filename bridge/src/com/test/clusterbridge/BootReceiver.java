package com.test.clusterbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

// После загрузки ГУ поднимаем мост.
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        c.startForegroundService(new Intent(c, BridgeService.class));
    }
}

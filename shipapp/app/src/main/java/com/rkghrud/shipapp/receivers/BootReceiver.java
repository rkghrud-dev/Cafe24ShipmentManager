package com.rkghrud.shipapp.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.rkghrud.shipapp.data.AlertPrefs;
import com.rkghrud.shipapp.workers.ShipmentAlertWorker;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        // 주기 조회 알림 복구
        if (AlertPrefs.isPollingEnabled(context)) {
            ShipmentAlertWorker.schedule(context, AlertPrefs.getPollingInterval(context));
        }

        // 지정 시간 알림 복구
        if (AlertPrefs.isScheduledEnabled(context)) {
            ScheduledAlarmReceiver.scheduleNext(context);
        }
    }
}

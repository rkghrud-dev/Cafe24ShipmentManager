package com.rkghrud.shipapp.workers;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.rkghrud.shipapp.data.AlertPrefs;
import com.rkghrud.shipapp.data.LiveShipmentRepository;
import com.rkghrud.shipapp.data.ShipmentDashboardSnapshot;
import com.rkghrud.shipapp.notifications.NotificationHelper;

import java.util.concurrent.TimeUnit;

public class ShipmentAlertWorker extends Worker {
    public static final String WORK_NAME = "shipment-alert-worker";

    public ShipmentAlertWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        try {
            ShipmentDashboardSnapshot snapshot = new LiveShipmentRepository(context).fetchDashboard();
            int currentCount = snapshot.getActionCount();
            int lastCount = AlertPrefs.getLastOrderCount(context);

            if (lastCount >= 0 && currentCount > lastCount) {
                NotificationHelper.ensureChannels(context);
                NotificationHelper.showPollingNotification(context, currentCount, lastCount);
            }

            AlertPrefs.saveLastOrderCount(context, currentCount);
            NotificationHelper.saveWidgetData(context, snapshot);
            NotificationHelper.updateWidget(context);
            return Result.success();
        } catch (Exception ex) {
            return Result.retry();
        }
    }

    /** intervalMinutes: 15, 20, 30 */
    public static void schedule(Context context, int intervalMinutes) {
        int normalizedInterval = AlertPrefs.normalizePollingInterval(intervalMinutes);
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                ShipmentAlertWorker.class,
                normalizedInterval,
                TimeUnit.MINUTES
        )
                .setConstraints(constraints)
                .build();

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
        );
    }

    public static void cancel(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME);
    }
}

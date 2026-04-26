package com.rkghrud.shipapp.workers;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
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
            if (AlertPrefs.isPollingEnabled(context)) {
                scheduleNext(context, AlertPrefs.getPollingInterval(context));
            }
            return Result.success();
        } catch (Exception ex) {
            return Result.retry();
        }
    }

    public static void schedule(Context context, int intervalMinutes) {
        enqueue(context, intervalMinutes, ExistingWorkPolicy.REPLACE);
    }

    private static void scheduleNext(Context context, int intervalMinutes) {
        enqueue(context, intervalMinutes, ExistingWorkPolicy.APPEND_OR_REPLACE);
    }

    private static void enqueue(Context context, int intervalMinutes, ExistingWorkPolicy policy) {
        int normalizedInterval = AlertPrefs.normalizePollingInterval(intervalMinutes);
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(ShipmentAlertWorker.class)
                .setInitialDelay(normalizedInterval, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build();

        WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                policy,
                request
        );
    }

    public static void cancel(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME);
    }
}

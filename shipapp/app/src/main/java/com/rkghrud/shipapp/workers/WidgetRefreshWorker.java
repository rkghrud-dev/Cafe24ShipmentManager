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

import com.rkghrud.shipapp.data.LiveShipmentRepository;
import com.rkghrud.shipapp.data.ShipmentDashboardSnapshot;
import com.rkghrud.shipapp.notifications.NotificationHelper;

public class WidgetRefreshWorker extends Worker {
    private static final String WORK_NAME = "shipment-widget-refresh";

    public WidgetRefreshWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        try {
            ShipmentDashboardSnapshot snapshot = new LiveShipmentRepository(context).fetchDashboard();
            NotificationHelper.saveWidgetData(context, snapshot);
            NotificationHelper.updateWidget(context);
            return Result.success();
        } catch (Exception ex) {
            return Result.retry();
        }
    }

    public static void enqueue(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(WidgetRefreshWorker.class)
                .setConstraints(constraints)
                .build();

        WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
        );
    }
}

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

import com.rkghrud.shipapp.data.LiveShipmentRepository;
import com.rkghrud.shipapp.data.ShipmentDashboardSnapshot;
import com.rkghrud.shipapp.data.ShipmentRepository;
import com.rkghrud.shipapp.notifications.NotificationHelper;

import java.util.concurrent.TimeUnit;

public class ShipmentAlertWorker extends Worker {
    public static final String WORK_NAME = "shipment-alert-worker";

    public ShipmentAlertWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            ShipmentRepository repository = new LiveShipmentRepository(getApplicationContext());
            ShipmentDashboardSnapshot snapshot = repository.fetchDashboard();

            NotificationHelper.ensureChannel(getApplicationContext());
            if (snapshot.getActionCount() > 0) {
                NotificationHelper.showSummaryNotification(getApplicationContext(), snapshot.getActionCount());
            }
            return Result.success();
        } catch (Exception ex) {
            return Result.retry();
        }
    }

    public static void schedule(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                ShipmentAlertWorker.class,
                15,
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
package com.rkghrud.shipapp.receivers;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.rkghrud.shipapp.data.AlertPrefs;
import com.rkghrud.shipapp.data.LiveShipmentRepository;
import com.rkghrud.shipapp.data.ShipmentDashboardSnapshot;
import com.rkghrud.shipapp.notifications.NotificationHelper;

import java.util.Calendar;

public class ScheduledAlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Context appContext = context.getApplicationContext();
        int days = AlertPrefs.getScheduledDays(appContext);

        if (!AlertPrefs.isScheduledEnabled(appContext) || !AlertPrefs.hasScheduledDays(days)) {
            cancel(appContext);
            return;
        }

        if (!isTodayEnabled(days)) {
            scheduleNext(appContext);
            return;
        }

        PendingResult pendingResult = goAsync();
        new Thread(() -> {
            try {
                ShipmentDashboardSnapshot snapshot = new LiveShipmentRepository(appContext).fetchDashboard();
                NotificationHelper.ensureChannels(appContext);
                NotificationHelper.showScheduledNotification(appContext, snapshot.getActionCount());
                NotificationHelper.saveWidgetData(appContext, snapshot);
                NotificationHelper.updateWidget(appContext);
            } catch (Exception ignored) {
            } finally {
                try {
                    scheduleNext(appContext);
                } finally {
                    pendingResult.finish();
                }
            }
        }, "shipapp-scheduled-alert").start();
    }

    public static void scheduleNext(Context context) {
        if (!AlertPrefs.isScheduledEnabled(context)) {
            cancel(context);
            return;
        }

        int days = AlertPrefs.getScheduledDays(context);
        if (!AlertPrefs.hasScheduledDays(days)) {
            cancel(context);
            return;
        }

        Calendar nextTrigger = findNextTrigger(days,
                AlertPrefs.getScheduledHour(context),
                AlertPrefs.getScheduledMinute(context));

        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }

        alarmManager.cancel(buildPendingIntent(context));
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTrigger.getTimeInMillis(), buildPendingIntent(context));
    }

    public static void cancel(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) {
            alarmManager.cancel(buildPendingIntent(context));
        }
    }

    private static Calendar findNextTrigger(int days, int hour, int minute) {
        Calendar now = Calendar.getInstance();
        for (int offset = 0; offset <= 7; offset++) {
            Calendar candidate = (Calendar) now.clone();
            candidate.set(Calendar.HOUR_OF_DAY, hour);
            candidate.set(Calendar.MINUTE, minute);
            candidate.set(Calendar.SECOND, 0);
            candidate.set(Calendar.MILLISECOND, 0);
            candidate.add(Calendar.DAY_OF_YEAR, offset);

            if (!isDayEnabled(candidate, days)) {
                continue;
            }
            if (candidate.getTimeInMillis() <= now.getTimeInMillis()) {
                continue;
            }
            return candidate;
        }

        Calendar fallback = (Calendar) now.clone();
        fallback.add(Calendar.DAY_OF_YEAR, 1);
        fallback.set(Calendar.HOUR_OF_DAY, hour);
        fallback.set(Calendar.MINUTE, minute);
        fallback.set(Calendar.SECOND, 0);
        fallback.set(Calendar.MILLISECOND, 0);
        return fallback;
    }

    private static PendingIntent buildPendingIntent(Context context) {
        Intent intent = new Intent(context, ScheduledAlarmReceiver.class);
        return PendingIntent.getBroadcast(context, 2001, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static boolean isTodayEnabled(int days) {
        return isDayEnabled(Calendar.getInstance(), days);
    }

    /** bit0=월, bit1=화, ..., bit6=일 */
    private static boolean isDayEnabled(Calendar calendar, int days) {
        int dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK); // 1=일, 2=월...7=토
        int bit = (dayOfWeek == Calendar.SUNDAY) ? 6 : (dayOfWeek - Calendar.MONDAY);
        return (days & (1 << bit)) != 0;
    }
}

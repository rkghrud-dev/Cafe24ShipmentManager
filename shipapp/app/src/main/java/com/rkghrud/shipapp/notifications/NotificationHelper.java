package com.rkghrud.shipapp.notifications;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.rkghrud.shipapp.MainActivity;
import com.rkghrud.shipapp.data.AlertPrefs;
import com.rkghrud.shipapp.data.ShipmentDashboardSnapshot;
import com.rkghrud.shipapp.widget.ShipmentWidget;

public final class NotificationHelper {
    public static final String CHANNEL_POLLING_VIBRATE = "ship_polling_vibrate";
    public static final String CHANNEL_POLLING_SILENT = "ship_polling_silent";
    public static final String CHANNEL_SCHEDULED_VIBRATE = "ship_scheduled_vibrate";
    public static final String CHANNEL_SCHEDULED_SILENT = "ship_scheduled_silent";

    private static final int ID_TEST = 1001;
    private static final int ID_POLLING = 1002;
    private static final int ID_SCHEDULED = 1003;

    private static final long[] POLLING_VIBRATION = {0, 300, 150, 300};
    private static final long[] SCHEDULED_VIBRATION = {0, 400, 200, 400};

    // 위젯용 SharedPreferences 키
    public static final String PREFS_WIDGET = "shipapp_widget_prefs";
    public static final String KEY_WIDGET_TOTAL = "widget_total";
    public static final String KEY_WIDGET_HOME = "widget_home";
    public static final String KEY_WIDGET_PREPARE = "widget_prepare";
    public static final String KEY_WIDGET_COUPANG = "widget_coupang";
    public static final String KEY_WIDGET_TIME = "widget_updated_at";

    private NotificationHelper() {
    }

    public static void ensureChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }

        createChannel(manager, CHANNEL_POLLING_VIBRATE, "주기 조회 알림(진동)", "설정 주기마다 신규 주문을 조회해 알림", NotificationManager.IMPORTANCE_DEFAULT, true, POLLING_VIBRATION);
        createChannel(manager, CHANNEL_POLLING_SILENT, "주기 조회 알림(무진동)", "설정 주기마다 신규 주문을 조회해 알림", NotificationManager.IMPORTANCE_DEFAULT, false, POLLING_VIBRATION);
        createChannel(manager, CHANNEL_SCHEDULED_VIBRATE, "지정 시간 알림(진동)", "지정한 시간·요일에 주문 현황을 알림", NotificationManager.IMPORTANCE_HIGH, true, SCHEDULED_VIBRATION);
        createChannel(manager, CHANNEL_SCHEDULED_SILENT, "지정 시간 알림(무진동)", "지정한 시간·요일에 주문 현황을 알림", NotificationManager.IMPORTANCE_HIGH, false, SCHEDULED_VIBRATION);
    }

    public static void showTestNotification(Context context) {
        ensureChannels(context);
        boolean vibrate = AlertPrefs.isVibrateEnabled(context);
        notify(context, ID_TEST, resolvePollingChannel(vibrate), "ShipApp 알림 테스트", "현재 알림 설정으로 테스트했습니다.", NotificationCompat.PRIORITY_DEFAULT, vibrate, POLLING_VIBRATION);
    }

    public static void showPollingNotification(Context context, int current, int last) {
        boolean vibrate = AlertPrefs.isVibrateEnabled(context);
        int addedCount = Math.max(current - Math.max(last, 0), 0);
        String title = addedCount > 0 ? "신규 주문 " + addedCount + "건 추가" : "출고 확인 필요";
        String body = "현재 처리 대기 " + current + "건";
        notify(context, ID_POLLING, resolvePollingChannel(vibrate), title, body, NotificationCompat.PRIORITY_DEFAULT, vibrate, POLLING_VIBRATION);
    }

    public static void showScheduledNotification(Context context, int count) {
        boolean vibrate = AlertPrefs.isVibrateEnabled(context);
        String title = "출고 현황 알림";
        String body = count > 0 ? "처리 대기 " + count + "건 있습니다." : "현재 처리 대기 주문 없음";
        notify(context, ID_SCHEDULED, resolveScheduledChannel(vibrate), title, body, NotificationCompat.PRIORITY_HIGH, vibrate, SCHEDULED_VIBRATION);
    }

    private static void createChannel(NotificationManager manager, String channelId, String name, String description,
                                      int importance, boolean vibrate, long[] vibrationPattern) {
        NotificationChannel channel = new NotificationChannel(channelId, name, importance);
        channel.setDescription(description);
        channel.enableVibration(vibrate);
        channel.setVibrationPattern(vibrate ? vibrationPattern : new long[0]);
        manager.createNotificationChannel(channel);
    }

    private static String resolvePollingChannel(boolean vibrate) {
        return vibrate ? CHANNEL_POLLING_VIBRATE : CHANNEL_POLLING_SILENT;
    }

    private static String resolveScheduledChannel(boolean vibrate) {
        return vibrate ? CHANNEL_SCHEDULED_VIBRATE : CHANNEL_SCHEDULED_SILENT;
    }

    private static void notify(Context context, int id, String channelId, String title, String body,
                               int priority, boolean vibrate, long[] vibrationPattern) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(pendingIntent)
                .setPriority(priority)
                .setAutoCancel(true);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && vibrate) {
            builder.setVibrate(vibrationPattern);
        }

        NotificationManagerCompat.from(context).notify(id, builder.build());
    }

    public static void saveWidgetData(Context context, ShipmentDashboardSnapshot snapshot) {
        int home = 0;
        int prepare = 0;
        int coupang = 0;
        for (var shipment : snapshot.getShipments()) {
            String label = shipment.getMarketLabel();
            if (label != null && label.contains("홈런")) {
                home++;
            } else if (label != null && label.contains("준비")) {
                prepare++;
            } else if (label != null && label.contains("쿠팡")) {
                coupang++;
            }
        }

        String time = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA)
                .format(new java.util.Date());

        context.getSharedPreferences(PREFS_WIDGET, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_WIDGET_TOTAL, snapshot.getActionCount())
                .putInt(KEY_WIDGET_HOME, home)
                .putInt(KEY_WIDGET_PREPARE, prepare)
                .putInt(KEY_WIDGET_COUPANG, coupang)
                .putString(KEY_WIDGET_TIME, time)
                .apply();
    }

    public static void updateWidget(Context context) {
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            int[] ids = manager.getAppWidgetIds(new ComponentName(context, ShipmentWidget.class));
            if (ids.length > 0) {
                Intent intent = new Intent(context, ShipmentWidget.class);
                intent.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
                intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids);
                context.sendBroadcast(intent);
            }
        } catch (Exception ignored) {
        }
    }
}


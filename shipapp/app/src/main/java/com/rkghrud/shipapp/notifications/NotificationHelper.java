package com.rkghrud.shipapp.notifications;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

public final class NotificationHelper {
    public static final String CHANNEL_ID = "shipment_alerts";
    private static final int TEST_NOTIFICATION_ID = 1001;
    private static final int SUMMARY_NOTIFICATION_ID = 1002;

    private NotificationHelper() {
    }

    public static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "출고 알림",
                NotificationManager.IMPORTANCE_DEFAULT
        );
        channel.setDescription("출고 반영 실패나 확인 필요 주문을 알려줍니다.");

        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    public static void showTestNotification(Context context) {
        showNotification(context, TEST_NOTIFICATION_ID, "ShipApp 테스트 알림", "백그라운드 알림 연결이 정상입니다.");
    }

    public static void showSummaryNotification(Context context, int actionCount) {
        showNotification(context, SUMMARY_NOTIFICATION_ID, "출고 확인 필요", "확인 필요한 주문이 " + actionCount + "건 있습니다.");
    }

    private static void showNotification(Context context, int notificationId, String title, String body) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true);

        NotificationManagerCompat.from(context).notify(notificationId, builder.build());
    }
}

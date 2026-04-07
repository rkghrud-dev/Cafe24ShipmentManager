package com.rkghrud.shipapp.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

import com.rkghrud.shipapp.MainActivity;
import com.rkghrud.shipapp.R;
import com.rkghrud.shipapp.notifications.NotificationHelper;

public class ShipmentWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        SharedPreferences prefs = context.getSharedPreferences(
                NotificationHelper.PREFS_WIDGET, Context.MODE_PRIVATE);

        int total   = prefs.getInt(NotificationHelper.KEY_WIDGET_TOTAL,   -1);
        int home    = prefs.getInt(NotificationHelper.KEY_WIDGET_HOME,     0);
        int prepare = prefs.getInt(NotificationHelper.KEY_WIDGET_PREPARE,  0);
        int coupang = prefs.getInt(NotificationHelper.KEY_WIDGET_COUPANG,  0);
        String time = prefs.getString(NotificationHelper.KEY_WIDGET_TIME,  "--:--");

        for (int id : ids) {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_shipment);

            views.setTextViewText(R.id.widgetTotalCount,   total < 0 ? "-- 건" : total + " 건");
            views.setTextViewText(R.id.widgetHomeCount,    total < 0 ? "--" : String.valueOf(home));
            views.setTextViewText(R.id.widgetPrepareCount, total < 0 ? "--" : String.valueOf(prepare));
            views.setTextViewText(R.id.widgetCoupangCount, total < 0 ? "--" : String.valueOf(coupang));
            views.setTextViewText(R.id.widgetUpdatedAt,    time);

            // 탭하면 MainActivity 오픈
            Intent intent = new Intent(context, MainActivity.class);
            PendingIntent pi = PendingIntent.getActivity(context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            views.setOnClickPendingIntent(R.id.widgetTotalCount, pi);

            manager.updateAppWidget(id, views);
        }
    }
}

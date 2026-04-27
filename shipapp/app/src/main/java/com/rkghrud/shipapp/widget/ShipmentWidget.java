package com.rkghrud.shipapp.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.RemoteViews;

import com.rkghrud.shipapp.MainActivity;
import com.rkghrud.shipapp.R;
import com.rkghrud.shipapp.notifications.NotificationHelper;
import com.rkghrud.shipapp.workers.WidgetRefreshWorker;

public class ShipmentWidget extends AppWidgetProvider {
    private static final String ACTION_WIDGET_REFRESH = "com.rkghrud.shipapp.action.WIDGET_REFRESH";

    protected int layoutId() {
        return R.layout.widget_shipment;
    }

    protected boolean showsTotalCount() {
        return true;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && ACTION_WIDGET_REFRESH.equals(intent.getAction())) {
            WidgetRefreshWorker.enqueue(context.getApplicationContext());
            return;
        }
        super.onReceive(context, intent);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        SharedPreferences prefs = context.getSharedPreferences(
                NotificationHelper.PREFS_WIDGET, Context.MODE_PRIVATE);

        int total = prefs.getInt(NotificationHelper.KEY_WIDGET_TOTAL, -1);
        String time = prefs.getString(NotificationHelper.KEY_WIDGET_TIME, "--:--");

        WidgetMarket[] markets = new WidgetMarket[] {
                readMarket(prefs, 1, "홈런"),
                readMarket(prefs, 2, "준비"),
                readMarket(prefs, 3, "쿠팡"),
                readMarket(prefs, 4, "전체")
        };

        for (int id : ids) {
            RemoteViews views = new RemoteViews(context.getPackageName(), layoutId());
            WidgetSettingsStore.WidgetConfig config = WidgetSettingsStore.load(context, id);
            int marketCount = WidgetSettingsStore.clampMarketCount(config.marketCount);
            WidgetMarket[] displayMarkets = new WidgetMarket[] {
                    resolveConfiguredMarket(prefs, config.marketAt(0, markets[0].key), markets[0]),
                    resolveConfiguredMarket(prefs, config.marketAt(1, markets[1].key), markets[1]),
                    resolveConfiguredMarket(prefs, config.marketAt(2, markets[2].key), markets[2]),
                    resolveConfiguredMarket(prefs, config.marketAt(3, markets[3].key), markets[3])
            };
            views.setFloat(R.id.widgetContainer, "setAlpha", config.opacity / 100f);

            if (showsTotalCount()) {
                views.setTextViewText(R.id.widgetTotalCount, total < 0 ? "--" : String.valueOf(total));
                views.setTextViewText(R.id.widgetUpdatedAt, time);
            }

            bindMarket(context, views, id, 0, marketCount >= 1, R.id.widgetMarket1Tile, R.id.widgetMarket1Name, R.id.widgetMarket1Count, displayMarkets[0]);
            bindMarket(context, views, id, 1, marketCount >= 2, R.id.widgetMarket2Tile, R.id.widgetMarket2Name, R.id.widgetMarket2Count, displayMarkets[1]);
            bindMarket(context, views, id, 2, marketCount >= 3, R.id.widgetMarket3Tile, R.id.widgetMarket3Name, R.id.widgetMarket3Count, displayMarkets[2]);
            bindMarket(context, views, id, 3, marketCount >= 4, R.id.widgetMarket4Tile, R.id.widgetMarket4Name, R.id.widgetMarket4Count, displayMarkets[3]);

            views.setOnClickPendingIntent(R.id.widgetContainer, openAppIntent(context, id, 8, MainActivity.MARKET_FILTER_ALL, false));
            views.setOnClickPendingIntent(R.id.widgetRefreshButton, refreshWidgetIntent(context, id));

            manager.updateAppWidget(id, views);
        }
    }

    private static WidgetMarket readMarket(SharedPreferences prefs, int index, String fallbackName) {
        String nameKey;
        String countKey;
        String filterKey;
        if (index == 1) {
            nameKey = NotificationHelper.KEY_WIDGET_MARKET1_NAME;
            countKey = NotificationHelper.KEY_WIDGET_MARKET1_COUNT;
            filterKey = NotificationHelper.KEY_WIDGET_MARKET1_KEY;
        } else if (index == 2) {
            nameKey = NotificationHelper.KEY_WIDGET_MARKET2_NAME;
            countKey = NotificationHelper.KEY_WIDGET_MARKET2_COUNT;
            filterKey = NotificationHelper.KEY_WIDGET_MARKET2_KEY;
        } else if (index == 3) {
            nameKey = NotificationHelper.KEY_WIDGET_MARKET3_NAME;
            countKey = NotificationHelper.KEY_WIDGET_MARKET3_COUNT;
            filterKey = NotificationHelper.KEY_WIDGET_MARKET3_KEY;
        } else {
            nameKey = NotificationHelper.KEY_WIDGET_MARKET4_NAME;
            countKey = NotificationHelper.KEY_WIDGET_MARKET4_COUNT;
            filterKey = NotificationHelper.KEY_WIDGET_MARKET4_KEY;
        }
        return new WidgetMarket(
                prefs.getString(nameKey, fallbackName),
                prefs.getInt(countKey, -1),
                prefs.getString(filterKey, ""));
    }

    private static WidgetMarket resolveConfiguredMarket(SharedPreferences prefs, String marketKey, WidgetMarket fallback) {
        if (marketKey == null || marketKey.isEmpty()) {
            return fallback;
        }
        if (MainActivity.MARKET_FILTER_ALL.equals(marketKey)) {
            return new WidgetMarket("전체", prefs.getInt(NotificationHelper.KEY_WIDGET_TOTAL,
                    fallback == null ? -1 : fallback.count), marketKey);
        }
        String fallbackName = fallback == null ? "마켓" : fallback.name;
        String name = prefs.getString(NotificationHelper.widgetMarketNameKey(marketKey), fallbackName);
        int count = prefs.getInt(NotificationHelper.widgetMarketCountKey(marketKey), fallback == null ? -1 : fallback.count);
        return new WidgetMarket(name, count, marketKey);
    }

    private static void bindMarket(Context context, RemoteViews views, int widgetId, int index, boolean visible,
                                   int tileId, int nameId, int countId, WidgetMarket market) {
        views.setViewVisibility(tileId, visible ? View.VISIBLE : View.GONE);
        if (!visible) {
            return;
        }
        views.setTextViewText(nameId, market.name);
        views.setTextViewText(countId, market.count < 0 ? "--" : String.valueOf(market.count));
        views.setOnClickPendingIntent(tileId, openAppIntent(context, widgetId, index, market.key, false));
    }

    private static PendingIntent openAppIntent(Context context, int widgetId, int actionIndex,
                                               String marketKey, boolean refresh) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (marketKey != null && !marketKey.isEmpty()) {
            intent.putExtra(MainActivity.EXTRA_MARKET_FILTER, marketKey);
        }
        intent.putExtra(MainActivity.EXTRA_REFRESH_ON_OPEN, refresh);
        int requestCode = widgetId * 16 + actionIndex;
        return PendingIntent.getActivity(context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent refreshWidgetIntent(Context context, int widgetId) {
        Intent intent = new Intent(context, ShipmentWidget.class);
        intent.setAction(ACTION_WIDGET_REFRESH);
        int requestCode = widgetId * 16 + 9;
        return PendingIntent.getBroadcast(context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static final class WidgetMarket {
        final String name;
        final int count;
        final String key;

        WidgetMarket(String name, int count, String key) {
            this.name = name == null || name.trim().isEmpty() ? "마켓" : name;
            this.count = count;
            this.key = key == null ? "" : key;
        }
    }
}

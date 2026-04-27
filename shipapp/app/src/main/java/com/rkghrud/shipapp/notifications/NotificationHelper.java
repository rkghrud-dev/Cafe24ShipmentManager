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

import com.rkghrud.shipapp.FeatureFlags;
import com.rkghrud.shipapp.MainActivity;
import com.rkghrud.shipapp.data.AlertPrefs;
import com.rkghrud.shipapp.data.Cafe24MarketConfig;
import com.rkghrud.shipapp.data.CredentialStore;
import com.rkghrud.shipapp.data.DispatchOrder;
import com.rkghrud.shipapp.data.ShipmentDashboardSnapshot;
import com.rkghrud.shipapp.widget.ShipmentCompactWidget;
import com.rkghrud.shipapp.widget.ShipmentWidget;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    public static final String KEY_WIDGET_MARKET1_NAME = "widget_market1_name";
    public static final String KEY_WIDGET_MARKET1_COUNT = "widget_market1_count";
    public static final String KEY_WIDGET_MARKET1_KEY = "widget_market1_key";
    public static final String KEY_WIDGET_MARKET2_NAME = "widget_market2_name";
    public static final String KEY_WIDGET_MARKET2_COUNT = "widget_market2_count";
    public static final String KEY_WIDGET_MARKET2_KEY = "widget_market2_key";
    public static final String KEY_WIDGET_MARKET3_NAME = "widget_market3_name";
    public static final String KEY_WIDGET_MARKET3_COUNT = "widget_market3_count";
    public static final String KEY_WIDGET_MARKET3_KEY = "widget_market3_key";
    public static final String KEY_WIDGET_MARKET4_NAME = "widget_market4_name";
    public static final String KEY_WIDGET_MARKET4_COUNT = "widget_market4_count";
    public static final String KEY_WIDGET_MARKET4_KEY = "widget_market4_key";

    public static String widgetMarketNameKey(String marketKey) {
        return "widget_market_name_" + normalizeWidgetMarketKey(marketKey);
    }

    public static String widgetMarketCountKey(String marketKey) {
        return "widget_market_count_" + normalizeWidgetMarketKey(marketKey);
    }

    private static String normalizeWidgetMarketKey(String marketKey) {
        String safe = marketKey == null ? "" : marketKey.trim();
        if (safe.isEmpty()) {
            return "empty";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < safe.length(); i++) {
            char c = safe.charAt(i);
            if ((c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '_') {
                builder.append(c);
            } else {
                builder.append('_');
            }
        }
        return builder.toString();
    }

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
        LinkedHashMap<String, WidgetMarketCount> markets = buildWidgetMarkets(context);

        for (var shipment : snapshot.getShipments()) {
            String label = shipment.getMarketLabel();
            if (label != null && label.contains("홈런")) {
                home++;
            } else if (label != null && label.contains("준비")) {
                prepare++;
            } else if (label != null && label.contains("쿠팡")) {
                coupang++;
            }
            WidgetMarketCount market = resolveWidgetMarket(markets, label);
            if (market != null) {
                market.count++;
            }
        }

        String time = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA)
                .format(new java.util.Date());
        WidgetMarketCount[] top = topWidgetMarkets(markets);

        writeWidgetPrefs(context, snapshot.getActionCount(), home, prepare, coupang, time, top, markets);
    }

    public static void saveWidgetDataFromDispatchOrders(Context context, List<DispatchOrder> orders, int totalCount) {
        int home = 0;
        int prepare = 0;
        int coupang = 0;
        LinkedHashMap<String, WidgetMarketCount> markets = buildWidgetMarkets(context);

        for (DispatchOrder order : orders) {
            String label = order.marketLabel;
            if (label != null && label.contains("홈런")) {
                home++;
            } else if (label != null && label.contains("준비")) {
                prepare++;
            } else if (label != null && label.contains("쿠팡")) {
                coupang++;
            }
            WidgetMarketCount market = resolveWidgetMarket(markets, label);
            if (market != null) {
                market.count++;
            }
        }

        String time = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA)
                .format(new java.util.Date());
        WidgetMarketCount[] top = topWidgetMarkets(markets);

        writeWidgetPrefs(context, totalCount, home, prepare, coupang, time, top, markets);
    }

    private static void writeWidgetPrefs(Context context, int total, int home, int prepare, int coupang,
                                         String time, WidgetMarketCount[] top,
                                         LinkedHashMap<String, WidgetMarketCount> markets) {
        var editor = context.getSharedPreferences(PREFS_WIDGET, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_WIDGET_TOTAL, total)
                .putInt(KEY_WIDGET_HOME, home)
                .putInt(KEY_WIDGET_PREPARE, prepare)
                .putInt(KEY_WIDGET_COUPANG, coupang)
                .putString(KEY_WIDGET_TIME, time)
                .putString(KEY_WIDGET_MARKET1_NAME, top[0].name)
                .putInt(KEY_WIDGET_MARKET1_COUNT, top[0].count)
                .putString(KEY_WIDGET_MARKET1_KEY, top[0].key)
                .putString(KEY_WIDGET_MARKET2_NAME, top[1].name)
                .putInt(KEY_WIDGET_MARKET2_COUNT, top[1].count)
                .putString(KEY_WIDGET_MARKET2_KEY, top[1].key)
                .putString(KEY_WIDGET_MARKET3_NAME, top[2].name)
                .putInt(KEY_WIDGET_MARKET3_COUNT, top[2].count)
                .putString(KEY_WIDGET_MARKET3_KEY, top[2].key)
                .putString(KEY_WIDGET_MARKET4_NAME, top[3].name)
                .putInt(KEY_WIDGET_MARKET4_COUNT, countForWidgetMarket(top[3], total))
                .putString(KEY_WIDGET_MARKET4_KEY, top[3].key)
                .putString(widgetMarketNameKey(MainActivity.MARKET_FILTER_ALL), "전체")
                .putInt(widgetMarketCountKey(MainActivity.MARKET_FILTER_ALL), total);
        for (WidgetMarketCount market : markets.values()) {
            editor.putString(widgetMarketNameKey(market.key), market.name)
                    .putInt(widgetMarketCountKey(market.key), market.count);
        }
        editor.apply();
    }

    private static LinkedHashMap<String, WidgetMarketCount> buildWidgetMarkets(Context context) {
        LinkedHashMap<String, WidgetMarketCount> markets = new LinkedHashMap<>();
        CredentialStore store = new CredentialStore(context);
        for (Cafe24MarketConfig config : store.getActiveCafe24Markets()) {
            markets.put(config.key, new WidgetMarketCount(shortMarketName(config.displayName), config.key));
        }
        if (FeatureFlags.ENABLE_COUPANG && store.getCoupangCredentials().isComplete()) {
            markets.put("coupang", new WidgetMarketCount("쿠팡", "coupang"));
        }
        addFallbackMarket(markets, "홈런", CredentialStore.SLOT_CAFE24_HOME);
        addFallbackMarket(markets, "준비", CredentialStore.SLOT_CAFE24_PREPARE);
        addFallbackMarket(markets, "쿠팡", "coupang");
        return markets;
    }

    private static void addFallbackMarket(LinkedHashMap<String, WidgetMarketCount> markets, String name, String key) {
        if (markets.size() < 4 && !markets.containsKey(key)) {
            markets.put(key, new WidgetMarketCount(name, key));
        }
    }

    private static WidgetMarketCount resolveWidgetMarket(LinkedHashMap<String, WidgetMarketCount> markets, String label) {
        String safeLabel = label == null ? "" : label;
        for (WidgetMarketCount market : markets.values()) {
            if (!market.name.isEmpty() && safeLabel.contains(market.name)) {
                return market;
            }
            if (CredentialStore.SLOT_CAFE24_HOME.equals(market.key) && safeLabel.contains("홈런")) {
                return market;
            }
            if (CredentialStore.SLOT_CAFE24_PREPARE.equals(market.key) && safeLabel.contains("준비")) {
                return market;
            }
            if ("coupang".equals(market.key) && safeLabel.contains("쿠팡")) {
                return market;
            }
        }
        return null;
    }

    private static WidgetMarketCount[] topWidgetMarkets(LinkedHashMap<String, WidgetMarketCount> markets) {
        WidgetMarketCount[] result = new WidgetMarketCount[] {
                new WidgetMarketCount("홈런", CredentialStore.SLOT_CAFE24_HOME),
                new WidgetMarketCount("준비", CredentialStore.SLOT_CAFE24_PREPARE),
                new WidgetMarketCount("쿠팡", "coupang"),
                new WidgetMarketCount("전체", MainActivity.MARKET_FILTER_ALL)
        };
        int index = 0;
        for (Map.Entry<String, WidgetMarketCount> entry : markets.entrySet()) {
            if (index >= result.length) {
                break;
            }
            result[index++] = entry.getValue();
        }
        return result;
    }

    private static int countForWidgetMarket(WidgetMarketCount market, int total) {
        if (market != null && MainActivity.MARKET_FILTER_ALL.equals(market.key)) {
            return total;
        }
        return market == null ? 0 : market.count;
    }

    private static String shortMarketName(String name) {
        String safe = name == null ? "" : name.trim();
        if (safe.endsWith("마켓")) {
            return safe.substring(0, safe.length() - 2);
        }
        return safe.isEmpty() ? "마켓" : safe;
    }

    private static final class WidgetMarketCount {
        final String name;
        final String key;
        int count;

        WidgetMarketCount(String name, String key) {
            this.name = name;
            this.key = key;
        }
    }

    public static void updateWidget(Context context) {
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            updateWidgetProvider(context, manager, ShipmentWidget.class);
            updateWidgetProvider(context, manager, ShipmentCompactWidget.class);
        } catch (Exception ignored) {
        }
    }

    private static void updateWidgetProvider(Context context, AppWidgetManager manager, Class<?> providerClass) {
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, providerClass));
        if (ids.length == 0) {
            return;
        }
        Intent intent = new Intent(context, providerClass);
        intent.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids);
        context.sendBroadcast(intent);
    }
}


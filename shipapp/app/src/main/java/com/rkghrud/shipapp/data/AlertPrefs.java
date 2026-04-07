package com.rkghrud.shipapp.data;

import android.content.Context;
import android.content.SharedPreferences;

public final class AlertPrefs {
    private static final String PREFS = "shipapp_alert_prefs";
    private static final int DEFAULT_POLLING_INTERVAL = 15;
    private static final int DEFAULT_SCHEDULED_DAYS = 0b0011111; // 월-금

    // 주기 조회 알림
    public static final String KEY_POLLING_ENABLED = "polling_enabled";
    public static final String KEY_POLLING_INTERVAL = "polling_interval";   // 분: 15, 20, 30

    // 지정 시간 알림
    public static final String KEY_SCHEDULED_ENABLED = "scheduled_enabled";
    public static final String KEY_SCHEDULED_HOUR = "scheduled_hour";       // 0-23
    public static final String KEY_SCHEDULED_MINUTE = "scheduled_minute";   // 0-59
    public static final String KEY_SCHEDULED_DAYS = "scheduled_days";       // bitmask bit0=월..bit6=일

    // 진동
    public static final String KEY_VIBRATE = "vibrate_enabled";

    // 신규 주문 비교용 마지막 카운트
    public static final String KEY_LAST_ORDER_COUNT = "last_order_count";

    private AlertPrefs() {
    }

    public static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isPollingEnabled(Context context) {
        return prefs(context).getBoolean(KEY_POLLING_ENABLED, false);
    }

    public static int getPollingInterval(Context context) {
        return normalizePollingInterval(prefs(context).getInt(KEY_POLLING_INTERVAL, DEFAULT_POLLING_INTERVAL));
    }

    public static int normalizePollingInterval(int intervalMinutes) {
        if (intervalMinutes >= 30) {
            return 30;
        }
        if (intervalMinutes >= 20) {
            return 20;
        }
        return DEFAULT_POLLING_INTERVAL;
    }

    public static boolean isScheduledEnabled(Context context) {
        return prefs(context).getBoolean(KEY_SCHEDULED_ENABLED, false);
    }

    public static int getScheduledHour(Context context) {
        return prefs(context).getInt(KEY_SCHEDULED_HOUR, 9);
    }

    public static int getScheduledMinute(Context context) {
        return prefs(context).getInt(KEY_SCHEDULED_MINUTE, 0);
    }

    /** bitmask: bit0=월, bit1=화, ..., bit6=일 */
    public static int getScheduledDays(Context context) {
        return prefs(context).getInt(KEY_SCHEDULED_DAYS, DEFAULT_SCHEDULED_DAYS);
    }

    public static boolean hasScheduledDays(Context context) {
        return hasScheduledDays(getScheduledDays(context));
    }

    public static boolean hasScheduledDays(int days) {
        return days != 0;
    }

    public static boolean isVibrateEnabled(Context context) {
        return prefs(context).getBoolean(KEY_VIBRATE, true);
    }

    public static int getLastOrderCount(Context context) {
        return prefs(context).getInt(KEY_LAST_ORDER_COUNT, -1);
    }

    public static void saveLastOrderCount(Context context, int count) {
        prefs(context).edit().putInt(KEY_LAST_ORDER_COUNT, count).apply();
    }
}

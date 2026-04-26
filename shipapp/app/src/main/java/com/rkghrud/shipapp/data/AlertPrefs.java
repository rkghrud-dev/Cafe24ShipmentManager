package com.rkghrud.shipapp.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.LinkedHashSet;
import java.util.Set;

public final class AlertPrefs {
    private static final String PREFS = "shipapp_alert_prefs";
    private static final int DEFAULT_POLLING_INTERVAL = 15;
    public static final int MIN_POLLING_INTERVAL = 1;
    public static final int MAX_POLLING_INTERVAL = 1440;
    private static final int DEFAULT_SCHEDULED_DAYS = 0b0011111; // 월-금
    private static final int MAX_KNOWN_ORDER_KEYS = 2000;

    public static final String KEY_POLLING_ENABLED = "polling_enabled";
    public static final String KEY_POLLING_INTERVAL = "polling_interval";
    public static final String KEY_SCHEDULED_ENABLED = "scheduled_enabled";
    public static final String KEY_SCHEDULED_HOUR = "scheduled_hour";
    public static final String KEY_SCHEDULED_MINUTE = "scheduled_minute";
    public static final String KEY_SCHEDULED_DAYS = "scheduled_days";
    public static final String KEY_VIBRATE = "vibrate_enabled";
    public static final String KEY_DARK_MODE = "dark_mode_enabled";
    public static final String KEY_LAST_ORDER_COUNT = "last_order_count";
    public static final String KEY_KNOWN_ORDER_KEYS = "known_order_keys";

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
        if (intervalMinutes < MIN_POLLING_INTERVAL) {
            return DEFAULT_POLLING_INTERVAL;
        }
        if (intervalMinutes > MAX_POLLING_INTERVAL) {
            return MAX_POLLING_INTERVAL;
        }
        return intervalMinutes;
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

    public static boolean isDarkModeEnabled(Context context) {
        return prefs(context).getBoolean(KEY_DARK_MODE, false);
    }

    public static int getLastOrderCount(Context context) {
        return prefs(context).getInt(KEY_LAST_ORDER_COUNT, -1);
    }

    public static void saveLastOrderCount(Context context, int count) {
        prefs(context).edit().putInt(KEY_LAST_ORDER_COUNT, count).apply();
    }

    public static Set<String> getKnownOrderKeys(Context context) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        String raw = prefs(context).getString(KEY_KNOWN_ORDER_KEYS, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                String value = array.optString(i, "").trim();
                if (!value.isEmpty()) {
                    keys.add(value);
                }
            }
        } catch (Exception ignored) {
        }
        return keys;
    }

    public static void saveKnownOrderKeys(Context context, Iterable<String> keys) {
        JSONArray array = new JSONArray();
        int count = 0;
        for (String key : keys) {
            if (key == null) {
                continue;
            }
            String trimmed = key.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            array.put(trimmed);
            count++;
            if (count >= MAX_KNOWN_ORDER_KEYS) {
                break;
            }
        }
        prefs(context).edit().putString(KEY_KNOWN_ORDER_KEYS, array.toString()).apply();
    }

    public static void clearKnownOrderKeys(Context context) {
        prefs(context).edit().remove(KEY_KNOWN_ORDER_KEYS).apply();
    }
}

package com.rkghrud.shipapp.widget;

import android.content.Context;
import android.content.SharedPreferences;

public final class WidgetSettingsStore {
    static final String PREFS = "shipapp_widget_settings";
    static final int DEFAULT_OPACITY = 95;

    private WidgetSettingsStore() {
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static WidgetConfig load(Context context, int widgetId) {
        SharedPreferences prefs = prefs(context);
        return new WidgetConfig(
                prefs.getInt(key(widgetId, "market_count"), 3),
                prefs.getString(key(widgetId, "market_1"), ""),
                prefs.getString(key(widgetId, "market_2"), ""),
                prefs.getString(key(widgetId, "market_3"), ""),
                prefs.getString(key(widgetId, "market_4"), ""),
                prefs.getInt(key(widgetId, "opacity"), DEFAULT_OPACITY)
        );
    }

    static void save(Context context, int widgetId, int marketCount,
                     String market1, String market2, String market3, String market4, int opacity) {
        prefs(context).edit()
                .putInt(key(widgetId, "market_count"), clampMarketCount(marketCount))
                .putString(key(widgetId, "market_1"), market1 == null ? "" : market1)
                .putString(key(widgetId, "market_2"), market2 == null ? "" : market2)
                .putString(key(widgetId, "market_3"), market3 == null ? "" : market3)
                .putString(key(widgetId, "market_4"), market4 == null ? "" : market4)
                .putInt(key(widgetId, "opacity"), clampOpacity(opacity))
                .apply();
    }

    static int clampMarketCount(int marketCount) {
        return Math.max(1, Math.min(4, marketCount));
    }

    static int clampOpacity(int opacity) {
        return Math.max(35, Math.min(100, opacity));
    }

    private static String key(int widgetId, String suffix) {
        return "widget_" + widgetId + "_" + suffix;
    }

    static final class WidgetConfig {
        final int marketCount;
        final String market1;
        final String market2;
        final String market3;
        final String market4;
        final int opacity;

        WidgetConfig(int marketCount, String market1, String market2, String market3, String market4, int opacity) {
            this.marketCount = clampMarketCount(marketCount);
            this.market1 = market1 == null ? "" : market1;
            this.market2 = market2 == null ? "" : market2;
            this.market3 = market3 == null ? "" : market3;
            this.market4 = market4 == null ? "" : market4;
            this.opacity = clampOpacity(opacity);
        }

        String marketAt(int index, String fallback) {
            String value;
            if (index == 0) {
                value = market1;
            } else if (index == 1) {
                value = market2;
            } else if (index == 2) {
                value = market3;
            } else {
                value = market4;
            }
            return value == null || value.isEmpty() ? fallback : value;
        }
    }
}

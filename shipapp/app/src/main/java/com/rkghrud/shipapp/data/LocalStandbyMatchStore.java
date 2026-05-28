package com.rkghrud.shipapp.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LocalStandbyMatchStore {
    private static final String PREFS_NAME = "local_standby_matches";
    private static final String KEY_COUPANG_MATCHES = "coupang_matches";

    private final SharedPreferences prefs;

    public LocalStandbyMatchStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public int saveCoupangMatches(Collection<DispatchOrder> orders) {
        Map<String, StandbyMatch> matches = loadMatches();
        int saved = 0;
        for (DispatchOrder order : orders) {
            if (!isCoupang(order) || safe(order.trackingNumber).isEmpty()) {
                continue;
            }
            String key = order.stableOrderKey();
            if (key.isEmpty()) {
                continue;
            }
            matches.put(key, StandbyMatch.fromOrder(key, order));
            saved++;
        }
        saveMatches(matches);
        return saved;
    }

    public int applyToOrders(List<DispatchOrder> orders) {
        Map<String, StandbyMatch> matches = loadMatches();
        int applied = 0;
        for (DispatchOrder order : orders) {
            if (!isCoupang(order)) {
                continue;
            }
            StandbyMatch match = matches.get(order.stableOrderKey());
            if (match == null || match.trackingNumber.isEmpty()) {
                continue;
            }
            order.trackingNumber = match.trackingNumber;
            if (!match.shippingCompanyName.isEmpty()) {
                order.shippingCompanyName = match.shippingCompanyName;
            }
            order.selected = order.isSelectableForUpload();
            applied++;
        }
        return applied;
    }

    public int removeCoupangMatches(Collection<DispatchOrder> orders) {
        Map<String, StandbyMatch> matches = loadMatches();
        int removed = 0;
        for (DispatchOrder order : orders) {
            if (!isCoupang(order)) {
                continue;
            }
            if (matches.remove(order.stableOrderKey()) != null) {
                removed++;
            }
        }
        if (removed > 0) {
            saveMatches(matches);
        }
        return removed;
    }

    private Map<String, StandbyMatch> loadMatches() {
        Map<String, StandbyMatch> matches = new LinkedHashMap<>();
        String raw = prefs.getString(KEY_COUPANG_MATCHES, "[]");
        try {
            JSONArray array = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                StandbyMatch match = StandbyMatch.fromJson(object);
                if (match != null && !match.key.isEmpty()) {
                    matches.put(match.key, match);
                }
            }
        } catch (Exception ignored) {
            prefs.edit().remove(KEY_COUPANG_MATCHES).apply();
        }
        return matches;
    }

    private void saveMatches(Map<String, StandbyMatch> matches) {
        JSONArray array = new JSONArray();
        for (StandbyMatch match : matches.values()) {
            array.put(match.toJson());
        }
        prefs.edit().putString(KEY_COUPANG_MATCHES, array.toString()).apply();
    }

    private static boolean isCoupang(DispatchOrder order) {
        return order != null && "coupang".equals(order.marketKey);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class StandbyMatch {
        final String key;
        final String marketName;
        final String orderId;
        final String orderItemCode;
        final String shipmentBoxId;
        final String trackingNumber;
        final String shippingCompanyName;
        final long createdAtMillis;

        StandbyMatch(
                String key,
                String marketName,
                String orderId,
                String orderItemCode,
                String shipmentBoxId,
                String trackingNumber,
                String shippingCompanyName,
                long createdAtMillis
        ) {
            this.key = safe(key);
            this.marketName = safe(marketName);
            this.orderId = safe(orderId);
            this.orderItemCode = safe(orderItemCode);
            this.shipmentBoxId = safe(shipmentBoxId);
            this.trackingNumber = safe(trackingNumber);
            this.shippingCompanyName = safe(shippingCompanyName);
            this.createdAtMillis = createdAtMillis;
        }

        static StandbyMatch fromOrder(String key, DispatchOrder order) {
            return new StandbyMatch(
                    key,
                    order.marketName,
                    order.orderId,
                    order.orderItemCode,
                    order.shipmentBoxId,
                    order.trackingNumber,
                    order.shippingCompanyName,
                    System.currentTimeMillis()
            );
        }

        static StandbyMatch fromJson(JSONObject object) {
            if (object == null) {
                return null;
            }
            return new StandbyMatch(
                    object.optString("key", ""),
                    object.optString("marketName", ""),
                    object.optString("orderId", ""),
                    object.optString("orderItemCode", ""),
                    object.optString("shipmentBoxId", ""),
                    object.optString("trackingNumber", ""),
                    object.optString("shippingCompanyName", ""),
                    object.optLong("createdAtMillis", 0L)
            );
        }

        JSONObject toJson() {
            JSONObject object = new JSONObject();
            try {
                object.put("key", key);
                object.put("marketName", marketName);
                object.put("orderId", orderId);
                object.put("orderItemCode", orderItemCode);
                object.put("shipmentBoxId", shipmentBoxId);
                object.put("trackingNumber", trackingNumber);
                object.put("shippingCompanyName", shippingCompanyName);
                object.put("createdAtMillis", createdAtMillis);
            } catch (Exception ignored) {
            }
            return object;
        }
    }
}

package com.rkghrud.shipapp.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DispatchOrderUiHelper {
    private static final String FILTER_ALL = "all";

    private DispatchOrderUiHelper() {
    }

    public static List<DispatchOrder> filterOrdersForSelection(List<DispatchOrder> orders, String marketFilterKey, Set<String> selectedStatusKeys) {
        List<DispatchOrder> filtered = new ArrayList<>();
        if (orders == null || orders.isEmpty() || selectedStatusKeys == null || selectedStatusKeys.isEmpty()) {
            return filtered;
        }

        String selectedMarketKey = safe(marketFilterKey);
        for (DispatchOrder order : orders) {
            if (order == null) {
                continue;
            }
            if (!matchesMarketKey(order.marketKey, selectedMarketKey)) {
                continue;
            }
            if (!selectedStatusKeys.contains(order.statusFilterKey())) {
                continue;
            }
            filtered.add(order);
        }
        return filtered;
    }

    public static boolean matchesMarketKey(String candidateMarketKey, String selectedMarketKey) {
        String selectedKey = safe(selectedMarketKey);
        return FILTER_ALL.equals(selectedKey) || selectedKey.isEmpty() || selectedKey.equals(safe(candidateMarketKey));
    }

    public static String formatMarketCountSummary(Map<String, String> marketCounts) {
        if (marketCounts == null || marketCounts.isEmpty()) {
            return "";
        }

        StringBuilder summary = new StringBuilder();
        for (Map.Entry<String, String> entry : marketCounts.entrySet()) {
            String marketName = safe(entry.getKey());
            String value = safe(entry.getValue());
            if (marketName.isEmpty() || value.isEmpty()) {
                continue;
            }
            if (summary.length() > 0) {
                summary.append(" · ");
            }
            summary.append(marketName).append(' ').append(value);
            if (isNumeric(value)) {
                summary.append("건");
            }
        }
        return summary.toString();
    }

    private static boolean isNumeric(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return !value.isEmpty();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}


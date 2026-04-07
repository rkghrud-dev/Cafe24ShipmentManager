package com.rkghrud.shipapp.data;

public class Cafe24MarketConfig {
    public final String key;
    public final String displayName;
    public final String json;
    public final String sourceLabel;
    public final String sourceUri;
    public final boolean enabled;

    public Cafe24MarketConfig(String key, String displayName, String json,
                              String sourceLabel, String sourceUri, boolean enabled) {
        this.key = safe(key);
        this.displayName = safe(displayName);
        this.json = json == null ? "" : json.trim();
        this.sourceLabel = safe(sourceLabel);
        this.sourceUri = safe(sourceUri);
        this.enabled = enabled;
    }

    public boolean hasJson() {
        return !json.isEmpty();
    }

    public String buildMarketLabel() {
        return displayName.isEmpty() ? "Cafe24" : displayName + " / Cafe24";
    }

    public Cafe24MarketConfig withJson(String nextJson, String nextSourceLabel, String nextSourceUri) {
        return new Cafe24MarketConfig(key, displayName, nextJson, nextSourceLabel, nextSourceUri, enabled);
    }

    public Cafe24MarketConfig withDisplayName(String nextDisplayName) {
        return new Cafe24MarketConfig(key, nextDisplayName, json, sourceLabel, sourceUri, enabled);
    }

    public Cafe24MarketConfig withEnabled(boolean nextEnabled) {
        return new Cafe24MarketConfig(key, displayName, json, sourceLabel, sourceUri, nextEnabled);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}

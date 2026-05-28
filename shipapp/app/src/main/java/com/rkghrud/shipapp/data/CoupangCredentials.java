package com.rkghrud.shipapp.data;

public class CoupangCredentials {
    private final String marketName;
    private final String vendorId;
    private final String accessKey;
    private final String secretKey;

    public CoupangCredentials(String vendorId, String accessKey, String secretKey) {
        this("홈런마켓", vendorId, accessKey, secretKey);
    }

    public CoupangCredentials(String marketName, String vendorId, String accessKey, String secretKey) {
        this.marketName = safe(marketName).isEmpty() ? "홈런마켓" : safe(marketName);
        this.vendorId = vendorId;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
    }

    public String getMarketName() {
        return marketName;
    }

    public String getVendorId() {
        return vendorId;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public boolean isComplete() {
        return !vendorId.isEmpty() && !accessKey.isEmpty() && !secretKey.isEmpty();
    }

    public String buildMarketLabel() {
        return marketName + " / 쿠팡";
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}

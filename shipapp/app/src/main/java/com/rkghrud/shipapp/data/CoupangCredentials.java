package com.rkghrud.shipapp.data;

public class CoupangCredentials {
    private final String vendorId;
    private final String accessKey;
    private final String secretKey;

    public CoupangCredentials(String vendorId, String accessKey, String secretKey) {
        this.vendorId = vendorId;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
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
}

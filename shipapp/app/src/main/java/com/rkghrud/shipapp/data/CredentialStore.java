package com.rkghrud.shipapp.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

public class CredentialStore {
    public static final String SLOT_CAFE24_HOME = "cafe24_home";
    public static final String SLOT_CAFE24_PREPARE = "cafe24_prepare";

    private static final String PREFS_NAME = "shipapp_credentials";
    private static final String KEY_CAFE24_HOME_JSON = "cafe24_home_json";
    private static final String KEY_CAFE24_PREPARE_JSON = "cafe24_prepare_json";
    private static final String KEY_COUPANG_VENDOR_ID = "coupang_vendor_id";
    private static final String KEY_COUPANG_ACCESS_KEY = "coupang_access_key";
    private static final String KEY_COUPANG_SECRET_KEY = "coupang_secret_key";

    private final SharedPreferences preferences;

    public CredentialStore(Context context) {
        preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void saveCafe24Json(String slot, String json) {
        preferences.edit().putString(resolveCafe24Key(slot), json).apply();
    }

    public String getCafe24Json(String slot) {
        return preferences.getString(resolveCafe24Key(slot), "");
    }

    public void saveCoupangCredentials(String vendorId, String accessKey, String secretKey) {
        preferences.edit()
                .putString(KEY_COUPANG_VENDOR_ID, vendorId.trim())
                .putString(KEY_COUPANG_ACCESS_KEY, accessKey.trim())
                .putString(KEY_COUPANG_SECRET_KEY, secretKey.trim())
                .apply();
    }

    public CoupangCredentials getCoupangCredentials() {
        return new CoupangCredentials(
                preferences.getString(KEY_COUPANG_VENDOR_ID, ""),
                preferences.getString(KEY_COUPANG_ACCESS_KEY, ""),
                preferences.getString(KEY_COUPANG_SECRET_KEY, "")
        );
    }

    public int getConnectedSourceCount() {
        int count = 0;
        if (hasCafe24Slot(SLOT_CAFE24_HOME)) {
            count++;
        }
        if (hasCafe24Slot(SLOT_CAFE24_PREPARE)) {
            count++;
        }
        if (getCoupangCredentials().isComplete()) {
            count++;
        }
        return count;
    }

    public boolean hasCafe24Slot(String slot) {
        return !getCafe24Json(slot).isEmpty();
    }

    public boolean hasAnyCredentials() {
        return getConnectedSourceCount() > 0;
    }

    public void clearAll() {
        preferences.edit().clear().apply();
    }

    public String getCafe24Status(String slot, String label) {
        String raw = getCafe24Json(slot);
        if (raw.isEmpty()) {
            return label + "\n미연결";
        }

        try {
            JSONObject json = new JSONObject(raw);
            String mallId = json.optString("MallId", "미확인");
            String updatedAt = json.optString("UpdatedAt", "시간 정보 없음");
            return label + "\nMallId " + mallId + "\n업데이트 " + updatedAt;
        } catch (Exception e) {
            return label + "\n저장됨, 형식 확인 필요";
        }
    }

    public String getCoupangStatus() {
        CoupangCredentials credentials = getCoupangCredentials();
        if (!credentials.isComplete()) {
            return "쿠팡\n미연결";
        }

        return "쿠팡\nVendorId " + credentials.getVendorId() + "\nAccessKey " + mask(credentials.getAccessKey());
    }

    private String resolveCafe24Key(String slot) {
        if (SLOT_CAFE24_PREPARE.equals(slot)) {
            return KEY_CAFE24_PREPARE_JSON;
        }
        return KEY_CAFE24_HOME_JSON;
    }

    private String mask(String value) {
        if (value.length() <= 8) {
            return value;
        }
        return value.substring(0, 4) + "..." + value.substring(value.length() - 4);
    }
}

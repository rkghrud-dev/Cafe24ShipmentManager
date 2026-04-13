package com.rkghrud.shipapp.data;



import android.content.Context;

import android.content.SharedPreferences;



import com.rkghrud.shipapp.FeatureFlags;



import org.json.JSONArray;

import org.json.JSONObject;



import java.util.ArrayList;

import java.util.List;



public class CredentialStore {

    public static final String SLOT_CAFE24_HOME = "cafe24_home";

    public static final String SLOT_CAFE24_PREPARE = "cafe24_prepare";



    private static final String PREFS_NAME = "shipapp_credentials";

    private static final String KEY_CAFE24_MARKETS = "cafe24_markets";

    private static final String KEY_CAFE24_HOME_JSON = "cafe24_home_json";

    private static final String KEY_CAFE24_PREPARE_JSON = "cafe24_prepare_json";

    private static final String KEY_COUPANG_VENDOR_ID = "coupang_vendor_id";

    private static final String KEY_COUPANG_ACCESS_KEY = "coupang_access_key";

    private static final String KEY_COUPANG_SECRET_KEY = "coupang_secret_key";



    private static final String FIELD_KEY = "key";

    private static final String FIELD_NAME = "name";

    private static final String FIELD_JSON = "json";

    private static final String FIELD_SOURCE_LABEL = "sourceLabel";

    private static final String FIELD_SOURCE_URI = "sourceUri";

    private static final String FIELD_ENABLED = "enabled";



    private final SharedPreferences preferences;



    public CredentialStore(Context context) {

        preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

    }



    public List<Cafe24MarketConfig> getCafe24Markets() {

        ensureCafe24Migration();

        return readCafe24Markets();

    }



    public List<Cafe24MarketConfig> getEnabledCafe24Markets() {

        List<Cafe24MarketConfig> enabled = new ArrayList<>();

        for (Cafe24MarketConfig config : getCafe24Markets()) {

            if (config.enabled) {

                enabled.add(config);

            }

        }

        return enabled;

    }



    public List<Cafe24MarketConfig> getActiveCafe24Markets() {

        List<Cafe24MarketConfig> active = new ArrayList<>();

        for (Cafe24MarketConfig config : getCafe24Markets()) {

            if (config.enabled && config.hasJson()) {

                active.add(config);

            }

        }

        return active;

    }



    public Cafe24MarketConfig getCafe24Market(String key) {

        for (Cafe24MarketConfig config : getCafe24Markets()) {

            if (config.key.equals(key)) {

                return config;

            }

        }

        return null;

    }



    public Cafe24MarketConfig createCafe24Market(String displayName) {

        String trimmed = safe(displayName);

        if (trimmed.isEmpty()) {

            throw new IllegalArgumentException("판매처명을 입력하세요.");

        }



        List<Cafe24MarketConfig> markets = getCafe24Markets();

        Cafe24MarketConfig created = new Cafe24MarketConfig(

                buildMarketKey(markets),

                trimmed,

                "",

                "",

                "",

                true

        );

        markets.add(created);

        saveCafe24Markets(markets);

        return created;

    }



    public void renameCafe24Market(String key, String displayName) {

        String trimmed = safe(displayName);

        if (trimmed.isEmpty()) {

            throw new IllegalArgumentException("판매처명을 입력하세요.");

        }



        List<Cafe24MarketConfig> markets = getCafe24Markets();

        for (int i = 0; i < markets.size(); i++) {

            Cafe24MarketConfig config = markets.get(i);

            if (config.key.equals(key)) {

                markets.set(i, config.withDisplayName(trimmed));

                saveCafe24Markets(markets);

                return;

            }

        }

        throw new IllegalArgumentException("판매처를 찾지 못했습니다.");

    }



    public void setCafe24MarketEnabled(String key, boolean enabled) {

        List<Cafe24MarketConfig> markets = getCafe24Markets();

        for (int i = 0; i < markets.size(); i++) {

            Cafe24MarketConfig config = markets.get(i);

            if (config.key.equals(key)) {

                markets.set(i, config.withEnabled(enabled));

                saveCafe24Markets(markets);

                return;

            }

        }

    }



    public void deleteCafe24Market(String key) {

        List<Cafe24MarketConfig> markets = getCafe24Markets();

        List<Cafe24MarketConfig> remaining = new ArrayList<>();

        for (Cafe24MarketConfig config : markets) {

            if (!config.key.equals(key)) {

                remaining.add(config);

            }

        }

        saveCafe24Markets(remaining);



        SharedPreferences.Editor editor = preferences.edit();

        if (SLOT_CAFE24_HOME.equals(key)) {

            editor.remove(KEY_CAFE24_HOME_JSON);

        } else if (SLOT_CAFE24_PREPARE.equals(key)) {

            editor.remove(KEY_CAFE24_PREPARE_JSON);

        }

        editor.apply();

    }



    public void saveCafe24Json(String slot, String json) {

        saveCafe24Json(slot, json, "", "");

    }



    public void saveCafe24Json(String slot, String json, String sourceLabel, String sourceUri) {

        List<Cafe24MarketConfig> markets = getCafe24Markets();

        boolean replaced = false;

        for (int i = 0; i < markets.size(); i++) {

            Cafe24MarketConfig config = markets.get(i);

            if (config.key.equals(slot)) {

                markets.set(i, config.withJson(json, sourceLabel, sourceUri));

                replaced = true;

                break;

            }

        }



        if (!replaced) {

            markets.add(new Cafe24MarketConfig(

                    safe(slot),

                    defaultDisplayName(slot),

                    json,

                    sourceLabel,

                    sourceUri,

                    true

            ));

        }

        saveCafe24Markets(markets);



        SharedPreferences.Editor editor = preferences.edit();

        if (SLOT_CAFE24_HOME.equals(slot)) {

            editor.putString(KEY_CAFE24_HOME_JSON, json);

        } else if (SLOT_CAFE24_PREPARE.equals(slot)) {

            editor.putString(KEY_CAFE24_PREPARE_JSON, json);

        }

        editor.apply();

    }



    public String getCafe24Json(String slot) {

        Cafe24MarketConfig config = getCafe24Market(slot);

        if (config != null && config.hasJson()) {

            return config.json;

        }

        return getLegacyCafe24Json(slot);

    }



    public void saveCoupangCredentials(String vendorId, String accessKey, String secretKey) {

        preferences.edit()

                .putString(KEY_COUPANG_VENDOR_ID, safe(vendorId))

                .putString(KEY_COUPANG_ACCESS_KEY, safe(accessKey))

                .putString(KEY_COUPANG_SECRET_KEY, safe(secretKey))

                .apply();

    }



    public CoupangCredentials getCoupangCredentials() {

        return new CoupangCredentials(

                preferences.getString(KEY_COUPANG_VENDOR_ID, ""),

                preferences.getString(KEY_COUPANG_ACCESS_KEY, ""),

                preferences.getString(KEY_COUPANG_SECRET_KEY, "")

        );

    }



    public int getRegisteredCafe24Count() {

        return getCafe24Markets().size();

    }



    public int getEnabledCafe24Count() {

        int count = 0;

        for (Cafe24MarketConfig config : getCafe24Markets()) {

            if (config.enabled) {

                count++;

            }

        }

        return count;

    }



    public int getConnectedCafe24Count() {

        int count = 0;

        for (Cafe24MarketConfig config : getCafe24Markets()) {

            if (config.hasJson()) {

                count++;

            }

        }

        return count;

    }



    public int getConnectedSourceCount() {

        int count = getActiveCafe24Markets().size();

        if (FeatureFlags.ENABLE_COUPANG && getCoupangCredentials().isComplete()) {

            count++;

        }

        return count;

    }



    public boolean hasCafe24Slot(String slot) {

        return !getCafe24Json(slot).isEmpty();

    }



    public boolean hasAnyCredentials() {

        return getConnectedCafe24Count() > 0

                || (FeatureFlags.ENABLE_COUPANG && getCoupangCredentials().isComplete());

    }



    public void clearAll() {

        preferences.edit().clear().apply();

    }



    public String getCafe24Status(String slot) {

        Cafe24MarketConfig config = getCafe24Market(slot);

        String label = config == null ? defaultDisplayName(slot) : config.displayName;

        return getCafe24Status(slot, label + " / Cafe24");

    }



    public String getCafe24Status(String slot, String label) {

        String raw = getCafe24Json(slot);

        String title = safe(label);

        if (title.isEmpty()) {

            title = defaultDisplayName(slot) + " / Cafe24";

        }



        if (raw.isEmpty()) {

            return title + "\nJSON 미연결";

        }



        try {

            JSONObject json = new JSONObject(raw);

            String mallId = json.optString("MallId", "미확인");

            String updatedAt = json.optString("UpdatedAt", "시간 정보 없음");

            return title + "\nMallId " + mallId + "\n업데이트 " + updatedAt;

        } catch (Exception e) {

            return title + "\n저장됨, 형식 확인 필요";

        }

    }



    public String getCoupangStatus() {

        CoupangCredentials credentials = getCoupangCredentials();

        if (!credentials.isComplete()) {

            return "쿠팡\n미연결";

        }



        return "쿠팡\nVendorId " + credentials.getVendorId() + "\nAccessKey " + mask(credentials.getAccessKey());

    }



    private void ensureCafe24Migration() {

        if (preferences.contains(KEY_CAFE24_MARKETS)) {

            return;

        }



        JSONArray array = new JSONArray();

        appendLegacyMarket(array, SLOT_CAFE24_HOME, "홈런마켓", preferences.getString(KEY_CAFE24_HOME_JSON, ""));

        appendLegacyMarket(array, SLOT_CAFE24_PREPARE, "준비몰", preferences.getString(KEY_CAFE24_PREPARE_JSON, ""));

        preferences.edit().putString(KEY_CAFE24_MARKETS, array.toString()).apply();

    }



    private void appendLegacyMarket(JSONArray array, String key, String displayName, String json) {

        if (json == null || json.trim().isEmpty()) {

            return;

        }

        try {

            JSONObject object = new JSONObject();

            object.put(FIELD_KEY, key);

            object.put(FIELD_NAME, displayName);

            object.put(FIELD_JSON, json.trim());

            object.put(FIELD_SOURCE_LABEL, "기존 저장값");

            object.put(FIELD_SOURCE_URI, "");

            object.put(FIELD_ENABLED, true);

            array.put(object);

        } catch (Exception ignored) {

        }

    }



    private List<Cafe24MarketConfig> readCafe24Markets() {

        List<Cafe24MarketConfig> markets = new ArrayList<>();

        String raw = preferences.getString(KEY_CAFE24_MARKETS, "[]");

        try {

            JSONArray array = new JSONArray(raw);

            for (int i = 0; i < array.length(); i++) {

                JSONObject object = array.optJSONObject(i);

                if (object == null) {

                    continue;

                }



                String key = safe(object.optString(FIELD_KEY, ""));

                if (key.isEmpty()) {

                    continue;

                }



                String name = safe(object.optString(FIELD_NAME, defaultDisplayName(key)));

                String json = object.optString(FIELD_JSON, "");

                String sourceLabel = object.optString(FIELD_SOURCE_LABEL, "");

                String sourceUri = object.optString(FIELD_SOURCE_URI, "");

                boolean enabled = object.optBoolean(FIELD_ENABLED, true);

                markets.add(new Cafe24MarketConfig(key, name, json, sourceLabel, sourceUri, enabled));

            }

        } catch (Exception ignored) {

        }

        return markets;

    }



    private void saveCafe24Markets(List<Cafe24MarketConfig> markets) {

        JSONArray array = new JSONArray();

        for (Cafe24MarketConfig config : markets) {

            try {

                JSONObject object = new JSONObject();

                object.put(FIELD_KEY, config.key);

                object.put(FIELD_NAME, config.displayName);

                object.put(FIELD_JSON, config.json);

                object.put(FIELD_SOURCE_LABEL, config.sourceLabel);

                object.put(FIELD_SOURCE_URI, config.sourceUri);

                object.put(FIELD_ENABLED, config.enabled);

                array.put(object);

            } catch (Exception ignored) {

            }

        }

        preferences.edit().putString(KEY_CAFE24_MARKETS, array.toString()).apply();

    }



    private String getLegacyCafe24Json(String slot) {

        if (SLOT_CAFE24_PREPARE.equals(slot)) {

            return preferences.getString(KEY_CAFE24_PREPARE_JSON, "");

        }

        if (SLOT_CAFE24_HOME.equals(slot)) {

            return preferences.getString(KEY_CAFE24_HOME_JSON, "");

        }

        return "";

    }



    private String defaultDisplayName(String slot) {

        if (SLOT_CAFE24_PREPARE.equals(slot)) {

            return "준비몰";

        }

        if (SLOT_CAFE24_HOME.equals(slot)) {

            return "홈런마켓";

        }

        return safe(slot).isEmpty() ? "판매처" : safe(slot);

    }



    private String buildMarketKey(List<Cafe24MarketConfig> markets) {

        String baseKey = "cafe24_" + System.currentTimeMillis();

        String candidate = baseKey;

        int suffix = 1;

        while (containsMarketKey(markets, candidate)) {

            candidate = baseKey + "_" + suffix;

            suffix++;

        }

        return candidate;

    }



    private boolean containsMarketKey(List<Cafe24MarketConfig> markets, String key) {

        for (Cafe24MarketConfig config : markets) {

            if (config.key.equals(key)) {

                return true;

            }

        }

        return false;

    }


    private String mask(String value) {

        if (value.length() <= 8) {

            return value;

        }

        return value.substring(0, 4) + "..." + value.substring(value.length() - 4);

    }



    private String safe(String value) {

        return value == null ? "" : value.trim();

    }

}




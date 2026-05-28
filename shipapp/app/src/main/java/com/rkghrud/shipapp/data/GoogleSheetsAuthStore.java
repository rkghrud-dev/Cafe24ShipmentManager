package com.rkghrud.shipapp.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.rkghrud.shipapp.BuildConfig;

import net.openid.appauth.TokenResponse;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class GoogleSheetsAuthStore {
    private static final String PREFS_NAME = "shipapp_google_sheets_auth";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String KEY_ACCESS_TOKEN = "access_token";
    private static final String KEY_REFRESH_TOKEN = "refresh_token";
    private static final String KEY_EXPIRY_TIME = "expiry_time";
    private static final long EXPIRY_SKEW_MS = 60_000L;

    private final SharedPreferences prefs;

    public GoogleSheetsAuthStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public boolean hasRefreshToken() {
        return !getRefreshToken().isEmpty();
    }

    public String getFreshAccessToken() throws Exception {
        String accessToken = prefs.getString(KEY_ACCESS_TOKEN, "");
        long expiryTime = prefs.getLong(KEY_EXPIRY_TIME, 0L);
        if (accessToken != null && !accessToken.isEmpty()
                && System.currentTimeMillis() + EXPIRY_SKEW_MS < expiryTime) {
            return accessToken;
        }

        String refreshToken = getRefreshToken();
        if (refreshToken.isEmpty()) {
            throw new IllegalArgumentException("Google 로그인이 필요합니다.");
        }

        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", BuildConfig.GOOGLE_SHEETS_CLIENT_ID);
        if (!BuildConfig.GOOGLE_SHEETS_CLIENT_SECRET.trim().isEmpty()) {
            form.put("client_secret", BuildConfig.GOOGLE_SHEETS_CLIENT_SECRET);
        }
        form.put("refresh_token", refreshToken);
        form.put("grant_type", "refresh_token");

        HttpResult response = executeFormRequest(TOKEN_URL, form);
        if (!response.isSuccessful()) {
            throw new IllegalArgumentException("Google 토큰 갱신 실패 " + response.statusCode + ": " + clip(response.body));
        }

        JSONObject root = new JSONObject(response.body);
        String nextAccessToken = root.optString("access_token", "").trim();
        if (nextAccessToken.isEmpty()) {
            throw new IllegalArgumentException("Google access_token을 받지 못했습니다.");
        }
        long expiresIn = root.optLong("expires_in", 3600L);
        saveTokens(nextAccessToken, refreshToken, expiresIn);
        return nextAccessToken;
    }

    public void saveTokenResponse(TokenResponse response) {
        if (response == null) {
            return;
        }
        String refreshToken = response.refreshToken == null || response.refreshToken.trim().isEmpty()
                ? getRefreshToken()
                : response.refreshToken.trim();
        saveTokens(
                response.accessToken == null ? "" : response.accessToken.trim(),
                refreshToken,
                response.accessTokenExpirationTime == null
                        ? 3600L
                        : Math.max(60L, (response.accessTokenExpirationTime - System.currentTimeMillis()) / 1000L)
        );
    }

    public void clear() {
        prefs.edit().clear().apply();
    }

    public void invalidateAccessToken() {
        prefs.edit()
                .remove(KEY_ACCESS_TOKEN)
                .remove(KEY_EXPIRY_TIME)
                .apply();
    }

    private String getRefreshToken() {
        return safe(prefs.getString(KEY_REFRESH_TOKEN, ""));
    }

    private void saveTokens(String accessToken, String refreshToken, long expiresInSeconds) {
        SharedPreferences.Editor editor = prefs.edit();
        if (!safe(accessToken).isEmpty()) {
            editor.putString(KEY_ACCESS_TOKEN, safe(accessToken));
            editor.putLong(KEY_EXPIRY_TIME, System.currentTimeMillis() + Math.max(60L, expiresInSeconds) * 1000L);
        }
        if (!safe(refreshToken).isEmpty()) {
            editor.putString(KEY_REFRESH_TOKEN, safe(refreshToken));
        }
        editor.apply();
    }

    private static HttpResult executeFormRequest(String url, Map<String, String> form) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(30000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
        connection.setRequestProperty("Accept", "application/json");

        try (OutputStream outputStream = connection.getOutputStream()) {
            outputStream.write(buildQueryString(form).getBytes(StandardCharsets.UTF_8));
        }

        return readResponse(connection);
    }

    private static HttpResult readResponse(HttpURLConnection connection) throws Exception {
        int code = connection.getResponseCode();
        InputStream inputStream = code >= 200 && code < 400
                ? new BufferedInputStream(connection.getInputStream())
                : connection.getErrorStream();

        StringBuilder builder = new StringBuilder();
        if (inputStream != null) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    builder.append(line);
                }
            }
        }
        return new HttpResult(code, builder.toString());
    }

    private static String buildQueryString(Map<String, String> values) throws Exception {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (builder.length() > 0) {
                builder.append('&');
            }
            builder.append(urlEncode(entry.getKey())).append('=').append(urlEncode(entry.getValue()));
        }
        return builder.toString();
    }

    private static String urlEncode(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
    }

    private static String clip(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 180 ? body.substring(0, 180) : body;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class HttpResult {
        final int statusCode;
        final String body;

        HttpResult(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body == null ? "" : body;
        }

        boolean isSuccessful() {
            return statusCode >= 200 && statusCode < 300;
        }
    }
}

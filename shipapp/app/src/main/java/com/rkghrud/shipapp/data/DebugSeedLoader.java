package com.rkghrud.shipapp.data;

import android.content.Context;
import android.content.res.AssetManager;

import com.rkghrud.shipapp.BuildConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

public final class DebugSeedLoader {
    private DebugSeedLoader() {
    }

    public static int load(Context context, CredentialStore store) {
        if (!BuildConfig.DEBUG) {
            return 0;
        }

        int loaded = 0;
        String homeJson = readAsset(context, "seeds/cafe24_home.json");
        if (homeJson != null && !homeJson.isEmpty()) {
            store.saveCafe24Json(CredentialStore.SLOT_CAFE24_HOME, homeJson);
            loaded++;
        }

        String prepareJson = readAsset(context, "seeds/cafe24_prepare.json");
        if (prepareJson != null && !prepareJson.isEmpty()) {
            store.saveCafe24Json(CredentialStore.SLOT_CAFE24_PREPARE, prepareJson);
            loaded++;
        }

        Properties properties = readProperties(context, "seeds/coupang.properties");
        if (properties != null) {
            String vendorId = properties.getProperty("vendorId", "").trim();
            String accessKey = properties.getProperty("accessKey", "").trim();
            String secretKey = properties.getProperty("secretKey", "").trim();
            if (!vendorId.isEmpty() && !accessKey.isEmpty() && !secretKey.isEmpty()) {
                store.saveCoupangCredentials(vendorId, accessKey, secretKey);
                loaded++;
            }
        }

        return loaded;
    }

    private static String readAsset(Context context, String assetPath) {
        AssetManager assets = context.getAssets();
        try (InputStream inputStream = assets.open(assetPath);
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
            return builder.toString().trim();
        } catch (IOException e) {
            return null;
        }
    }

    private static Properties readProperties(Context context, String assetPath) {
        AssetManager assets = context.getAssets();
        try (InputStream inputStream = assets.open(assetPath)) {
            Properties properties = new Properties();
            properties.load(inputStream);
            return properties;
        } catch (IOException e) {
            return null;
        }
    }
}

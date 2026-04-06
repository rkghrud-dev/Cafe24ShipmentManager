package com.rkghrud.shipapp.data;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class ShippingCompanyResolver {
    private static final String DEFAULT_DISPLAY_NAME = "CJ대한통운";
    private static final String DEFAULT_CAFE24_CODE = "0006";
    private static final String DEFAULT_COUPANG_CODE = "CJGLS";

    private static final Map<String, String> CAFE24_CODES = new LinkedHashMap<>();
    private static final Map<String, String> COUPANG_CODES = new LinkedHashMap<>();
    private static final Map<String, String> DISPLAY_NAMES = new LinkedHashMap<>();

    static {
        putCafe24("CJ대한통운", "0006");
        putCafe24("대한통운", "0006");
        putCafe24("한진택배", "0018");
        putCafe24("롯데글로벌로지스", "0079");
        putCafe24("롯데택배", "0079");
        putCafe24("로젠택배", "0004");
        putCafe24("우체국택배", "0012");
        putCafe24("우체국", "0012");
        putCafe24("자체배송", "0001");

        putCoupang("CJ대한통운", "CJGLS");
        putCoupang("대한통운", "CJGLS");
        putCoupang("한진택배", "HANJIN");
        putCoupang("롯데택배", "HYUNDAI");
        putCoupang("롯데글로벌로지스", "LOTTEGLOBAL");
        putCoupang("로젠택배", "KGB");
        putCoupang("우체국택배", "EPOST");
        putCoupang("우체국", "EPOST");
        putCoupang("경동택배", "KDEXP");
        putCoupang("대신택배", "DAESIN");
        putCoupang("자체배송", "DIRECT");
        putCoupang("직접배송", "DIRECT");
        putCoupang("직접수령", "DIRECT");
        putCoupang("업체직송", "DIRECT");

        DISPLAY_NAMES.put("0006", "CJ대한통운");
        DISPLAY_NAMES.put("0018", "한진택배");
        DISPLAY_NAMES.put("0079", "롯데택배");
        DISPLAY_NAMES.put("0004", "로젠택배");
        DISPLAY_NAMES.put("0012", "우체국택배");
        DISPLAY_NAMES.put("0001", "자체배송");
        DISPLAY_NAMES.put("CJGLS", "CJ대한통운");
        DISPLAY_NAMES.put("HANJIN", "한진택배");
        DISPLAY_NAMES.put("HYUNDAI", "롯데택배");
        DISPLAY_NAMES.put("LOTTEGLOBAL", "롯데글로벌로지스");
        DISPLAY_NAMES.put("KGB", "로젠택배");
        DISPLAY_NAMES.put("EPOST", "우체국택배");
        DISPLAY_NAMES.put("KDEXP", "경동택배");
        DISPLAY_NAMES.put("DAESIN", "대신택배");
        DISPLAY_NAMES.put("DIRECT", "자체배송");
    }

    private ShippingCompanyResolver() {
    }

    public static String displayName(String shippingCompanyName) {
        if (shippingCompanyName == null || shippingCompanyName.trim().isEmpty()) {
            return DEFAULT_DISPLAY_NAME;
        }

        String trimmed = shippingCompanyName.trim();
        String normalizedCode = trimmed.toUpperCase(Locale.ROOT);
        String fromCode = DISPLAY_NAMES.get(normalizedCode);
        return fromCode != null ? fromCode : trimmed;
    }

    public static String resolve(String marketKey, String shippingCompanyName) {
        return "coupang".equals(marketKey)
                ? resolveCoupang(shippingCompanyName)
                : resolveCafe24(shippingCompanyName);
    }

    private static String resolveCafe24(String shippingCompanyName) {
        if (shippingCompanyName == null || shippingCompanyName.trim().isEmpty()) {
            return DEFAULT_CAFE24_CODE;
        }

        String trimmed = shippingCompanyName.trim();
        if (trimmed.matches("\\d{4}")) {
            return trimmed;
        }

        for (Map.Entry<String, String> entry : CAFE24_CODES.entrySet()) {
            if (trimmed.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return DEFAULT_CAFE24_CODE;
    }

    private static String resolveCoupang(String shippingCompanyName) {
        if (shippingCompanyName == null || shippingCompanyName.trim().isEmpty()) {
            return DEFAULT_COUPANG_CODE;
        }

        String trimmed = shippingCompanyName.trim();
        String normalizedCode = trimmed.toUpperCase(Locale.ROOT);
        if (DISPLAY_NAMES.containsKey(normalizedCode)) {
            return normalizedCode;
        }

        for (Map.Entry<String, String> entry : COUPANG_CODES.entrySet()) {
            if (trimmed.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return DEFAULT_COUPANG_CODE;
    }

    private static void putCafe24(String name, String code) {
        CAFE24_CODES.put(name, code);
        DISPLAY_NAMES.putIfAbsent(code, name);
    }

    private static void putCoupang(String name, String code) {
        COUPANG_CODES.put(name, code);
        DISPLAY_NAMES.putIfAbsent(code, name);
    }
}

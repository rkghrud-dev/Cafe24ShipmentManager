package com.rkghrud.shipapp.data;

import org.json.JSONArray;
import org.json.JSONObject;

final class CoupangUploadResponseParser {
    private CoupangUploadResponseParser() {
    }

    static String parseError(String actionLabel, String shipmentBoxId, int httpStatus, String body) {
        if (httpStatus < 200 || httpStatus >= 300) {
            return actionLabel + " 실패 " + httpStatus + ": " + clipBody(body);
        }
        if (body == null || body.trim().isEmpty()) {
            return "";
        }

        try {
            JSONObject root = new JSONObject(body);
            JSONObject data = root.optJSONObject("data");
            if (data == null) {
                return "";
            }

            int responseCode = data.optInt("responseCode", 0);
            String responseMessage = data.optString("responseMessage", "");
            JSONArray responseList = data.optJSONArray("responseList");
            String normalizedShipmentBoxId = normalizeNumericId(shipmentBoxId);

            if (responseList != null) {
                for (int i = 0; i < responseList.length(); i++) {
                    JSONObject item = responseList.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }

                    String itemShipmentBoxId = normalizeNumericId(item.optString("shipmentBoxId", String.valueOf(item.optLong("shipmentBoxId", 0L))));
                    if (!normalizedShipmentBoxId.isEmpty() && !normalizedShipmentBoxId.equals(itemShipmentBoxId)) {
                        continue;
                    }

                    if (!item.optBoolean("succeed", responseCode == 0)) {
                        String resultCode = item.optString("resultCode", "");
                        String resultMessage = item.optString("resultMessage", "");
                        String detail = resultCode.isEmpty() ? responseMessage : resultCode;
                        if (!resultMessage.isEmpty()) {
                            detail = detail.isEmpty() ? resultMessage : detail + " / " + resultMessage;
                        }
                        return actionLabel + " 실패: " + detail;
                    }
                    return "";
                }
            }

            if (responseCode != 0) {
                return actionLabel + " 실패: " + firstNonEmpty(responseMessage, clipBody(body));
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String normalizeNumericId(String value) {
        return value == null ? "" : value.replaceAll("[^0-9]", "");
    }

    private static String clipBody(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 180 ? body.substring(0, 180) : body;
    }

    private static String firstNonEmpty(String first, String fallback) {
        return first == null || first.trim().isEmpty() ? fallback : first;
    }
}

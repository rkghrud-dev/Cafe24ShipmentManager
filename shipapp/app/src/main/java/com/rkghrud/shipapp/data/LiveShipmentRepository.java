package com.rkghrud.shipapp.data;



import android.content.Context;

import android.net.Uri;




import com.rkghrud.shipapp.FeatureFlags;



import org.json.JSONArray;

import org.json.JSONObject;



import java.io.BufferedInputStream;

import java.io.BufferedReader;

import java.io.BufferedWriter;

import java.io.InputStream;

import java.io.InputStreamReader;

import java.io.OutputStream;

import java.io.OutputStreamWriter;

import java.net.HttpURLConnection;

import java.net.URL;

import java.nio.charset.StandardCharsets;

import java.time.Instant;

import java.time.LocalDate;

import java.time.LocalDateTime;

import java.time.OffsetDateTime;

import java.time.ZoneId;

import java.time.format.DateTimeFormatter;

import java.util.ArrayList;

import java.util.Collections;

import java.util.Comparator;

import java.util.LinkedHashMap;

import java.util.List;

import java.util.Locale;

import java.util.Map;

import java.util.regex.Matcher;

import java.util.regex.Pattern;



import javax.crypto.Mac;

import javax.crypto.spec.SecretKeySpec;



public class LiveShipmentRepository implements ShipmentRepository {

    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.KOREA);

    private static final DateTimeFormatter DISPLAY_TIME_WITH_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.KOREA);

    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US);

    private static final String DEFAULT_CAFE24_API_VERSION = "2025-12-01";

    private static final Pattern CAFE24_API_VERSION_PATTERN = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final String CAFE24_DISPATCH_STATUS_QUERY = "N20,N21,N30,C00,E00";
    private static final String CAFE24_EXTERNAL_AUTH_REQUIRED =
            "Cafe24 인증은 외부 인증 프로그램에서만 갱신합니다. 새 JSON을 발급한 뒤 앱에 다시 가져오세요.";



    private final Context context;

    private final CredentialStore credentialStore;



    public LiveShipmentRepository(Context context) {

        this.context = context.getApplicationContext();

        this.credentialStore = new CredentialStore(this.context);

    }



    @Override

    public ShipmentDashboardSnapshot fetchDashboard() {

        List<ShipmentSummary> shipments = new ArrayList<>();

        List<String> cafe24Statuses = new ArrayList<>();

        int connectedCount = credentialStore.getActiveCafe24Markets().size();

        int fetchedCount = 0;



        for (Cafe24MarketConfig config : credentialStore.getActiveCafe24Markets()) {

            SourceFetchResult result = fetchCafe24Source(config.key, config.displayName);

            shipments.addAll(result.shipments);

            cafe24Statuses.add(result.statusText);

            if (result.success) {

                fetchedCount++;

            }

        }



        String coupangStatus = "";

        if (FeatureFlags.ENABLE_COUPANG && credentialStore.getCoupangCredentials().isComplete()) {

            connectedCount++;

            SourceFetchResult result = fetchCoupangSource();

            shipments.addAll(result.shipments);

            coupangStatus = result.statusText;

            if (result.success) {

                fetchedCount++;

            }

        }



        Collections.sort(shipments, Comparator.comparingLong(ShipmentSummary::getSortTime).reversed());



        String heroMessage;

        if (connectedCount == 0) {

            heroMessage = "아직 저장된 Cafe24 판매처 JSON이 없습니다. 각 판매처 JSON을 연결하세요.";

        } else if (shipments.isEmpty() && fetchedCount > 0) {

            heroMessage = "연결된 " + connectedCount + "개 소스를 조회했고, 현재 출고대상 주문은 없습니다.";

        } else if (shipments.isEmpty()) {

            heroMessage = "연결된 키는 있지만 조회 성공한 소스가 없습니다. 각 키 상태 패널을 먼저 확인하세요.";

        } else {

            heroMessage = "연결된 " + connectedCount + "개 중 " + fetchedCount + "개 조회 성공. 지금 처리할 출고대상 주문은 " + shipments.size() + "건입니다.";

        }



        String cafe24Status = cafe24Statuses.isEmpty()

                ? "Cafe24 판매처\n미연결"

                : String.join("\n\n", cafe24Statuses);



        return new ShipmentDashboardSnapshot(

                shipments,

                connectedCount,

                fetchedCount,

                heroMessage,

                cafe24Status,

                "",

                coupangStatus

        );

    }



    private SourceFetchResult fetchCafe24Source(String slot, String displayName) {

        String rawJson = getCafe24JsonForUse(slot);

        if (rawJson.isEmpty()) {

            return SourceFetchResult.failure(displayName + " Cafe24\n미연결");

        }



        try {

            JSONObject credential = new JSONObject(rawJson);

            String mallId = credential.optString("MallId", "").trim();
        String accessToken = credential.optString("AccessToken", "").trim();
        String apiVersion = credential.optString("ApiVersion", DEFAULT_CAFE24_API_VERSION).trim();



            if (mallId.isEmpty()) {

                return SourceFetchResult.failure(displayName + " Cafe24\n필수 키가 부족합니다. MallId를 확인하세요.");

            }

            if (accessToken.isEmpty()) {

                return SourceFetchResult.failure(displayName + " Cafe24\n" + CAFE24_EXTERNAL_AUTH_REQUIRED);

            }



            LocalDate endDate = LocalDate.now(SEOUL);

            LocalDate startDate = endDate.minusDays(14);

            List<ShipmentSummary> shipments = new ArrayList<>();

            int offset = 0;

            final int limit = 100;

            boolean versionRetried = false;



            while (true) {

                String url = "https://" + mallId + ".cafe24api.com/api/v2/admin/orders"

                        + "?start_date=" + ISO_DATE.format(startDate)

                        + "&end_date=" + ISO_DATE.format(endDate)

                        + "&limit=" + limit

                        + "&offset=" + offset

                        + "&embed=receivers,items"

                        + "&order_status=N20";



                HttpResult response = executeJsonRequest("GET", url, null, buildCafe24Headers(accessToken, apiVersion));

                String fallbackApiVersion = maybeUpgradeCafe24ApiVersion(response, credential, slot, apiVersion);

                if (!fallbackApiVersion.equals(apiVersion) && !versionRetried) {

                    apiVersion = fallbackApiVersion;

                    versionRetried = true;

                    continue;

                }
                if (response.statusCode == 401 || response.statusCode == 403) {

                    return SourceFetchResult.failure(displayName + " Cafe24\n" + CAFE24_EXTERNAL_AUTH_REQUIRED + "\n응답: " + clip(response.body));

                }



                if (!response.isSuccessful()) {

                    return SourceFetchResult.failure(displayName + " Cafe24\n조회 실패 " + response.statusCode + "\n" + clip(response.body));

                }



                JSONObject root = new JSONObject(response.body);

                JSONArray orders = root.optJSONArray("orders");

                if (orders == null || orders.length() == 0) {

                    break;

                }



                for (int i = 0; i < orders.length(); i++) {

                    JSONObject order = orders.optJSONObject(i);

                    if (order == null) {

                        continue;

                    }

                    parseCafe24Order(displayName, order, shipments);

                }



                if (orders.length() < limit) {

                    break;

                }

                offset += limit;

            }



            String status = displayName + " Cafe24\nMallId " + mallId + "\n조회 성공 " + shipments.size() + "건";

            return SourceFetchResult.success(status, shipments);

        } catch (Exception ex) {

            return SourceFetchResult.failure(displayName + " Cafe24\n예외 " + ex.getMessage());

        }

    }



    private SourceFetchResult fetchCoupangSource() {

        CoupangCredentials credentials = credentialStore.getCoupangCredentials();

        if (!credentials.isComplete()) {

            return SourceFetchResult.failure("쿠팡\n미연결");

        }



        try {

            LocalDate endDate = LocalDate.now(SEOUL);

            LocalDate startDate = endDate.minusDays(14);

            List<ShipmentSummary> shipments = new ArrayList<>();

            String[] statuses = new String[]{"ACCEPT", "INSTRUCT"};



            for (String status : statuses) {

                String nextToken = null;

                do {

                    Map<String, String> query = new LinkedHashMap<>();

                    query.put("createdAtFrom", ISO_DATE.format(startDate) + "+09:00");

                    query.put("createdAtTo", ISO_DATE.format(endDate) + "+09:00");

                    query.put("status", status);

                    query.put("maxPerPage", "50");

                    if (nextToken != null && !nextToken.isEmpty()) {

                        query.put("nextToken", nextToken);

                    }



                    String path = "/v2/providers/openapi/apis/api/v5/vendors/" + credentials.getVendorId() + "/ordersheets";

                    String queryString = buildQueryString(query);

                    String signedDate = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)

                            .format(DateTimeFormatter.ofPattern("yyMMdd'T'HHmmss'Z'", Locale.US));

                    String signature = createCoupangSignature(credentials.getSecretKey(), signedDate, "GET", path, queryString);

                    Map<String, String> headers = new LinkedHashMap<>();

                    headers.put("Authorization", "CEA algorithm=HmacSHA256, access-key=" + credentials.getAccessKey() + ", signed-date=" + signedDate + ", signature=" + signature);

                    headers.put("X-EXTENDED-TIMEOUT", "90000");

                    headers.put("Accept", "application/json");



                    String url = "https://api-gateway.coupang.com" + path + (queryString.isEmpty() ? "" : "?" + queryString);

                    HttpResult response = executeJsonRequest("GET", url, null, headers);

                    if (!response.isSuccessful()) {

                        return SourceFetchResult.failure("쿠팡\n조회 실패 " + response.statusCode + "\n" + clip(response.body));

                    }



                    JSONObject root = new JSONObject(response.body);

                    JSONArray data = root.optJSONArray("data");

                    if (data == null || data.length() == 0) {

                        break;

                    }



                    for (int i = 0; i < data.length(); i++) {

                        JSONObject orderSheet = data.optJSONObject(i);

                        if (orderSheet != null) {

                            parseCoupangOrder(status, orderSheet, shipments);

                        }

                    }



                    nextToken = root.optString("nextToken", "");

                    if (nextToken.isEmpty()) {

                        nextToken = null;

                    }

                } while (nextToken != null);

            }



            String status = "쿠팡\nVendorId " + credentials.getVendorId() + "\n조회 성공 " + shipments.size() + "건";

            return SourceFetchResult.success(status, dedupeShipments(shipments));

        } catch (Exception ex) {

            return SourceFetchResult.failure("쿠팡\n예외 " + ex.getMessage());

        }

    }



    private void parseCafe24Order(String displayName, JSONObject order, List<ShipmentSummary> shipments) {

        JSONArray items = order.optJSONArray("items");

        JSONArray receivers = order.optJSONArray("receivers");

        JSONObject receiver = receivers != null && receivers.length() > 0 ? receivers.optJSONObject(0) : null;

        if (items == null) {

            return;

        }



        String orderId = order.optString("order_id", "");

        String recipientName = firstNonEmpty(opt(receiver, "name", opt(order, "receiver_name", opt(order, "buyer_name", ""))), "수령인 없음");

        String orderDate = order.optString("order_date", "");



        for (int i = 0; i < items.length(); i++) {

            JSONObject item = items.optJSONObject(i);

            if (item == null) {

                continue;

            }

            String productName = item.optString("product_name", "상품명 없음");

            int quantity = item.optInt("quantity", 0);

            String orderItemCode = item.optString("order_item_code", "");

            long sortTime = parseSortTime(orderDate);

            shipments.add(new ShipmentSummary(

                    displayName + " / Cafe24",

                    orderId,

                    orderItemCode,

                    "Cafe24",

                    recipientName,

                    productName,

                    quantity,

                    extractCafe24Amount(order, item, quantity),

                    formatDisplayTime(orderDate),

                    true,

                    sortTime

            ));

        }

    }

    private void parseCoupangOrder(String status, JSONObject orderSheet, List<ShipmentSummary> shipments) {

        JSONArray items = orderSheet.optJSONArray("orderItems");

        JSONObject receiver = orderSheet.optJSONObject("receiver");

        if (items == null) {

            return;

        }



        String orderId = orderSheet.optString("orderId", "");

        String shipmentBoxId = orderSheet.optString("shipmentBoxId", "");

        String orderedAt = orderSheet.optString("orderedAt", "");

        String recipientName = firstNonEmpty(opt(receiver, "name", ""), "수령인 없음");



        for (int i = 0; i < items.length(); i++) {

            JSONObject item = items.optJSONObject(i);

            if (item == null) {

                continue;

            }

            String sellerProductName = item.optString("sellerProductName", "");

            String sellerProductItemName = item.optString("sellerProductItemName", "");

            String vendorItemName = item.optString("vendorItemName", "");

            String productName = firstNonEmpty(joinDistinct(sellerProductName, sellerProductItemName, vendorItemName), "상품명 없음");

            int quantity = item.has("shippingCount") ? item.optInt("shippingCount", 0) : item.optInt("orderCount", 0);

            String ref = item.optString("shipmentBoxId", shipmentBoxId);

            shipments.add(new ShipmentSummary(

                    "홈런마켓 / 쿠팡",

                    orderId,

                    ref,

                    "쿠팡",

                    recipientName,

                    productName,

                    quantity,

                    extractCoupangAmount(orderSheet, item, quantity),

                    formatDisplayTime(orderedAt),

                    true,

                    parseSortTime(orderedAt)

            ));

        }

    }

    public static String normalizeCafe24JsonForStorage(String rawJson) throws Exception {

        return normalizeCafe24JsonForStorage(rawJson, true);

    }

    private static String normalizeCafe24JsonForStorage(String rawJson, boolean stampUpdatedAt) throws Exception {

        JSONObject credential = new JSONObject(rawJson == null ? "{}" : rawJson);

        String mallId = credential.optString("MallId", "").trim();

        if (mallId.isEmpty()) {

            throw new Exception("MallId가 없습니다.");

        }
        String accessToken = credential.optString("AccessToken", "").trim();
        String tokenProviderUrl = credential.optString("TokenProviderUrl", "").trim();

        String apiVersion = credential.optString("ApiVersion", DEFAULT_CAFE24_API_VERSION).trim();

        if (apiVersion.isEmpty()) {

            apiVersion = DEFAULT_CAFE24_API_VERSION;

        }
        if (accessToken.isEmpty() && tokenProviderUrl.isEmpty()) {

            throw new Exception(CAFE24_EXTERNAL_AUTH_REQUIRED);

        }

        credential.put("ApiVersion", apiVersion);

        if (stampUpdatedAt && credential.optString("UpdatedAt", "").trim().isEmpty()) {

            credential.put("UpdatedAt", Instant.now().toString());

        }

        return credential.toString();

    }

    private String getCafe24JsonForUse(String slot) {

        String storedJson = credentialStore.getCafe24Json(slot);

        Cafe24MarketConfig config = credentialStore.getCafe24Market(slot);

        String resolvedJson = storedJson;

        if (config != null && !config.sourceUri.isEmpty()) {

            String sourceJson = readCafe24SourceJson(config.sourceUri);

            if (!sourceJson.isEmpty()) {

                try {

                    String normalizedJson = preserveStoredCafe24UpdatedAt(
                            normalizeCafe24JsonForStorage(sourceJson, false),
                            storedJson
                    );

                    if (!normalizedJson.equals(config.json)) {

                        credentialStore.saveCafe24Json(slot, normalizedJson, config.sourceLabel, config.sourceUri);

                    }

                    resolvedJson = normalizedJson;

                } catch (Exception ignored) {

                    resolvedJson = storedJson;

                }

            }

        }

        return refreshCafe24JsonFromTokenProvider(slot, resolvedJson, config);

    }

    private String refreshCafe24JsonFromTokenProvider(String slot, String rawJson, Cafe24MarketConfig config) {

        if (rawJson == null || rawJson.trim().isEmpty()) {

            return rawJson;

        }

        try {

            JSONObject credential = new JSONObject(rawJson);

            if (credential.optString("TokenProviderUrl", "").trim().isEmpty()) {

                return rawJson;

            }

            JSONObject updated = applyCafe24TokenProvider(credential);

            String normalizedJson = normalizeCafe24JsonForStorage(updated.toString(), false);

            if (!normalizedJson.equals(rawJson)) {

                String sourceLabel = config == null ? "Google Apps Script" : config.sourceLabel;
                String sourceUri = config == null ? "" : config.sourceUri;
                credentialStore.saveCafe24Json(slot, normalizedJson, sourceLabel, sourceUri);

            }

            return normalizedJson;

        } catch (Exception ignored) {

            return rawJson;

        }

    }

    private JSONObject applyCafe24TokenProvider(JSONObject credential) throws Exception {

        String providerUrl = credential.optString("TokenProviderUrl", "").trim();

        if (providerUrl.isEmpty()) {

            return credential;

        }

        String mallId = credential.optString("MallId", "").trim();

        if (mallId.isEmpty()) {

            throw new Exception("MallId가 없습니다.");

        }

        String providerKey = firstNonEmpty(
                credential.optString("TokenProviderKey", ""),
                credential.optString("ProviderKey", "")
        );

        Uri.Builder builder = Uri.parse(providerUrl).buildUpon()
                .appendQueryParameter("mall", mallId);

        if (!providerKey.isEmpty()) {

            builder.appendQueryParameter("key", providerKey);

        }

        builder.appendQueryParameter("_", String.valueOf(System.currentTimeMillis() / 1000L));

        HttpResult response = executeJsonRequest("GET", builder.build().toString(), null, Collections.emptyMap());

        if (response.statusCode < 200 || response.statusCode >= 300) {

            throw new Exception("Google Apps Script 토큰 조회 실패 " + response.statusCode + ": " + clip(response.body));

        }

        JSONObject tokenJson = new JSONObject(response.body);

        if (tokenJson.optBoolean("ok", true) == false) {

            throw new Exception("Google Apps Script 토큰 오류: " + clip(response.body));

        }

        String accessToken = firstNonEmpty(
                tokenJson.optString("AccessToken", ""),
                tokenJson.optString("access_token", "")
        );

        if (accessToken.isEmpty()) {

            throw new Exception("Google Apps Script 응답에 AccessToken이 없습니다.");

        }

        credential.put("AccessToken", accessToken);

        String apiVersion = firstNonEmpty(
                tokenJson.optString("ApiVersion", ""),
                tokenJson.optString("api_version", "")
        );

        if (!apiVersion.isEmpty()) {

            credential.put("ApiVersion", apiVersion);

        }

        String shopNo = firstNonEmpty(
                tokenJson.optString("ShopNo", ""),
                tokenJson.optString("shop_no", "")
        );

        if (!shopNo.isEmpty()) {

            credential.put("ShopNo", shopNo);

        }

        String updatedAt = firstNonEmpty(
                tokenJson.optString("UpdatedAt", ""),
                tokenJson.optString("updated_at", "")
        );

        credential.put("UpdatedAt", updatedAt.isEmpty() ? Instant.now().toString() : updatedAt);

        return credential;

    }
    private String preserveStoredCafe24UpdatedAt(String normalizedJson, String storedJson) {

        try {

            JSONObject normalized = new JSONObject(normalizedJson);

            if (!normalized.optString("UpdatedAt", "").trim().isEmpty()) {

                return normalizedJson;

            }

            JSONObject stored = new JSONObject(storedJson == null ? "{}" : storedJson);

            String updatedAt = stored.optString("UpdatedAt", "").trim();

            if (updatedAt.isEmpty()) {

                return normalizedJson;

            }

            normalized.put("UpdatedAt", updatedAt);

            return normalized.toString();

        } catch (Exception ignored) {

            return normalizedJson;

        }

    }

    private String readCafe24SourceJson(String sourceUri) {

        if (sourceUri == null || sourceUri.trim().isEmpty() || sourceUri.startsWith("assets://")) {

            return "";

        }

        StringBuilder builder = new StringBuilder();

        try (InputStream inputStream = context.getContentResolver().openInputStream(Uri.parse(sourceUri));
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

            String line;

            while ((line = reader.readLine()) != null) {

                builder.append(line).append('\n');

            }

            return builder.toString().trim();

        } catch (Exception ignored) {

            return "";

        }

    }

    public String validateAndNormalizeCafe24Json(String rawJson) throws Exception {

        JSONObject credential = new JSONObject(rawJson);

        String mallId = credential.optString("MallId", "").trim();

        if (mallId.isEmpty()) {

            throw new Exception("MallId가 없습니다.");

        }
        String accessToken = credential.optString("AccessToken", "").trim();
        String tokenProviderUrl = credential.optString("TokenProviderUrl", "").trim();
        if (accessToken.isEmpty() && !tokenProviderUrl.isEmpty()) {
            credential = applyCafe24TokenProvider(credential);
            accessToken = credential.optString("AccessToken", "").trim();
        }

        String apiVersion = credential.optString("ApiVersion", DEFAULT_CAFE24_API_VERSION).trim();

        if (apiVersion.isEmpty()) {

            apiVersion = DEFAULT_CAFE24_API_VERSION;

        }

        boolean versionRetried = false;



        while (true) {

            if (accessToken.isEmpty()) {

                throw new Exception(CAFE24_EXTERNAL_AUTH_REQUIRED);

            }



            HttpResult response = executeJsonRequest(

                    "GET",

                    buildCafe24ValidationUrl(mallId),

                    null,

                    buildCafe24Headers(accessToken, apiVersion)

            );



            String fallbackApiVersion = maybeUpgradeCafe24ApiVersionInMemory(response, credential, apiVersion);

            if (!fallbackApiVersion.equals(apiVersion) && !versionRetried) {

                apiVersion = fallbackApiVersion;

                versionRetried = true;

                continue;

            }
            if (response.statusCode == 401 || response.statusCode == 403) {

                throw new Exception(CAFE24_EXTERNAL_AUTH_REQUIRED + " 응답: " + clip(response.body));

            }



            if (!response.isSuccessful()) {

                throw new Exception("Cafe24 인증 검증 실패 " + response.statusCode + ": " + clip(response.body));

            }



            credential.put("ApiVersion", apiVersion);

            credential.put("UpdatedAt", Instant.now().toString());

            return credential.toString();

        }

    }



    private String buildCafe24ValidationUrl(String mallId) {

        LocalDate endDate = LocalDate.now(SEOUL);

        LocalDate startDate = endDate.minusDays(14);

        return "https://" + mallId + ".cafe24api.com/api/v2/admin/orders"

                + "?start_date=" + ISO_DATE.format(startDate)

                + "&end_date=" + ISO_DATE.format(endDate)

                + "&limit=1"

                + "&offset=0"

                + "&embed=receivers,items"

                + "&order_status=" + CAFE24_DISPATCH_STATUS_QUERY;

    }



    private String maybeUpgradeCafe24ApiVersionInMemory(HttpResult response, JSONObject rawCredential, String currentApiVersion) {

        if (response == null || response.statusCode != 400) {

            return currentApiVersion;

        }

        String responseBody = response.body == null ? "" : response.body;

        if (!responseBody.contains("version you requested is not available")) {

            return currentApiVersion;

        }



        String fallbackApiVersion = extractCafe24SuggestedApiVersion(responseBody);

        if (fallbackApiVersion.isEmpty() || fallbackApiVersion.equals(currentApiVersion)) {

            return currentApiVersion;

        }



        try {

            rawCredential.put("ApiVersion", fallbackApiVersion);

            rawCredential.put("UpdatedAt", Instant.now().toString());

        } catch (Exception ignored) {

        }

        return fallbackApiVersion;

    }



    private String maybeUpgradeCafe24ApiVersion(HttpResult response, JSONObject rawCredential, String slot, String currentApiVersion) {

        String fallbackApiVersion = maybeUpgradeCafe24ApiVersionInMemory(response, rawCredential, currentApiVersion);

        if (fallbackApiVersion.equals(currentApiVersion)) {

            return currentApiVersion;

        }



        try {

            credentialStore.saveCafe24Json(slot, rawCredential.toString());

        } catch (Exception ignored) {

        }

        return fallbackApiVersion;

    }


    private String extractCafe24SuggestedApiVersion(String responseBody) {

        if (responseBody == null || responseBody.trim().isEmpty()) {

            return "";

        }

        Matcher matcher = CAFE24_API_VERSION_PATTERN.matcher(responseBody);

        String lastMatch = "";

        while (matcher.find()) {

            lastMatch = matcher.group(1);

        }

        return lastMatch;

    }



    private Map<String, String> buildCafe24Headers(String accessToken, String apiVersion) {

        Map<String, String> headers = new LinkedHashMap<>();

        headers.put("Authorization", "Bearer " + accessToken);

        headers.put("X-Cafe24-Api-Version", apiVersion.isEmpty() ? DEFAULT_CAFE24_API_VERSION : apiVersion);

        headers.put("Accept", "application/json");

        return headers;

    }



    private HttpResult executeJsonRequest(String method, String url, String body, Map<String, String> headers) throws Exception {

        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();

        connection.setRequestMethod(method);

        connection.setConnectTimeout(30000);

        connection.setReadTimeout(30000);

        // Content-Type은 body가 있을 때만 설정 (GET 요청에 설정하면 일부 서버가 4xx 반환)

        if (body != null) {

            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");

        }

        for (Map.Entry<String, String> entry : headers.entrySet()) {

            connection.setRequestProperty(entry.getKey(), entry.getValue());

        }



        if (body != null) {

            connection.setDoOutput(true);

            try (OutputStream outputStream = connection.getOutputStream()) {

                outputStream.write(body.getBytes(StandardCharsets.UTF_8));

            }

        }



        return readResponse(connection);

    }



    private HttpResult executeFormRequest(String url, Map<String, String> form, Map<String, String> headers) throws Exception {

        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();

        connection.setRequestMethod("POST");

        connection.setConnectTimeout(30000);

        connection.setReadTimeout(30000);

        connection.setDoOutput(true);

        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");

        for (Map.Entry<String, String> entry : headers.entrySet()) {

            connection.setRequestProperty(entry.getKey(), entry.getValue());

        }



        String payload = buildQueryString(form);

        try (OutputStream outputStream = connection.getOutputStream();

             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8))) {

            writer.write(payload);

        }



        return readResponse(connection);

    }



    private HttpResult readResponse(HttpURLConnection connection) throws Exception {

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

        connection.disconnect();

        return new HttpResult(code, builder.toString());

    }



    private String buildQueryString(Map<String, String> query) {

        Uri.Builder builder = new Uri.Builder();

        for (Map.Entry<String, String> entry : query.entrySet()) {

            builder.appendQueryParameter(entry.getKey(), entry.getValue());

        }

        String encoded = builder.build().getEncodedQuery();

        return encoded == null ? "" : encoded;

    }



    private String createCoupangSignature(String secretKey, String signedDate, String method, String path, String queryString) throws Exception {

        String message = signedDate + method.toUpperCase(Locale.US) + path + queryString;

        Mac mac = Mac.getInstance("HmacSHA256");

        mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));

        byte[] hash = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));

        StringBuilder builder = new StringBuilder();

        for (byte value : hash) {

            builder.append(String.format(Locale.US, "%02x", value));

        }

        return builder.toString();

    }



    // ─────────────────────────────────────────────

    //  출고처리 화면 전용: 주문 조회

    // ─────────────────────────────────────────────



    public List<DispatchOrder> fetchOrdersForDispatch(String marketKey, LocalDate start, LocalDate end) throws Exception {

        if ("coupang".equals(marketKey)) {

            return fetchCoupangDispatchOrders(start, end);

        }



        Cafe24MarketConfig config = credentialStore.getCafe24Market(marketKey);

        if (config == null) {

            throw new Exception("판매처 설정을 찾지 못했습니다.");

        }

        return fetchCafe24DispatchOrders(config.key, config.buildMarketLabel(), config.displayName, start, end);

    }



    private List<DispatchOrder> fetchCafe24DispatchOrders(String slot, String label, String marketName, LocalDate start, LocalDate end) throws Exception {

        String rawJson = getCafe24JsonForUse(slot);

        if (rawJson.isEmpty()) throw new Exception(label + " 키가 없습니다.");



        JSONObject cred = new JSONObject(rawJson);

        String mallId       = cred.optString("MallId", "").trim();

        String accessToken  = cred.optString("AccessToken", "").trim();

        String apiVersion   = cred.optString("ApiVersion", DEFAULT_CAFE24_API_VERSION).trim();

        if (mallId.isEmpty()) throw new Exception("MallId가 없습니다.");
        if (accessToken.isEmpty()) {
            throw new Exception(CAFE24_EXTERNAL_AUTH_REQUIRED);
        }



        List<DispatchOrder> orders = new ArrayList<>();

        int offset = 0;

        final int limit = 100;

        boolean versionRetried = false;



        while (true) {

            String url = "https://" + mallId + ".cafe24api.com/api/v2/admin/orders"

                    + "?start_date=" + ISO_DATE.format(start)

                    + "&end_date=" + ISO_DATE.format(end)

                    + "&limit=" + limit

                    + "&offset=" + offset

                    + "&embed=receivers,items"

                    + "&order_status=" + CAFE24_DISPATCH_STATUS_QUERY;



            HttpResult resp = executeJsonRequest("GET", url, null, buildCafe24Headers(accessToken, apiVersion));

            String fallbackApiVersion = maybeUpgradeCafe24ApiVersion(resp, cred, slot, apiVersion);

            if (!fallbackApiVersion.equals(apiVersion) && !versionRetried) {

                apiVersion = fallbackApiVersion;

                versionRetried = true;

                continue;

            }
            if (resp.statusCode == 401 || resp.statusCode == 403) {
                throw new Exception(CAFE24_EXTERNAL_AUTH_REQUIRED + " 응답: " + clip(resp.body));
            }

            if (!resp.isSuccessful()) throw new Exception("조회 실패 " + resp.statusCode + ": " + clip(resp.body));



            JSONObject root = new JSONObject(resp.body);

            JSONArray arr = root.optJSONArray("orders");

            if (arr == null || arr.length() == 0) break;



            for (int i = 0; i < arr.length(); i++) {

                JSONObject order = arr.optJSONObject(i);

                if (order == null) continue;

                String orderId = order.optString("order_id", "");

                JSONArray receivers = order.optJSONArray("receivers");

                JSONObject rcv = receivers != null && receivers.length() > 0 ? receivers.optJSONObject(0) : null;

                String recipient = opt(rcv, "name", opt(order, "receiver_name", opt(order, "buyer_name", "")));

                String recipientCellPhone = PhoneNormalizer.normalize(

                        opt(rcv, "cellphone",

                                opt(rcv, "receiver_cellphone",

                                        opt(order, "receiver_cellphone", opt(order, "buyer_cellphone", "")))));

                String recipientPhone = PhoneNormalizer.normalize(

                        opt(rcv, "phone",

                                opt(rcv, "receiver_phone",

                                        opt(order, "receiver_phone", opt(order, "buyer_phone", "")))));

                String orderDate = order.optString("order_date", "");

                JSONArray items = order.optJSONArray("items");

                if (items == null) continue;

                List<Cafe24ShipmentSnapshot> shipmentSnapshots = needsCafe24ShipmentFetch(order, items)
                        ? fetchCafe24ShipmentSnapshots(mallId, orderId, accessToken, apiVersion, cred, slot)
                        : Collections.emptyList();

                for (int j = 0; j < items.length(); j++) {

                    JSONObject item = items.optJSONObject(j);

                    if (item == null) continue;

                    String cafe24OrderStatus = firstNonEmpty(

                            firstNonEmpty(order.optString("order_status", ""), item.optString("order_status", "")),

                            "N20");

                    Cafe24ShipmentSnapshot shipmentSnapshot =
                            findCafe24ShipmentSnapshot(shipmentSnapshots, item.optString("order_item_code", ""));
                    if (shipmentSnapshot != null && !shipmentSnapshot.status.isEmpty()) {
                        cafe24OrderStatus = shipmentSnapshot.statusCode();
                    }

                    DispatchOrder dispatchOrder = new DispatchOrder(

                            label, slot, marketName,

                            orderId,

                            item.optString("order_item_code", ""),

                            "", // no shipmentBoxId for Cafe24

                            cafe24OrderStatus,

                            recipient,

                            item.optString("product_name", ""),

                            item.optInt("quantity", 0),

                            formatDisplayTime(orderDate),

                            extractCafe24Amount(order, item, item.optInt("quantity", 0)),

                            recipientCellPhone,

                            recipientPhone

                    );

                    dispatchOrder.marketSourceLabel = firstNonEmpty(

                            opt(order, "order_place_name", ""),

                            firstNonEmpty(opt(order, "market_id", ""), opt(order, "order_place_id", ""))

                    );

                    dispatchOrder.marketOrderReference = firstNonEmpty(opt(order, "market_order_no", ""), orderId);
                    if (shipmentSnapshot != null) {
                        dispatchOrder.cafe24ShippingCode = shipmentSnapshot.shippingCode;
                        if (!shipmentSnapshot.trackingNumber.isEmpty()) {
                            dispatchOrder.trackingNumber = shipmentSnapshot.trackingNumber;
                        }
                        if (!shipmentSnapshot.shippingCompanyName.isEmpty()) {
                            dispatchOrder.shippingCompanyName = shipmentSnapshot.shippingCompanyName;
                        }
                    }

                    orders.add(dispatchOrder);

                }

            }

            if (arr.length() < limit) break;

            offset += limit;

        }

        return orders;

    }

    private boolean needsCafe24ShipmentFetch(JSONObject order, JSONArray items) {
        if (isCafe24ShipmentStatus(firstNonEmpty(order.optString("order_status", ""), order.optString("status", "")))) {
            return true;
        }
        if (items == null) {
            return false;
        }
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && isCafe24ShipmentStatus(item.optString("order_status", ""))) {
                return true;
            }
        }
        return false;
    }

    private boolean isCafe24ShipmentStatus(String status) {
        String normalized = status == null ? "" : status.trim().toUpperCase(Locale.US);
        return "N21".equals(normalized)
                || "N30".equals(normalized)
                || "STANDBY".equals(normalized)
                || "SHIPPING".equals(normalized)
                || "SHIPPED".equals(normalized);
    }

    private List<Cafe24ShipmentSnapshot> fetchCafe24ShipmentSnapshots(
            String mallId,
            String orderId,
            String accessToken,
            String apiVersion,
            JSONObject cred,
            String slot
    ) {
        String url = "https://" + mallId + ".cafe24api.com/api/v2/admin/orders/" + orderId + "/shipments";
        boolean versionRetried = false;

        try {
            while (true) {
                HttpResult response = executeJsonRequest("GET", url, null, buildCafe24Headers(accessToken, apiVersion));
                if (response.isSuccessful()) {
                    return parseCafe24ShipmentSnapshots(response.body);
                }

                String fallbackApiVersion = maybeUpgradeCafe24ApiVersion(response, cred, slot, apiVersion);
                if (!fallbackApiVersion.equals(apiVersion) && !versionRetried) {
                    apiVersion = fallbackApiVersion;
                    versionRetried = true;
                    continue;
                }
                if (response.statusCode == 401 || response.statusCode == 403) {
                    return Collections.emptyList();
                }
                return Collections.emptyList();
            }
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private List<Cafe24ShipmentSnapshot> parseCafe24ShipmentSnapshots(String body) throws Exception {
        List<Cafe24ShipmentSnapshot> result = new ArrayList<>();
        JSONObject root = new JSONObject(body == null ? "{}" : body);
        JSONArray shipments = root.optJSONArray("shipments");
        if (shipments == null) {
            JSONObject shipment = root.optJSONObject("shipment");
            if (shipment != null) {
                shipments = new JSONArray();
                shipments.put(shipment);
            }
        }
        if (shipments == null) {
            return result;
        }

        for (int i = 0; i < shipments.length(); i++) {
            JSONObject shipment = shipments.optJSONObject(i);
            if (shipment == null) {
                continue;
            }
            String shippingCode = firstNonEmpty(
                    shipment.optString("shipping_code", ""),
                    shipment.optString("shippingCode", ""),
                    shipment.optString("shipment_code", "")
            );
            String status = firstNonEmpty(
                    shipment.optString("status", ""),
                    shipment.optString("shipping_status", ""),
                    shipment.optString("order_status", "")
            );
            String trackingNumber = normalizeUploadTracking(firstNonEmpty(
                    shipment.optString("tracking_no", ""),
                    shipment.optString("tracking_number", ""),
                    shipment.optString("invoice_no", "")
            ));
            String shippingCompanyName = firstNonEmpty(
                    shipment.optString("shipping_company_name", ""),
                    shipment.optString("shipping_company_code", "")
            );
            JSONArray items = firstJsonArray(shipment, "items", "order_items", "order_item");
            if (items == null || items.length() == 0) {
                result.add(new Cafe24ShipmentSnapshot("", shippingCode, status, trackingNumber, shippingCompanyName));
                continue;
            }
            for (int j = 0; j < items.length(); j++) {
                JSONObject item = items.optJSONObject(j);
                if (item == null) {
                    continue;
                }
                String orderItemCode = firstNonEmpty(
                        item.optString("order_item_code", ""),
                        item.optString("item_code", ""),
                        item.optString("orderItemCode", "")
                );
                result.add(new Cafe24ShipmentSnapshot(orderItemCode, shippingCode, status, trackingNumber, shippingCompanyName));
            }
        }
        return result;
    }

    private JSONArray firstJsonArray(JSONObject object, String... keys) {
        if (object == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            JSONArray array = object.optJSONArray(key);
            if (array != null) {
                return array;
            }
        }
        return null;
    }

    private Cafe24ShipmentSnapshot findCafe24ShipmentSnapshot(List<Cafe24ShipmentSnapshot> snapshots, String orderItemCode) {
        if (snapshots == null || snapshots.isEmpty()) {
            return null;
        }
        String itemCode = orderItemCode == null ? "" : orderItemCode.trim();
        Cafe24ShipmentSnapshot fallback = null;
        for (Cafe24ShipmentSnapshot snapshot : snapshots) {
            if (snapshot == null) {
                continue;
            }
            if (snapshot.orderItemCode.isEmpty() && fallback == null) {
                fallback = snapshot;
                continue;
            }
            if (!itemCode.isEmpty() && itemCode.equals(snapshot.orderItemCode)) {
                return snapshot;
            }
        }
        return fallback;
    }



    private List<DispatchOrder> fetchCoupangDispatchOrders(LocalDate start, LocalDate end) throws Exception {

        CoupangCredentials creds = credentialStore.getCoupangCredentials();

        if (!creds.isComplete()) throw new Exception("쿠팡 키가 없습니다.");



        List<DispatchOrder> orders = new ArrayList<>();

        for (String status : new String[]{"ACCEPT", "INSTRUCT"}) {

            String nextToken = null;

            do {

                Map<String, String> query = new LinkedHashMap<>();

                query.put("createdAtFrom", ISO_DATE.format(start) + "+09:00");

                query.put("createdAtTo",   ISO_DATE.format(end)   + "+09:00");

                query.put("status", status);

                query.put("maxPerPage", "50");

                if (nextToken != null) query.put("nextToken", nextToken);



                String path = "/v2/providers/openapi/apis/api/v5/vendors/" + creds.getVendorId() + "/ordersheets";

                String qs = buildQueryString(query);

                String signedDate = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)

                        .format(DateTimeFormatter.ofPattern("yyMMdd'T'HHmmss'Z'", Locale.US));

                String sig = createCoupangSignature(creds.getSecretKey(), signedDate, "GET", path, qs);

                Map<String, String> headers = new LinkedHashMap<>();

                headers.put("Authorization", "CEA algorithm=HmacSHA256, access-key=" + creds.getAccessKey() + ", signed-date=" + signedDate + ", signature=" + sig);

                headers.put("X-EXTENDED-TIMEOUT", "90000");

                headers.put("Accept", "application/json");



                HttpResult resp = executeJsonRequest("GET", "https://api-gateway.coupang.com" + path + (qs.isEmpty() ? "" : "?" + qs), null, headers);

                if (!resp.isSuccessful()) throw new Exception("쿠팡 조회 실패 " + resp.statusCode + ": " + clip(resp.body));



                JSONObject root = new JSONObject(resp.body);

                JSONArray data = root.optJSONArray("data");

                if (data == null || data.length() == 0) { nextToken = null; break; }



                for (int i = 0; i < data.length(); i++) {

                    JSONObject sheet = data.optJSONObject(i);

                    if (sheet == null) continue;

                    String orderId      = sheet.optString("orderId", "");

                    String boxId        = sheet.optString("shipmentBoxId", "");

                    String orderedAt    = sheet.optString("orderedAt", "");

                    JSONObject rcv      = sheet.optJSONObject("receiver");

                    String recipient    = opt(rcv, "name", "");

                    String recipientCellPhone = PhoneNormalizer.normalize(opt(rcv, "safeNumber", opt(rcv, "receiverNumber", "")));

                    String recipientPhone = PhoneNormalizer.normalize(opt(rcv, "receiverNumber", ""));

                    JSONArray items     = sheet.optJSONArray("orderItems");

                    if (items == null) continue;

                    for (int j = 0; j < items.length(); j++) {

                        JSONObject item = items.optJSONObject(j);

                        if (item == null) continue;

                        String vendorItemId  = String.valueOf(item.optLong("vendorItemId", 0));

                        String sellerName    = item.optString("sellerProductName", "");

                        String variantName   = item.optString("sellerProductItemName", "");

                        String productName   = sellerName.isEmpty() ? variantName : sellerName + (variantName.isEmpty() || variantName.equals(sellerName) ? "" : " / " + variantName);

                        int    qty           = item.has("shippingCount") ? item.optInt("shippingCount", 0) : item.optInt("orderCount", 0);

                        DispatchOrder dispatchOrder = new DispatchOrder(

                                "홈런마켓 / 쿠팡", "coupang",

                                orderId, vendorItemId, boxId, status,

                                recipient, productName, qty,

                                formatDisplayTime(orderedAt),

                                extractCoupangAmount(sheet, item, qty),

                                recipientCellPhone,

                                recipientPhone

                        );

                        dispatchOrder.marketSourceLabel = "쿠팡";

                        dispatchOrder.marketOrderReference = orderId;

                        orders.add(dispatchOrder);

                    }

                }



                nextToken = root.optString("nextToken", "");

                if (nextToken.isEmpty()) nextToken = null;

            } while (nextToken != null);

        }

        return orders;

    }



    // ─────────────────────────────────────────────

    //  출고처리 화면 전용: 송장 전송

    // ─────────────────────────────────────────────



    /** @return 빈 문자열이면 성공, 아니면 오류 메시지 */

    public String pushTrackingNumber(DispatchOrder order, String shippingCode) {

        try {

            if ("coupang".equals(order.marketKey)) {

                return pushCoupangTracking(order, shippingCode);

            } else {

                return pushCafe24Tracking(order, shippingCode);

            }

        } catch (Exception ex) {

            return ex.getMessage();

        }

    }

    /** @return 빈 문자열이면 성공, 아니면 오류 메시지 */
    public String pushTrackingStandby(DispatchOrder order, String shippingCode) {

        try {

            if ("coupang".equals(order.marketKey)) {

                return "쿠팡은 송장대기 API 대상이 아닙니다.";

            }

            return pushCafe24Shipment(order, shippingCode, "standby");

        } catch (Exception ex) {

            return ex.getMessage();

        }

    }



    private String pushCafe24Tracking(DispatchOrder order, String shippingCode) throws Exception {

        return pushCafe24Shipment(order, shippingCode, "shipping");

    }

    private String pushCafe24Shipment(DispatchOrder order, String shippingCode, String status) throws Exception {

        String rawJson = getCafe24JsonForUse(order.marketKey);

        if (rawJson.isEmpty()) return "Cafe24 키 없음";

        JSONObject cred = new JSONObject(rawJson);

        String mallId       = cred.optString("MallId", "").trim();

        String accessToken  = cred.optString("AccessToken", "").trim();

        String apiVersion   = cred.optString("ApiVersion", DEFAULT_CAFE24_API_VERSION).trim();



        String url = "https://" + mallId + ".cafe24api.com/api/v2/admin/orders/" + order.orderId + "/shipments";

        String requestedTracking = normalizeUploadTracking(order.trackingNumber);
        String requestedStatus = "standby".equals(status) ? "standby" : "shipping";

        if ("shipping".equals(requestedStatus) && !order.cafe24ShippingCode.trim().isEmpty()) {
            return updateCafe24ShipmentStatus(
                    mallId,
                    order,
                    order.cafe24ShippingCode.trim(),
                    requestedStatus,
                    accessToken,
                    apiVersion,
                    cred
            );
        }

        String body = "{\"shop_no\":1,\"request\":{\"order_item_code\":[\"" + order.orderItemCode + "\"],"

                + "\"tracking_no\":\"" + requestedTracking + "\","

                + "\"shipping_company_code\":\"" + shippingCode + "\","

                + "\"status\":\"" + requestedStatus + "\"}}";

        boolean versionRetried = false;



        while (true) {

            HttpResult resp = executeJsonRequest("POST", url, body, buildCafe24Headers(accessToken, apiVersion));

            if (resp.isSuccessful()) return "";



            String fallbackApiVersion = maybeUpgradeCafe24ApiVersion(resp, cred, order.marketKey, apiVersion);

            if (!fallbackApiVersion.equals(apiVersion) && !versionRetried) {

                apiVersion = fallbackApiVersion;

                versionRetried = true;

                continue;

            }
            if (resp.statusCode == 401 || resp.statusCode == 403) {
                return CAFE24_EXTERNAL_AUTH_REQUIRED + " 응답: " + clip(resp.body);
            }



            if ("shipping".equals(requestedStatus)) {
                Cafe24ShipmentSnapshot existingShipment = findCafe24ShipmentSnapshot(
                        fetchCafe24ShipmentSnapshots(mallId, order.orderId, accessToken, apiVersion, cred,
                                order.marketKey),
                        order.orderItemCode
                );
                if (existingShipment != null && !existingShipment.shippingCode.isEmpty()) {
                    order.cafe24ShippingCode = existingShipment.shippingCode;
                    if (!existingShipment.trackingNumber.isEmpty()) {
                        order.trackingNumber = existingShipment.trackingNumber;
                    }
                    if (!existingShipment.shippingCompanyName.isEmpty()) {
                        order.shippingCompanyName = existingShipment.shippingCompanyName;
                    }
                    return updateCafe24ShipmentStatus(
                            mallId,
                            order,
                            existingShipment.shippingCode,
                            requestedStatus,
                            accessToken,
                            apiVersion,
                            cred
                    );
                }
            }



            String resolvedMessage = resolveCafe24UploadConflict(resp, mallId, order, requestedTracking, requestedStatus, accessToken, apiVersion, cred, order.marketKey);

            if (resolvedMessage != null) {

                return resolvedMessage;

            }



            return "Cafe24 오류 " + resp.statusCode + ": " + clip(resp.body);

        }

    }

    private String updateCafe24ShipmentStatus(
            String mallId,
            DispatchOrder order,
            String cafe24ShippingCode,
            String requestedStatus,
            String accessToken,
            String apiVersion,
            JSONObject cred
    ) throws Exception {
        String url = "https://" + mallId + ".cafe24api.com/api/v2/admin/orders/"
                + order.orderId + "/shipments/" + cafe24ShippingCode;
        String body = "{\"shop_no\":1,\"request\":{\"status\":\"" + requestedStatus + "\"}}";
        boolean versionRetried = false;

        while (true) {
            HttpResult response = executeJsonRequest("PUT", url, body, buildCafe24Headers(accessToken, apiVersion));
            if (response.isSuccessful()) {
                return "";
            }

            String fallbackApiVersion = maybeUpgradeCafe24ApiVersion(response, cred, order.marketKey, apiVersion);
            if (!fallbackApiVersion.equals(apiVersion) && !versionRetried) {
                apiVersion = fallbackApiVersion;
                versionRetried = true;
                continue;
            }
            if (response.statusCode == 401 || response.statusCode == 403) {
                return CAFE24_EXTERNAL_AUTH_REQUIRED + " 응답: " + clip(response.body);
            }

            String resolvedMessage = resolveCafe24UploadConflict(
                    response,
                    mallId,
                    order,
                    normalizeUploadTracking(order.trackingNumber),
                    requestedStatus,
                    accessToken,
                    apiVersion,
                    cred,
                    order.marketKey
            );
            if (resolvedMessage != null) {
                return resolvedMessage;
            }

            return "Cafe24 오류 " + response.statusCode + ": " + clip(response.body);
        }
    }

    private String resolveCafe24UploadConflict(
            HttpResult response,
            String mallId,
            DispatchOrder order,
            String requestedTracking,
            String requestedStatus,
            String accessToken,
            String apiVersion,
            JSONObject cred,
            String slot
    ) throws Exception {

        if (response == null || response.statusCode != 422) {
            return null;
        }

        String body = response.body == null ? "" : response.body;
        if (!body.contains("You cannot change to that order state")) {
            return null;
        }

        Cafe24OrderItemSnapshot snapshot = fetchCafe24OrderItemSnapshot(
                mallId,
                order.orderId,
                order.orderItemCode,
                accessToken,
                apiVersion,
                cred,
                slot
        );
        if (snapshot == null) {
            return "이미 배송 처리된 주문일 수 있습니다. 새로고침 후 다시 확인하세요.";
        }

        if (!snapshot.trackingNumber.isEmpty()) {
            order.trackingNumber = snapshot.trackingNumber;
        }
        if (!snapshot.shippingCompanyName.isEmpty()) {
            order.shippingCompanyName = snapshot.shippingCompanyName;
        }

        if ("standby".equals(requestedStatus) && snapshot.isStandbyDone()) {
            if (!snapshot.trackingNumber.isEmpty() && snapshot.trackingNumber.equals(requestedTracking)) {
                return "";
            }
            if (!snapshot.trackingNumber.isEmpty()) {
                return "이미 송장대기 상태입니다. 현재 송장: " + snapshot.trackingNumber;
            }
            return "이미 송장대기 상태입니다. 새로고침 후 확인하세요.";
        }

        if (snapshot.isShippingDone()) {
            if (!snapshot.trackingNumber.isEmpty() && snapshot.trackingNumber.equals(requestedTracking)) {
                return "";
            }
            if (!snapshot.trackingNumber.isEmpty()) {
                return "이미 배송중인 주문입니다. 현재 송장: " + snapshot.trackingNumber;
            }
            return "이미 배송중인 주문입니다. 새로고침 후 목록에서 제외되는지 확인하세요.";
        }

        String statusLabel = snapshot.statusLabel();
        if (!statusLabel.isEmpty()) {
            return "현재 Cafe24 상태가 업로드 가능 상태가 아닙니다: " + statusLabel;
        }
        return "현재 Cafe24 상태에서 업로드할 수 없습니다. 새로고침 후 다시 확인하세요.";
    }

    private Cafe24OrderItemSnapshot fetchCafe24OrderItemSnapshot(
            String mallId,
            String orderId,
            String orderItemCode,
            String accessToken,
            String apiVersion,
            JSONObject cred,
            String slot
    ) throws Exception {

        String url = "https://" + mallId + ".cafe24api.com/api/v2/admin/orders/" + orderId + "?embed=items";
        boolean versionRetried = false;

        while (true) {
            HttpResult response = executeJsonRequest("GET", url, null, buildCafe24Headers(accessToken, apiVersion));
            if (response.isSuccessful()) {
                JSONObject root = new JSONObject(response.body);
                JSONObject order = root.optJSONObject("order");
                if (order == null) {
                    return null;
                }
                JSONArray items = order.optJSONArray("items");
                if (items == null) {
                    return null;
                }
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    if (!orderItemCode.equals(item.optString("order_item_code", ""))) {
                        continue;
                    }
                    return new Cafe24OrderItemSnapshot(
                            item.optString("order_status", ""),
                            firstNonEmpty(item.optString("status_text", ""), item.optString("order_status", "")),
                            normalizeUploadTracking(item.optString("tracking_no", "")),
                            firstNonEmpty(item.optString("shipping_company_name", ""), item.optString("shipping_company_code", "")),
                            item.optString("shipped_date", "")
                    );
                }
                return null;
            }

            String fallbackApiVersion = maybeUpgradeCafe24ApiVersion(response, cred, slot, apiVersion);
            if (!fallbackApiVersion.equals(apiVersion) && !versionRetried) {
                apiVersion = fallbackApiVersion;
                versionRetried = true;
                continue;
            }
            if (response.statusCode == 401 || response.statusCode == 403) {
                return null;
            }

            return null;
        }
    }

    private String normalizeUploadTracking(String trackingNumber) {
        return trackingNumber == null ? "" : trackingNumber.replaceAll("[^a-zA-Z0-9]", "");
    }

    private static final class Cafe24ShipmentSnapshot {
        final String orderItemCode;
        final String shippingCode;
        final String status;
        final String trackingNumber;
        final String shippingCompanyName;

        Cafe24ShipmentSnapshot(String orderItemCode, String shippingCode, String status,
                               String trackingNumber, String shippingCompanyName) {
            this.orderItemCode = orderItemCode == null ? "" : orderItemCode.trim();
            this.shippingCode = shippingCode == null ? "" : shippingCode.trim();
            this.status = status == null ? "" : status.trim();
            this.trackingNumber = trackingNumber == null ? "" : trackingNumber.trim();
            this.shippingCompanyName = shippingCompanyName == null ? "" : shippingCompanyName.trim();
        }

        String statusCode() {
            String normalized = status.toUpperCase(Locale.US);
            if ("STANDBY".equals(normalized)) {
                return "N21";
            }
            if ("SHIPPING".equals(normalized) || "SHIPPED".equals(normalized)) {
                return "N30";
            }
            return status;
        }
    }

    private static final class Cafe24OrderItemSnapshot {
        final String orderStatus;
        final String statusText;
        final String trackingNumber;
        final String shippingCompanyName;
        final String shippedDate;

        Cafe24OrderItemSnapshot(String orderStatus, String statusText, String trackingNumber, String shippingCompanyName, String shippedDate) {
            this.orderStatus = orderStatus == null ? "" : orderStatus.trim();
            this.statusText = statusText == null ? "" : statusText.trim();
            this.trackingNumber = trackingNumber == null ? "" : trackingNumber.trim();
            this.shippingCompanyName = shippingCompanyName == null ? "" : shippingCompanyName.trim();
            this.shippedDate = shippedDate == null ? "" : shippedDate.trim();
        }

        boolean isShippingDone() {
            return "N30".equalsIgnoreCase(orderStatus)
                    || statusText.contains("배송중")
                    || !shippedDate.isEmpty();
        }

        boolean isStandbyDone() {
            return "N21".equalsIgnoreCase(orderStatus)
                    || statusText.contains("배송대기")
                    || statusText.toLowerCase(Locale.US).contains("standby");
        }

        String statusLabel() {
            if (!statusText.isEmpty()) {
                return statusText;
            }
            return orderStatus;
        }
    }

    private String pushCoupangTracking(DispatchOrder order, String deliveryCode) throws Exception {

        CoupangCredentials creds = credentialStore.getCoupangCredentials();

        if (!creds.isComplete()) return "쿠팡 키 없음";

        if (order.shipmentBoxId.isEmpty()) return "shipmentBoxId 없음";



        String currentStatus = queryCoupangOrderStatus(order.shipmentBoxId, creds);

        if ("ACCEPT".equalsIgnoreCase(currentStatus)) {

            String ackPath = "/v2/providers/openapi/apis/api/v4/vendors/" + creds.getVendorId() + "/ordersheets/acknowledgement";

            String ackBody = "{\"vendorId\":\"" + creds.getVendorId() + "\",\"shipmentBoxIds\":[" + order.shipmentBoxId + "]}";

            HttpResult ackResp = executeCoupangRequest("PUT", ackPath, "", ackBody, creds);

            String ackError = CoupangUploadResponseParser.parseError("출고지시", order.shipmentBoxId, ackResp.statusCode, ackResp.body);

            if (!ackError.isEmpty()) return ackError;



            currentStatus = waitForCoupangStatus(order.shipmentBoxId, "INSTRUCT", creds, 5, 1500L);

        }



        if (!"INSTRUCT".equalsIgnoreCase(currentStatus)) {

            if ("ACCEPT".equalsIgnoreCase(currentStatus)) {

                return "출고지시는 접수됐지만 쿠팡 상태가 아직 상품준비(INSTRUCT)로 바뀌지 않았습니다. 잠시 후 다시 시도하세요.";

            }

            return "송장 업로드 가능한 쿠팡 상태가 아닙니다. 현재 상태: " + currentStatus;

        }



        String invPath = "/v2/providers/openapi/apis/api/v4/vendors/" + creds.getVendorId() + "/orders/invoices";

        String trackNorm = order.trackingNumber.replaceAll("[^a-zA-Z0-9]", "");

        String invBody = "{\"vendorId\":\"" + creds.getVendorId() + "\",\"orderSheetInvoiceApplyDtos\":[{"

                + "\"shipmentBoxId\":" + order.shipmentBoxId + ","

                + "\"orderId\":" + order.orderId + ","

                + "\"vendorItemId\":" + order.orderItemCode + ","

                + "\"deliveryCompanyCode\":\"" + deliveryCode + "\","

                + "\"invoiceNumber\":\"" + trackNorm + "\","

                + "\"splitShipping\":false,\"preSplitShipped\":false,\"estimatedShippingDate\":\"\""

                + "}]}";



        HttpResult invResp = executeCoupangRequest("POST", invPath, "", invBody, creds);

        return CoupangUploadResponseParser.parseError("송장 업로드", order.shipmentBoxId, invResp.statusCode, invResp.body);

    }



    static String parseCoupangActionError(String actionLabel, String shipmentBoxId, int httpStatus, String body) {

        if (httpStatus < 200 || httpStatus >= 300) {

            return actionLabel + " 실패 " + httpStatus + ": " + clipCoupangBody(body);

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

            String normalizedShipmentBoxId = normalizeCoupangNumericId(shipmentBoxId);



            if (responseList != null) {

                for (int i = 0; i < responseList.length(); i++) {

                    JSONObject item = responseList.optJSONObject(i);

                    if (item == null) {

                        continue;

                    }



                    String itemShipmentBoxId = normalizeCoupangNumericId(item.optString("shipmentBoxId", String.valueOf(item.optLong("shipmentBoxId", 0L))));

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

                return actionLabel + " 실패: " + firstNonEmpty(responseMessage, clipCoupangBody(body));

            }

        } catch (Exception ignored) {

        }

        return "";

    }



    private String waitForCoupangStatus(String shipmentBoxId, String targetStatus, CoupangCredentials creds, int attempts, long sleepMillis) throws Exception {

        String currentStatus = "";

        for (int i = 0; i < attempts; i++) {

            currentStatus = queryCoupangOrderStatus(shipmentBoxId, creds);

            if (targetStatus.equalsIgnoreCase(currentStatus)) {

                return currentStatus;

            }

            if (i + 1 < attempts) {

                try {

                    Thread.sleep(sleepMillis);

                } catch (InterruptedException ex) {

                    Thread.currentThread().interrupt();

                    break;

                }

            }

        }

        return currentStatus;

    }



    private String queryCoupangOrderStatus(String shipmentBoxId, CoupangCredentials creds) throws Exception {

        String path = "/v2/providers/openapi/apis/api/v5/vendors/" + creds.getVendorId() + "/ordersheets/" + shipmentBoxId;

        HttpResult response = executeCoupangRequest("GET", path, "", null, creds);

        if (!response.isSuccessful()) {

            throw new Exception("쿠팡 주문 상태 조회 실패 " + response.statusCode + ": " + clip(response.body));

        }



        JSONObject root = new JSONObject(response.body);

        JSONObject data = root.optJSONObject("data");

        String status = data == null ? "" : data.optString("status", "");

        if (status.isEmpty()) {

            throw new Exception("쿠팡 주문 상태를 확인하지 못했습니다.");

        }

        return status;

    }



    private static String normalizeCoupangNumericId(String value) {

        return value == null ? "" : value.replaceAll("[^0-9]", "");

    }



    private static String clipCoupangBody(String body) {

        if (body == null) {

            return "";

        }

        return body.length() > 180 ? body.substring(0, 180) : body;

    }





    private HttpResult executeCoupangRequest(String method, String path, String queryString,

                                              String body, CoupangCredentials creds) throws Exception {

        String signedDate = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)

                .format(DateTimeFormatter.ofPattern("yyMMdd'T'HHmmss'Z'", Locale.US));

        String sig = createCoupangSignature(creds.getSecretKey(), signedDate, method, path, queryString);

        Map<String, String> headers = new LinkedHashMap<>();

        headers.put("Authorization", "CEA algorithm=HmacSHA256, access-key=" + creds.getAccessKey() + ", signed-date=" + signedDate + ", signature=" + sig);

        headers.put("Accept", "application/json");

        return executeJsonRequest(method, "https://api-gateway.coupang.com" + path, body, headers);

    }



    private List<ShipmentSummary> dedupeShipments(List<ShipmentSummary> shipments) {

        Map<String, ShipmentSummary> unique = new LinkedHashMap<>();

        for (ShipmentSummary shipment : shipments) {

            String key = shipment.getMarketLabel() + '|' + shipment.getOrderId() + '|' + shipment.getTrackingNumber();

            if (!unique.containsKey(key)) {

                unique.put(key, shipment);

            }

        }

        return new ArrayList<>(unique.values());

    }



    private String joinDistinct(String... values) {

        List<String> collected = new ArrayList<>();

        for (String value : values) {

            if (value == null) {

                continue;

            }

            String trimmed = value.trim();

            if (trimmed.isEmpty() || collected.contains(trimmed)) {

                continue;

            }

            collected.add(trimmed);

        }

        return String.join(" / ", collected);

    }



    private static String firstNonEmpty(String... values) {

        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return "";

    }



    private String buildQuantitySuffix(int quantity) {

        return quantity > 0 ? " · " + quantity + "개" : "";

    }



    private String extractCafe24Amount(JSONObject order, JSONObject item, int quantity) {

        long amount = firstPositiveAmount(item,

                "order_price_amount",

                "payment_amount",

                "total_amount",

                "actual_price",

                "order_price",

                "sale_price");

        if (amount <= 0) {

            amount = firstPositiveAmount(order,

                    "actual_order_amount",

                    "actual_payment_amount",

                    "payment_amount",

                    "total_amount",

                    "order_amount");

        }

        if (amount <= 0) {

            long unitAmount = firstPositiveAmount(item,

                    "product_price",

                    "price",

                    "product_price_amount");

            if (unitAmount > 0 && quantity > 0) {

                amount = unitAmount * quantity;

            }

        }

        return formatAmount(amount);

    }



    private String extractCoupangAmount(JSONObject orderSheet, JSONObject item, int quantity) {

        long amount = firstPositiveAmount(item,

                "orderPrice",

                "sellingPrice",

                "salePrice",

                "paidPrice",

                "discountedPrice",

                "finalPaidPrice");

        if (amount <= 0) {

            amount = firstPositiveAmount(orderSheet,

                    "orderPrice",

                    "paidAmount",

                    "paymentAmount",

                    "totalPrice");

        }

        if (amount <= 0) {

            long unitAmount = firstPositiveAmount(item,

                    "unitPrice",

                    "vendorItemPrice",

                    "listPrice");

            if (unitAmount > 0 && quantity > 0) {

                amount = unitAmount * quantity;

            }

        }

        return formatAmount(amount);

    }



    private long firstPositiveAmount(JSONObject source, String... keys) {

        if (source == null) {

            return -1L;

        }

        for (String key : keys) {

            if (!source.has(key)) {

                continue;

            }

            long amount = parseAmountValue(source.opt(key));

            if (amount > 0) {

                return amount;

            }

        }

        return -1L;

    }



    private long parseAmountValue(Object value) {

        if (value == null || JSONObject.NULL.equals(value)) {

            return -1L;

        }

        if (value instanceof Number) {

            return Math.round(((Number) value).doubleValue());

        }

        if (value instanceof JSONObject) {

            JSONObject json = (JSONObject) value;

            long currencyAmount = parseCurrencyAmountObject(json);

            if (currencyAmount > 0) {

                return currencyAmount;

            }



            long nestedAmount = firstPositiveAmount(json,

                    "order_price_amount",

                    "payment_amount",

                    "actual_payment_amount",

                    "total_amount",

                    "order_amount",

                    "sale_price",

                    "price",

                    "amount");

            if (nestedAmount > 0) {

                return nestedAmount;

            }

            return -1L;

        }



        String raw = String.valueOf(value).trim();

        if (raw.isEmpty()) {

            return -1L;

        }

        raw = raw.replaceAll("[^0-9.-]", "");

        if (raw.isEmpty() || "-".equals(raw)) {

            return -1L;

        }



        try {

            return Math.round(Double.parseDouble(raw));

        } catch (Exception ignored) {

            return -1L;

        }

    }



    private long parseCurrencyAmountObject(JSONObject json) {

        if (!json.has("units") && !json.has("nanos")) {

            return -1L;

        }



        long units = parseAmountValue(json.opt("units"));

        long nanos = parseAmountValue(json.opt("nanos"));

        if (units < 0 && nanos < 0) {

            return -1L;

        }

        return Math.max(units, 0L) + Math.round(Math.max(nanos, 0L) / 1000000000.0d);

    }



    private String formatAmount(long amount) {

        return amount > 0 ? String.format(Locale.KOREA, "%,d원", amount) : "-";

    }



    private String mapCoupangStatus(String status) {

        if ("ACCEPT".equalsIgnoreCase(status)) {

            return "상품준비";

        }

        if ("INSTRUCT".equalsIgnoreCase(status)) {

            return "출고지시";

        }

        return status;

    }



    private String formatDisplayTime(String raw) {

        if (raw == null || raw.trim().isEmpty()) {

            return "시간 정보 없음";

        }



        try {

            return DISPLAY_TIME.format(OffsetDateTime.parse(raw).atZoneSameInstant(SEOUL));

        } catch (Exception ignored) {

        }



        try {

            return DISPLAY_TIME.format(LocalDateTime.parse(raw.replace(' ', 'T')).atZone(SEOUL));

        } catch (Exception ignored) {

        }



        if (raw.length() >= 16) {

            return raw.substring(0, 16);

        }

        return raw;

    }



    private long parseSortTime(String raw) {

        if (raw == null || raw.trim().isEmpty()) {

            return 0L;

        }



        try {

            return OffsetDateTime.parse(raw).toInstant().toEpochMilli();

        } catch (Exception ignored) {

        }



        try {

            return LocalDateTime.parse(raw.replace(' ', 'T')).atZone(SEOUL).toInstant().toEpochMilli();

        } catch (Exception ignored) {

        }



        return 0L;

    }



    private String opt(JSONObject object, String key, String fallback) {

        return object == null ? fallback : object.optString(key, fallback);

    }



    private String clip(String body) {

        if (body == null) {

            return "";

        }

        return body.length() > 180 ? body.substring(0, 180) : body;

    }



    private static class HttpResult {

        private final int statusCode;

        private final String body;



        private HttpResult(int statusCode, String body) {

            this.statusCode = statusCode;

            this.body = body == null ? "" : body;

        }



        private boolean isSuccessful() {

            return statusCode >= 200 && statusCode < 300;

        }

    }

    private static class SourceFetchResult {

        private final boolean success;

        private final String statusText;

        private final List<ShipmentSummary> shipments;



        private SourceFetchResult(boolean success, String statusText, List<ShipmentSummary> shipments) {

            this.success = success;

            this.statusText = statusText;

            this.shipments = shipments;

        }



        private static SourceFetchResult success(String statusText, List<ShipmentSummary> shipments) {

            return new SourceFetchResult(true, statusText, shipments);

        }



        private static SourceFetchResult failure(String statusText) {

            return new SourceFetchResult(false, statusText, new ArrayList<>());

        }

    }

}



























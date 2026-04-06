package com.rkghrud.shipapp.data;

import android.content.Context;
import android.net.Uri;
import android.util.Base64;

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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class LiveShipmentRepository implements ShipmentRepository {
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.KOREA);
    private static final DateTimeFormatter DISPLAY_TIME_WITH_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.KOREA);
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final Context context;
    private final CredentialStore credentialStore;

    public LiveShipmentRepository(Context context) {
        this.context = context.getApplicationContext();
        this.credentialStore = new CredentialStore(this.context);
    }

    @Override
    public ShipmentDashboardSnapshot fetchDashboard() {
        List<ShipmentSummary> shipments = new ArrayList<>();
        int connectedCount = credentialStore.getConnectedSourceCount();
        int fetchedCount = 0;

        String homeStatus = credentialStore.getCafe24Status(CredentialStore.SLOT_CAFE24_HOME, "홈런마켓 Cafe24");
        String prepareStatus = credentialStore.getCafe24Status(CredentialStore.SLOT_CAFE24_PREPARE, "준비몰 Cafe24");
        String coupangStatus = credentialStore.getCoupangStatus();

        if (credentialStore.hasCafe24Slot(CredentialStore.SLOT_CAFE24_HOME)) {
            SourceFetchResult result = fetchCafe24Source(CredentialStore.SLOT_CAFE24_HOME, "홈런마켓");
            shipments.addAll(result.shipments);
            homeStatus = result.statusText;
            if (result.success) {
                fetchedCount++;
            }
        }

        if (credentialStore.hasCafe24Slot(CredentialStore.SLOT_CAFE24_PREPARE)) {
            SourceFetchResult result = fetchCafe24Source(CredentialStore.SLOT_CAFE24_PREPARE, "준비몰");
            shipments.addAll(result.shipments);
            prepareStatus = result.statusText;
            if (result.success) {
                fetchedCount++;
            }
        }

        if (credentialStore.getCoupangCredentials().isComplete()) {
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
            heroMessage = "아직 저장된 키가 없습니다. 테스트 키를 먼저 불러오거나 사용자 JSON과 쿠팡 키를 가져오세요.";
        } else if (shipments.isEmpty() && fetchedCount > 0) {
            heroMessage = "연결된 " + connectedCount + "개 소스를 조회했고, 현재 출고대상 주문은 없습니다.";
        } else if (shipments.isEmpty()) {
            heroMessage = "연결된 키는 있지만 조회 성공한 소스가 없습니다. 각 키 상태 패널을 먼저 확인하세요.";
        } else {
            heroMessage = "연결된 " + connectedCount + "개 중 " + fetchedCount + "개 조회 성공. 지금 처리할 출고대상 주문은 " + shipments.size() + "건입니다.";
        }

        return new ShipmentDashboardSnapshot(
                shipments,
                connectedCount,
                fetchedCount,
                heroMessage,
                homeStatus,
                prepareStatus,
                coupangStatus
        );
    }

    private SourceFetchResult fetchCafe24Source(String slot, String displayName) {
        String rawJson = credentialStore.getCafe24Json(slot);
        if (rawJson.isEmpty()) {
            return SourceFetchResult.failure(displayName + " Cafe24\n미연결");
        }

        try {
            JSONObject credential = new JSONObject(rawJson);
            String mallId = credential.optString("MallId", "").trim();
            String accessToken = credential.optString("AccessToken", "").trim();
            String clientId = credential.optString("ClientId", "").trim();
            String clientSecret = credential.optString("ClientSecret", "").trim();
            String refreshToken = credential.optString("RefreshToken", "").trim();
            String apiVersion = credential.optString("ApiVersion", "2025-12-01").trim();

            if (mallId.isEmpty() || accessToken.isEmpty()) {
                return SourceFetchResult.failure(displayName + " Cafe24\n필수 키가 부족합니다. MallId와 AccessToken을 확인하세요.");
            }

            LocalDate endDate = LocalDate.now(SEOUL);
            LocalDate startDate = endDate.minusDays(14);
            List<ShipmentSummary> shipments = new ArrayList<>();
            int offset = 0;
            final int limit = 100;
            boolean retried = false;

            while (true) {
                String url = "https://" + mallId + ".cafe24api.com/api/v2/admin/orders"
                        + "?start_date=" + ISO_DATE.format(startDate)
                        + "&end_date=" + ISO_DATE.format(endDate)
                        + "&limit=" + limit
                        + "&offset=" + offset
                        + "&embed=receivers,items"
                        + "&order_status=N20";

                HttpResult response = executeJsonRequest("GET", url, null, buildCafe24Headers(accessToken, apiVersion));
                if (response.statusCode == 401 && !retried && !refreshToken.isEmpty() && !clientId.isEmpty() && !clientSecret.isEmpty()) {
                    String refreshedToken = refreshCafe24AccessToken(mallId, clientId, clientSecret, refreshToken, credential, slot);
                    if (!refreshedToken.isEmpty()) {
                        accessToken = refreshedToken;
                        retried = true;
                        continue;
                    }
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
    private String refreshCafe24AccessToken(
            String mallId,
            String clientId,
            String clientSecret,
            String refreshToken,
            JSONObject rawCredential,
            String slot
    ) {
        try {
            String url = "https://" + mallId + ".cafe24api.com/api/v2/oauth/token";
            Map<String, String> form = new LinkedHashMap<>();
            form.put("grant_type", "refresh_token");
            form.put("refresh_token", refreshToken);

            Map<String, String> headers = new LinkedHashMap<>();
            String basic = clientId + ":" + clientSecret;
            headers.put("Authorization", "Basic " + Base64.encodeToString(basic.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
            HttpResult response = executeFormRequest(url, form, headers);
            if (!response.isSuccessful() && response.body.contains("Invalid client_secret")) {
                form.put("client_id", clientId);
                form.put("client_secret", clientSecret);
                headers.remove("Authorization");
                response = executeFormRequest(url, form, headers);
            }

            if (!response.isSuccessful()) {
                return "";
            }

            JSONObject tokenJson = new JSONObject(response.body);
            String newAccessToken = tokenJson.optString("access_token", "");
            if (newAccessToken.isEmpty()) {
                return "";
            }
            rawCredential.put("AccessToken", newAccessToken);
            String newRefreshToken = tokenJson.optString("refresh_token", "");
            if (!newRefreshToken.isEmpty()) {
                rawCredential.put("RefreshToken", newRefreshToken);
            }
            rawCredential.put("UpdatedAt", Instant.now().toString());
            credentialStore.saveCafe24Json(slot, rawCredential.toString());
            return newAccessToken;
        } catch (Exception ex) {
            return "";
        }
    }

    private Map<String, String> buildCafe24Headers(String accessToken, String apiVersion) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + accessToken);
        headers.put("X-Cafe24-Api-Version", apiVersion.isEmpty() ? "2025-12-01" : apiVersion);
        headers.put("Accept", "application/json");
        return headers;
    }

    private HttpResult executeJsonRequest(String method, String url, String body, Map<String, String> headers) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
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
        if (CredentialStore.SLOT_CAFE24_HOME.equals(marketKey)) {
            return fetchCafe24DispatchOrders(marketKey, "홈런마켓 / Cafe24", start, end);
        } else if (CredentialStore.SLOT_CAFE24_PREPARE.equals(marketKey)) {
            return fetchCafe24DispatchOrders(marketKey, "준비몰 / Cafe24", start, end);
        } else {
            return fetchCoupangDispatchOrders(start, end);
        }
    }

    private List<DispatchOrder> fetchCafe24DispatchOrders(String slot, String label, LocalDate start, LocalDate end) throws Exception {
        String rawJson = credentialStore.getCafe24Json(slot);
        if (rawJson.isEmpty()) throw new Exception(label + " 키가 없습니다.");

        JSONObject cred = new JSONObject(rawJson);
        String mallId       = cred.optString("MallId", "").trim();
        String accessToken  = cred.optString("AccessToken", "").trim();
        String clientId     = cred.optString("ClientId", "").trim();
        String clientSecret = cred.optString("ClientSecret", "").trim();
        String refreshToken = cred.optString("RefreshToken", "").trim();
        String apiVersion   = cred.optString("ApiVersion", "2025-12-01").trim();
        if (mallId.isEmpty() || accessToken.isEmpty()) throw new Exception("MallId 또는 AccessToken이 없습니다.");

        List<DispatchOrder> orders = new ArrayList<>();
        int offset = 0;
        final int limit = 100;
        boolean retried = false;

        while (true) {
            String url = "https://" + mallId + ".cafe24api.com/api/v2/admin/orders"
                    + "?start_date=" + ISO_DATE.format(start)
                    + "&end_date=" + ISO_DATE.format(end)
                    + "&limit=" + limit
                    + "&offset=" + offset
                    + "&embed=receivers,items"
                    + "&order_status=N20";

            HttpResult resp = executeJsonRequest("GET", url, null, buildCafe24Headers(accessToken, apiVersion));
            if (resp.statusCode == 401 && !retried && !refreshToken.isEmpty()) {
                String refreshed = refreshCafe24AccessToken(mallId, clientId, clientSecret, refreshToken, cred, slot);
                if (!refreshed.isEmpty()) { accessToken = refreshed; retried = true; continue; }
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
                for (int j = 0; j < items.length(); j++) {
                    JSONObject item = items.optJSONObject(j);
                    if (item == null) continue;
                    orders.add(new DispatchOrder(
                            label, slot,
                            orderId,
                            item.optString("order_item_code", ""),
                            "", // no shipmentBoxId for Cafe24
                            "N20",
                            recipient,
                            item.optString("product_name", ""),
                            item.optInt("quantity", 0),
                            formatDisplayTime(orderDate),
                            extractCafe24Amount(order, item, item.optInt("quantity", 0)),
                            recipientCellPhone,
                            recipientPhone
                    ));
                }
            }
            if (arr.length() < limit) break;
            offset += limit;
        }
        return orders;
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
                        orders.add(new DispatchOrder(
                                "홈런마켓 / 쿠팡", "coupang",
                                orderId, vendorItemId, boxId, status,
                                recipient, productName, qty,
                                formatDisplayTime(orderedAt),
                                extractCoupangAmount(sheet, item, qty),
                                recipientCellPhone,
                                recipientPhone
                        ));
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

    private String pushCafe24Tracking(DispatchOrder order, String shippingCode) throws Exception {
        String rawJson = credentialStore.getCafe24Json(order.marketKey);
        if (rawJson.isEmpty()) return "Cafe24 키 없음";
        JSONObject cred = new JSONObject(rawJson);
        String mallId      = cred.optString("MallId", "").trim();
        String accessToken = cred.optString("AccessToken", "").trim();
        String apiVersion  = cred.optString("ApiVersion", "2025-12-01").trim();

        String url = "https://" + mallId + ".cafe24api.com/api/v2/admin/orders/" + order.orderId + "/shipments";
        String body = "{\"shop_no\":1,\"request\":{\"order_item_code\":[\"" + order.orderItemCode + "\"],"
                + "\"tracking_no\":\"" + order.trackingNumber.replaceAll("[^a-zA-Z0-9]", "") + "\","
                + "\"shipping_company_code\":\"" + shippingCode + "\","
                + "\"status\":\"shipping\"}}";

        HttpResult resp = executeJsonRequest("POST", url, body, buildCafe24Headers(accessToken, apiVersion));
        if (resp.isSuccessful()) return "";
        // 401 retry (simplified — refresh not implemented here for brevity)
        return "Cafe24 오류 " + resp.statusCode + ": " + clip(resp.body);
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

    private static String firstNonEmpty(String first, String fallback) {
        return first == null || first.trim().isEmpty() ? fallback : first;
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









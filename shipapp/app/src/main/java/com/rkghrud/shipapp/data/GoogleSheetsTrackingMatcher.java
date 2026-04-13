package com.rkghrud.shipapp.data;

import com.rkghrud.shipapp.BuildConfig;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class GoogleSheetsTrackingMatcher {
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String SHEETS_BASE_URL = "https://sheets.googleapis.com/v4/spreadsheets/";
    private static final String SPREADSHEET_ID = BuildConfig.GOOGLE_SHEETS_SPREADSHEET_ID;
    private static final String SHEET_NAME = BuildConfig.GOOGLE_SHEETS_SHEET_NAME;
    private static final String LIVE_TRACKING_SHEET_NAME = "CJ발주서";
    private static final String LIVE_PENDING_SHEET_NAME = "미출고정보";
    private static final String CLIENT_ID = BuildConfig.GOOGLE_SHEETS_CLIENT_ID;
    private static final String CLIENT_SECRET = BuildConfig.GOOGLE_SHEETS_CLIENT_SECRET;
    private static final String REFRESH_TOKEN = BuildConfig.GOOGLE_SHEETS_REFRESH_TOKEN;
    private static final String CJ_SHIPPING_COMPANY = "CJ대한통운";
    private static final int DEFAULT_VENDOR_COLUMN_INDEX = 2;
    private static final int DEFAULT_RECIPIENT_NAME_COLUMN_INDEX = 5;
    private static final int DEFAULT_PHONE_COLUMN_INDEX = 6;
    private static final int DEFAULT_TRACKING_COLUMN_INDEX = 11;
    private static final Pattern DATE_MARKER_PATTERN = Pattern.compile("^20\\d{2}\\.\\d{1,2}\\.\\d{1,2}$");

    private static final Set<String> NON_SPECIFIC_FALLBACK_NAMES = new HashSet<>(Arrays.asList(
            "집", "자택", "회사", "배송지", "수령인", "받는분", "고객", "고객님", "주문자", "테스트"
    ));

    private static final String[] HEADER_KEYWORDS = {
            "발주사", "상품코드", "수령인", "송장", "택배", "주문", "발주일",
            "수취인", "상품명", "연락처", "휴대폰", "배송", "운송장"
    };

    private static final String[] PHONE_KEYWORDS = {
            "휴대폰", "핸드폰", "수령인휴대폰", "수취인휴대폰", "수령인연락처", "수취인연락처",
            "연락처", "수취인전화", "수령인전화", "HP", "Phone", "CellPhone", "전화번호",
            "수령인HP", "수취인HP", "휴대전화"
    };

    private static final String[] PHONE_FALLBACK_KEYWORDS = {
            "전화", "폰", "휴대", "HP", "Phone", "Cell", "연락"
    };

    private static final String[] SHIPPING_COMPANY_KEYWORDS = {
            "택배사", "배송사", "운송사", "택배", "배송업체", "운송업체"
    };

    private GoogleSheetsTrackingMatcher() {
    }

    public static MatchResult applyToOrders(List<DispatchOrder> orders) throws Exception {
        if (orders == null || orders.isEmpty()) {
            return new MatchResult(buildLiveMatchSourceName(orders), 0, 0, 0, 0, 0, 0, 0, 0, "");
        }

        ensureConfigured();
        String accessToken = refreshAccessToken();
        ParsedSheet trackingSheet = scopeParsedSheetToOrders(readLiveSheet(accessToken, LIVE_TRACKING_SHEET_NAME, false), orders);
        ParsedSheet pendingSheet = scopeParsedSheetToOrders(readLiveSheet(accessToken, LIVE_PENDING_SHEET_NAME, true), orders);
        ParsedSheet combinedSheet = mergeParsedSheets(trackingSheet, pendingSheet);
        return applyRowsToOrders(combinedSheet, orders, buildLiveMatchSourceName(orders));
    }

    private static ParsedSheet readLiveSheet(String accessToken, String sheetName, boolean pendingShipment) throws Exception {
        List<List<String>> rows = readSheetRows(accessToken, sheetName);
        int headerRowIndex = detectHeaderRow(rows);
        List<String> headersRow = rows.get(headerRowIndex);
        int phoneColumnIndex = detectPhoneColumn(headersRow);
        int trackingColumnIndex = detectTrackingColumn(headersRow);

        List<SheetRow> parsedRows = new ArrayList<>();
        int trackingRowCount = 0;
        for (int rowIndex = headerRowIndex + 1; rowIndex < rows.size(); rowIndex++) {
            List<String> row = rows.get(rowIndex);
            String vendorName = getCell(row, DEFAULT_VENDOR_COLUMN_INDEX);
            if (vendorName.isEmpty()) {
                continue;
            }

            String recipientPhone = phoneColumnIndex >= 0 ? getCell(row, phoneColumnIndex) : getCell(row, DEFAULT_PHONE_COLUMN_INDEX);
            String normalizedPhone = PhoneNormalizer.normalize(recipientPhone);
            String recipientName = getCell(row, DEFAULT_RECIPIENT_NAME_COLUMN_INDEX);
            String trackingNumber = pendingShipment ? "" : normalizeTracking(getCell(row, trackingColumnIndex));
            if (!trackingNumber.isEmpty()) {
                trackingRowCount++;
            }

            parsedRows.add(new SheetRow(
                    rowIndex,
                    sheetName + "|" + vendorName + "|" + normalizedPhone + "|" + recipientName + "|" + rowIndex,
                    vendorName,
                    trackingNumber,
                    normalizedPhone,
                    recipientName,
                    pendingShipment ? "" : CJ_SHIPPING_COMPANY,
                    pendingShipment
            ));
        }

        return new ParsedSheet(parsedRows.size(), trackingRowCount, parsedRows, PendingWindow.NONE);
    }

    private static List<List<String>> readSheetRows(String accessToken, String sheetName) throws Exception {
        String url = SHEETS_BASE_URL + SPREADSHEET_ID + "/values:batchGet"
                + "?ranges=" + urlEncode(sheetName)
                + "&majorDimension=ROWS"
                + "&valueRenderOption=FORMATTED_VALUE";

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + accessToken);
        headers.put("Accept", "application/json");

        HttpResult response = executeJsonRequest("GET", url, null, headers);
        if (!response.isSuccessful()) {
            throw new IllegalArgumentException(sheetName + " 시트 조회 실패 " + response.statusCode + ": " + clip(response.body));
        }

        return parseRowsFromSheetsResponse(response.body);
    }

    private static int detectTrackingColumn(List<String> headers) {
        for (int index = 0; index < headers.size(); index++) {
            String normalized = normalizeHeader(headers.get(index));
            if (normalized.contains(normalizeHeader("송장번호"))
                    || normalized.contains(normalizeHeader("운송장번호"))
                    || normalized.contains(normalizeHeader("송장"))
                    || normalized.contains(normalizeHeader("운송장"))) {
                return index;
            }
        }
        return headers.size() > DEFAULT_TRACKING_COLUMN_INDEX ? DEFAULT_TRACKING_COLUMN_INDEX : -1;
    }

    private static ParsedSheet mergeParsedSheets(ParsedSheet trackingSheet, ParsedSheet pendingSheet) {
        List<SheetRow> mergedRows = new ArrayList<>();
        int sheetRowCount = 0;
        int trackingRowCount = 0;

        if (trackingSheet != null) {
            sheetRowCount += trackingSheet.sheetRowCount;
            trackingRowCount += trackingSheet.trackingRowCount;
            mergedRows.addAll(trackingSheet.rows);
        }
        if (pendingSheet != null) {
            sheetRowCount += pendingSheet.sheetRowCount;
            trackingRowCount += pendingSheet.trackingRowCount;
            mergedRows.addAll(pendingSheet.rows);
        }

        return new ParsedSheet(sheetRowCount, trackingRowCount, mergedRows, PendingWindow.NONE);
    }

    static MatchResult applyRowsToOrders(ParsedSheet parsedSheet, List<DispatchOrder> orders) {
        return applyRowsToOrders(parsedSheet, orders, buildMatchSourceName(parsedSheet, orders));
    }

    private static MatchResult applyRowsToOrders(ParsedSheet parsedSheet, List<DispatchOrder> orders, String sourceName) {
        Map<String, List<SheetRow>> trackingPhoneIndex = buildPhoneIndex(parsedSheet.rows, false);
        Map<String, List<SheetRow>> pendingPhoneIndex = buildPhoneIndex(parsedSheet.rows, true);

        int matchedCount = 0;
        int noTrackingCount = 0;
        int unmatchedCount = 0;
        int candidateCount = 0;
        int nameFallbackCount = 0;
        int pendingBlockedCount = 0;

        for (DispatchOrder order : orders) {
            order.clearTrackingMatchState();

            OrderMatchResolution trackingResolution = resolveTrackingMatch(order, trackingPhoneIndex);
            OrderMatchResolution pendingResolution = resolvePendingMatch(order, pendingPhoneIndex);
            boolean pendingMatched = pendingResolution.state == MatchState.PENDING_NO_TRACKING;

            if (pendingMatched) {
                if (trackingResolution.state == MatchState.MATCHED) {
                    applyMatchedTracking(order, trackingResolution.matchedRow, false);
                    matchedCount++;
                }
                order.markPendingShipment("미출고 확인 필요", pendingResolution.matchedRow.rowIndex);
                pendingBlockedCount++;
                continue;
            }

            if (trackingResolution.state == MatchState.MATCHED) {
                applyMatchedTracking(order, trackingResolution.matchedRow, true);
                matchedCount++;
                continue;
            }

            if (trackingResolution.state == MatchState.CANDIDATE || pendingResolution.state == MatchState.CANDIDATE) {
                candidateCount++;
                continue;
            }

            if (trackingResolution.state == MatchState.NO_TRACKING) {
                noTrackingCount++;
                continue;
            }

            unmatchedCount++;
        }

        return new MatchResult(
                sourceName,
                parsedSheet.sheetRowCount,
                parsedSheet.trackingRowCount,
                matchedCount,
                noTrackingCount,
                unmatchedCount,
                candidateCount,
                nameFallbackCount,
                pendingBlockedCount,
                parsedSheet.pendingWindow.summaryLabel()
        );
    }

    private static void applyMatchedTracking(DispatchOrder order, SheetRow matchedRow, boolean selectForUpload) {
        order.trackingNumber = matchedRow.trackingNumber;
        if (!matchedRow.shippingCompany.isEmpty()) {
            order.shippingCompanyName = matchedRow.shippingCompany;
        }
        order.selected = selectForUpload;
    }

    private static Map<String, List<SheetRow>> buildPhoneIndex(List<SheetRow> rows, boolean pendingShipment) {
        Map<String, List<SheetRow>> phoneIndex = new HashMap<>();
        if (rows == null) {
            return phoneIndex;
        }
        for (SheetRow row : rows) {
            if (row.pendingShipment != pendingShipment || row.recipientPhone.isEmpty()) {
                continue;
            }
            phoneIndex.computeIfAbsent(row.recipientPhone, key -> new ArrayList<>()).add(row);
        }
        return phoneIndex;
    }

    private static OrderMatchResolution resolveTrackingMatch(DispatchOrder order, Map<String, List<SheetRow>> phoneIndex) {
        List<SheetRow> candidates = buildCandidates(order, phoneIndex);
        if (candidates.isEmpty()) {
            return OrderMatchResolution.unmatched();
        }

        List<SheetRow> marketMatches = findMarketMatches(order.marketName, candidates);
        if (marketMatches.isEmpty()) {
            return OrderMatchResolution.unmatched();
        }

        List<SheetRow> withTracking = new ArrayList<>();
        for (SheetRow candidate : marketMatches) {
            if (!candidate.trackingNumber.isEmpty()) {
                withTracking.add(candidate);
            }
        }
        if (withTracking.isEmpty()) {
            List<SheetRow> nameMatches = findNameMatches(order.recipientName, marketMatches);
            return nameMatches.size() == 1 ? OrderMatchResolution.noTracking() : OrderMatchResolution.candidate();
        }

        List<SheetRow> nameMatches = findNameMatches(order.recipientName, withTracking);
        if (nameMatches.isEmpty()) {
            return OrderMatchResolution.candidate();
        }

        SheetRow resolved = resolveSharedTrackingMatch(nameMatches);
        return resolved == null ? OrderMatchResolution.candidate() : OrderMatchResolution.matched(resolved);
    }

    private static OrderMatchResolution resolvePendingMatch(DispatchOrder order, Map<String, List<SheetRow>> phoneIndex) {
        List<SheetRow> candidates = buildCandidates(order, phoneIndex);
        if (candidates.isEmpty()) {
            return OrderMatchResolution.unmatched();
        }

        List<SheetRow> marketMatches = findMarketMatches(order.marketName, candidates);
        if (marketMatches.isEmpty()) {
            return OrderMatchResolution.unmatched();
        }

        List<SheetRow> nameMatches = findNameMatches(order.recipientName, marketMatches);
        return nameMatches.size() == 1 ? OrderMatchResolution.pendingNoTracking(nameMatches.get(0)) : OrderMatchResolution.candidate();
    }

    private static List<SheetRow> buildCandidates(DispatchOrder order, Map<String, List<SheetRow>> phoneIndex) {
        List<SheetRow> candidates = new ArrayList<>();
        addCandidates(candidates, phoneIndex, order.recipientCellPhone);
        addCandidates(candidates, phoneIndex, order.recipientPhone);
        return dedupeCandidates(candidates);
    }

    private static SheetRow resolveSharedTrackingMatch(List<SheetRow> matches) {
        if (matches == null || matches.isEmpty()) {
            return null;
        }

        SheetRow first = matches.get(0);
        String trackingNumber = first.trackingNumber;
        if (trackingNumber.isEmpty()) {
            return null;
        }

        for (int i = 1; i < matches.size(); i++) {
            if (!trackingNumber.equals(matches.get(i).trackingNumber)) {
                return null;
            }
        }
        return first;
    }

    static ParsedSheet scopeParsedSheetToOrders(ParsedSheet parsedSheet, List<DispatchOrder> orders) {
        if (parsedSheet == null || parsedSheet.rows == null || parsedSheet.rows.isEmpty() || orders == null || orders.isEmpty()) {
            return parsedSheet;
        }

        List<String> normalizedMarkets = collectNormalizedOrderMarkets(orders);
        if (normalizedMarkets.isEmpty()) {
            return parsedSheet;
        }

        List<SheetRow> scopedRows = new ArrayList<>();
        int scopedTrackingRowCount = 0;
        for (SheetRow row : parsedSheet.rows) {
            if (!matchesAnyMarket(row.vendorName, normalizedMarkets)) {
                continue;
            }
            scopedRows.add(row);
            if (!row.trackingNumber.isEmpty()) {
                scopedTrackingRowCount++;
            }
        }

        return new ParsedSheet(scopedRows.size(), scopedTrackingRowCount, scopedRows, parsedSheet.pendingWindow);
    }

    private static String buildMatchSourceName(ParsedSheet parsedSheet, List<DispatchOrder> orders) {
        String label = buildOrderScopeLabel(orders);
        String configuredSheetName = cleanValue(SHEET_NAME);
        if (configuredSheetName.isEmpty()) {
            configuredSheetName = LIVE_TRACKING_SHEET_NAME;
        }
        if (label.isEmpty()) {
            return "구글시트 " + configuredSheetName;
        }
        return "구글시트 " + configuredSheetName + " / " + label;
    }

    private static String buildLiveMatchSourceName(List<DispatchOrder> orders) {
        String label = buildOrderScopeLabel(orders);
        String baseLabel = "구글시트 " + LIVE_TRACKING_SHEET_NAME + " + " + LIVE_PENDING_SHEET_NAME;
        if (label.isEmpty()) {
            return baseLabel;
        }
        return baseLabel + " / " + label;
    }

    private static String buildOrderScopeLabel(List<DispatchOrder> orders) {
        List<String> marketNames = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (orders == null) {
            return "";
        }
        for (DispatchOrder order : orders) {
            if (order == null) {
                continue;
            }
            String displayName = cleanValue(order.marketName);
            String normalized = normalizeMarketName(displayName);
            if (displayName.isEmpty() || normalized.isEmpty() || !seen.add(normalized)) {
                continue;
            }
            marketNames.add(displayName);
        }
        if (marketNames.isEmpty()) {
            return "";
        }
        if (marketNames.size() == 1) {
            return marketNames.get(0);
        }
        return "판매처 " + marketNames.size() + "개";
    }

    private static List<String> collectNormalizedOrderMarkets(List<DispatchOrder> orders) {
        List<String> normalizedMarkets = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (orders == null) {
            return normalizedMarkets;
        }
        for (DispatchOrder order : orders) {
            if (order == null) {
                continue;
            }
            String normalized = normalizeMarketName(order.marketName);
            if (normalized.isEmpty() || !seen.add(normalized)) {
                continue;
            }
            normalizedMarkets.add(normalized);
        }
        return normalizedMarkets;
    }

    private static boolean matchesAnyMarket(String vendorName, List<String> normalizedMarkets) {
        if (normalizedMarkets == null || normalizedMarkets.isEmpty()) {
            return true;
        }
        String normalizedVendor = normalizeMarketName(vendorName);
        if (normalizedVendor.isEmpty()) {
            return false;
        }
        for (String normalizedMarket : normalizedMarkets) {
            if (normalizedMarket.isEmpty()) {
                continue;
            }
            if (normalizedVendor.contains(normalizedMarket) || normalizedMarket.contains(normalizedVendor)) {
                return true;
            }
        }
        return false;
    }

    private static void addCandidates(List<SheetRow> target, Map<String, List<SheetRow>> phoneIndex, String phone) {
        if (phone == null || phone.trim().isEmpty()) {
            return;
        }
        List<SheetRow> rows = phoneIndex.get(PhoneNormalizer.normalize(phone));
        if (rows != null && !rows.isEmpty()) {
            target.addAll(rows);
        }
    }

    private static List<SheetRow> dedupeCandidates(List<SheetRow> candidates) {
        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, SheetRow> deduped = new LinkedHashMap<>();
        for (SheetRow candidate : candidates) {
            deduped.put(candidate.sourceRowKey, candidate);
        }
        List<SheetRow> rows = new ArrayList<>(deduped.values());
        rows.sort(Comparator.comparingInt(row -> row.rowIndex));
        return rows;
    }

    private static List<SheetRow> findNameMatches(String orderName, List<SheetRow> candidates) {
        if (orderName == null || orderName.trim().isEmpty()) {
            return Collections.emptyList();
        }

        String normalizedOrderName = orderName.trim();
        List<SheetRow> matches = new ArrayList<>();
        for (SheetRow candidate : candidates) {
            if (candidate.recipientName.isEmpty()) {
                continue;
            }
            if (normalizedOrderName.contains(candidate.recipientName) || candidate.recipientName.contains(normalizedOrderName)) {
                matches.add(candidate);
            }
        }
        return matches;
    }

    private static List<SheetRow> findMarketMatches(String marketName, List<SheetRow> candidates) {
        if (marketName == null || marketName.trim().isEmpty()) {
            return candidates;
        }

        String normalizedOrderMarket = normalizeMarketName(marketName);
        if (normalizedOrderMarket.isEmpty()) {
            return candidates;
        }

        List<SheetRow> matches = new ArrayList<>();
        for (SheetRow candidate : candidates) {
            String normalizedVendor = normalizeMarketName(candidate.vendorName);
            if (normalizedVendor.isEmpty()) {
                continue;
            }
            if (normalizedVendor.contains(normalizedOrderMarket) || normalizedOrderMarket.contains(normalizedVendor)) {
                matches.add(candidate);
            }
        }
        return matches;
    }

    private static PendingWindow detectPendingWindow(List<List<String>> rows, int headerRowIndex) {
        List<DateMarker> dateMarkers = new ArrayList<>();

        for (int rowIndex = headerRowIndex + 1; rowIndex < rows.size(); rowIndex++) {
            String dateMarker = extractDateMarker(rows.get(rowIndex));
            if (dateMarker.isEmpty()) {
                continue;
            }
            dateMarkers.add(new DateMarker(rowIndex, dateMarker));
        }

        if (dateMarkers.size() < 2) {
            return PendingWindow.NONE;
        }

        String latestDate = dateMarkers.get(dateMarkers.size() - 1).date;
        String previousDate = "";
        for (int i = dateMarkers.size() - 2; i >= 0; i--) {
            String candidate = dateMarkers.get(i).date;
            if (!latestDate.equals(candidate)) {
                previousDate = candidate;
                break;
            }
        }

        if (previousDate.isEmpty()) {
            return PendingWindow.NONE;
        }

        return new PendingWindow(previousDate, latestDate, dateMarkers);
    }

    static String extractDateMarker(List<String> row) {
        return isDateMarker(getCell(row, 0)) ? cleanValue(getCell(row, 0)) : "";
    }

    static boolean isDateMarker(String value) {
        return DATE_MARKER_PATTERN.matcher(cleanValue(value)).matches();
    }

    static boolean isRowInLatestPendingWindow(List<List<String>> rows, int headerRowIndex, int rowIndex) {
        return detectPendingWindow(rows, headerRowIndex).contains(rowIndex);
    }

    private static int detectHeaderRow(List<List<String>> rows) {
        int headerRowIndex = 0;
        int maxKeywordHits = -1;
        int limit = Math.min(rows.size(), 10);

        for (int rowIndex = 0; rowIndex < limit; rowIndex++) {
            String rowText = joinRow(rows.get(rowIndex));
            int hits = 0;
            for (String keyword : HEADER_KEYWORDS) {
                if (rowText.contains(keyword)) {
                    hits++;
                }
            }
            if (hits > maxKeywordHits) {
                maxKeywordHits = hits;
                headerRowIndex = rowIndex;
            }
        }

        return headerRowIndex;
    }

    private static int detectPhoneColumn(List<String> headers) {
        for (int index = 0; index < headers.size(); index++) {
            String normalized = normalizeHeader(headers.get(index));
            for (String keyword : PHONE_KEYWORDS) {
                if (normalized.contains(normalizeHeader(keyword))) {
                    return index;
                }
            }
        }

        for (int index = 0; index < headers.size(); index++) {
            String normalized = normalizeHeader(headers.get(index));
            for (String keyword : PHONE_FALLBACK_KEYWORDS) {
                if (normalized.contains(normalizeHeader(keyword))) {
                    return index;
                }
            }
        }

        return headers.size() > 6 ? 6 : -1;
    }

    private static int detectShippingCompanyColumn(List<String> headers) {
        for (int index = 0; index < headers.size(); index++) {
            String normalized = normalizeHeader(headers.get(index));
            for (String keyword : SHIPPING_COMPANY_KEYWORDS) {
                if (normalized.contains(normalizeHeader(keyword))) {
                    return index;
                }
            }
        }
        return -1;
    }

    private static boolean canUseNameFallback(String name) {
        if (name == null || name.trim().length() <= 1) {
            return false;
        }
        return !NON_SPECIFIC_FALLBACK_NAMES.contains(name.trim());
    }

    private static void ensureConfigured() {
        if (SPREADSHEET_ID == null || SPREADSHEET_ID.trim().isEmpty()) {
            throw new IllegalArgumentException("구글시트 설정이 비어 있습니다.");
        }
        if (CLIENT_ID == null || CLIENT_ID.trim().isEmpty()
                || CLIENT_SECRET == null || CLIENT_SECRET.trim().isEmpty()
                || REFRESH_TOKEN == null || REFRESH_TOKEN.trim().isEmpty()) {
            throw new IllegalArgumentException("local.properties에 shipapp 구글시트 인증값을 넣어야 합니다.");
        }
    }

    private static String refreshAccessToken() throws Exception {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", CLIENT_ID);
        form.put("client_secret", CLIENT_SECRET);
        form.put("refresh_token", REFRESH_TOKEN);
        form.put("grant_type", "refresh_token");

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");

        HttpResult response = executeFormRequest(TOKEN_URL, form, headers);
        if (!response.isSuccessful()) {
            throw new IllegalArgumentException("구글 인증 실패 " + response.statusCode + ": " + clip(response.body));
        }

        JSONObject root = new JSONObject(response.body);
        String accessToken = root.optString("access_token", "").trim();
        if (accessToken.isEmpty()) {
            throw new IllegalArgumentException("구글 access_token을 받지 못했습니다.");
        }
        return accessToken;
    }

    private static HttpResult executeJsonRequest(String method, String url, String body, Map<String, String> headers) throws Exception {
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

    private static HttpResult executeFormRequest(String url, Map<String, String> form, Map<String, String> headers) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(30000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            connection.setRequestProperty(entry.getKey(), entry.getValue());
        }

        try (OutputStream outputStream = connection.getOutputStream();
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8))) {
            writer.write(buildQueryString(form));
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
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            parts.add(urlEncode(entry.getKey()) + "=" + urlEncode(entry.getValue()));
        }
        return String.join("&", parts);
    }

    private static String urlEncode(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
    }

    static List<List<String>> parseRowsFromSheetsResponse(String responseBody) throws Exception {
        JSONObject root = new JSONObject(responseBody);
        JSONArray values = root.optJSONArray("values");
        if (values == null) {
            JSONArray valueRanges = root.optJSONArray("valueRanges");
            if (valueRanges != null && valueRanges.length() > 0) {
                JSONObject firstRange = valueRanges.optJSONObject(0);
                if (firstRange != null) {
                    values = firstRange.optJSONArray("values");
                }
            }
        }
        if (values == null || values.length() < 2) {
            throw new IllegalArgumentException("출고정보 시트에 읽을 데이터가 없습니다.");
        }

        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < values.length(); i++) {
            JSONArray rowArray = values.optJSONArray(i);
            List<String> row = new ArrayList<>();
            if (rowArray != null) {
                for (int j = 0; j < rowArray.length(); j++) {
                    row.add(cleanValue(rowArray.optString(j, "")));
                }
            }
            rows.add(row);
        }
        return rows;
    }

    private static String joinRow(List<String> row) {
        if (row == null || row.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String value : row) {
            if (value == null || value.trim().isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(value.trim());
        }
        return builder.toString();
    }

    private static String getCell(List<String> row, int index) {
        return index >= 0 && index < row.size() ? cleanValue(row.get(index)) : "";
    }

    private static String normalizeHeader(String value) {
        return cleanValue(value)
                .toLowerCase(Locale.ROOT)
                .replace(" ", "")
                .replace("_", "")
                .replace("-", "")
                .replace("/", "")
                .replace("(", "")
                .replace(")", "")
                .replace(".", "");
    }

    private static String normalizeMarketName(String value) {
        return cleanValue(value)
                .toLowerCase(Locale.ROOT)
                .replace(" ", "")
                .replace("_", "")
                .replace("-", "")
                .replace("/", "")
                .replace("(", "")
                .replace(")", "")
                .replace(".", "")
                .replace("cafe24", "");
    }

    private static String normalizeTracking(String value) {
        return cleanValue(value).replaceAll("[^A-Za-z0-9]", "");
    }

    private static String cleanValue(String value) {
        return value == null ? "" : value.trim();
    }

    private static String clip(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 180 ? body.substring(0, 180) : body;
    }

    static final class ParsedSheet {
        final int sheetRowCount;
        final int trackingRowCount;
        final List<SheetRow> rows;
        final PendingWindow pendingWindow;

        ParsedSheet(int sheetRowCount, int trackingRowCount, List<SheetRow> rows) {
            this(sheetRowCount, trackingRowCount, rows, PendingWindow.NONE);
        }

        ParsedSheet(int sheetRowCount, int trackingRowCount, List<SheetRow> rows, PendingWindow pendingWindow) {
            this.sheetRowCount = sheetRowCount;
            this.trackingRowCount = trackingRowCount;
            this.rows = rows;
            this.pendingWindow = pendingWindow == null ? PendingWindow.NONE : pendingWindow;
        }
    }

    static final class SheetRow {
        final int rowIndex;
        final String sourceRowKey;
        final String vendorName;
        final String trackingNumber;
        final String recipientPhone;
        final String recipientName;
        final String shippingCompany;
        final boolean pendingShipment;

        SheetRow(int rowIndex, String sourceRowKey, String vendorName, String trackingNumber,
                 String recipientPhone, String recipientName, String shippingCompany) {
            this(rowIndex, sourceRowKey, vendorName, trackingNumber, recipientPhone, recipientName, shippingCompany, false);
        }

        SheetRow(int rowIndex, String sourceRowKey, String vendorName, String trackingNumber,
                 String recipientPhone, String recipientName, String shippingCompany, boolean pendingShipment) {
            this.rowIndex = rowIndex;
            this.sourceRowKey = sourceRowKey;
            this.vendorName = vendorName;
            this.trackingNumber = trackingNumber;
            this.recipientPhone = recipientPhone;
            this.recipientName = recipientName;
            this.shippingCompany = shippingCompany == null ? "" : shippingCompany.trim();
            this.pendingShipment = pendingShipment;
        }
    }

    private static final class PendingWindow {
        static final PendingWindow NONE = new PendingWindow("", "", Collections.emptyList());

        final String previousDate;
        final String latestDate;
        final List<DateMarker> dateMarkers;

        PendingWindow(String previousDate, String latestDate, List<DateMarker> dateMarkers) {
            this.previousDate = cleanValue(previousDate);
            this.latestDate = cleanValue(latestDate);
            this.dateMarkers = dateMarkers == null ? Collections.emptyList() : dateMarkers;
        }

        boolean contains(int rowIndex) {
            if (previousDate.isEmpty()) {
                return false;
            }
            for (DateMarker marker : dateMarkers) {
                if (marker.rowIndex == rowIndex) {
                    return false;
                }
            }
            return previousDate.equals(activeDateForRow(rowIndex));
        }

        private String activeDateForRow(int rowIndex) {
            String activeDate = "";
            for (DateMarker marker : dateMarkers) {
                if (marker.rowIndex >= rowIndex) {
                    break;
                }
                activeDate = marker.date;
            }
            return activeDate;
        }

        String summaryLabel() {
            if (previousDate.isEmpty() || latestDate.isEmpty()) {
                return "";
            }
            return previousDate + " ~ " + latestDate;
        }
    }

    private enum MatchState {
        MATCHED,
        PENDING_NO_TRACKING,
        NO_TRACKING,
        UNMATCHED,
        CANDIDATE
    }

    private static final class OrderMatchResolution {
        final MatchState state;
        final SheetRow matchedRow;

        private OrderMatchResolution(MatchState state, SheetRow matchedRow) {
            this.state = state;
            this.matchedRow = matchedRow;
        }

        static OrderMatchResolution matched(SheetRow row) {
            return new OrderMatchResolution(MatchState.MATCHED, row);
        }

        static OrderMatchResolution pendingNoTracking(SheetRow row) {
            return new OrderMatchResolution(MatchState.PENDING_NO_TRACKING, row);
        }

        static OrderMatchResolution noTracking() {
            return new OrderMatchResolution(MatchState.NO_TRACKING, null);
        }

        static OrderMatchResolution unmatched() {
            return new OrderMatchResolution(MatchState.UNMATCHED, null);
        }

        static OrderMatchResolution candidate() {
            return new OrderMatchResolution(MatchState.CANDIDATE, null);
        }
    }

    private static final class DateMarker {
        final int rowIndex;
        final String date;

        DateMarker(int rowIndex, String date) {
            this.rowIndex = rowIndex;
            this.date = cleanValue(date);
        }
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

    public static final class MatchResult {
        public final String sourceName;
        public final int sheetRowCount;
        public final int trackingRowCount;
        public final int matchedCount;
        public final int noTrackingCount;
        public final int unmatchedCount;
        public final int candidateCount;
        public final int nameFallbackCount;
        public final int pendingBlockedCount;
        public final String pendingWindowLabel;

        MatchResult(String sourceName, int sheetRowCount, int trackingRowCount, int matchedCount,
                    int noTrackingCount, int unmatchedCount, int candidateCount, int nameFallbackCount,
                    int pendingBlockedCount, String pendingWindowLabel) {
            this.sourceName = sourceName;
            this.sheetRowCount = sheetRowCount;
            this.trackingRowCount = trackingRowCount;
            this.matchedCount = matchedCount;
            this.noTrackingCount = noTrackingCount;
            this.unmatchedCount = unmatchedCount;
            this.candidateCount = candidateCount;
            this.nameFallbackCount = nameFallbackCount;
            this.pendingBlockedCount = pendingBlockedCount;
            this.pendingWindowLabel = cleanValue(pendingWindowLabel);
        }

        public String summary() {
            StringBuilder builder = new StringBuilder();
            builder.append(sourceName)
                    .append(" · 송장행 ")
                    .append(trackingRowCount)
                    .append("개, 매칭 ")
                    .append(matchedCount)
                    .append("건");
            if (pendingBlockedCount > 0) {
                builder.append(", 미출고확인 ").append(pendingBlockedCount).append("건");
                if (!pendingWindowLabel.isEmpty()) {
                    builder.append(" (").append(pendingWindowLabel).append(")");
                }
            }
            if (nameFallbackCount > 0) {
                builder.append(", 이름폴백 ").append(nameFallbackCount).append("건");
            }
            if (noTrackingCount > 0) {
                builder.append(", 송장없음 ").append(noTrackingCount).append("건");
            }
            if (candidateCount > 0) {
                builder.append(", 확인필요 ").append(candidateCount).append("건");
            }
            if (unmatchedCount > 0) {
                builder.append(", 미매칭 ").append(unmatchedCount).append("건");
            }
            return builder.toString();
        }
    }
}



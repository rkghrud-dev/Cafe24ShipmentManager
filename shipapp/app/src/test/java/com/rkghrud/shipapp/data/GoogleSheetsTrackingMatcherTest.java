package com.rkghrud.shipapp.data;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GoogleSheetsTrackingMatcherTest {

    @Test
    public void buildServiceAccountPayloadJson_usesClockSkewAndShortTtl() throws Exception {
        long now = 1_800_000_000L;

        JSONObject payload = new JSONObject(
                GoogleSheetsTrackingMatcher.buildServiceAccountPayloadJson(
                        "shipapp-test@example.iam.gserviceaccount.com",
                        now
                )
        );

        assertEquals("shipapp-test@example.iam.gserviceaccount.com", payload.getString("iss"));
        assertEquals(now - 60L, payload.getLong("iat"));
        assertEquals(now + 55L * 60L, payload.getLong("exp"));
        assertTrue(payload.getLong("exp") - payload.getLong("iat") <= 3600L);
    }

    @Test
    public void withServiceAccountAuthHint_explainsInvalidGrantClockCause() {
        String message = GoogleSheetsTrackingMatcher.withServiceAccountAuthHint(
                "구글 서비스계정 인증 실패 400: {\"error\":\"invalid_grant\",\"error_description\":\"Token must be a short-lived token\"}"
        );

        assertTrue(message.contains("Android 기기 날짜/시간"));
        assertTrue(message.contains("서비스계정 JWT"));
    }

    @Test
    public void parseRowsFromSheetsResponse_readsBatchGetPayload() throws Exception {
        String response = "{"
                + "\"spreadsheetId\":\"sheet-id\"," 
                + "\"valueRanges\":[{" 
                + "\"range\":\"'출고정보'!A1:M3\"," 
                + "\"majorDimension\":\"ROWS\"," 
                + "\"values\":["
                + "[\"주문코드\",\"상품코드\",\"발주사\"],"
                + "[\"상품A\",\"CODE-1\",\"가게A\"],"
                + "[\"상품B\",\"CODE-2\",\"가게B\"]"
                + "]"
                + "}]"
                + "}";

        List<List<String>> rows = GoogleSheetsTrackingMatcher.parseRowsFromSheetsResponse(response);

        assertEquals(3, rows.size());
        assertEquals("주문코드", rows.get(0).get(0));
        assertEquals("가게A", rows.get(1).get(2));
        assertEquals("상품B", rows.get(2).get(0));
    }

    @Test
    public void applyRowsToOrders_requiresMarketPhoneAndNameToAllMatch() {
        DispatchOrder exactOrder = new DispatchOrder(
                "가게A / Cafe24",
                "cafe24_home",
                "가게A",
                "ORDER-1",
                "ITEM-1",
                "",
                "INSTRUCT",
                "홍길동",
                "상품A",
                1,
                "2026-04-07",
                "1000원",
                "010-1234-5678",
                ""
        );
        DispatchOrder wrongMarketOrder = new DispatchOrder(
                "가게B / Cafe24",
                "cafe24_prepare",
                "가게B",
                "ORDER-2",
                "ITEM-2",
                "",
                "INSTRUCT",
                "홍길동",
                "상품B",
                1,
                "2026-04-07",
                "2000원",
                "010-1234-5678",
                ""
        );
        DispatchOrder phoneOnlyOrder = new DispatchOrder(
                "가게A / Cafe24",
                "cafe24_home",
                "가게A",
                "ORDER-3",
                "ITEM-3",
                "",
                "INSTRUCT",
                "김영희",
                "상품C",
                1,
                "2026-04-07",
                "3000원",
                "010-9999-8888",
                ""
        );

        GoogleSheetsTrackingMatcher.SheetRow wrongNameSamePhone = new GoogleSheetsTrackingMatcher.SheetRow(
                0,
                "가게A|01099998888|TRACK-ONE|0",
                "가게A",
                "TRACKONE",
                "01099998888",
                "박철수",
                "CJ대한통운"
        );
        GoogleSheetsTrackingMatcher.SheetRow exactRow = new GoogleSheetsTrackingMatcher.SheetRow(
                1,
                "가게A|01012345678|TRACK-TWO|1",
                "가게A",
                "TRACKTWO",
                "01012345678",
                "홍길동",
                "CJ대한통운"
        );

        GoogleSheetsTrackingMatcher.ParsedSheet parsedSheet = new GoogleSheetsTrackingMatcher.ParsedSheet(
                3,
                2,
                Arrays.asList(wrongNameSamePhone, exactRow)
        );

        GoogleSheetsTrackingMatcher.MatchResult result = GoogleSheetsTrackingMatcher.applyRowsToOrders(
                parsedSheet,
                Arrays.asList(exactOrder, wrongMarketOrder, phoneOnlyOrder)
        );

        assertEquals("TRACKTWO", exactOrder.trackingNumber);
        assertTrue(exactOrder.selected);

        assertEquals("", wrongMarketOrder.trackingNumber);
        assertFalse(wrongMarketOrder.selected);

        assertEquals("", phoneOnlyOrder.trackingNumber);
        assertFalse(phoneOnlyOrder.selected);

        assertEquals(1, result.matchedCount);
        assertEquals(1, result.candidateCount);
        assertEquals(1, result.unmatchedCount);
    }

    @Test
    public void latestPendingWindow_usesLatestDistinctDateBoundary() {
        List<List<String>> rows = Arrays.asList(
                Arrays.asList("상품명", "상품코드", "발주사"),
                Arrays.asList("2026.04.07", "마감"),
                Arrays.asList("상품A", "CODE-A", "가게A"),
                Arrays.asList("2026.04.08", "1차"),
                Arrays.asList("상품B", "CODE-B", "가게A"),
                Arrays.asList("상품C", "CODE-C", "가게B"),
                Arrays.asList("2026.04.08", "긴급 2")
        );

        assertFalse(GoogleSheetsTrackingMatcher.isRowInLatestPendingWindow(rows, 0, 1));
        assertTrue(GoogleSheetsTrackingMatcher.isRowInLatestPendingWindow(rows, 0, 2));
        assertFalse(GoogleSheetsTrackingMatcher.isRowInLatestPendingWindow(rows, 0, 3));
        assertFalse(GoogleSheetsTrackingMatcher.isRowInLatestPendingWindow(rows, 0, 4));
        assertFalse(GoogleSheetsTrackingMatcher.isRowInLatestPendingWindow(rows, 0, 5));
        assertFalse(GoogleSheetsTrackingMatcher.isRowInLatestPendingWindow(rows, 0, 6));
    }

    @Test
    public void applyRowsToOrders_blocksPendingShipmentMatches() {
        DispatchOrder order = new DispatchOrder(
                "가게A / Cafe24",
                "cafe24_home",
                "가게A",
                "ORDER-1",
                "ITEM-1",
                "",
                "INSTRUCT",
                "홍길동",
                "상품A",
                1,
                "2026-04-08",
                "1000원",
                "010-1234-5678",
                ""
        );

        GoogleSheetsTrackingMatcher.SheetRow pendingRow = new GoogleSheetsTrackingMatcher.SheetRow(
                10,
                "가게A|01012345678|TRACK-PENDING|10",
                "가게A",
                "TRACKPENDING",
                "01012345678",
                "홍길동",
                "CJ대한통운",
                true
        );

        GoogleSheetsTrackingMatcher.ParsedSheet parsedSheet = new GoogleSheetsTrackingMatcher.ParsedSheet(
                12,
                1,
                Arrays.asList(pendingRow)
        );

        GoogleSheetsTrackingMatcher.MatchResult result = GoogleSheetsTrackingMatcher.applyRowsToOrders(
                parsedSheet,
                Arrays.asList(order)
        );

        assertEquals("", order.trackingNumber);
        assertTrue(order.isUploadBlocked());
        assertFalse(order.selected);
        assertEquals(1, result.pendingBlockedCount);
    }

    @Test
    public void latestPendingWindow_keepsAllRepeatedPreviousDateSectionsPending() {
        List<List<String>> rows = Arrays.asList(
                Arrays.asList("상품명", "상품코드", "발주사"),
                Arrays.asList("2026.04.08", "1차"),
                Arrays.asList("상품A", "CODE-A", "가게A"),
                Arrays.asList("2026.04.08", "2차"),
                Arrays.asList("상품B", "CODE-B", "가게A"),
                Arrays.asList("2026.04.09", "오늘 1차"),
                Arrays.asList("상품C", "CODE-C", "가게A"),
                Arrays.asList("2026.04.09", "오늘 2차"),
                Arrays.asList("상품D", "CODE-D", "가게A")
        );

        assertFalse(GoogleSheetsTrackingMatcher.isRowInLatestPendingWindow(rows, 0, 1));
        assertTrue(GoogleSheetsTrackingMatcher.isRowInLatestPendingWindow(rows, 0, 2));
        assertFalse(GoogleSheetsTrackingMatcher.isRowInLatestPendingWindow(rows, 0, 3));
        assertTrue(GoogleSheetsTrackingMatcher.isRowInLatestPendingWindow(rows, 0, 4));
        assertFalse(GoogleSheetsTrackingMatcher.isRowInLatestPendingWindow(rows, 0, 5));
        assertFalse(GoogleSheetsTrackingMatcher.isRowInLatestPendingWindow(rows, 0, 6));
        assertFalse(GoogleSheetsTrackingMatcher.isRowInLatestPendingWindow(rows, 0, 7));
        assertFalse(GoogleSheetsTrackingMatcher.isRowInLatestPendingWindow(rows, 0, 8));
    }

    @Test
    public void applyRowsToOrders_blocksPendingShipmentWithoutTrackingAndClearsStaleTracking() {
        DispatchOrder order = new DispatchOrder(
                "가게A / Cafe24",
                "cafe24_home",
                "가게A",
                "ORDER-1",
                "ITEM-1",
                "",
                "INSTRUCT",
                "홍길동",
                "상품A",
                1,
                "2026-04-08",
                "1000원",
                "010-1234-5678",
                ""
        );
        order.trackingNumber = "OLDTRACK";
        order.selected = true;

        GoogleSheetsTrackingMatcher.SheetRow pendingRow = new GoogleSheetsTrackingMatcher.SheetRow(
                10,
                "가게A|01012345678||10",
                "가게A",
                "",
                "01012345678",
                "홍길동",
                "CJ대한통운",
                true
        );

        GoogleSheetsTrackingMatcher.ParsedSheet parsedSheet = new GoogleSheetsTrackingMatcher.ParsedSheet(
                12,
                0,
                Arrays.asList(pendingRow)
        );

        GoogleSheetsTrackingMatcher.MatchResult result = GoogleSheetsTrackingMatcher.applyRowsToOrders(
                parsedSheet,
                Arrays.asList(order)
        );

        assertEquals("", order.trackingNumber);
        assertTrue(order.isUploadBlocked());
        assertFalse(order.selected);
        assertEquals(1, result.pendingBlockedCount);
        assertEquals(0, result.noTrackingCount);
    }

    @Test
    public void applyRowsToOrders_clearsStaleTrackingWhenNoCandidateMatches() {
        DispatchOrder order = new DispatchOrder(
                "가게A / Cafe24",
                "cafe24_home",
                "가게A",
                "ORDER-1",
                "ITEM-1",
                "",
                "INSTRUCT",
                "홍길동",
                "상품A",
                1,
                "2026-04-08",
                "1000원",
                "010-1234-5678",
                ""
        );
        order.trackingNumber = "OLDTRACK";
        order.selected = true;

        GoogleSheetsTrackingMatcher.ParsedSheet parsedSheet = new GoogleSheetsTrackingMatcher.ParsedSheet(
                3,
                1,
                Arrays.asList(
                        new GoogleSheetsTrackingMatcher.SheetRow(
                                1,
                                "가게A|01099998888|TRACK-ONE|1",
                                "가게A",
                                "TRACKONE",
                                "01099998888",
                                "홍길동",
                                "CJ대한통운"
                        )
                )
        );

        GoogleSheetsTrackingMatcher.MatchResult result = GoogleSheetsTrackingMatcher.applyRowsToOrders(
                parsedSheet,
                Arrays.asList(order)
        );

        assertEquals("", order.trackingNumber);
        assertFalse(order.selected);
        assertFalse(order.isUploadBlocked());
        assertEquals(1, result.unmatchedCount);
    }

    @Test
    public void applyRowsToOrders_matchesWhenDuplicateRowsShareOneTrackingNumber() {
        DispatchOrder order = new DispatchOrder(
                "홈런마켓 / Cafe24",
                "home",
                "홈런마켓",
                "ORDER-1",
                "ITEM-1",
                "",
                "INSTRUCT",
                "홍길동",
                "상품A",
                1,
                "2026-04-10",
                "1000원",
                "010-1234-5678",
                ""
        );

        GoogleSheetsTrackingMatcher.ParsedSheet parsedSheet = new GoogleSheetsTrackingMatcher.ParsedSheet(
                4,
                2,
                Arrays.asList(
                        new GoogleSheetsTrackingMatcher.SheetRow(
                                1,
                                "홈런마켓|01012345678|TRACKHOME|1",
                                "홈런마켓",
                                "TRACKHOME",
                                "01012345678",
                                "홍길동",
                                "CJ대한통운"
                        ),
                        new GoogleSheetsTrackingMatcher.SheetRow(
                                2,
                                "홈런마켓|01012345678|TRACKHOME|2",
                                "홈런마켓",
                                "TRACKHOME",
                                "01012345678",
                                "홍길동",
                                "CJ대한통운"
                        )
                )
        );

        GoogleSheetsTrackingMatcher.MatchResult result = GoogleSheetsTrackingMatcher.applyRowsToOrders(
                parsedSheet,
                Arrays.asList(order)
        );

        assertEquals("TRACKHOME", order.trackingNumber);
        assertTrue(order.selected);
        assertEquals(1, result.matchedCount);
        assertEquals(0, result.candidateCount);
    }

    @Test
    public void scopeParsedSheetToOrders_limitsSummaryRowsToSelectedMarket() {
        DispatchOrder homeOrder = new DispatchOrder(
                "홈런마켓 / Cafe24",
                "home",
                "홈런마켓",
                "ORDER-1",
                "ITEM-1",
                "",
                "INSTRUCT",
                "홍길동",
                "상품A",
                1,
                "2026-04-10",
                "1000원",
                "010-1234-5678",
                ""
        );

        GoogleSheetsTrackingMatcher.ParsedSheet parsedSheet = new GoogleSheetsTrackingMatcher.ParsedSheet(
                4,
                2,
                Arrays.asList(
                        new GoogleSheetsTrackingMatcher.SheetRow(
                                1,
                                "홈런마켓|01012345678|TRACKHOME|1",
                                "홈런마켓",
                                "TRACKHOME",
                                "01012345678",
                                "홍길동",
                                "CJ대한통운"
                        ),
                        new GoogleSheetsTrackingMatcher.SheetRow(
                                2,
                                "준비몰|01099998888|TRACKPREP|2",
                                "준비몰",
                                "TRACKPREP",
                                "01099998888",
                                "김영희",
                                "CJ대한통운"
                        )
                )
        );

        GoogleSheetsTrackingMatcher.ParsedSheet scopedSheet = GoogleSheetsTrackingMatcher.scopeParsedSheetToOrders(
                parsedSheet,
                Arrays.asList(homeOrder)
        );
        GoogleSheetsTrackingMatcher.MatchResult result = GoogleSheetsTrackingMatcher.applyRowsToOrders(
                scopedSheet,
                Arrays.asList(homeOrder)
        );

        assertEquals(1, scopedSheet.sheetRowCount);
        assertEquals(1, scopedSheet.trackingRowCount);
        assertEquals("구글시트 출고정보 / 홈런마켓 · 송장행 1개, 매칭 1건", result.summary());
    }
}

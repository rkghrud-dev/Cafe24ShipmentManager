package com.rkghrud.shipapp.data;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GoogleSheetsTrackingMatcherTest {

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
}

package com.rkghrud.shipapp.data;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DispatchOrderUiHelperTest {

    @Test
    public void filterOrdersForSelection_limitsInvoiceMatchingToSelectedMarket() {
        DispatchOrder homeOrder = new DispatchOrder(
                "홈런마켓 / Cafe24",
                "home",
                "홈런마켓",
                "ORDER-1",
                "ITEM-1",
                "",
                "N20",
                "홍길동",
                "상품A",
                1,
                "2026-04-10",
                "1000원",
                "010-1111-1111",
                ""
        );
        DispatchOrder prepareOrder = new DispatchOrder(
                "준비몰 / Cafe24",
                "prepare",
                "준비몰",
                "ORDER-2",
                "ITEM-2",
                "",
                "N20",
                "김영희",
                "상품B",
                1,
                "2026-04-10",
                "2000원",
                "010-2222-2222",
                ""
        );
        DispatchOrder shippingOrder = new DispatchOrder(
                "홈런마켓 / Cafe24",
                "home",
                "홈런마켓",
                "ORDER-3",
                "ITEM-3",
                "",
                "N30",
                "박철수",
                "상품C",
                1,
                "2026-04-10",
                "3000원",
                "010-3333-3333",
                ""
        );

        List<DispatchOrder> scoped = DispatchOrderUiHelper.filterOrdersForSelection(
                Arrays.asList(homeOrder, prepareOrder, shippingOrder),
                "home",
                new LinkedHashSet<>(Arrays.asList(DispatchOrder.STATUS_FILTER_PREPARING, DispatchOrder.STATUS_FILTER_STANDBY))
        );

        assertEquals(1, scoped.size());
        assertEquals("ORDER-1", scoped.get(0).orderId);
    }

    @Test
    public void formatMarketCountSummary_formatsCountsAndWarningsInOrder() {
        LinkedHashMap<String, String> counts = new LinkedHashMap<>();
        counts.put("홈런마켓", "120");
        counts.put("준비몰", "확인 필요");
        counts.put("뭉클몰", "0");

        String summary = DispatchOrderUiHelper.formatMarketCountSummary(counts);

        assertEquals("홈런마켓 120건 · 준비몰 확인 필요 · 뭉클몰 0건", summary);
    }

    @Test
    public void matchesMarketKey_treatsAllAndEmptyAsUnscoped() {
        assertTrue(DispatchOrderUiHelper.matchesMarketKey("home", "all"));
        assertTrue(DispatchOrderUiHelper.matchesMarketKey("home", ""));
        assertTrue(DispatchOrderUiHelper.matchesMarketKey("coupang", null));
    }

    @Test
    public void matchesMarketKey_limitsFetchesToSelectedMarketOnly() {
        assertTrue(DispatchOrderUiHelper.matchesMarketKey("home", "home"));
        assertFalse(DispatchOrderUiHelper.matchesMarketKey("prepare", "home"));
        assertFalse(DispatchOrderUiHelper.matchesMarketKey("coupang", "home"));
        assertTrue(DispatchOrderUiHelper.matchesMarketKey("coupang", "coupang"));
    }
}

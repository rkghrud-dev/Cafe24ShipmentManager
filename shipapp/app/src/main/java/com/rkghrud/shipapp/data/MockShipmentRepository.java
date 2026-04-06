package com.rkghrud.shipapp.data;

import java.util.ArrayList;
import java.util.List;

public class MockShipmentRepository implements ShipmentRepository {
    @Override
    public ShipmentDashboardSnapshot fetchDashboard() {
        List<ShipmentSummary> shipments = new ArrayList<>();
        shipments.add(new ShipmentSummary(
                "홈런마켓 / 쿠팡",
                "31100181665876",
                "672182317236258",
                "쿠팡",
                "김용문",
                "쿠팡 주문 예시 상품",
                2,
                "15,800원",
                "2026-04-03 23:32",
                true,
                20260403233200L
        ));
        shipments.add(new ShipmentSummary(
                "홈런마켓 / Cafe24",
                "20260402-0000024",
                "20260402-0000024-01",
                "Cafe24",
                "강명호",
                "Cafe24 배송준비 상품",
                1,
                "21,000원",
                "2026-04-03 18:52",
                true,
                20260403185200L
        ));

        return new ShipmentDashboardSnapshot(
                shipments,
                0,
                0,
                "아직 저장된 키가 없어 목업 데이터만 표시합니다.",
                "홈런마켓 Cafe24\n미연결",
                "준비몰 Cafe24\n미연결",
                "쿠팡\n미연결"
        );
    }
}

package com.rkghrud.shipapp.data;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class DispatchOrder {
    public static final String STATUS_FILTER_PREPARING = "preparing";
    public static final String STATUS_FILTER_STANDBY = "standby";
    public static final String STATUS_FILTER_SHIPPING = "shipping";
    public static final String STATUS_FILTER_CANCEL_REQUEST = "cancel_request";
    public static final String STATUS_FILTER_EXCHANGE_REQUEST = "exchange_request";
    public static final String STATUS_FILTER_OTHER = "other";

    public final String marketLabel;
    public final String marketKey;
    public final String marketName;
    public final String orderId;
    public final String orderItemCode;
    public final String shipmentBoxId;
    public final String orderStatus;
    public final String recipientName;
    public final String productName;
    public final int quantity;
    public final String orderDate;
    public final String purchaseAmount;
    public final String recipientCellPhone;
    public final String recipientPhone;

    public String trackingNumber = "";
    public String shippingCompanyName = "CJ대한통운";
    public String cafe24ShippingCode = "";
    public String marketSourceLabel = "";
    public String marketOrderReference = "";
    public boolean selected = false;
    public boolean pendingShipment = false;
    public boolean newlyDetected = false;
    public String pendingShipmentMessage = "";
    public int pendingSheetRowIndex = -1;

    public DispatchOrder(
            String marketLabel,
            String marketKey,
            String orderId,
            String orderItemCode,
            String shipmentBoxId,
            String orderStatus,
            String recipientName,
            String productName,
            int quantity,
            String orderDate,
            String purchaseAmount
    ) {
        this(
                marketLabel,
                marketKey,
                deriveMarketName(marketLabel, marketKey),
                orderId,
                orderItemCode,
                shipmentBoxId,
                orderStatus,
                recipientName,
                productName,
                quantity,
                orderDate,
                purchaseAmount,
                "",
                ""
        );
    }

    public DispatchOrder(
            String marketLabel,
            String marketKey,
            String orderId,
            String orderItemCode,
            String shipmentBoxId,
            String orderStatus,
            String recipientName,
            String productName,
            int quantity,
            String orderDate,
            String purchaseAmount,
            String recipientCellPhone,
            String recipientPhone
    ) {
        this(
                marketLabel,
                marketKey,
                deriveMarketName(marketLabel, marketKey),
                orderId,
                orderItemCode,
                shipmentBoxId,
                orderStatus,
                recipientName,
                productName,
                quantity,
                orderDate,
                purchaseAmount,
                recipientCellPhone,
                recipientPhone
        );
    }

    public DispatchOrder(
            String marketLabel,
            String marketKey,
            String marketName,
            String orderId,
            String orderItemCode,
            String shipmentBoxId,
            String orderStatus,
            String recipientName,
            String productName,
            int quantity,
            String orderDate,
            String purchaseAmount,
            String recipientCellPhone,
            String recipientPhone
    ) {
        this.marketLabel = marketLabel;
        this.marketKey = marketKey;
        this.marketName = safe(marketName);
        this.orderId = orderId;
        this.orderItemCode = orderItemCode;
        this.shipmentBoxId = shipmentBoxId;
        this.orderStatus = orderStatus;
        this.recipientName = recipientName;
        this.productName = productName;
        this.quantity = quantity;
        this.orderDate = orderDate;
        this.purchaseAmount = purchaseAmount;
        this.recipientCellPhone = PhoneNormalizer.normalize(recipientCellPhone);
        this.recipientPhone = PhoneNormalizer.normalize(recipientPhone);
    }

    public String statusLabel() {
        String normalized = safe(orderStatus).toUpperCase(Locale.US);
        switch (normalized) {
            case "N10":
                return "상품준비중";
            case "N20":
            case "ACCEPT":
                return "배송준비중";
            case "N21":
            case "STANDBY":
            case "INSTRUCT":
                return "배송대기";
            case "N22":
                return "배송보류";
            case "N30":
            case "SHIPPING":
                return "배송중";
            case "SHIPPED":
                return "배송완료";
            case "C00":
                return "취소신청";
            case "E00":
                return "교환신청";
            default:
                return normalized.isEmpty() ? "상태 미확인" : normalized;
        }
    }

    public String statusFilterKey() {
        String normalized = safe(orderStatus).toUpperCase(Locale.US);
        switch (normalized) {
            case "N10":
            case "N20":
            case "ACCEPT":
                return STATUS_FILTER_PREPARING;
            case "N21":
            case "STANDBY":
            case "INSTRUCT":
                return STATUS_FILTER_STANDBY;
            case "N30":
            case "SHIPPING":
            case "SHIPPED":
                return STATUS_FILTER_SHIPPING;
            case "C00":
                return STATUS_FILTER_CANCEL_REQUEST;
            case "E00":
                return STATUS_FILTER_EXCHANGE_REQUEST;
            default:
                return STATUS_FILTER_OTHER;
        }
    }

    public boolean canUploadTracking() {
        String statusKey = statusFilterKey();
        return STATUS_FILTER_PREPARING.equals(statusKey) || STATUS_FILTER_STANDBY.equals(statusKey);
    }

    public boolean isSelectableForUpload() {
        return canUploadTracking() && !pendingShipment;
    }

    public String shortMarketLabel() {
        if ("coupang".equals(marketKey)) {
            return "쿠팡";
        }
        if (!marketName.isEmpty()) {
            return marketName;
        }
        if (marketLabel != null && marketLabel.contains("/")) {
            return marketLabel.split("/")[0].trim();
        }
        return safe(marketLabel);
    }

    public String marketSourceBadgeLabel() {
        String source = safe(marketSourceLabel);
        if (!source.isEmpty()) {
            return source;
        }
        if (marketLabel != null && marketLabel.contains("/")) {
            String[] parts = marketLabel.split("/");
            if (parts.length > 1) {
                return parts[1].trim();
            }
        }
        if ("coupang".equals(marketKey)) {
            return "쿠팡";
        }
        return "Cafe24";
    }

    public String stableOrderKey() {
        String basis = firstNonEmpty(orderItemCode, shipmentBoxId, marketOrderReference, orderId, productName);
        if (basis.isEmpty()) {
            return "";
        }
        return safe(marketKey) + "|" + basis;
    }

    public String carrierLabel() {
        return ShippingCompanyResolver.displayName(shippingCompanyName);
    }

    public String shippingCode() {
        return ShippingCompanyResolver.resolve(marketKey, shippingCompanyName);
    }

    public boolean isUploadBlocked() {
        return pendingShipment;
    }

    public String uploadBlockedLabel() {
        return pendingShipmentMessage.isEmpty() ? "미출고 확인 필요" : pendingShipmentMessage;
    }

    public void clearPendingShipmentFlag() {
        pendingShipment = false;
        pendingShipmentMessage = "";
        pendingSheetRowIndex = -1;
    }

    public void clearTrackingMatchState() {
        trackingNumber = "";
        selected = false;
        clearPendingShipmentFlag();
    }

    public void markPendingShipment(String message, int sheetRowIndex) {
        pendingShipment = true;
        pendingShipmentMessage = safe(message);
        pendingSheetRowIndex = sheetRowIndex;
        selected = false;
    }

    public List<String> getMatchKeys() {
        List<String> keys = new ArrayList<>();
        addKey(keys, shipmentBoxId);
        addKey(keys, orderItemCode);
        addKey(keys, orderId);
        return keys;
    }

    private void addKey(List<String> keys, String value) {
        if (value == null) {
            return;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        keys.add(trimmed);
    }

    private static String deriveMarketName(String marketLabel, String marketKey) {
        if (marketLabel != null && marketLabel.contains("/")) {
            return marketLabel.split("/")[0].trim();
        }
        if ("coupang".equals(marketKey)) {
            return "쿠팡";
        }
        return safe(marketLabel);
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            String trimmed = safe(value);
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return "";
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public static Comparator<DispatchOrder> displayComparator() {
        return Comparator
                .comparing(DispatchOrder::isUploadBlocked)
                .reversed()
                .thenComparing((DispatchOrder order) -> order.newlyDetected)
                .reversed()
                .thenComparing((DispatchOrder order) -> safe(order.orderDate), Comparator.reverseOrder())
                .thenComparing(order -> safe(order.marketLabel))
                .thenComparing(order -> safe(order.recipientName));
    }
}

package com.rkghrud.shipapp.data;

import java.util.ArrayList;
import java.util.List;

public class DispatchOrder {
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
    public boolean selected = false;

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
        if ("ACCEPT".equalsIgnoreCase(orderStatus)) return "상품준비";
        if ("INSTRUCT".equalsIgnoreCase(orderStatus)) return "출고지시";
        return "배송준비";
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

    public String carrierLabel() {
        return ShippingCompanyResolver.displayName(shippingCompanyName);
    }

    public String shippingCode() {
        return ShippingCompanyResolver.resolve(marketKey, shippingCompanyName);
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

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}

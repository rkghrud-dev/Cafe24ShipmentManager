package com.rkghrud.shipapp.data;

public class ShipmentSummary {
    private final String marketLabel;
    private final String orderId;
    private final String trackingNumber;
    private final String shopLabel;
    private final String recipientName;
    private final String productName;
    private final int quantity;
    private final String purchaseAmount;
    private final String updatedAt;
    private final boolean requiresAction;
    private final long sortTime;

    public ShipmentSummary(
            String marketLabel,
            String orderId,
            String trackingNumber,
            String shopLabel,
            String recipientName,
            String productName,
            int quantity,
            String purchaseAmount,
            String updatedAt,
            boolean requiresAction,
            long sortTime
    ) {
        this.marketLabel = marketLabel;
        this.orderId = orderId;
        this.trackingNumber = trackingNumber;
        this.shopLabel = shopLabel;
        this.recipientName = recipientName;
        this.productName = productName;
        this.quantity = quantity;
        this.purchaseAmount = purchaseAmount;
        this.updatedAt = updatedAt;
        this.requiresAction = requiresAction;
        this.sortTime = sortTime;
    }

    public String getMarketLabel() { return marketLabel; }
    public String getOrderId() { return orderId; }
    public String getTrackingNumber() { return trackingNumber; }
    public String getShopLabel() { return shopLabel; }
    public String getRecipientName() { return recipientName; }
    public String getProductName() { return productName; }
    public int getQuantity() { return quantity; }
    public String getPurchaseAmount() { return purchaseAmount; }
    public String getUpdatedAt() { return updatedAt; }
    public boolean requiresAction() { return requiresAction; }
    public long getSortTime() { return sortTime; }
}

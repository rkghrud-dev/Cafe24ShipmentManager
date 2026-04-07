package com.rkghrud.shipapp.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.rkghrud.shipapp.R;
import com.rkghrud.shipapp.data.DispatchOrder;

public final class OrderDetailDialog {
    private static final int PRODUCT_NAME_LIMIT = 10;

    private OrderDetailDialog() {
    }

    public static void show(Context context, DispatchOrder order) {
        View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_order_detail, null);
        LinearLayout detailTable = dialogView.findViewById(R.id.layoutDetailTable);

        addRow(detailTable, "마켓", safe(order.marketLabel), 2);
        addRow(detailTable, "주문번호", safe(order.orderId), 2);
        addRow(detailTable, "주문상품코드", safe(order.orderItemCode), 2);
        addRow(detailTable, "shipmentBoxId", safe(order.shipmentBoxId), 2);
        addRow(detailTable, "수령인", safe(order.recipientName), 1);
        addRow(detailTable, "휴대폰", safe(order.recipientCellPhone), 1);
        addRow(detailTable, "전화번호", safe(order.recipientPhone), 1);
        addRow(detailTable, "상품명", compactProductName(order.productName), 1);
        addRow(detailTable, "수량", order.quantity > 0 ? order.quantity + "개" : "-", 1);
        addRow(detailTable, "금액", safe(order.purchaseAmount), 1);
        addRow(detailTable, "주문일", safe(order.orderDate), 2);
        addRow(detailTable, "상태", safe(order.statusLabel()), 1);
        addRow(detailTable, "택배사", safe(order.carrierLabel()), 1);
        addRow(detailTable, "송장번호", safe(order.trackingNumber), 2);

        new MaterialAlertDialogBuilder(context)
                .setTitle("주문 상세")
                .setView(dialogView)
                .setPositiveButton("확인", null)
                .show();
    }

    private static void addRow(LinearLayout detailTable, String label, String value, int maxLines) {
        Context context = detailTable.getContext();
        if (detailTable.getChildCount() > 0) {
            View divider = new View(context);
            divider.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dpToPx(context, 1)
            ));
            divider.setBackgroundColor(ContextCompat.getColor(context, R.color.ship_outline));
            detailTable.addView(divider);
        }

        View row = LayoutInflater.from(context).inflate(R.layout.item_order_detail_row, detailTable, false);
        TextView labelView = row.findViewById(R.id.tvDetailLabel);
        TextView valueView = row.findViewById(R.id.tvDetailValue);
        String safeValue = safe(value);
        labelView.setText(label);
        valueView.setText(safeValue);
        valueView.setMaxLines(maxLines);
        valueView.setContentDescription(label + " " + safeValue);
        detailTable.addView(row);
    }

    private static String compactProductName(String value) {
        String clean = safe(value);
        if ("-".equals(clean) || clean.length() <= PRODUCT_NAME_LIMIT) {
            return clean;
        }
        return clean.substring(0, PRODUCT_NAME_LIMIT - 1) + "…";
    }

    private static String safe(String value) {
        return (value == null || value.trim().isEmpty()) ? "-" : value.trim();
    }

    private static int dpToPx(Context context, int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }
}

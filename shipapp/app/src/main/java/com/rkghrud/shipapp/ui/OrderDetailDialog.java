package com.rkghrud.shipapp.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.rkghrud.shipapp.R;
import com.rkghrud.shipapp.data.DispatchOrder;

public final class OrderDetailDialog {
    public interface OnTrackingSavedListener {
        void onTrackingSaved(String trackingNumber);
    }

    private OrderDetailDialog() {
    }

    public static void show(Context context, DispatchOrder order) {
        show(context, order, null);
    }

    public static void show(Context context, DispatchOrder order, OnTrackingSavedListener listener) {
        View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_order_detail, null);
        LinearLayout detailTable = dialogView.findViewById(R.id.layoutDetailTable);
        EditText trackingInput = dialogView.findViewById(R.id.etDetailTracking);
        TextView trackingHintView = dialogView.findViewById(R.id.tvTrackingInputHint);

        addRow(detailTable, "판매처", safe(order.shortMarketLabel()), 2);
        addRow(detailTable, "주문수집마켓", safe(order.marketSourceBadgeLabel()), 1);
        addRow(detailTable, "배송상태", safe(order.statusLabel()), 1);
        addRow(detailTable, "택배사", safe(order.carrierLabel()), 1);
        addRow(detailTable, "현재 송장번호", safe(order.trackingNumber), 2);
        if (hasText(order.marketOrderReference)) {
            addRow(detailTable, "채널주문번호", safe(order.marketOrderReference), 2);
        }
        addRow(detailTable, "주문번호", safe(order.orderId), 2);
        addRow(detailTable, "주문상품코드", safe(order.orderItemCode), 2);
        addRow(detailTable, "shipmentBoxId", safe(order.shipmentBoxId), 2);
        addRow(detailTable, "수령인", safe(order.recipientName), 1);
        addRow(detailTable, "휴대폰", safe(order.recipientCellPhone), 1);
        addRow(detailTable, "전화번호", safe(order.recipientPhone), 1);
        addRow(detailTable, "상품명", safe(order.productName), 4);
        addRow(detailTable, "수량", order.quantity > 0 ? order.quantity + "개" : "-", 1);
        addRow(detailTable, "금액", safe(order.purchaseAmount), 1);
        addRow(detailTable, "주문일", safe(order.orderDate), 2);

        boolean trackingEditable = order.isSelectableForUpload();
        trackingInput.setText(order.trackingNumber == null ? "" : order.trackingNumber.trim());
        trackingInput.setEnabled(trackingEditable);
        trackingInput.setFocusable(trackingEditable);
        trackingInput.setFocusableInTouchMode(trackingEditable);
        trackingInput.setClickable(trackingEditable);
        trackingInput.setAlpha(trackingEditable ? 1f : 0.7f);
        trackingHintView.setText(resolveTrackingHint(order, trackingEditable));
        trackingHintView.setTextColor(ContextCompat.getColor(context,
                order.isUploadBlocked() ? R.color.ship_error : R.color.ship_text_secondary));

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context)
                .setTitle("주문 상세")
                .setView(dialogView);

        if (trackingEditable) {
            builder.setNegativeButton("닫기", null)
                    .setPositiveButton("저장", (dialog, which) -> {
                        String trackingNumber = trackingInput.getText() == null ? "" : trackingInput.getText().toString().trim();
                        order.trackingNumber = trackingNumber;
                        if (listener != null) {
                            listener.onTrackingSaved(trackingNumber);
                        }
                    });
        } else {
            builder.setPositiveButton("확인", null);
        }

        builder.show();
    }

    private static String resolveTrackingHint(DispatchOrder order, boolean trackingEditable) {
        if (order.isUploadBlocked()) {
            return "미출고 주문은 송장번호를 입력할 수 없습니다.";
        }
        if (!trackingEditable) {
            return "현재 상태에서는 송장번호를 수정할 수 없습니다.";
        }
        return "상세 상단에서 송장번호를 입력하고 저장하세요.";
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

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String safe(String value) {
        return (value == null || value.trim().isEmpty()) ? "-" : value.trim();
    }

    private static int dpToPx(Context context, int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }
}

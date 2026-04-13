package com.rkghrud.shipapp.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.rkghrud.shipapp.R;
import com.rkghrud.shipapp.data.DispatchOrder;

import java.util.ArrayList;
import java.util.List;

public class DispatchOrderAdapter extends RecyclerView.Adapter<DispatchOrderAdapter.ViewHolder> {
    private final List<DispatchOrder> items = new ArrayList<>();
    private Runnable onSelectionChanged;
    private OnItemClickListener onItemClick;

    public interface OnItemClickListener {
        void onItemClick(DispatchOrder order);
    }

    public void setItems(List<DispatchOrder> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
        if (onSelectionChanged != null) onSelectionChanged.run();
    }

    public void setOnSelectionChanged(Runnable runnable) {
        onSelectionChanged = runnable;
    }

    public void setOnItemClick(OnItemClickListener listener) {
        onItemClick = listener;
    }

    public void selectAll(boolean selected) {
        for (DispatchOrder order : items) {
            if (!order.isSelectableForUpload()) {
                order.selected = false;
                continue;
            }
            order.selected = selected;
        }
        notifyDataSetChanged();
        if (onSelectionChanged != null) onSelectionChanged.run();
    }

    public List<DispatchOrder> getSelectedItems() {
        List<DispatchOrder> result = new ArrayList<>();
        for (DispatchOrder order : items) {
            if (order.selected && isReady(order)) result.add(order);
        }
        return result;
    }

    public int getSelectedCount() {
        int count = 0;
        for (DispatchOrder order : items) if (order.selected) count++;
        return count;
    }

    public int getReadyCount() {
        int count = 0;
        for (DispatchOrder order : items) if (order.selected && isReady(order)) count++;
        return count;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_dispatch_order, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(items.get(position), onSelectionChanged, onItemClick);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private boolean hasTracking(DispatchOrder order) {
        return order.trackingNumber != null && !order.trackingNumber.trim().isEmpty();
    }

    private boolean isReady(DispatchOrder order) {
        return hasTracking(order) && order.isSelectableForUpload();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final MaterialCardView cardRoot;
        final MaterialCheckBox cbSelect;
        final TextView tvRecipient;
        final TextView tvMeta;
        final TextView tvAmount;
        final TextView tvProduct;
        final TextView tvQuantity;
        final TextView tvMarket;
        final TextView tvStatusBadge;
        final TextView tvMarketSource;
        final TextView tvTrackingSummary;
        final TextView tvNewBadge;
        final TextView tvAlert;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            cardRoot = itemView.findViewById(R.id.cardRoot);
            cbSelect = itemView.findViewById(R.id.cbSelect);
            tvRecipient = itemView.findViewById(R.id.tvRecipient);
            tvMeta = itemView.findViewById(R.id.tvMeta);
            tvAmount = itemView.findViewById(R.id.tvAmount);
            tvProduct = itemView.findViewById(R.id.tvProduct);
            tvQuantity = itemView.findViewById(R.id.tvQuantity);
            tvMarket = itemView.findViewById(R.id.tvMarket);
            tvStatusBadge = itemView.findViewById(R.id.tvStatusBadge);
            tvMarketSource = itemView.findViewById(R.id.tvMarketSource);
            tvTrackingSummary = itemView.findViewById(R.id.tvTrackingSummary);
            tvNewBadge = itemView.findViewById(R.id.tvNewBadge);
            tvAlert = itemView.findViewById(R.id.tvAlert);
        }

        void bind(DispatchOrder order, Runnable onSelectionChanged, OnItemClickListener onItemClick) {
            boolean selectable = order.isSelectableForUpload();
            boolean pendingBlocked = order.isUploadBlocked();
            if (!selectable && order.selected) {
                order.selected = false;
            }

            cbSelect.setOnCheckedChangeListener(null);
            cbSelect.setEnabled(selectable);
            cbSelect.setAlpha(selectable ? 1f : 0.45f);
            cbSelect.setChecked(selectable && order.selected);
            cbSelect.setOnCheckedChangeListener((btn, checked) -> {
                order.selected = selectable && checked;
                if (onSelectionChanged != null) onSelectionChanged.run();
            });

            String amount = safe(order.purchaseAmount, "");
            boolean hasTracking = hasTracking(order);
            View.OnClickListener detailClickListener = onItemClick == null ? null : v -> onItemClick.onItemClick(order);

            tvRecipient.setText(safe(order.recipientName, "이름 없음"));
            tvMeta.setText(buildMeta(order));
            tvProduct.setText(truncateProductName(order.productName));
            tvQuantity.setText(order.quantity > 0 ? String.valueOf(order.quantity) : "-");
            tvAmount.setText(amount.isEmpty() ? "-" : amount);

            tvMarket.setText(order.shortMarketLabel());
            tvMarketSource.setText(order.marketSourceBadgeLabel());
            tvTrackingSummary.setText(buildTrackingSummary(order));
            bindStatusBadge(order, pendingBlocked);
            tvNewBadge.setVisibility(order.newlyDetected ? View.VISIBLE : View.GONE);
            tvAlert.setVisibility(pendingBlocked ? View.VISIBLE : View.GONE);
            tvAlert.setText(buildBlockedLabel(order));

            int cardBackground = pendingBlocked ? R.color.ship_error_surface : R.color.ship_surface;
            int cardStroke = pendingBlocked ? R.color.ship_error : order.newlyDetected ? R.color.ship_primary : R.color.ship_outline;
            int marketText = pendingBlocked ? R.color.ship_error : R.color.ship_primary;
            int sourceText = pendingBlocked ? R.color.ship_error : R.color.ship_text_secondary;
            int recipientText = pendingBlocked ? R.color.ship_error : R.color.ship_text_primary;
            int metaText = pendingBlocked ? R.color.ship_error : R.color.ship_text_secondary;
            int productText = pendingBlocked ? R.color.ship_error : R.color.ship_text_primary;
            int quantityText = pendingBlocked ? R.color.ship_error : R.color.ship_text_primary;
            int trackingText = pendingBlocked ? R.color.ship_error : hasTracking ? R.color.ship_text_primary : R.color.ship_text_secondary;

            cardRoot.setCardBackgroundColor(ContextCompat.getColor(itemView.getContext(), cardBackground));
            cardRoot.setStrokeColor(ContextCompat.getColor(itemView.getContext(), cardStroke));
            cardRoot.setStrokeWidth(dp(pendingBlocked ? 2 : 1));
            tvMarket.setTextColor(ContextCompat.getColor(itemView.getContext(), marketText));
            tvMarketSource.setTextColor(ContextCompat.getColor(itemView.getContext(), sourceText));
            tvRecipient.setTextColor(ContextCompat.getColor(itemView.getContext(), recipientText));
            tvMeta.setTextColor(ContextCompat.getColor(itemView.getContext(), metaText));
            tvProduct.setTextColor(ContextCompat.getColor(itemView.getContext(), productText));
            tvQuantity.setTextColor(ContextCompat.getColor(itemView.getContext(), quantityText));
            tvTrackingSummary.setTextColor(ContextCompat.getColor(itemView.getContext(), trackingText));
            tvAmount.setTextColor(ContextCompat.getColor(itemView.getContext(),
                    pendingBlocked ? R.color.ship_error
                            : amount.isEmpty() || "-".equals(amount)
                            ? R.color.ship_text_secondary : R.color.ship_text_primary));

            cardRoot.setOnClickListener(detailClickListener);
            tvRecipient.setOnClickListener(detailClickListener);
            tvMeta.setOnClickListener(detailClickListener);
            tvProduct.setOnClickListener(detailClickListener);
            tvTrackingSummary.setOnClickListener(detailClickListener);
        }

        private boolean hasTracking(DispatchOrder order) {
            return order.trackingNumber != null && !order.trackingNumber.trim().isEmpty();
        }

        private String safe(String value, String fallback) {
            return (value == null || value.trim().isEmpty()) ? fallback : value.trim();
        }

        private String buildMeta(DispatchOrder order) {
            String orderDate = safe(order.orderDate, "");
            String orderId = safe(order.orderId, "");
            if (!orderDate.isEmpty() && !orderId.isEmpty()) {
                return orderDate + " · " + orderId;
            }
            if (!orderDate.isEmpty()) {
                return orderDate;
            }
            if (!orderId.isEmpty()) {
                return orderId;
            }
            return "주문 정보 없음";
        }

        private String truncateProductName(String productName) {
            String label = safe(productName, "상품명 없음");
            return label.length() <= 10 ? label : label.substring(0, 10) + "...";
        }

        private String buildTrackingSummary(DispatchOrder order) {
            String tracking = safe(order.trackingNumber, "");
            if (!tracking.isEmpty()) {
                return "송장 " + tracking;
            }
            if (order.isUploadBlocked()) {
                return "입력 불가";
            }
            if (order.isSelectableForUpload()) {
                return "송장 미입력";
            }
            return "송장 없음";
        }

        private String buildBlockedLabel(DispatchOrder order) {
            String label = safe(order.uploadBlockedLabel(), "미출고 확인 필요");
            return label.contains("미출고") ? label : "미출고 · " + label;
        }

        private void bindStatusBadge(DispatchOrder order, boolean pendingBlocked) {
            if (pendingBlocked) {
                tvStatusBadge.setText("미출고");
                tvStatusBadge.setBackgroundResource(R.drawable.bg_dispatch_pending_alert);
                tvStatusBadge.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.ship_error));
                return;
            }

            String statusKey = order.statusFilterKey();
            int backgroundRes;
            int textColorRes;
            switch (statusKey) {
                case DispatchOrder.STATUS_FILTER_SHIPPING:
                    backgroundRes = R.drawable.bg_status_success;
                    textColorRes = R.color.ship_success;
                    break;
                case DispatchOrder.STATUS_FILTER_STANDBY:
                case DispatchOrder.STATUS_FILTER_CANCEL_REQUEST:
                case DispatchOrder.STATUS_FILTER_EXCHANGE_REQUEST:
                    backgroundRes = R.drawable.bg_status_warning;
                    textColorRes = R.color.ship_warning;
                    break;
                case DispatchOrder.STATUS_FILTER_PREPARING:
                    backgroundRes = R.drawable.bg_market_chip;
                    textColorRes = R.color.ship_primary;
                    break;
                default:
                    backgroundRes = R.drawable.bg_badge_carrier;
                    textColorRes = R.color.ship_text_secondary;
                    break;
            }

            tvStatusBadge.setText(order.statusLabel());
            tvStatusBadge.setBackgroundResource(backgroundRes);
            tvStatusBadge.setTextColor(ContextCompat.getColor(itemView.getContext(), textColorRes));
        }

        private int dp(int value) {
            return Math.round(value * itemView.getResources().getDisplayMetrics().density);
        }
    }
}


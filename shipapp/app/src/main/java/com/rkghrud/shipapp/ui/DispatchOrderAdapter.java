package com.rkghrud.shipapp.ui;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

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
        for (DispatchOrder order : items) order.selected = selected;
        notifyDataSetChanged();
        if (onSelectionChanged != null) onSelectionChanged.run();
    }

    public List<DispatchOrder> getSelectedItems() {
        List<DispatchOrder> result = new ArrayList<>();
        for (DispatchOrder order : items) {
            if (order.selected && hasTracking(order)) result.add(order);
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
        for (DispatchOrder order : items) if (order.selected && hasTracking(order)) count++;
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

    static class ViewHolder extends RecyclerView.ViewHolder {
        final View cardRoot;
        final CheckBox cbSelect;
        final TextView tvRecipient;
        final TextView tvAmount;
        final TextView tvProduct;
        final TextView tvQuantity;
        final TextView tvMarket;
        final TextView tvCarrier;
        final EditText etTracking;
        TextWatcher trackingWatcher;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            cardRoot    = itemView.findViewById(R.id.cardRoot);
            cbSelect    = itemView.findViewById(R.id.cbSelect);
            tvRecipient = itemView.findViewById(R.id.tvRecipient);
            tvAmount    = itemView.findViewById(R.id.tvAmount);
            tvProduct   = itemView.findViewById(R.id.tvProduct);
            tvQuantity  = itemView.findViewById(R.id.tvQuantity);
            tvMarket    = itemView.findViewById(R.id.tvMarket);
            tvCarrier   = itemView.findViewById(R.id.tvCarrier);
            etTracking  = itemView.findViewById(R.id.etTracking);
        }

        void bind(DispatchOrder order, Runnable onSelectionChanged, OnItemClickListener onItemClick) {
            cbSelect.setOnCheckedChangeListener(null);
            cbSelect.setChecked(order.selected);
            cbSelect.setOnCheckedChangeListener((btn, checked) -> {
                order.selected = checked;
                if (onSelectionChanged != null) onSelectionChanged.run();
            });

            tvRecipient.setText(safe(order.recipientName, "이름 없음"));
            tvProduct.setText(safe(order.productName, "상품명 없음"));
            tvQuantity.setText(order.quantity > 0 ? order.quantity + "개" : "");

            String amt = safe(order.purchaseAmount, "");
            tvAmount.setText(amt);
            tvAmount.setTextColor(ContextCompat.getColor(itemView.getContext(),
                    amt.isEmpty() || "-".equals(amt)
                            ? R.color.ship_text_secondary : R.color.ship_text_primary));

            tvMarket.setText(order.shortMarketLabel());
            tvCarrier.setText(order.carrierLabel());

            if (trackingWatcher != null) etTracking.removeTextChangedListener(trackingWatcher);
            etTracking.setText(order.trackingNumber);
            trackingWatcher = new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override
                public void afterTextChanged(Editable s) {
                    order.trackingNumber = s.toString().trim();
                    if (onSelectionChanged != null) onSelectionChanged.run();
                }
            };
            etTracking.addTextChangedListener(trackingWatcher);

            // 카드 탭 → 주문 상세 (체크박스·EditText는 자체 이벤트 소비)
            cardRoot.setOnClickListener(onItemClick != null ? v -> onItemClick.onItemClick(order) : null);
        }

        private String safe(String value, String fallback) {
            return (value == null || value.trim().isEmpty()) ? fallback : value.trim();
        }
    }
}

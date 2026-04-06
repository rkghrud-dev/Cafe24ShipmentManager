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

    public void setItems(List<DispatchOrder> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
        if (onSelectionChanged != null) {
            onSelectionChanged.run();
        }
    }

    public void setOnSelectionChanged(Runnable runnable) {
        onSelectionChanged = runnable;
    }

    public void selectAll(boolean selected) {
        for (DispatchOrder order : items) {
            order.selected = selected;
        }
        notifyDataSetChanged();
        if (onSelectionChanged != null) {
            onSelectionChanged.run();
        }
    }

    public List<DispatchOrder> getSelectedItems() {
        List<DispatchOrder> selectedItems = new ArrayList<>();
        for (DispatchOrder order : items) {
            if (order.selected && hasTrackingNumber(order)) {
                selectedItems.add(order);
            }
        }
        return selectedItems;
    }

    public int getSelectedCount() {
        int count = 0;
        for (DispatchOrder order : items) {
            if (order.selected) {
                count++;
            }
        }
        return count;
    }

    public int getReadyCount() {
        int count = 0;
        for (DispatchOrder order : items) {
            if (order.selected && hasTrackingNumber(order)) {
                count++;
            }
        }
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
        holder.bind(items.get(position), onSelectionChanged);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private boolean hasTrackingNumber(DispatchOrder order) {
        return order.trackingNumber != null && !order.trackingNumber.trim().isEmpty();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
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
            cbSelect = itemView.findViewById(R.id.cbSelect);
            tvRecipient = itemView.findViewById(R.id.tvRecipient);
            tvAmount = itemView.findViewById(R.id.tvAmount);
            tvProduct = itemView.findViewById(R.id.tvProduct);
            tvQuantity = itemView.findViewById(R.id.tvQuantity);
            tvMarket = itemView.findViewById(R.id.tvMarket);
            tvCarrier = itemView.findViewById(R.id.tvCarrier);
            etTracking = itemView.findViewById(R.id.etTracking);
        }

        void bind(DispatchOrder order, Runnable onSelectionChanged) {
            cbSelect.setOnCheckedChangeListener(null);
            cbSelect.setChecked(order.selected);
            cbSelect.setOnCheckedChangeListener((buttonView, isChecked) -> {
                order.selected = isChecked;
                if (onSelectionChanged != null) {
                    onSelectionChanged.run();
                }
            });

            tvRecipient.setText(shorten(defaultText(order.recipientName, "이름 없음"), 6));
            tvAmount.setText(defaultText(order.purchaseAmount, "-"));
            tvProduct.setText(shorten(defaultText(order.productName, "상품명 없음"), 12));
            tvQuantity.setText(order.quantity > 0 ? order.quantity + "개" : "-");
            tvMarket.setText(order.shortMarketLabel());
            tvCarrier.setText(order.carrierLabel());
            tvAmount.setTextColor(ContextCompat.getColor(itemView.getContext(),
                    "-".equals(order.purchaseAmount) ? R.color.ship_text_secondary : R.color.ship_text_primary));

            if (trackingWatcher != null) {
                etTracking.removeTextChangedListener(trackingWatcher);
            }
            etTracking.setText(order.trackingNumber);
            trackingWatcher = new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    order.trackingNumber = s.toString().trim();
                    if (onSelectionChanged != null) {
                        onSelectionChanged.run();
                    }
                }
            };
            etTracking.addTextChangedListener(trackingWatcher);
        }

        private String defaultText(String value, String fallback) {
            return value == null || value.trim().isEmpty() ? fallback : value.trim();
        }

        private String shorten(String value, int limit) {
            return value.length() <= limit ? value : value.substring(0, limit) + "…";
        }
    }
}


package com.rkghrud.shipapp.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.rkghrud.shipapp.R;
import com.rkghrud.shipapp.data.ShipmentSummary;

import java.util.ArrayList;
import java.util.List;

public class ShipmentAdapter extends RecyclerView.Adapter<ShipmentAdapter.ViewHolder> {
    private final List<ShipmentSummary> items = new ArrayList<>();

    public void submitList(List<ShipmentSummary> shipments) {
        items.clear();
        items.addAll(shipments);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_shipment, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(items.get(position));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        private final TextView marketView;
        private final TextView recipientView;
        private final TextView productView;
        private final TextView quantityView;
        private final TextView amountView;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            marketView = itemView.findViewById(R.id.tvMarket);
            recipientView = itemView.findViewById(R.id.tvRecipient);
            productView = itemView.findViewById(R.id.tvProduct);
            quantityView = itemView.findViewById(R.id.tvQuantity);
            amountView = itemView.findViewById(R.id.tvAmount);
        }

        void bind(ShipmentSummary shipment) {
            recipientView.setText(defaultText(shipment.getRecipientName(), "이름 없음"));
            productView.setText(shortenProductName(defaultText(shipment.getProductName(), "상품명 없음")));
            quantityView.setText(buildQuantityLabel(shipment.getQuantity()));
            marketView.setText("쇼핑몰 " + defaultText(shipment.getShopLabel(), "-"));
            amountView.setText(defaultText(shipment.getPurchaseAmount(), "-"));

            int amountColor = "-".equals(shipment.getPurchaseAmount())
                    ? R.color.ship_text_secondary
                    : R.color.ship_text_primary;
            amountView.setTextColor(ContextCompat.getColor(itemView.getContext(), amountColor));
        }

        private String defaultText(String value, String fallback) {
            return value == null || value.trim().isEmpty() ? fallback : value.trim();
        }

        private String buildQuantityLabel(int quantity) {
            return quantity > 0 ? "수량 " + quantity + "개" : "수량 -";
        }

        private String shortenProductName(String productName) {
            return productName.length() <= 10 ? productName : productName.substring(0, 10) + "…";
        }
    }
}

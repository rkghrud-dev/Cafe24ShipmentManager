package com.rkghrud.shipapp;

import android.app.DatePickerDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.rkghrud.shipapp.data.Cafe24MarketConfig;
import com.rkghrud.shipapp.data.CredentialStore;
import com.rkghrud.shipapp.data.DispatchOrder;
import com.rkghrud.shipapp.data.GoogleSheetsTrackingMatcher;
import com.rkghrud.shipapp.data.LiveShipmentRepository;
import com.rkghrud.shipapp.ui.DispatchOrderAdapter;
import com.rkghrud.shipapp.ui.OrderDetailDialog;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ShipmentDispatchActivity extends AppCompatActivity {
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US);

    private Spinner spinnerMarket;
    private MaterialButton btnStartDate;
    private MaterialButton btnEndDate;
    private MaterialButton btnQuery;
    private MaterialButton btnSelectAll;
    private MaterialButton btnDeselectAll;
    private MaterialButton btnDispatch;
    private ProgressBar progressBar;
    private TextView tvStatusMsg;
    private RecyclerView rvOrders;
    private View layoutActions;
    private View dividerActions;

    private DispatchOrderAdapter adapter;
    private LiveShipmentRepository repository;
    private CredentialStore credentialStore;
    private ExecutorService executor;
    private ArrayAdapter<String> marketAdapter;

    private final List<String> marketLabels = new ArrayList<>();
    private final List<String> marketKeys = new ArrayList<>();

    private LocalDate startDate;
    private LocalDate endDate;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dispatch);

        repository = new LiveShipmentRepository(this);
        credentialStore = new CredentialStore(this);
        executor = Executors.newSingleThreadExecutor();

        endDate = LocalDate.now();
        startDate = endDate.minusDays(14);

        MaterialToolbar toolbar = findViewById(R.id.toolbarDispatch);
        spinnerMarket = findViewById(R.id.spinnerMarket);
        btnStartDate = findViewById(R.id.btnStartDate);
        btnEndDate = findViewById(R.id.btnEndDate);
        btnQuery = findViewById(R.id.btnQuery);
        btnSelectAll = findViewById(R.id.btnSelectAll);
        btnDeselectAll = findViewById(R.id.btnDeselectAll);
        btnDispatch = findViewById(R.id.btnDispatch);
        progressBar = findViewById(R.id.progressBar);
        tvStatusMsg = findViewById(R.id.tvStatusMsg);
        rvOrders = findViewById(R.id.rvOrders);
        layoutActions = findViewById(R.id.layoutActions);
        dividerActions = findViewById(R.id.dividerActions);

        toolbar.setNavigationOnClickListener(v -> finish());

        marketAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, marketLabels);
        marketAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerMarket.setAdapter(marketAdapter);

        updateDateButtons();
        rebuildMarketOptions();

        adapter = new DispatchOrderAdapter();
        adapter.setOnSelectionChanged(this::updateDispatchButton);
        adapter.setOnItemClick(this::showOrderDetail);
        rvOrders.setLayoutManager(new LinearLayoutManager(this));
        rvOrders.setAdapter(adapter);

        btnStartDate.setOnClickListener(v -> pickDate(true));
        btnEndDate.setOnClickListener(v -> pickDate(false));
        btnQuery.setOnClickListener(v -> queryOrders());
        btnSelectAll.setOnClickListener(v -> {
            adapter.selectAll(true);
            updateDispatchButton();
        });
        btnDeselectAll.setOnClickListener(v -> {
            adapter.selectAll(false);
            updateDispatchButton();
        });
        btnDispatch.setOnClickListener(v -> confirmAndDispatch());
    }

    @Override
    protected void onResume() {
        super.onResume();
        rebuildMarketOptions();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void rebuildMarketOptions() {
        String selectedKey = getSelectedMarketKey();
        marketLabels.clear();
        marketKeys.clear();

        for (Cafe24MarketConfig config : credentialStore.getActiveCafe24Markets()) {
            marketLabels.add("Cafe24 · " + config.displayName);
            marketKeys.add(config.key);
        }
        if (FeatureFlags.ENABLE_COUPANG && credentialStore.getCoupangCredentials().isComplete()) {
            marketLabels.add("쿠팡 · 홈런마켓");
            marketKeys.add("coupang");
        }
        if (marketLabels.isEmpty()) {
            marketLabels.add("판매처 없음");
            marketKeys.add("");
        }

        marketAdapter.notifyDataSetChanged();
        int selectedIndex = marketKeys.indexOf(selectedKey);
        spinnerMarket.setSelection(selectedIndex >= 0 ? selectedIndex : 0, false);

        boolean hasMarket = hasQueryableMarket();
        btnQuery.setEnabled(hasMarket && progressBar.getVisibility() != View.VISIBLE);
        if (!hasMarket) {
            tvStatusMsg.setText("설정에서 표시할 판매처를 먼저 추가하고 JSON을 연결하세요.");
            tvStatusMsg.setVisibility(View.VISIBLE);
            layoutActions.setVisibility(View.GONE);
            dividerActions.setVisibility(View.GONE);
        }
    }

    private void pickDate(boolean isStart) {
        LocalDate current = isStart ? startDate : endDate;
        Calendar calendar = Calendar.getInstance();
        calendar.set(current.getYear(), current.getMonthValue() - 1, current.getDayOfMonth());
        new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
            LocalDate picked = LocalDate.of(year, month + 1, dayOfMonth);
            if (isStart) {
                startDate = picked;
            } else {
                endDate = picked;
            }
            updateDateButtons();
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void updateDateButtons() {
        btnStartDate.setText(DATE_FMT.format(startDate));
        btnEndDate.setText(DATE_FMT.format(endDate));
    }

    private void queryOrders() {
        String marketKey = getSelectedMarketKey();
        if (marketKey.isEmpty()) {
            Toast.makeText(this, "조회 가능한 판매처가 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }

        setLoading(true);
        tvStatusMsg.setVisibility(View.GONE);
        layoutActions.setVisibility(View.GONE);
        dividerActions.setVisibility(View.GONE);

        LocalDate queryStartDate = startDate;
        LocalDate queryEndDate = endDate;
        String marketLabel = getSelectedMarketLabel();
        executor.execute(() -> {
            try {
                List<DispatchOrder> orders = repository.fetchOrdersForDispatch(marketKey, queryStartDate, queryEndDate);

                String matchSummary = "";
                if (!orders.isEmpty()) {
                    try {
                        GoogleSheetsTrackingMatcher.MatchResult match = GoogleSheetsTrackingMatcher.applyToOrders(orders);
                        matchSummary = match.summary();
                    } catch (Exception matchEx) {
                        matchSummary = "구글시트 매칭 실패: " + matchEx.getMessage();
                    }
                    orders.sort(DispatchOrder.displayComparator());
                }

                String finalMatchSummary = matchSummary;
                runOnUiThread(() -> {
                    setLoading(false);
                    if (orders.isEmpty()) {
                        tvStatusMsg.setText("조회 결과가 없습니다.\n(" + marketLabel + " / " + DATE_FMT.format(queryStartDate) + " ~ " + DATE_FMT.format(queryEndDate) + ")");
                        tvStatusMsg.setVisibility(View.VISIBLE);
                        layoutActions.setVisibility(View.GONE);
                        dividerActions.setVisibility(View.GONE);
                    } else {
                        layoutActions.setVisibility(View.VISIBLE);
                        dividerActions.setVisibility(View.VISIBLE);
                        adapter.setItems(orders);
                        updateDispatchButton();
                        if (!finalMatchSummary.isEmpty()) {
                            tvStatusMsg.setText(finalMatchSummary);
                            tvStatusMsg.setVisibility(View.VISIBLE);
                        } else {
                            tvStatusMsg.setVisibility(View.GONE);
                        }
                        Toast.makeText(this, orders.size() + "건 조회 완료", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception ex) {
                runOnUiThread(() -> {
                    setLoading(false);
                    tvStatusMsg.setText("조회 실패: " + ex.getMessage());
                    tvStatusMsg.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private void updateDispatchButton() {
        int selected = adapter.getSelectedCount();
        int ready = adapter.getReadyCount();
        btnDispatch.setText("선택 출고처리 (" + ready + "/" + selected + "건)");
        btnDispatch.setEnabled(ready > 0 && progressBar.getVisibility() != View.VISIBLE);
    }

    private void confirmAndDispatch() {
        List<DispatchOrder> targets = adapter.getSelectedItems();
        if (targets.isEmpty()) {
            Toast.makeText(this, "송장번호가 입력된 항목을 선택하세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("출고 처리 확인")
                .setMessage(targets.size() + "건의 주문에 송장을 전송합니다.\n계속하시겠습니까?")
                .setNegativeButton("취소", null)
                .setPositiveButton("출고처리", (dialog, which) -> dispatchSelected(targets))
                .show();
    }

    private void dispatchSelected(List<DispatchOrder> targets) {
        setLoading(true);
        executor.execute(() -> {
            List<String> success = new ArrayList<>();
            List<String> failed = new ArrayList<>();

            for (DispatchOrder order : targets) {
                String error = repository.pushTrackingNumber(order, order.shippingCode());
                String label = order.shortMarketLabel() + " / " + order.recipientName + " / " + order.trackingNumber;
                if (error == null || error.isEmpty()) {
                    success.add(label);
                } else {
                    failed.add(label + "\n  → " + error);
                }
            }

            runOnUiThread(() -> {
                setLoading(false);
                showResultDialog(success, failed);
            });
        });
    }

    private void showResultDialog(List<String> success, List<String> failed) {
        StringBuilder builder = new StringBuilder();
        if (!success.isEmpty()) {
            builder.append("성공 ").append(success.size()).append("건\n");
            for (String item : success) {
                builder.append("• ").append(item).append("\n");
            }
        }
        if (!failed.isEmpty()) {
            if (builder.length() > 0) {
                builder.append("\n");
            }
            builder.append("실패 ").append(failed.size()).append("건\n");
            for (String item : failed) {
                builder.append("• ").append(item).append("\n");
            }
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("출고처리 결과")
                .setMessage(builder.toString().trim())
                .setPositiveButton("확인", null)
                .show();
    }

    private void showOrderDetail(DispatchOrder order) {
        OrderDetailDialog.show(this, order);
    }

    private void setLoading(boolean loading) {
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnQuery.setEnabled(!loading && hasQueryableMarket());
        btnDispatch.setEnabled(!loading && adapter.getReadyCount() > 0);
    }

    private boolean hasQueryableMarket() {
        return !marketKeys.isEmpty() && !marketKeys.get(0).isEmpty();
    }

    private String getSelectedMarketKey() {
        int position = spinnerMarket.getSelectedItemPosition();
        if (position < 0 || position >= marketKeys.size()) {
            return "";
        }
        return marketKeys.get(position);
    }

    private String getSelectedMarketLabel() {
        int position = spinnerMarket.getSelectedItemPosition();
        if (position < 0 || position >= marketLabels.size()) {
            return "-";
        }
        return marketLabels.get(position);
    }
}


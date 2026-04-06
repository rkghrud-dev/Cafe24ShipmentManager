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
import com.rkghrud.shipapp.data.CredentialStore;
import com.rkghrud.shipapp.data.DispatchOrder;
import com.rkghrud.shipapp.data.LiveShipmentRepository;
import com.rkghrud.shipapp.ui.DispatchOrderAdapter;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ShipmentDispatchActivity extends AppCompatActivity {

    // 마켓 목록 (표시명 → marketKey 매핑)
    private static final String[] MARKET_LABELS = {
            "Cafe24 · 홈런마켓",
            "Cafe24 · 준비몰",
            "쿠팡 · 홈런마켓"
    };
    private static final String[] MARKET_KEYS = {
            CredentialStore.SLOT_CAFE24_HOME,
            CredentialStore.SLOT_CAFE24_PREPARE,
            "coupang"
    };

    // 기본 택배사 (Cafe24: 0006=CJ대한통운, Coupang: CJGLS)
    private static final String CAFE24_SHIPPING_CODE  = "0006";
    private static final String COUPANG_SHIPPING_CODE = "CJGLS";

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US);
    private static final DateTimeFormatter DATE_DISPLAY = DateTimeFormatter.ofPattern("MM/dd", Locale.US);

    private Spinner           spinnerMarket;
    private MaterialButton    btnStartDate, btnEndDate, btnQuery;
    private MaterialButton    btnSelectAll, btnDeselectAll, btnDispatch;
    private ProgressBar       progressBar;
    private TextView          tvStatusMsg;
    private RecyclerView      rvOrders;
    private View              layoutActions;

    private DispatchOrderAdapter adapter;
    private LiveShipmentRepository repository;
    private ExecutorService executor;

    private LocalDate startDate;
    private LocalDate endDate;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dispatch);

        repository = new LiveShipmentRepository(this);
        executor   = Executors.newSingleThreadExecutor();

        // 기본 날짜: 오늘-14일 ~ 오늘
        endDate   = LocalDate.now();
        startDate = endDate.minusDays(14);

        // View 바인딩
        MaterialToolbar toolbar = findViewById(R.id.toolbarDispatch);
        spinnerMarket  = findViewById(R.id.spinnerMarket);
        btnStartDate   = findViewById(R.id.btnStartDate);
        btnEndDate     = findViewById(R.id.btnEndDate);
        btnQuery       = findViewById(R.id.btnQuery);
        btnSelectAll   = findViewById(R.id.btnSelectAll);
        btnDeselectAll = findViewById(R.id.btnDeselectAll);
        btnDispatch    = findViewById(R.id.btnDispatch);
        progressBar    = findViewById(R.id.progressBar);
        tvStatusMsg    = findViewById(R.id.tvStatusMsg);
        rvOrders       = findViewById(R.id.rvOrders);
        layoutActions  = findViewById(R.id.layoutActions);

        // 툴바 뒤로가기
        toolbar.setNavigationOnClickListener(v -> finish());

        // 마켓 스피너
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, MARKET_LABELS);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerMarket.setAdapter(spinnerAdapter);

        // 날짜 버튼 초기 텍스트
        updateDateButtons();

        // RecyclerView
        adapter = new DispatchOrderAdapter();
        adapter.setOnSelectionChanged(this::updateDispatchButton);
        rvOrders.setLayoutManager(new LinearLayoutManager(this));
        rvOrders.setAdapter(adapter);

        // 버튼 클릭
        btnStartDate.setOnClickListener(v -> pickDate(true));
        btnEndDate.setOnClickListener(v -> pickDate(false));
        btnQuery.setOnClickListener(v -> queryOrders());
        btnSelectAll.setOnClickListener(v -> { adapter.selectAll(true); updateDispatchButton(); });
        btnDeselectAll.setOnClickListener(v -> { adapter.selectAll(false); updateDispatchButton(); });
        btnDispatch.setOnClickListener(v -> confirmAndDispatch());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executor != null) executor.shutdownNow();
    }

    private void pickDate(boolean isStart) {
        LocalDate cur = isStart ? startDate : endDate;
        Calendar cal = Calendar.getInstance();
        cal.set(cur.getYear(), cur.getMonthValue() - 1, cur.getDayOfMonth());
        new DatePickerDialog(this, (view, y, m, d) -> {
            LocalDate picked = LocalDate.of(y, m + 1, d);
            if (isStart) startDate = picked;
            else         endDate   = picked;
            updateDateButtons();
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void updateDateButtons() {
        btnStartDate.setText(DATE_FMT.format(startDate));
        btnEndDate.setText(DATE_FMT.format(endDate));
    }

    private void queryOrders() {
        int idx = spinnerMarket.getSelectedItemPosition();
        if (idx < 0 || idx >= MARKET_KEYS.length) return;
        String marketKey = MARKET_KEYS[idx];

        setLoading(true);
        tvStatusMsg.setVisibility(View.GONE);
        layoutActions.setVisibility(View.GONE);

        LocalDate s = startDate, e = endDate;
        executor.execute(() -> {
            try {
                List<DispatchOrder> orders = repository.fetchOrdersForDispatch(marketKey, s, e);
                runOnUiThread(() -> {
                    setLoading(false);
                    if (orders.isEmpty()) {
                        tvStatusMsg.setText("조회 결과가 없습니다.\n(" + MARKET_LABELS[idx] + " / " + DATE_FMT.format(s) + " ~ " + DATE_FMT.format(e) + ")");
                        tvStatusMsg.setVisibility(View.VISIBLE);
                        layoutActions.setVisibility(View.GONE);
                    } else {
                        tvStatusMsg.setVisibility(View.GONE);
                        layoutActions.setVisibility(View.VISIBLE);
                        adapter.setItems(orders);
                        updateDispatchButton();
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
        int ready    = adapter.getReadyCount();
        btnDispatch.setText("선택 출고처리 (" + ready + "/" + selected + "건)");
        btnDispatch.setEnabled(ready > 0);
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
                .setPositiveButton("출고처리", (d, w) -> dispatchSelected(targets))
                .show();
    }

    private void dispatchSelected(List<DispatchOrder> targets) {
        setLoading(true);
        int idx = spinnerMarket.getSelectedItemPosition();
        boolean isCoupang = idx >= 0 && "coupang".equals(MARKET_KEYS[idx]);
        String shippingCode = isCoupang ? COUPANG_SHIPPING_CODE : CAFE24_SHIPPING_CODE;

        executor.execute(() -> {
            List<String> success = new ArrayList<>();
            List<String> failed  = new ArrayList<>();

            for (DispatchOrder order : targets) {
                String err = repository.pushTrackingNumber(order, shippingCode);
                String label = order.recipientName + " / " + order.trackingNumber;
                if (err == null || err.isEmpty()) {
                    success.add(label);
                } else {
                    failed.add(label + "\n  → " + err);
                }
            }

            runOnUiThread(() -> {
                setLoading(false);
                showResultDialog(success, failed);
            });
        });
    }

    private void showResultDialog(List<String> success, List<String> failed) {
        StringBuilder sb = new StringBuilder();
        if (!success.isEmpty()) {
            sb.append("✔ 성공 ").append(success.size()).append("건\n");
            for (String s : success) sb.append("  • ").append(s).append("\n");
        }
        if (!failed.isEmpty()) {
            sb.append("\n✖ 실패 ").append(failed.size()).append("건\n");
            for (String f : failed) sb.append("  • ").append(f).append("\n");
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("출고처리 결과")
                .setMessage(sb.toString().trim())
                .setPositiveButton("확인", null)
                .show();
    }

    private void setLoading(boolean loading) {
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnQuery.setEnabled(!loading);
        btnDispatch.setEnabled(!loading && adapter.getReadyCount() > 0);
    }
}

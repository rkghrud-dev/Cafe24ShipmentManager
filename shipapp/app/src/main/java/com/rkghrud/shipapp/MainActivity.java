package com.rkghrud.shipapp;

import android.Manifest;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.rkghrud.shipapp.data.CoupangCredentials;
import com.rkghrud.shipapp.data.CredentialStore;
import com.rkghrud.shipapp.data.DebugSeedLoader;
import com.rkghrud.shipapp.data.DispatchOrder;
import com.rkghrud.shipapp.data.GoogleSheetsTrackingMatcher;
import com.rkghrud.shipapp.data.LiveShipmentRepository;
import com.rkghrud.shipapp.data.TrackingSpreadsheetImporter;
import com.rkghrud.shipapp.notifications.NotificationHelper;
import com.rkghrud.shipapp.ui.DispatchOrderAdapter;
import com.rkghrud.shipapp.workers.ShipmentAlertWorker;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String PREFS_NAME = "shipapp_prefs";
    private static final String PREF_ALERTS_ENABLED = "alerts_enabled";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private static final int STATUS_OFFLINE = 0;
    private static final int STATUS_ONLINE = 1;
    private static final int STATUS_WARNING = 2;

    private static final String FILTER_ALL = "all";
    private static final String[] MARKET_FILTER_LABELS = {"전체보기", "홈런", "준비몰", "쿠팡"};
    private static final String[] MARKET_FILTER_KEYS = {
            FILTER_ALL,
            CredentialStore.SLOT_CAFE24_HOME,
            CredentialStore.SLOT_CAFE24_PREPARE,
            "coupang"
    };
    private static final String[] PAGE_SIZE_LABELS = {"5개씩 보기", "10개씩 보기", "20개씩 보기", "100개씩 보기"};
    private static final int[] PAGE_SIZES = {5, 10, 20, 100};

    private CredentialStore credentialStore;
    private LiveShipmentRepository repository;
    private DispatchOrderAdapter adapter;
    private ExecutorService executorService;

    private SwipeRefreshLayout swipeRefreshLayout;
    private TextView aggregateStatusView;
    private TextView totalCountView;
    private TextView pendingBadgeView;
    private TextView selectionSummaryView;
    private TextView emptyStateView;
    private Spinner marketFilterSpinner;
    private Spinner pageSizeSpinner;
    private CheckBox selectAllCheckBox;
    private MaterialButton refreshButton;
    private MaterialButton importSpreadsheetButton;
    private MaterialButton uploadSelectedButton;

    private final List<DispatchOrder> allOrders = new ArrayList<>();
    private final List<DispatchOrder> displayedOrders = new ArrayList<>();
    private boolean suppressSelectAllCallback = false;
    private String pendingCafe24Slot = CredentialStore.SLOT_CAFE24_HOME;

    private String currentHomeStatus = "";
    private String currentPrepareStatus = "";
    private String currentCoupangStatus = "";
    private String currentUpdatedAt = "-";
    private int currentConnectedCount = 0;
    private int currentFetchedCount = 0;
    private int currentTotalCount = 0;
    private int currentFilteredCount = 0;

    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
            });

    private final ActivityResultLauncher<String[]> cafe24ImportLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::handleCafe24ImportResult);

    private final ActivityResultLauncher<String[]> spreadsheetImportLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::handleSpreadsheetImportResult);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        credentialStore = new CredentialStore(this);
        repository = new LiveShipmentRepository(this);
        executorService = Executors.newSingleThreadExecutor();
        NotificationHelper.ensureChannel(this);
        requestNotificationPermissionIfNeeded();

        swipeRefreshLayout = findViewById(R.id.swipeRefresh);
        aggregateStatusView = findViewById(R.id.tvAggregateStatus);
        totalCountView = findViewById(R.id.tvTotalCount);
        pendingBadgeView = findViewById(R.id.tvPendingBadge);
        selectionSummaryView = findViewById(R.id.tvSelectionSummary);
        emptyStateView = findViewById(R.id.tvEmptyState);
        marketFilterSpinner = findViewById(R.id.spinnerMarketFilter);
        pageSizeSpinner = findViewById(R.id.spinnerPageSize);
        selectAllCheckBox = findViewById(R.id.cbSelectAll);
        refreshButton = findViewById(R.id.btnRefresh);
        importSpreadsheetButton = findViewById(R.id.btnImportSpreadsheet);
        uploadSelectedButton = findViewById(R.id.btnUploadSelected);
        RecyclerView ordersRecyclerView = findViewById(R.id.rvDispatchOrders);

        adapter = new DispatchOrderAdapter();
        adapter.setOnSelectionChanged(this::updateSelectionUi);
        ordersRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        ordersRecyclerView.setAdapter(adapter);

        findViewById(R.id.btnOpenSettings).setOnClickListener(v -> showSettingsDialog());

        ArrayAdapter<String> marketAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, MARKET_FILTER_LABELS);
        marketAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        marketFilterSpinner.setAdapter(marketAdapter);

        ArrayAdapter<String> pageAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, PAGE_SIZE_LABELS);
        pageAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        pageSizeSpinner.setAdapter(pageAdapter);
        pageSizeSpinner.setSelection(2);

        AdapterView.OnItemSelectedListener filterListener = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                applyFilters();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        };
        marketFilterSpinner.setOnItemSelectedListener(filterListener);
        pageSizeSpinner.setOnItemSelectedListener(filterListener);

        selectAllCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!suppressSelectAllCallback) {
                setDisplayedOrdersSelected(isChecked);
            }
        });

        refreshButton.setOnClickListener(v -> refreshOrders());
        importSpreadsheetButton.setOnClickListener(v -> launchSpreadsheetImport());
        uploadSelectedButton.setOnClickListener(v -> confirmUploadSelected());
        swipeRefreshLayout.setOnRefreshListener(this::refreshOrders);

        maybeAutoLoadDebugSeeds();
        showCredentialOnlyStatus();
        refreshOrders();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executorService != null) {
            executorService.shutdownNow();
        }
    }

    private void refreshOrders() {
        setLoading(true);
        executorService.execute(() -> {
            DispatchLoadResult result = loadDispatchOrders();
            runOnUiThread(() -> applyDispatchLoadResult(result));
        });
    }

    private DispatchLoadResult loadDispatchOrders() {
        DispatchLoadResult result = new DispatchLoadResult();
        LocalDate endDate = LocalDate.now(SEOUL);
        LocalDate startDate = endDate.minusDays(14);

        if (credentialStore.hasCafe24Slot(CredentialStore.SLOT_CAFE24_HOME)) {
            result.connectedCount++;
            try {
                List<DispatchOrder> homeOrders = repository.fetchOrdersForDispatch(
                        CredentialStore.SLOT_CAFE24_HOME, startDate, endDate);
                result.orders.addAll(homeOrders);
                result.homeStatus = "홈런마켓 Cafe24\n조회 성공 " + homeOrders.size() + "건";
                result.fetchedCount++;
            } catch (Exception ex) {
                result.homeStatus = "홈런마켓 Cafe24\n확인 필요\n" + clipMessage(ex.getMessage());
                result.errors.add("홈런 조회 실패: " + ex.getMessage());
            }
        } else {
            result.homeStatus = credentialStore.getCafe24Status(CredentialStore.SLOT_CAFE24_HOME, "홈런마켓 Cafe24");
        }

        if (credentialStore.hasCafe24Slot(CredentialStore.SLOT_CAFE24_PREPARE)) {
            result.connectedCount++;
            try {
                List<DispatchOrder> prepareOrders = repository.fetchOrdersForDispatch(
                        CredentialStore.SLOT_CAFE24_PREPARE, startDate, endDate);
                result.orders.addAll(prepareOrders);
                result.prepareStatus = "준비몰 Cafe24\n조회 성공 " + prepareOrders.size() + "건";
                result.fetchedCount++;
            } catch (Exception ex) {
                result.prepareStatus = "준비몰 Cafe24\n확인 필요\n" + clipMessage(ex.getMessage());
                result.errors.add("준비몰 조회 실패: " + ex.getMessage());
            }
        } else {
            result.prepareStatus = credentialStore.getCafe24Status(CredentialStore.SLOT_CAFE24_PREPARE, "준비몰 Cafe24");
        }

        if (credentialStore.getCoupangCredentials().isComplete()) {
            result.connectedCount++;
            try {
                List<DispatchOrder> coupangOrders = repository.fetchOrdersForDispatch("coupang", startDate, endDate);
                result.orders.addAll(coupangOrders);
                result.coupangStatus = "쿠팡\n조회 성공 " + coupangOrders.size() + "건";
                result.fetchedCount++;
            } catch (Exception ex) {
                result.coupangStatus = "쿠팡\n확인 필요\n" + clipMessage(ex.getMessage());
                result.errors.add("쿠팡 조회 실패: " + ex.getMessage());
            }
        } else {
            result.coupangStatus = credentialStore.getCoupangStatus();
        }

        Collections.sort(result.orders, Comparator.comparing(
                (DispatchOrder order) -> safeText(order.orderDate)).reversed());
        result.totalCount = result.orders.size();
        result.updatedAt = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(new Date());
        return result;
    }

    private void applyDispatchLoadResult(DispatchLoadResult result) {
        allOrders.clear();
        allOrders.addAll(result.orders);

        currentHomeStatus = result.homeStatus;
        currentPrepareStatus = result.prepareStatus;
        currentCoupangStatus = result.coupangStatus;
        currentConnectedCount = result.connectedCount;
        currentFetchedCount = result.fetchedCount;
        currentTotalCount = result.totalCount;
        currentUpdatedAt = result.updatedAt;

        updateHeaderUi();
        applyFilters();
        setLoading(false);

        if (!result.errors.isEmpty()) {
            showToast("일부 판매처 조회에 실패했습니다. 설정에서 상태를 확인하세요.");
        }
    }
    private void showCredentialOnlyStatus() {
        currentHomeStatus = credentialStore.getCafe24Status(CredentialStore.SLOT_CAFE24_HOME, "홈런마켓 Cafe24");
        currentPrepareStatus = credentialStore.getCafe24Status(CredentialStore.SLOT_CAFE24_PREPARE, "준비몰 Cafe24");
        currentCoupangStatus = credentialStore.getCoupangStatus();
        currentConnectedCount = credentialStore.getConnectedSourceCount();
        currentFetchedCount = 0;
        currentTotalCount = 0;
        currentFilteredCount = 0;
        currentUpdatedAt = "-";
        allOrders.clear();
        displayedOrders.clear();
        updateHeaderUi();
        adapter.setItems(Collections.emptyList());
        updateEmptyState();
        updateSelectionUi();
    }

    private void updateHeaderUi() {
        totalCountView.setText(String.valueOf(currentTotalCount));

        if (currentTotalCount > 0) {
            pendingBadgeView.setVisibility(View.VISIBLE);
            pendingBadgeView.setText("! " + currentTotalCount);
        } else {
            pendingBadgeView.setVisibility(View.GONE);
        }

        int state = resolveAggregateState();
        if (state == STATUS_ONLINE) {
            aggregateStatusView.setText("● 접속 ON");
            aggregateStatusView.setBackgroundResource(R.drawable.bg_status_chip_online);
            aggregateStatusView.setTextColor(ContextCompat.getColor(this, R.color.ship_success));
        } else if (state == STATUS_WARNING) {
            aggregateStatusView.setText("● 확인 필요");
            aggregateStatusView.setBackgroundResource(R.drawable.bg_status_chip_warning);
            aggregateStatusView.setTextColor(ContextCompat.getColor(this, R.color.ship_warning));
        } else {
            aggregateStatusView.setText("● 접속 없음");
            aggregateStatusView.setBackgroundResource(R.drawable.bg_status_chip_offline);
            aggregateStatusView.setTextColor(ContextCompat.getColor(this, R.color.ship_error));
        }
        aggregateStatusView.setContentDescription(buildSettingsSummary());
    }

    private int resolveAggregateState() {
        if (currentConnectedCount == 0) {
            return STATUS_OFFLINE;
        }
        if (hasConnectedWarning(CredentialStore.SLOT_CAFE24_HOME, currentHomeStatus)
                || hasConnectedWarning(CredentialStore.SLOT_CAFE24_PREPARE, currentPrepareStatus)
                || hasConnectedWarning("coupang", currentCoupangStatus)
                || currentFetchedCount < currentConnectedCount) {
            return STATUS_WARNING;
        }
        return STATUS_ONLINE;
    }

    private boolean hasConnectedWarning(String marketKey, String status) {
        if (!isMarketConnected(marketKey)) {
            return false;
        }
        if (status == null || status.trim().isEmpty()) {
            return true;
        }
        return status.contains("확인 필요")
                || status.contains("실패")
                || status.contains("예외")
                || status.contains("부족")
                || status.contains("미연결");
    }

    private boolean isMarketConnected(String marketKey) {
        if ("coupang".equals(marketKey)) {
            return credentialStore.getCoupangCredentials().isComplete();
        }
        return credentialStore.hasCafe24Slot(marketKey);
    }

    private void applyFilters() {
        displayedOrders.clear();
        currentFilteredCount = 0;

        String marketFilterKey = getSelectedMarketFilter();
        int pageSize = getSelectedPageSize();

        for (DispatchOrder order : allOrders) {
            if (!matchesMarketFilter(order, marketFilterKey)) {
                continue;
            }
            currentFilteredCount++;
            if (displayedOrders.size() < pageSize) {
                displayedOrders.add(order);
            }
        }

        adapter.setItems(displayedOrders);
        updateEmptyState();
        updateSelectionUi();
    }

    private boolean matchesMarketFilter(DispatchOrder order, String marketFilterKey) {
        return FILTER_ALL.equals(marketFilterKey) || marketFilterKey.equals(order.marketKey);
    }

    private String getSelectedMarketFilter() {
        int position = marketFilterSpinner.getSelectedItemPosition();
        if (position < 0 || position >= MARKET_FILTER_KEYS.length) {
            return FILTER_ALL;
        }
        return MARKET_FILTER_KEYS[position];
    }

    private int getSelectedPageSize() {
        int position = pageSizeSpinner.getSelectedItemPosition();
        if (position < 0 || position >= PAGE_SIZES.length) {
            return PAGE_SIZES[2];
        }
        return PAGE_SIZES[position];
    }

    private void setDisplayedOrdersSelected(boolean selected) {
        for (DispatchOrder order : displayedOrders) {
            order.selected = selected;
        }
        adapter.notifyDataSetChanged();
        updateSelectionUi();
    }

    private void updateSelectionUi() {
        int selectedCount = countSelectedOrders();
        int readyCount = countReadyOrders();
        selectionSummaryView.setText("표시 " + displayedOrders.size() + "/" + currentFilteredCount
                + " · 선택 " + selectedCount + " · 업로드 " + readyCount);

        boolean allSelected = !displayedOrders.isEmpty();
        for (DispatchOrder order : displayedOrders) {
            if (!order.selected) {
                allSelected = false;
                break;
            }
        }

        suppressSelectAllCallback = true;
        selectAllCheckBox.setChecked(allSelected);
        suppressSelectAllCallback = false;

        uploadSelectedButton.setText(readyCount > 0 ? "선택 업로드 " + readyCount + "건" : "선택 업로드");
        selectAllCheckBox.setEnabled(!swipeRefreshLayout.isRefreshing() && !displayedOrders.isEmpty());
        uploadSelectedButton.setEnabled(!swipeRefreshLayout.isRefreshing() && readyCount > 0);
        importSpreadsheetButton.setEnabled(!swipeRefreshLayout.isRefreshing() && !allOrders.isEmpty());
    }

    private int countSelectedOrders() {
        int count = 0;
        for (DispatchOrder order : allOrders) {
            if (order.selected) {
                count++;
            }
        }
        return count;
    }

    private int countReadyOrders() {
        int count = 0;
        for (DispatchOrder order : allOrders) {
            if (order.selected && hasTrackingNumber(order)) {
                count++;
            }
        }
        return count;
    }

    private boolean hasTrackingNumber(DispatchOrder order) {
        return order.trackingNumber != null && !order.trackingNumber.trim().isEmpty();
    }

    private void updateEmptyState() {
        if (currentConnectedCount == 0) {
            emptyStateView.setText("설정에서 판매처 키를 먼저 연결하세요.");
            emptyStateView.setVisibility(View.VISIBLE);
            return;
        }
        if (allOrders.isEmpty()) {
            emptyStateView.setText("현재 출고 대상 주문이 없습니다.");
            emptyStateView.setVisibility(View.VISIBLE);
            return;
        }
        if (currentFilteredCount == 0) {
            emptyStateView.setText("선택한 판매처에 표시할 주문이 없습니다.");
            emptyStateView.setVisibility(View.VISIBLE);
            return;
        }
        emptyStateView.setVisibility(View.GONE);
    }
    private void launchSpreadsheetImport() {
        if (allOrders.isEmpty()) {
            showToast("먼저 주문을 조회하세요.");
            return;
        }

        setLoading(true);
        executorService.execute(() -> {
            try {
                GoogleSheetsTrackingMatcher.MatchResult result =
                        GoogleSheetsTrackingMatcher.applyToOrders(allOrders);
                runOnUiThread(() -> {
                    setLoading(false);
                    applyFilters();
                    showToast(result.summary());
                });
            } catch (Exception ex) {
                runOnUiThread(() -> {
                    setLoading(false);
                    showToast("송장 매칭 실패: " + ex.getMessage());
                });
            }
        });
    }

    private void handleSpreadsheetImportResult(Uri uri) {
        if (uri == null) {
            return;
        }

        setLoading(true);
        executorService.execute(() -> {
            try {
                TrackingSpreadsheetImporter.MatchResult result =
                        TrackingSpreadsheetImporter.applyToOrders(this, uri, allOrders);
                runOnUiThread(() -> {
                    setLoading(false);
                    applyFilters();
                    showToast(result.summary());
                });
            } catch (Exception ex) {
                runOnUiThread(() -> {
                    setLoading(false);
                    showToast("송장 매칭 실패: " + ex.getMessage());
                });
            }
        });
    }

    private void confirmUploadSelected() {
        List<DispatchOrder> targets = getReadyOrders();
        if (targets.isEmpty()) {
            showToast("체크된 주문 중 송장번호가 있는 항목이 없습니다.");
            return;
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle("송장 업로드")
                .setMessage(targets.size() + "건을 각 마켓 API로 업로드합니다. 계속하시겠습니까?")
                .setNegativeButton("취소", null)
                .setPositiveButton("업로드", (dialog, which) -> uploadSelectedOrders(targets))
                .show();
    }

    private List<DispatchOrder> getReadyOrders() {
        List<DispatchOrder> targets = new ArrayList<>();
        for (DispatchOrder order : allOrders) {
            if (order.selected && hasTrackingNumber(order)) {
                targets.add(order);
            }
        }
        return targets;
    }

    private void uploadSelectedOrders(List<DispatchOrder> targets) {
        setLoading(true);
        executorService.execute(() -> {
            List<String> success = new ArrayList<>();
            List<String> failed = new ArrayList<>();

            for (DispatchOrder order : targets) {
                String errorMessage = repository.pushTrackingNumber(order, order.shippingCode());
                String label = order.shortMarketLabel() + " / "
                        + safeText(order.recipientName) + " / "
                        + safeText(order.trackingNumber);
                if (errorMessage == null || errorMessage.trim().isEmpty()) {
                    success.add(label);
                } else {
                    failed.add(label + "\n  → " + errorMessage.trim());
                }
            }

            runOnUiThread(() -> {
                setLoading(false);
                showUploadResultDialog(success, failed);
            });
        });
    }

    private void showUploadResultDialog(List<String> success, List<String> failed) {
        StringBuilder builder = new StringBuilder();
        if (!success.isEmpty()) {
            builder.append("성공 ").append(success.size()).append("건\n");
            for (String label : success) {
                builder.append("• ").append(label).append("\n");
            }
        }
        if (!failed.isEmpty()) {
            if (builder.length() > 0) {
                builder.append("\n");
            }
            builder.append("실패 ").append(failed.size()).append("건\n");
            for (String label : failed) {
                builder.append("• ").append(label).append("\n");
            }
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle("업로드 결과")
                .setMessage(builder.toString().trim())
                .setPositiveButton("확인", (dialog, which) -> refreshOrders())
                .show();
    }
    private void showSettingsDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_settings, null);

        TextView summaryView = dialogView.findViewById(R.id.tvSettingsSummary);
        TextView homeStatusView = dialogView.findViewById(R.id.tvSettingsCafe24HomeStatus);
        TextView prepareStatusView = dialogView.findViewById(R.id.tvSettingsCafe24PrepareStatus);
        TextView coupangStatusView = dialogView.findViewById(R.id.tvSettingsCoupangStatus);
        TextView lastUpdatedView = dialogView.findViewById(R.id.tvSettingsLastUpdated);
        SwitchCompat alertsSwitch = dialogView.findViewById(R.id.switchSettingsAlerts);

        summaryView.setText(buildSettingsSummary());
        homeStatusView.setText(currentHomeStatus);
        prepareStatusView.setText(currentPrepareStatus);
        coupangStatusView.setText(currentCoupangStatus);
        lastUpdatedView.setText(getString(R.string.last_updated_format, currentUpdatedAt));

        alertsSwitch.setChecked(areAlertsEnabled());
        alertsSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> setAlertsEnabled(isChecked));

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_title)
                .setView(dialogView)
                .setPositiveButton(android.R.string.ok, null)
                .create();

        dialogView.findViewById(R.id.btnSettingsLoadDebugSeeds).setOnClickListener(v -> {
            dialog.dismiss();
            loadDebugSeeds();
        });
        dialogView.findViewById(R.id.btnSettingsImportCafe24Home).setOnClickListener(v -> {
            dialog.dismiss();
            launchCafe24Import(CredentialStore.SLOT_CAFE24_HOME);
        });
        dialogView.findViewById(R.id.btnSettingsImportCafe24Prepare).setOnClickListener(v -> {
            dialog.dismiss();
            launchCafe24Import(CredentialStore.SLOT_CAFE24_PREPARE);
        });
        dialogView.findViewById(R.id.btnSettingsEditCoupang).setOnClickListener(v -> {
            dialog.dismiss();
            showCoupangDialog();
        });
        dialogView.findViewById(R.id.btnSettingsClearKeys).setOnClickListener(v -> {
            dialog.dismiss();
            clearSavedKeys();
        });
        dialogView.findViewById(R.id.btnSettingsTestNotification).setOnClickListener(v ->
                NotificationHelper.showTestNotification(this));

        dialog.show();
    }

    private String buildSettingsSummary() {
        if (currentConnectedCount == 0) {
            return getString(R.string.settings_summary_empty);
        }

        StringBuilder summary = new StringBuilder();
        summary.append("판매처 ").append(currentConnectedCount).append("개 연결");
        if (currentFetchedCount > 0) {
            summary.append(" / 조회 성공 ").append(currentFetchedCount).append("개");
        } else {
            summary.append(" / 조회 확인 전");
        }
        if (currentTotalCount > 0) {
            summary.append(" / 출고 ").append(currentTotalCount).append("건");
        } else {
            summary.append(" / 출고 없음");
        }
        return summary.toString();
    }

    private boolean areAlertsEnabled() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(PREF_ALERTS_ENABLED, true);
    }

    private void setAlertsEnabled(boolean enabled) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_ALERTS_ENABLED, enabled)
                .apply();
        applyAlertSchedule(enabled);
    }

    private void launchCafe24Import(String slot) {
        pendingCafe24Slot = slot;
        cafe24ImportLauncher.launch(new String[]{"application/json", "text/plain"});
    }

    private void handleCafe24ImportResult(Uri uri) {
        if (uri == null) {
            return;
        }

        try {
            String json = readText(uri);
            validateCafe24Json(json);
            credentialStore.saveCafe24Json(pendingCafe24Slot, json);
            showToast(CredentialStore.SLOT_CAFE24_PREPARE.equals(pendingCafe24Slot)
                    ? getString(R.string.import_prepare_success)
                    : getString(R.string.import_home_success));
            refreshOrders();
        } catch (Exception ex) {
            showToast(getString(R.string.import_failed_prefix) + ex.getMessage());
        }
    }

    private void maybeAutoLoadDebugSeeds() {
        if (!BuildConfig.DEBUG || credentialStore.hasAnyCredentials()) {
            return;
        }
        DebugSeedLoader.load(this, credentialStore);
    }

    private void loadDebugSeeds() {
        int loaded = DebugSeedLoader.load(this, credentialStore);
        if (loaded == 0) {
            showToast(getString(R.string.debug_seed_missing));
            showCredentialOnlyStatus();
        } else {
            showToast(getString(R.string.debug_seed_loaded, loaded));
            refreshOrders();
        }
    }

    private void clearSavedKeys() {
        credentialStore.clearAll();
        showToast(getString(R.string.saved_keys_cleared));
        showCredentialOnlyStatus();
    }

    private void showCoupangDialog() {
        CoupangCredentials current = credentialStore.getCoupangCredentials();

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int padding = dpToPx(20);
        container.setPadding(padding, dpToPx(8), padding, 0);

        EditText vendorIdInput = buildInput(R.string.hint_vendor_id, current.getVendorId());
        EditText accessKeyInput = buildInput(R.string.hint_access_key, current.getAccessKey());
        EditText secretKeyInput = buildInput(R.string.hint_secret_key, current.getSecretKey());

        container.addView(vendorIdInput);
        container.addView(accessKeyInput);
        container.addView(secretKeyInput);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.coupang_dialog_title)
                .setMessage(R.string.coupang_dialog_message)
                .setView(container)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.save_label, (dialog, which) -> {
                    String vendorId = vendorIdInput.getText().toString().trim();
                    String accessKey = accessKeyInput.getText().toString().trim();
                    String secretKey = secretKeyInput.getText().toString().trim();
                    if (vendorId.isEmpty() || accessKey.isEmpty() || secretKey.isEmpty()) {
                        showToast(getString(R.string.coupang_save_failed));
                        return;
                    }
                    credentialStore.saveCoupangCredentials(vendorId, accessKey, secretKey);
                    showToast(getString(R.string.coupang_save_success));
                    refreshOrders();
                })
                .show();
    }

    private EditText buildInput(int hintResId, String value) {
        EditText editText = new EditText(this);
        editText.setHint(hintResId);
        editText.setText(value);
        editText.setInputType(InputType.TYPE_CLASS_TEXT);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dpToPx(12);
        editText.setLayoutParams(params);
        return editText;
    }
    private String readText(Uri uri) throws IOException {
        try (InputStream inputStream = getContentResolver().openInputStream(uri)) {
            if (inputStream == null) {
                throw new IOException(getString(R.string.read_failed));
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                StringBuilder builder = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    builder.append(line).append('\n');
                }
                return builder.toString().trim();
            }
        }
    }

    private void validateCafe24Json(String json) throws Exception {
        JSONObject object = new JSONObject(json);
        if (object.optString("MallId").isEmpty()) {
            throw new IllegalArgumentException(getString(R.string.invalid_cafe24_json));
        }
    }

    private void applyAlertSchedule(boolean enabled) {
        if (enabled) {
            ShipmentAlertWorker.schedule(this);
        } else {
            ShipmentAlertWorker.cancel(this);
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
    }

    private void setLoading(boolean loading) {
        swipeRefreshLayout.setRefreshing(loading);
        refreshButton.setEnabled(!loading);
        marketFilterSpinner.setEnabled(!loading);
        pageSizeSpinner.setEnabled(!loading);
        importSpreadsheetButton.setEnabled(!loading && !allOrders.isEmpty());
        selectAllCheckBox.setEnabled(!loading && !displayedOrders.isEmpty());
        uploadSelectedButton.setEnabled(!loading && countReadyOrders() > 0);
    }

    private String clipMessage(String message) {
        if (message == null) {
            return "오류";
        }
        return message.length() > 90 ? message.substring(0, 90) : message;
    }

    private String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private static class DispatchLoadResult {
        final List<DispatchOrder> orders = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        String homeStatus = "";
        String prepareStatus = "";
        String coupangStatus = "";
        String updatedAt = "-";
        int connectedCount;
        int fetchedCount;
        int totalCount;
    }
}




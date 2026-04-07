package com.rkghrud.shipapp;

import android.Manifest;
import androidx.appcompat.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
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
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.rkghrud.shipapp.data.AlertPrefs;
import com.rkghrud.shipapp.data.Cafe24MarketConfig;
import com.rkghrud.shipapp.data.CoupangCredentials;
import com.rkghrud.shipapp.data.CredentialStore;
import com.rkghrud.shipapp.data.DebugSeedLoader;
import com.rkghrud.shipapp.data.DispatchOrder;
import com.rkghrud.shipapp.data.GoogleSheetsTrackingMatcher;
import com.rkghrud.shipapp.data.LiveShipmentRepository;
import com.rkghrud.shipapp.data.TrackingSpreadsheetImporter;
import com.rkghrud.shipapp.notifications.NotificationHelper;
import com.rkghrud.shipapp.ui.DispatchOrderAdapter;
import com.rkghrud.shipapp.ui.OrderDetailDialog;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final int STATUS_OFFLINE = 0;
    private static final int STATUS_ONLINE = 1;
    private static final int STATUS_WARNING = 2;

    private static final String FILTER_ALL = "all";
    private static final String COUPANG_KEY = "coupang";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final String[] PAGE_SIZE_LABELS = {"5개씩 보기", "10개씩 보기", "20개씩 보기", "100개씩 보기"};
    private static final int[] PAGE_SIZES = {5, 10, 20, 100};

    private CredentialStore credentialStore;
    private LiveShipmentRepository repository;
    private DispatchOrderAdapter adapter;
    private ExecutorService executorService;
    private ArrayAdapter<String> marketAdapter;

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
    private final List<String> marketFilterLabels = new ArrayList<>();
    private final List<String> marketFilterKeys = new ArrayList<>();
    private final List<String> currentActiveCafe24Keys = new ArrayList<>();
    private final Map<String, String> currentCafe24Statuses = new LinkedHashMap<>();

    private boolean suppressSelectAllCallback = false;
    private String pendingCafe24Slot = "";
    private String pendingCafe24Name = "";
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
        NotificationHelper.ensureChannels(this);
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
        adapter.setOnItemClick(this::showOrderDetail);
        ordersRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        ordersRecyclerView.setAdapter(adapter);

        findViewById(R.id.btnOpenSettings).setOnClickListener(v -> showSettingsDialog());

        marketAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new ArrayList<>());
        marketAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        marketFilterSpinner.setAdapter(marketAdapter);
        refreshMarketFilterOptions(FILTER_ALL);

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

        for (Cafe24MarketConfig config : credentialStore.getCafe24Markets()) {
            result.cafe24Statuses.put(config.key, credentialStore.getCafe24Status(config.key));
        }

        for (Cafe24MarketConfig config : credentialStore.getActiveCafe24Markets()) {
            result.activeCafe24Keys.add(config.key);
            result.connectedCount++;
            try {
                List<DispatchOrder> orders = repository.fetchOrdersForDispatch(config.key, startDate, endDate);
                result.orders.addAll(orders);
                result.cafe24Statuses.put(config.key, config.buildMarketLabel() + "\n조회 성공 " + orders.size() + "건");
                result.fetchedCount++;
            } catch (Exception ex) {
                result.cafe24Statuses.put(config.key, config.buildMarketLabel() + "\n확인 필요\n" + clipMessage(ex.getMessage()));
                result.errors.add(config.displayName + " 조회 실패: " + ex.getMessage());
            }
        }

        if (credentialStore.getCoupangCredentials().isComplete()) {
            result.connectedCount++;
            try {
                List<DispatchOrder> coupangOrders = repository.fetchOrdersForDispatch(COUPANG_KEY, startDate, endDate);
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
        String selectedFilter = getSelectedMarketFilter();

        allOrders.clear();
        allOrders.addAll(result.orders);

        currentCafe24Statuses.clear();
        currentCafe24Statuses.putAll(result.cafe24Statuses);
        currentActiveCafe24Keys.clear();
        currentActiveCafe24Keys.addAll(result.activeCafe24Keys);
        currentCoupangStatus = result.coupangStatus;
        currentConnectedCount = result.connectedCount;
        currentFetchedCount = result.fetchedCount;
        currentTotalCount = result.totalCount;
        currentUpdatedAt = result.updatedAt;

        refreshMarketFilterOptions(selectedFilter);
        updateHeaderUi();
        applyFilters();
        setLoading(false);

        if (!result.errors.isEmpty()) {
            showToast("일부 판매처 조회에 실패했습니다. 설정에서 상태를 확인하세요.");
        }
    }

    private void showCredentialOnlyStatus() {
        String selectedFilter = getSelectedMarketFilter();

        currentCafe24Statuses.clear();
        for (Cafe24MarketConfig config : credentialStore.getCafe24Markets()) {
            currentCafe24Statuses.put(config.key, credentialStore.getCafe24Status(config.key));
        }
        currentActiveCafe24Keys.clear();
        for (Cafe24MarketConfig config : credentialStore.getActiveCafe24Markets()) {
            currentActiveCafe24Keys.add(config.key);
        }

        currentCoupangStatus = credentialStore.getCoupangStatus();
        currentConnectedCount = currentActiveCafe24Keys.size() + (credentialStore.getCoupangCredentials().isComplete() ? 1 : 0);
        currentFetchedCount = 0;
        currentTotalCount = 0;
        currentFilteredCount = 0;
        currentUpdatedAt = "-";
        allOrders.clear();
        displayedOrders.clear();
        refreshMarketFilterOptions(selectedFilter);
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
        for (String marketKey : currentActiveCafe24Keys) {
            if (hasStatusWarning(resolveCafe24Status(marketKey))) {
                return STATUS_WARNING;
            }
        }
        if (credentialStore.getCoupangCredentials().isComplete() && hasStatusWarning(currentCoupangStatus)) {
            return STATUS_WARNING;
        }
        if (currentFetchedCount < currentConnectedCount) {
            return STATUS_WARNING;
        }
        return STATUS_ONLINE;
    }

    private boolean hasStatusWarning(String status) {
        if (status == null || status.trim().isEmpty()) {
            return true;
        }
        return status.contains("확인 필요")
                || status.contains("실패")
                || status.contains("예외")
                || status.contains("부족")
                || status.contains("미연결");
    }

    private void refreshMarketFilterOptions(String preferredKey) {
        String selectedKey = safeText(preferredKey);
        if (selectedKey.isEmpty()) {
            selectedKey = FILTER_ALL;
        }

        marketFilterLabels.clear();
        marketFilterKeys.clear();
        marketFilterLabels.add("전체보기");
        marketFilterKeys.add(FILTER_ALL);

        for (Cafe24MarketConfig config : credentialStore.getActiveCafe24Markets()) {
            marketFilterLabels.add(config.displayName);
            marketFilterKeys.add(config.key);
        }
        if (credentialStore.getCoupangCredentials().isComplete()) {
            marketFilterLabels.add("쿠팡");
            marketFilterKeys.add(COUPANG_KEY);
        }

        marketAdapter.clear();
        marketAdapter.addAll(marketFilterLabels);
        marketAdapter.notifyDataSetChanged();

        int selectedIndex = marketFilterKeys.indexOf(selectedKey);
        marketFilterSpinner.setSelection(selectedIndex >= 0 ? selectedIndex : 0, false);
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
        int position = marketFilterSpinner == null ? -1 : marketFilterSpinner.getSelectedItemPosition();
        if (position < 0 || position >= marketFilterKeys.size()) {
            return FILTER_ALL;
        }
        return marketFilterKeys.get(position);
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
            emptyStateView.setText("설정에서 표시할 판매처를 체크하고 JSON을 연결하세요.");
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
        TextView coupangStatusView = dialogView.findViewById(R.id.tvSettingsCoupangStatus);
        TextView lastUpdatedView = dialogView.findViewById(R.id.tvSettingsLastUpdated);
        LinearLayout marketContainer = dialogView.findViewById(R.id.layoutCafe24Markets);

        summaryView.setText(buildSettingsSummary());
        coupangStatusView.setText(currentCoupangStatus);
        lastUpdatedView.setText(getString(R.string.last_updated_format, currentUpdatedAt));

        configureAlertSettings(dialogView);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_title)
                .setView(dialogView)
                .setPositiveButton(android.R.string.ok, null)
                .create();

        populateCafe24MarketRows(marketContainer, dialog);

        dialogView.findViewById(R.id.btnSettingsAddCafe24Market).setOnClickListener(v -> {
            dialog.dismiss();
            showAddCafe24MarketDialog();
        });
        dialogView.findViewById(R.id.btnSettingsLoadDebugSeeds).setOnClickListener(v -> {
            dialog.dismiss();
            loadDebugSeeds();
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

    private void configureAlertSettings(View dialogView) {
        SwitchCompat switchPolling = dialogView.findViewById(R.id.switchPollingAlert);
        View layoutInterval = dialogView.findViewById(R.id.layoutPollingInterval);
        MaterialButton btn10 = dialogView.findViewById(R.id.btn10min);
        MaterialButton btn20 = dialogView.findViewById(R.id.btn20min);
        MaterialButton btn30 = dialogView.findViewById(R.id.btn30min);

        boolean pollingEnabled = AlertPrefs.isPollingEnabled(this);
        int pollingMin = AlertPrefs.getPollingInterval(this);
        switchPolling.setChecked(pollingEnabled);
        layoutInterval.setVisibility(pollingEnabled ? View.VISIBLE : View.GONE);
        highlightIntervalButton(btn10, btn20, btn30, pollingMin);

        switchPolling.setOnCheckedChangeListener((button, checked) -> {
            AlertPrefs.prefs(this).edit().putBoolean(AlertPrefs.KEY_POLLING_ENABLED, checked).apply();
            layoutInterval.setVisibility(checked ? View.VISIBLE : View.GONE);
            applyPollingSchedule(checked);
        });
        View.OnClickListener intervalClick = v -> {
            int min = (v == btn10) ? 15 : (v == btn20) ? 20 : 30;
            AlertPrefs.prefs(this).edit().putInt(AlertPrefs.KEY_POLLING_INTERVAL, min).apply();
            highlightIntervalButton(btn10, btn20, btn30, min);
            if (AlertPrefs.isPollingEnabled(this)) {
                applyPollingSchedule(true);
            }
        };
        btn10.setOnClickListener(intervalClick);
        btn20.setOnClickListener(intervalClick);
        btn30.setOnClickListener(intervalClick);

        SwitchCompat switchScheduled = dialogView.findViewById(R.id.switchScheduledAlert);
        View layoutScheduled = dialogView.findViewById(R.id.layoutScheduled);
        MaterialButton btnTime = dialogView.findViewById(R.id.btnScheduledTime);
        MaterialButton[] dayButtons = {
                dialogView.findViewById(R.id.btnDayMon),
                dialogView.findViewById(R.id.btnDayTue),
                dialogView.findViewById(R.id.btnDayWed),
                dialogView.findViewById(R.id.btnDayThu),
                dialogView.findViewById(R.id.btnDayFri),
                dialogView.findViewById(R.id.btnDaySat),
                dialogView.findViewById(R.id.btnDaySun)
        };

        boolean scheduledEnabled = AlertPrefs.isScheduledEnabled(this);
        switchScheduled.setChecked(scheduledEnabled);
        layoutScheduled.setVisibility(scheduledEnabled ? View.VISIBLE : View.GONE);
        btnTime.setText(String.format(Locale.US, "%02d:%02d",
                AlertPrefs.getScheduledHour(this), AlertPrefs.getScheduledMinute(this)));
        applyDayButtonColors(dayButtons, AlertPrefs.getScheduledDays(this));

        switchScheduled.setOnCheckedChangeListener((button, checked) -> {
            AlertPrefs.prefs(this).edit().putBoolean(AlertPrefs.KEY_SCHEDULED_ENABLED, checked).apply();
            layoutScheduled.setVisibility(checked ? View.VISIBLE : View.GONE);
            if (checked && !AlertPrefs.hasScheduledDays(this)) {
                showToast("요일을 하나 이상 선택해야 지정 시간 알림이 동작합니다.");
            }
            applyScheduledAlarm(checked);
        });
        btnTime.setOnClickListener(v -> {
            int hour = AlertPrefs.getScheduledHour(this);
            int minute = AlertPrefs.getScheduledMinute(this);
            new android.app.TimePickerDialog(this, (timePicker, selectedHour, selectedMinute) -> {
                AlertPrefs.prefs(this).edit()
                        .putInt(AlertPrefs.KEY_SCHEDULED_HOUR, selectedHour)
                        .putInt(AlertPrefs.KEY_SCHEDULED_MINUTE, selectedMinute)
                        .apply();
                btnTime.setText(String.format(Locale.US, "%02d:%02d", selectedHour, selectedMinute));
                if (AlertPrefs.isScheduledEnabled(this)) {
                    applyScheduledAlarm(true);
                }
            }, hour, minute, true).show();
        });
        for (int i = 0; i < dayButtons.length; i++) {
            final int bit = i;
            dayButtons[i].setOnClickListener(v -> {
                int days = AlertPrefs.getScheduledDays(this) ^ (1 << bit);
                AlertPrefs.prefs(this).edit().putInt(AlertPrefs.KEY_SCHEDULED_DAYS, days).apply();
                applyDayButtonColors(dayButtons, days);
                if (AlertPrefs.isScheduledEnabled(this)) {
                    if (!AlertPrefs.hasScheduledDays(days)) {
                        showToast("요일이 선택되지 않아 지정 시간 알림을 중지합니다.");
                    }
                    applyScheduledAlarm(true);
                }
            });
        }

        SwitchCompat switchVibrate = dialogView.findViewById(R.id.switchVibrate);
        switchVibrate.setChecked(AlertPrefs.isVibrateEnabled(this));
        switchVibrate.setOnCheckedChangeListener((button, checked) ->
                AlertPrefs.prefs(this).edit().putBoolean(AlertPrefs.KEY_VIBRATE, checked).apply());
    }

    private void populateCafe24MarketRows(LinearLayout container, AlertDialog dialog) {
        container.removeAllViews();
        List<Cafe24MarketConfig> markets = credentialStore.getCafe24Markets();
        if (markets.isEmpty()) {
            TextView emptyView = new TextView(this);
            emptyView.setText("등록된 Cafe24 판매처가 없습니다. 판매처를 추가해 주세요.");
            emptyView.setTextColor(ContextCompat.getColor(this, R.color.ship_text_secondary));
            emptyView.setTextSize(12f);
            emptyView.setPadding(0, 8, 0, 8);
            container.addView(emptyView);
            return;
        }

        for (Cafe24MarketConfig config : markets) {
            View itemView = getLayoutInflater().inflate(R.layout.item_settings_market, container, false);
            CheckBox enabledCheckBox = itemView.findViewById(R.id.cbMarketConfigEnabled);
            TextView nameView = itemView.findViewById(R.id.tvMarketConfigName);
            TextView pathView = itemView.findViewById(R.id.tvMarketConfigPath);
            TextView statusView = itemView.findViewById(R.id.tvMarketConfigStatus);

            enabledCheckBox.setChecked(config.enabled);
            nameView.setText(config.buildMarketLabel());
            pathView.setText(buildMarketSourceText(config));
            statusView.setText(resolveCafe24Status(config.key));

            enabledCheckBox.setOnClickListener(v -> {
                credentialStore.setCafe24MarketEnabled(config.key, enabledCheckBox.isChecked());
                dialog.dismiss();
                refreshOrders();
            });
            itemView.findViewById(R.id.btnMarketConfigJson).setOnClickListener(v -> {
                dialog.dismiss();
                launchCafe24Import(config.key, config.displayName);
            });
            itemView.findViewById(R.id.btnMarketConfigRename).setOnClickListener(v -> {
                dialog.dismiss();
                showRenameCafe24MarketDialog(config);
            });
            itemView.findViewById(R.id.btnMarketConfigDelete).setOnClickListener(v -> {
                dialog.dismiss();
                confirmDeleteCafe24Market(config);
            });

            container.addView(itemView);
        }
    }

    private String buildMarketSourceText(Cafe24MarketConfig config) {
        if (!config.hasJson()) {
            return "JSON 위치: 미연결";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("파일: ").append(config.sourceLabel.isEmpty() ? "선택된 파일 없음" : config.sourceLabel);
        if (!config.sourceUri.isEmpty()) {
            builder.append("\n위치: ").append(config.sourceUri);
        }
        return builder.toString();
    }

    private String resolveCafe24Status(String marketKey) {
        String status = currentCafe24Statuses.get(marketKey);
        if (status != null && !status.trim().isEmpty()) {
            return status;
        }
        return credentialStore.getCafe24Status(marketKey);
    }

    private void showAddCafe24MarketDialog() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int padding = dpToPx(20);
        container.setPadding(padding, dpToPx(8), padding, 0);

        EditText nameInput = buildTextInput("판매처명", "");
        container.addView(nameInput);

        new MaterialAlertDialogBuilder(this)
                .setTitle("판매처 추가")
                .setMessage("이 이름은 구글시트 발주사 매칭에 사용됩니다.")
                .setView(container)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("추가", (dialog, which) -> {
                    String name = safeText(nameInput.getText().toString());
                    if (name.isEmpty()) {
                        showToast("판매처명을 입력하세요.");
                        return;
                    }
                    try {
                        Cafe24MarketConfig created = credentialStore.createCafe24Market(name);
                        showToast(name + " 판매처를 추가했습니다. 이어서 JSON을 선택하세요.");
                        launchCafe24Import(created.key, created.displayName);
                        showCredentialOnlyStatus();
                    } catch (Exception ex) {
                        showToast(ex.getMessage());
                    }
                })
                .show();
    }

    private void showRenameCafe24MarketDialog(Cafe24MarketConfig config) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int padding = dpToPx(20);
        container.setPadding(padding, dpToPx(8), padding, 0);

        EditText nameInput = buildTextInput("판매처명", config.displayName);
        container.addView(nameInput);

        new MaterialAlertDialogBuilder(this)
                .setTitle("판매처명 수정")
                .setView(container)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("저장", (dialog, which) -> {
                    String name = safeText(nameInput.getText().toString());
                    if (name.isEmpty()) {
                        showToast("판매처명을 입력하세요.");
                        return;
                    }
                    try {
                        credentialStore.renameCafe24Market(config.key, name);
                        showToast("판매처명을 수정했습니다.");
                        refreshOrders();
                    } catch (Exception ex) {
                        showToast(ex.getMessage());
                    }
                })
                .show();
    }

    private void confirmDeleteCafe24Market(Cafe24MarketConfig config) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("판매처 삭제")
                .setMessage(config.displayName + " 판매처를 삭제하시겠습니까? 저장된 JSON도 같이 제거됩니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton("삭제", (dialog, which) -> {
                    credentialStore.deleteCafe24Market(config.key);
                    showToast("판매처를 삭제했습니다.");
                    refreshOrders();
                })
                .show();
    }

    private void highlightIntervalButton(MaterialButton b10, MaterialButton b20, MaterialButton b30, int selected) {
        int activeColor = getResources().getColor(R.color.ship_primary, getTheme());
        int defaultColor = getResources().getColor(R.color.ship_text_secondary, getTheme());
        b10.setTextColor(selected == 15 ? activeColor : defaultColor);
        b20.setTextColor(selected == 20 ? activeColor : defaultColor);
        b30.setTextColor(selected == 30 ? activeColor : defaultColor);
    }

    private void applyDayButtonColors(MaterialButton[] buttons, int days) {
        int activeColor = getResources().getColor(R.color.ship_primary, getTheme());
        int defaultColor = getResources().getColor(R.color.ship_text_secondary, getTheme());
        for (int i = 0; i < buttons.length; i++) {
            buttons[i].setTextColor(((days >> i) & 1) == 1 ? activeColor : defaultColor);
        }
    }

    private String buildSettingsSummary() {
        int registeredCafe24Count = credentialStore.getRegisteredCafe24Count();
        int enabledCafe24Count = credentialStore.getEnabledCafe24Count();
        int connectedCafe24Count = credentialStore.getConnectedCafe24Count();
        boolean coupangConnected = credentialStore.getCoupangCredentials().isComplete();

        if (registeredCafe24Count == 0 && !coupangConnected) {
            return getString(R.string.settings_summary_empty);
        }

        StringBuilder summary = new StringBuilder();
        summary.append("Cafe24 등록 ").append(registeredCafe24Count).append("개")
                .append(" / 표시 ").append(enabledCafe24Count).append("개")
                .append(" / JSON 연결 ").append(connectedCafe24Count).append("개")
                .append(" / 쿠팡 ").append(coupangConnected ? "연결" : "미연결");
        if (currentFetchedCount > 0) {
            summary.append(" / 조회 성공 ").append(currentFetchedCount).append("개");
        }
        if (currentTotalCount > 0) {
            summary.append(" / 출고 ").append(currentTotalCount).append("건");
        }
        return summary.toString();
    }

    private void launchCafe24Import(String slot, String displayName) {
        pendingCafe24Slot = safeText(slot);
        pendingCafe24Name = safeText(displayName);
        cafe24ImportLauncher.launch(new String[]{"application/json", "text/plain", "*/*"});
    }

    private void handleCafe24ImportResult(Uri uri) {
        if (uri == null || pendingCafe24Slot.isEmpty()) {
            return;
        }

        try {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }

            String json = readText(uri);
            validateCafe24Json(json);
            String sourceLabel = resolveDocumentLabel(uri);
            String sourceUri = uri.toString();
            credentialStore.saveCafe24Json(pendingCafe24Slot, json, sourceLabel, sourceUri);

            String marketName = pendingCafe24Name;
            if (marketName.isEmpty()) {
                Cafe24MarketConfig config = credentialStore.getCafe24Market(pendingCafe24Slot);
                marketName = config == null ? "판매처" : config.displayName;
            }
            showToast(marketName + " JSON을 저장했습니다.");
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
        return buildTextInput(getString(hintResId), value);
    }

    private EditText buildTextInput(String hint, String value) {
        EditText editText = new EditText(this);
        editText.setHint(hint);
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

    private String resolveDocumentLabel(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (columnIndex >= 0) {
                    String name = cursor.getString(columnIndex);
                    if (name != null && !name.trim().isEmpty()) {
                        return name.trim();
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return uri.getLastPathSegment() == null ? uri.toString() : uri.getLastPathSegment();
    }

    private void validateCafe24Json(String json) throws Exception {
        JSONObject object = new JSONObject(json);
        if (object.optString("MallId").isEmpty()) {
            throw new IllegalArgumentException(getString(R.string.invalid_cafe24_json));
        }
    }

    private void applyPollingSchedule(boolean enabled) {
        if (enabled) {
            ShipmentAlertWorker.schedule(this, AlertPrefs.getPollingInterval(this));
        } else {
            ShipmentAlertWorker.cancel(this);
        }
    }

    private void applyScheduledAlarm(boolean enabled) {
        if (enabled) {
            com.rkghrud.shipapp.receivers.ScheduledAlarmReceiver.scheduleNext(this);
        } else {
            com.rkghrud.shipapp.receivers.ScheduledAlarmReceiver.cancel(this);
        }
    }

    private void applyAlertSchedule(boolean enabled) {
        applyPollingSchedule(enabled);
    }

    private boolean areAlertsEnabled() {
        return AlertPrefs.isPollingEnabled(this);
    }

    private void setAlertsEnabled(boolean enabled) {
        AlertPrefs.prefs(this).edit()
                .putBoolean(AlertPrefs.KEY_POLLING_ENABLED, enabled).apply();
        applyPollingSchedule(enabled);
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

    private void showOrderDetail(DispatchOrder order) {
        OrderDetailDialog.show(this, order);
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
        final Map<String, String> cafe24Statuses = new LinkedHashMap<>();
        final List<String> activeCafe24Keys = new ArrayList<>();
        String coupangStatus = "";
        String updatedAt = "-";
        int connectedCount;
        int fetchedCount;
        int totalCount;
    }
}


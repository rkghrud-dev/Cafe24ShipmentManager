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

import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.graphics.Typeface;

import android.view.View;
import android.view.inputmethod.EditorInfo;

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
import androidx.appcompat.app.AppCompatDelegate;

import androidx.appcompat.widget.AppCompatImageButton;

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
import com.rkghrud.shipapp.data.DispatchOrderUiHelper;

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
import java.util.LinkedHashSet;

import java.util.List;

import java.util.Locale;

import java.util.Map;
import java.util.Set;

import java.util.concurrent.ExecutorService;

import java.util.concurrent.Executors;



public class MainActivity extends AppCompatActivity {

    public static final String EXTRA_MARKET_FILTER = "com.rkghrud.shipapp.extra.MARKET_FILTER";

    public static final String EXTRA_REFRESH_ON_OPEN = "com.rkghrud.shipapp.extra.REFRESH_ON_OPEN";

    public static final String MARKET_FILTER_ALL = "all";

    private static final int STATUS_OFFLINE = 0;

    private static final int STATUS_ONLINE = 1;

    private static final int STATUS_WARNING = 2;



    private static final String FILTER_ALL = MARKET_FILTER_ALL;

    private static final String COUPANG_KEY = "coupang";

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private static final String[] PAGE_SIZE_LABELS = {"5개씩 보기", "10개씩 보기", "20개씩 보기", "100개씩 보기"};

    private static final int[] PAGE_SIZES = {5, 10, 20, 100};
    private static final int MAX_VISIBLE_PAGE_BUTTONS = 5;
    private static final String DEFAULT_EMPTY_MESSAGE = "출고 대상 주문이 없습니다.";



    private CredentialStore credentialStore;

    private LiveShipmentRepository repository;

    private DispatchOrderAdapter adapter;

    private ExecutorService executorService;

    private ArrayAdapter<String> marketAdapter;



    private SwipeRefreshLayout swipeRefreshLayout;

    private TextView aggregateStatusView;

    private TextView totalCountView;
    private TextView marketCountSummaryView;

    private TextView pendingBadgeView;

    private TextView selectionSummaryView;

    private TextView emptyStateView;

    private Spinner marketFilterSpinner;

    private Spinner pageSizeSpinner;
    private LinearLayout paginationLayout;
    private LinearLayout pageNumbersLayout;
    private MaterialButton prevPageButton;
    private MaterialButton nextPageButton;

    private CheckBox statusPreparingCheckBox;

    private CheckBox statusStandbyCheckBox;

    private CheckBox statusShippingCheckBox;

    private CheckBox statusCancelRequestCheckBox;

    private CheckBox statusExchangeRequestCheckBox;

    private CheckBox selectAllCheckBox;

    private AppCompatImageButton refreshButton;

    private MaterialButton importSpreadsheetButton;

    private MaterialButton standbyPageButton;

    private MaterialButton uploadSelectedButton;



    private final List<DispatchOrder> allOrders = new ArrayList<>();

    private final List<DispatchOrder> filteredOrders = new ArrayList<>();

    private final List<DispatchOrder> displayedOrders = new ArrayList<>();

    private int currentPageIndex = 0;

    private final List<String> marketFilterLabels = new ArrayList<>();

    private final List<String> marketFilterKeys = new ArrayList<>();

    private final List<String> currentActiveCafe24Keys = new ArrayList<>();

    private final Map<String, String> currentCafe24Statuses = new LinkedHashMap<>();



    private boolean suppressSelectAllCallback = false;

    private String pendingCafe24Slot = "";

    private String pendingCafe24Name = "";

    private boolean pendingCafe24CreateMode = false;

    private String currentCoupangStatus = "";

    private String currentUpdatedAt = "-";
    private String currentMarketCountSummary = "";

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

        AppCompatDelegate.setDefaultNightMode(AlertPrefs.isDarkModeEnabled(this)
                ? AppCompatDelegate.MODE_NIGHT_YES
                : AppCompatDelegate.MODE_NIGHT_NO);

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
        marketCountSummaryView = findViewById(R.id.tvMarketCountSummary);

        pendingBadgeView = findViewById(R.id.tvPendingBadge);

        selectionSummaryView = findViewById(R.id.tvSelectionSummary);

        emptyStateView = findViewById(R.id.tvEmptyState);

        marketFilterSpinner = findViewById(R.id.spinnerMarketFilter);

        pageSizeSpinner = findViewById(R.id.spinnerPageSize);
        paginationLayout = findViewById(R.id.layoutPagination);
        pageNumbersLayout = findViewById(R.id.layoutPageNumbers);
        prevPageButton = findViewById(R.id.btnPrevPage);
        nextPageButton = findViewById(R.id.btnNextPage);

        statusPreparingCheckBox = findViewById(R.id.cbStatusPreparing);

        statusStandbyCheckBox = findViewById(R.id.cbStatusStandby);

        statusShippingCheckBox = findViewById(R.id.cbStatusShipping);

        statusCancelRequestCheckBox = findViewById(R.id.cbStatusCancelRequest);

        statusExchangeRequestCheckBox = findViewById(R.id.cbStatusExchangeRequest);

        selectAllCheckBox = findViewById(R.id.cbSelectAll);

        refreshButton = findViewById(R.id.btnRefresh);

        importSpreadsheetButton = findViewById(R.id.btnImportSpreadsheet);

        standbyPageButton = findViewById(R.id.btnOpenStandbyPage);

        uploadSelectedButton = findViewById(R.id.btnUploadSelected);

        RecyclerView ordersRecyclerView = findViewById(R.id.rvDispatchOrders);

        statusPreparingCheckBox.setChecked(true);
        statusStandbyCheckBox.setChecked(true);
        statusShippingCheckBox.setChecked(false);
        statusCancelRequestCheckBox.setChecked(false);
        statusExchangeRequestCheckBox.setChecked(false);



        adapter = new DispatchOrderAdapter();

        adapter.setOnSelectionChanged(this::updateSelectionUi);

        adapter.setOnItemClick(this::showOrderDetail);

        ordersRecyclerView.setLayoutManager(new LinearLayoutManager(this));

        ordersRecyclerView.setAdapter(adapter);



        findViewById(R.id.btnOpenSettings).setOnClickListener(v -> showSettingsDialog());



        marketAdapter = new ArrayAdapter<>(this, R.layout.item_compact_spinner, new ArrayList<>());

        marketAdapter.setDropDownViewResource(R.layout.item_compact_spinner_dropdown);

        marketFilterSpinner.setAdapter(marketAdapter);

        refreshMarketFilterOptions(FILTER_ALL);



        ArrayAdapter<String> pageAdapter = new ArrayAdapter<>(this,

                R.layout.item_compact_spinner, PAGE_SIZE_LABELS);

        pageAdapter.setDropDownViewResource(R.layout.item_compact_spinner_dropdown);

        pageSizeSpinner.setAdapter(pageAdapter);

        pageSizeSpinner.setSelection(2);



        AdapterView.OnItemSelectedListener filterListener = new AdapterView.OnItemSelectedListener() {

            @Override

            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {

                applyFilters(true);

            }



            @Override

            public void onNothingSelected(AdapterView<?> parent) {

            }

        };

        marketFilterSpinner.setOnItemSelectedListener(filterListener);

        pageSizeSpinner.setOnItemSelectedListener(filterListener);

        prevPageButton.setOnClickListener(v -> movePage(-1));
        nextPageButton.setOnClickListener(v -> movePage(1));

        selectAllCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {

            if (!suppressSelectAllCallback) {

                setDisplayedOrdersSelected(isChecked);

            }

        });

        bindStatusFilterCheckBox(statusPreparingCheckBox);
        bindStatusFilterCheckBox(statusStandbyCheckBox);
        bindStatusFilterCheckBox(statusShippingCheckBox);
        bindStatusFilterCheckBox(statusCancelRequestCheckBox);
        bindStatusFilterCheckBox(statusExchangeRequestCheckBox);

        refreshButton.setOnClickListener(v -> refreshOrders());

        importSpreadsheetButton.setOnClickListener(v -> launchSpreadsheetImport());

        standbyPageButton.setOnClickListener(v -> confirmStandbySelected());

        uploadSelectedButton.setOnClickListener(v -> confirmUploadSelected());

        swipeRefreshLayout.setOnRefreshListener(this::refreshOrders);



        showCredentialOnlyStatus();

        handleWidgetIntent(getIntent(), false);

        refreshOrders();

    }

    @Override
    protected void onNewIntent(Intent intent) {

        super.onNewIntent(intent);

        setIntent(intent);

        handleWidgetIntent(intent, true);

    }



    @Override

    protected void onDestroy() {

        super.onDestroy();

        if (executorService != null) {

            executorService.shutdownNow();

        }

    }



    private void refreshOrders() {
        String requestedMarketKey = getSelectedMarketFilter();
        setLoading(true);
        executorService.execute(() -> {
            DispatchLoadResult result = loadDispatchOrders(requestedMarketKey);
            runOnUiThread(() -> applyDispatchLoadResult(result));
        });
    }



    private DispatchLoadResult loadDispatchOrders(String requestedMarketKey) {
        DispatchLoadResult result = new DispatchLoadResult();
        LocalDate endDate = LocalDate.now(SEOUL);
        LocalDate startDate = endDate.minusDays(14);
        LinkedHashMap<String, String> marketCountLabels = new LinkedHashMap<>();

        for (Cafe24MarketConfig config : credentialStore.getCafe24Markets()) {
            result.cafe24Statuses.put(config.key, credentialStore.getCafe24Status(config.key));
        }

        for (Cafe24MarketConfig config : credentialStore.getActiveCafe24Markets()) {
            if (!DispatchOrderUiHelper.matchesMarketKey(config.key, requestedMarketKey)) {
                continue;
            }
            result.activeCafe24Keys.add(config.key);
            result.connectedCount++;
            marketCountLabels.put(config.displayName, "0");
            try {
                List<DispatchOrder> orders = repository.fetchOrdersForDispatch(config.key, startDate, endDate);
                result.orders.addAll(orders);
                marketCountLabels.put(config.displayName, String.valueOf(orders.size()));
                result.cafe24Statuses.put(config.key, config.buildMarketLabel() + "\n조회 성공 " + orders.size() + "건");
                result.fetchedCount++;
            } catch (Exception ex) {
                marketCountLabels.put(config.displayName, "확인 필요");
                result.cafe24Statuses.put(config.key, config.buildMarketLabel() + "\n확인 필요\n" + clipMessage(ex.getMessage()));
                result.errors.add(config.displayName + " 조회 실패: " + ex.getMessage());
            }
        }

        if (FeatureFlags.ENABLE_COUPANG
                && credentialStore.getCoupangCredentials().isComplete()
                && DispatchOrderUiHelper.matchesMarketKey(COUPANG_KEY, requestedMarketKey)) {
            result.connectedCount++;
            marketCountLabels.put("쿠팡", "0");
            try {
                List<DispatchOrder> coupangOrders = repository.fetchOrdersForDispatch(COUPANG_KEY, startDate, endDate);
                result.orders.addAll(coupangOrders);
                marketCountLabels.put("쿠팡", String.valueOf(coupangOrders.size()));
                result.coupangStatus = "쿠팡\n조회 성공 " + coupangOrders.size() + "건";
                result.fetchedCount++;
            } catch (Exception ex) {
                marketCountLabels.put("쿠팡", "확인 필요");
                result.coupangStatus = "쿠팡\n확인 필요\n" + clipMessage(ex.getMessage());
                result.errors.add("쿠팡 조회 실패: " + ex.getMessage());
            }
        } else {
            result.coupangStatus = "";
        }

        Collections.sort(result.orders, Comparator.comparing((DispatchOrder order) -> safeText(order.orderDate)).reversed());
        result.totalCount = result.orders.size();
        result.marketCountSummary = DispatchOrderUiHelper.formatMarketCountSummary(marketCountLabels);
        result.updatedAt = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(new Date());
        return result;
    }

    private void markNewOrders(List<DispatchOrder> orders, boolean updateSnapshot) {
        java.util.Set<String> knownOrderKeys = AlertPrefs.getKnownOrderKeys(this);
        boolean hasBaseline = !knownOrderKeys.isEmpty();
        java.util.LinkedHashSet<String> snapshotKeys = new java.util.LinkedHashSet<>();

        for (DispatchOrder order : orders) {
            order.newlyDetected = false;
            String stableKey = order.stableOrderKey();
            if (stableKey.isEmpty()) {
                continue;
            }
            snapshotKeys.add(stableKey);
            if (hasBaseline && !knownOrderKeys.contains(stableKey)) {
                order.newlyDetected = true;
            }
        }

        if (updateSnapshot) {
            AlertPrefs.saveKnownOrderKeys(this, snapshotKeys);
        }
    }



    private void applyDispatchLoadResult(DispatchLoadResult result) {

        String selectedFilter = getSelectedMarketFilter();



        markNewOrders(result.orders, result.fetchedCount > 0);

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

        currentMarketCountSummary = result.marketCountSummary;

        currentUpdatedAt = result.updatedAt;



        refreshMarketFilterOptions(selectedFilter);

        updateHeaderUi();

        applyFilters();

        NotificationHelper.saveWidgetDataFromDispatchOrders(this, result.orders, result.totalCount);

        NotificationHelper.updateWidget(this);

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



        currentCoupangStatus = FeatureFlags.ENABLE_COUPANG ? credentialStore.getCoupangStatus() : "";

        currentConnectedCount = currentActiveCafe24Keys.size() + ((FeatureFlags.ENABLE_COUPANG && credentialStore.getCoupangCredentials().isComplete()) ? 1 : 0);

        currentFetchedCount = 0;

        currentTotalCount = 0;

        currentMarketCountSummary = "";

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

        marketCountSummaryView.setVisibility(View.GONE);
        marketCountSummaryView.setText("");



        pendingBadgeView.setVisibility(View.GONE);
        pendingBadgeView.setText("");



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
        aggregateStatusView.setVisibility(View.GONE);

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

        if (FeatureFlags.ENABLE_COUPANG && credentialStore.getCoupangCredentials().isComplete() && hasStatusWarning(currentCoupangStatus)) {

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

        marketFilterLabels.add("전체");

        marketFilterKeys.add(FILTER_ALL);



        for (Cafe24MarketConfig config : credentialStore.getActiveCafe24Markets()) {

            marketFilterLabels.add(config.displayName);

            marketFilterKeys.add(config.key);

        }

        if (FeatureFlags.ENABLE_COUPANG && credentialStore.getCoupangCredentials().isComplete()) {

            marketFilterLabels.add("쿠팡");

            marketFilterKeys.add(COUPANG_KEY);

        }



        marketAdapter.clear();

        marketAdapter.addAll(marketFilterLabels);

        marketAdapter.notifyDataSetChanged();



        int selectedIndex = marketFilterKeys.indexOf(selectedKey);

        marketFilterSpinner.setSelection(selectedIndex >= 0 ? selectedIndex : 0, false);

    }

    private void handleWidgetIntent(@Nullable Intent intent, boolean allowRefresh) {

        if (intent == null) {

            return;

        }

        String marketKey = safeText(intent.getStringExtra(EXTRA_MARKET_FILTER));

        if (!marketKey.isEmpty()) {

            selectMarketFilter(marketKey);

        }

        if (allowRefresh && intent.getBooleanExtra(EXTRA_REFRESH_ON_OPEN, false)) {

            refreshOrders();

        }

    }

    private void selectMarketFilter(String marketKey) {

        if (marketFilterSpinner == null || marketKey.isEmpty()) {

            return;

        }

        int selectedIndex = marketFilterKeys.indexOf(marketKey);

        if (selectedIndex < 0) {

            return;

        }

        marketFilterSpinner.setSelection(selectedIndex, false);

        applyFilters(true);

    }



    private void applyFilters() {
        applyFilters(false);
    }

    private void applyFilters(boolean resetToFirstPage) {
        if (resetToFirstPage) {
            currentPageIndex = 0;
        }

        filteredOrders.clear();
        displayedOrders.clear();

        String marketFilterKey = getSelectedMarketFilter();
        for (DispatchOrder order : allOrders) {
            if (!matchesMarketFilter(order, marketFilterKey)) {
                continue;
            }
            if (!matchesStatusFilter(order)) {
                continue;
            }
            filteredOrders.add(order);
        }

        filteredOrders.sort(DispatchOrder.displayComparator());
        currentFilteredCount = filteredOrders.size();
        totalCountView.setText(String.valueOf(currentFilteredCount));

        int totalPages = getTotalPageCount();
        if (totalPages == 0) {
            currentPageIndex = 0;
        } else if (currentPageIndex >= totalPages) {
            currentPageIndex = totalPages - 1;
        }

        int pageSize = getSelectedPageSize();
        int start = Math.min(currentPageIndex * pageSize, filteredOrders.size());
        int end = Math.min(start + pageSize, filteredOrders.size());
        if (start < end) {
            displayedOrders.addAll(filteredOrders.subList(start, end));
        }

        adapter.setItems(displayedOrders);
        updateEmptyState();
        updatePaginationUi(totalPages);
        updateSelectionUi();
    }

    private void movePage(int delta) {
        int totalPages = getTotalPageCount();
        if (totalPages <= 1) {
            return;
        }
        int targetPage = Math.max(0, Math.min(currentPageIndex + delta, totalPages - 1));
        if (targetPage == currentPageIndex) {
            return;
        }
        currentPageIndex = targetPage;
        applyFilters();
    }

    private void goToPage(int pageIndex) {
        int totalPages = getTotalPageCount();
        if (pageIndex < 0 || pageIndex >= totalPages || pageIndex == currentPageIndex) {
            return;
        }
        currentPageIndex = pageIndex;
        applyFilters();
    }

    private int getTotalPageCount() {
        if (currentFilteredCount <= 0) {
            return 0;
        }
        int pageSize = Math.max(1, getSelectedPageSize());
        return (currentFilteredCount + pageSize - 1) / pageSize;
    }

    private void updatePaginationUi(int totalPages) {
        if (paginationLayout == null || pageNumbersLayout == null || prevPageButton == null || nextPageButton == null) {
            return;
        }

        pageNumbersLayout.removeAllViews();
        if (totalPages <= 1) {
            paginationLayout.setVisibility(View.GONE);
            prevPageButton.setEnabled(false);
            nextPageButton.setEnabled(false);
            return;
        }

        paginationLayout.setVisibility(View.VISIBLE);
        boolean interactive = !swipeRefreshLayout.isRefreshing();
        prevPageButton.setEnabled(interactive && currentPageIndex > 0);
        nextPageButton.setEnabled(interactive && currentPageIndex < totalPages - 1);

        int startPage = Math.max(0, currentPageIndex - 2);
        int endPage = Math.min(totalPages, startPage + MAX_VISIBLE_PAGE_BUTTONS);
        startPage = Math.max(0, endPage - MAX_VISIBLE_PAGE_BUTTONS);

        if (startPage > 0) {
            pageNumbersLayout.addView(buildPageChip(0, interactive));
            if (startPage > 1) {
                appendEllipsisChip();
            }
        }

        for (int page = startPage; page < endPage; page++) {
            pageNumbersLayout.addView(buildPageChip(page, interactive));
        }

        if (endPage < totalPages) {
            if (endPage < totalPages - 1) {
                appendEllipsisChip();
            }
            pageNumbersLayout.addView(buildPageChip(totalPages - 1, interactive));
        }
    }

    private TextView buildPageChip(int pageIndex, boolean interactive) {
        boolean currentPage = pageIndex == currentPageIndex;
        TextView chip = new TextView(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMarginEnd(dpToPx(6));
        chip.setLayoutParams(params);
        chip.setMinWidth(dpToPx(34));
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8));
        chip.setText(String.valueOf(pageIndex + 1));
        chip.setTextSize(12f);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setBackgroundResource(currentPage ? R.drawable.bg_market_chip : R.drawable.bg_inline_panel);
        chip.setTextColor(ContextCompat.getColor(this,
                currentPage ? R.color.ship_primary : R.color.ship_text_secondary));
        boolean enabled = interactive && !currentPage;
        chip.setEnabled(enabled);
        chip.setAlpha(enabled || currentPage ? 1f : 0.45f);
        if (enabled) {
            chip.setOnClickListener(v -> goToPage(pageIndex));
        }
        return chip;
    }

    private void appendEllipsisChip() {
        TextView ellipsis = new TextView(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMarginEnd(dpToPx(6));
        ellipsis.setLayoutParams(params);
        ellipsis.setPadding(dpToPx(6), dpToPx(8), dpToPx(6), dpToPx(8));
        ellipsis.setText("...");
        ellipsis.setTextSize(12f);
        ellipsis.setTypeface(Typeface.DEFAULT_BOLD);
        ellipsis.setTextColor(ContextCompat.getColor(this, R.color.ship_text_secondary));
        pageNumbersLayout.addView(ellipsis);
    }

    private boolean matchesMarketFilter(DispatchOrder order, String marketFilterKey) {

        return DispatchOrderUiHelper.matchesMarketKey(order.marketKey, marketFilterKey);

    }

    private boolean matchesStatusFilter(DispatchOrder order) {
        return isStatusFilterSelected(order.statusFilterKey());
    }

    private boolean isStatusFilterSelected(String statusKey) {
        switch (statusKey) {
            case DispatchOrder.STATUS_FILTER_PREPARING:
                return statusPreparingCheckBox.isChecked();
            case DispatchOrder.STATUS_FILTER_STANDBY:
                return statusStandbyCheckBox.isChecked();
            case DispatchOrder.STATUS_FILTER_SHIPPING:
                return statusShippingCheckBox.isChecked();
            case DispatchOrder.STATUS_FILTER_CANCEL_REQUEST:
                return statusCancelRequestCheckBox.isChecked();
            case DispatchOrder.STATUS_FILTER_EXCHANGE_REQUEST:
                return statusExchangeRequestCheckBox.isChecked();
            default:
                return false;
        }
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

    private void bindStatusFilterCheckBox(CheckBox checkBox) {
        checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> applyFilters(true));
    }

    private void setStatusFilterEnabled(boolean enabled) {
        statusPreparingCheckBox.setEnabled(enabled);
        statusStandbyCheckBox.setEnabled(enabled);
        statusShippingCheckBox.setEnabled(enabled);
        statusCancelRequestCheckBox.setEnabled(enabled);
        statusExchangeRequestCheckBox.setEnabled(enabled);
    }

    private boolean hasAnySelectedStatusFilter() {
        return statusPreparingCheckBox.isChecked()
                || statusStandbyCheckBox.isChecked()
                || statusShippingCheckBox.isChecked()
                || statusCancelRequestCheckBox.isChecked()
                || statusExchangeRequestCheckBox.isChecked();
    }

    private Set<String> getSelectedStatusFilterKeys() {
        Set<String> selectedStatusKeys = new LinkedHashSet<>();
        if (statusPreparingCheckBox.isChecked()) {
            selectedStatusKeys.add(DispatchOrder.STATUS_FILTER_PREPARING);
        }
        if (statusStandbyCheckBox.isChecked()) {
            selectedStatusKeys.add(DispatchOrder.STATUS_FILTER_STANDBY);
        }
        if (statusShippingCheckBox.isChecked()) {
            selectedStatusKeys.add(DispatchOrder.STATUS_FILTER_SHIPPING);
        }
        if (statusCancelRequestCheckBox.isChecked()) {
            selectedStatusKeys.add(DispatchOrder.STATUS_FILTER_CANCEL_REQUEST);
        }
        if (statusExchangeRequestCheckBox.isChecked()) {
            selectedStatusKeys.add(DispatchOrder.STATUS_FILTER_EXCHANGE_REQUEST);
        }
        return selectedStatusKeys;
    }

    private List<DispatchOrder> getOrdersForCurrentMatchScope() {
        return DispatchOrderUiHelper.filterOrdersForSelection(
                allOrders,
                getSelectedMarketFilter(),
                getSelectedStatusFilterKeys()
        );
    }



    private void setDisplayedOrdersSelected(boolean selected) {

        for (DispatchOrder order : displayedOrders) {

            if (!order.isSelectableForUpload()) {

                order.selected = false;

                continue;

            }

            order.selected = selected;

        }

        adapter.notifyDataSetChanged();

        updateSelectionUi();

    }



    private void updateSelectionUi() {
        int selectedCount = countSelectedOrders();
        int readyCount = countReadyOrders();
        int blockedCount = 0;
        int selectableCount = 0;
        int newCount = 0;

        for (DispatchOrder order : displayedOrders) {
            if (order.isUploadBlocked()) {
                blockedCount++;
            }
            if (order.isSelectableForUpload()) {
                selectableCount++;
            }
            if (order.newlyDetected) {
                newCount++;
            }
        }

        String summary = "표시 " + displayedOrders.size() + "/" + currentFilteredCount;
        int totalPages = getTotalPageCount();
        if (totalPages > 1) {
            summary += " · 페이지 " + (currentPageIndex + 1) + "/" + totalPages;
        }
        summary += " · 선택 " + selectedCount + " · 업로드 " + readyCount;
        if (newCount > 0) {
            summary += " · 신규 " + newCount;
        }
        if (blockedCount > 0) {
            summary += " · 미출고 " + blockedCount;
        }
        selectionSummaryView.setText(summary);

        boolean allSelected = selectableCount > 0;
        for (DispatchOrder order : displayedOrders) {
            if (!order.isSelectableForUpload()) {
                continue;
            }
            if (!order.selected) {
                allSelected = false;
                break;
            }
        }

        suppressSelectAllCallback = true;
        selectAllCheckBox.setChecked(allSelected);
        suppressSelectAllCallback = false;

        uploadSelectedButton.setText(readyCount > 0 ? "선택 업로드 " + readyCount + "건" : "선택 업로드");
        selectAllCheckBox.setEnabled(!swipeRefreshLayout.isRefreshing() && selectableCount > 0);
        uploadSelectedButton.setEnabled(!swipeRefreshLayout.isRefreshing() && readyCount > 0);
        importSpreadsheetButton.setEnabled(!swipeRefreshLayout.isRefreshing() && !allOrders.isEmpty());
        standbyPageButton.setText(readyCount > 0 ? "대기매칭 " + readyCount + "건" : "대기매칭");
        standbyPageButton.setEnabled(!swipeRefreshLayout.isRefreshing() && readyCount > 0);
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

            if (order.selected && hasTrackingNumber(order) && order.isSelectableForUpload()) {

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

        if (!hasAnySelectedStatusFilter()) {

            emptyStateView.setText("주문 상태를 하나 이상 체크하세요.");

            emptyStateView.setVisibility(View.VISIBLE);

            return;

        }

        if (currentFilteredCount == 0) {

            emptyStateView.setText("선택한 판매처와 상태에 표시할 주문이 없습니다.");

            emptyStateView.setVisibility(View.VISIBLE);

            return;

        }

        emptyStateView.setVisibility(View.GONE);

    }



    private void launchSpreadsheetImport() {
        List<DispatchOrder> targetOrders = getOrdersForCurrentMatchScope();
        if (targetOrders.isEmpty()) {
            showToast("선택한 판매처/상태에 맞는 주문이 없습니다.");
            return;
        }

        setLoading(true);
        executorService.execute(() -> {
            try {
                GoogleSheetsTrackingMatcher.MatchResult result =
                        GoogleSheetsTrackingMatcher.applyToOrders(targetOrders);
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

        List<DispatchOrder> targetOrders = getOrdersForCurrentMatchScope();
        if (targetOrders.isEmpty()) {
            showToast("선택한 판매처/상태에 맞는 주문이 없습니다.");
            return;
        }

        setLoading(true);
        executorService.execute(() -> {
            try {
                TrackingSpreadsheetImporter.MatchResult result =
                        TrackingSpreadsheetImporter.applyToOrders(this, uri, targetOrders);
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



    private void confirmStandbySelected() {

        List<DispatchOrder> targets = getReadyOrders();

        if (targets.isEmpty()) {

            showToast("체크된 주문 중 대기매칭 가능한 상태와 송장번호가 있는 항목이 없습니다.");

            return;

        }



        new MaterialAlertDialogBuilder(this)

                .setTitle("대기매칭")

                .setMessage(targets.size() + "건을 Cafe24 송장대기 상태로 등록합니다. 계속하시겠습니까?")

                .setNegativeButton("취소", null)

                .setPositiveButton("대기매칭", (dialog, which) -> standbySelectedOrders(targets))

                .show();

    }



    private void confirmUploadSelected() {

        List<DispatchOrder> targets = getReadyOrders();

        if (targets.isEmpty()) {

            showToast("체크된 주문 중 업로드 가능한 상태와 송장번호가 있는 항목이 없습니다.");

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

            if (order.selected && hasTrackingNumber(order) && order.isSelectableForUpload()) {

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

        TextView lastUpdatedView = dialogView.findViewById(R.id.tvSettingsLastUpdated);

        LinearLayout marketContainer = dialogView.findViewById(R.id.layoutCafe24Markets);



        summaryView.setText(buildSettingsSummary());

        lastUpdatedView.setText(getString(R.string.last_updated_format, currentUpdatedAt));



        configureAlertSettings(dialogView);



        AlertDialog dialog = new MaterialAlertDialogBuilder(this)

                .setTitle(R.string.settings_title)

                .setView(dialogView)

                .setPositiveButton(android.R.string.ok, null)

                .create();



        configureAppearanceSettings(dialogView, dialog);

        populateCafe24MarketRows(marketContainer, dialog);



        dialogView.findViewById(R.id.btnSettingsAddCafe24Market).setOnClickListener(v -> {

            dialog.dismiss();

            showAddCafe24MarketDialog();

        });

        dialogView.findViewById(R.id.btnSettingsLoadDebugSeeds).setOnClickListener(v -> {

            dialog.dismiss();

            loadDebugSeeds();

        });

        dialogView.findViewById(R.id.btnSettingsClearKeys).setOnClickListener(v -> {

            dialog.dismiss();

            clearSavedKeys();

        });

        dialogView.findViewById(R.id.btnSettingsTestNotification).setOnClickListener(v ->

                NotificationHelper.showTestNotification(this));



        dialog.show();

    }

    private void configureAppearanceSettings(View dialogView, @Nullable AlertDialog dialog) {

        SwitchCompat switchDarkMode = dialogView.findViewById(R.id.switchDarkMode);

        switchDarkMode.setChecked(AlertPrefs.isDarkModeEnabled(this));

        switchDarkMode.setOnCheckedChangeListener((button, checked) -> {

            AlertPrefs.prefs(this).edit().putBoolean(AlertPrefs.KEY_DARK_MODE, checked).apply();

            if (dialog != null) {

                dialog.dismiss();

            }

            AppCompatDelegate.setDefaultNightMode(checked
                    ? AppCompatDelegate.MODE_NIGHT_YES
                    : AppCompatDelegate.MODE_NIGHT_NO);

        });

    }



    private void standbySelectedOrders(List<DispatchOrder> targets) {

        setLoading(true);

        executorService.execute(() -> {

            List<String> success = new ArrayList<>();

            List<String> failed = new ArrayList<>();



            for (DispatchOrder order : targets) {

                String errorMessage = repository.pushTrackingStandby(order, order.shippingCode());

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



    private void configureAlertSettings(View dialogView) {

        SwitchCompat switchPolling = dialogView.findViewById(R.id.switchPollingAlert);

        View layoutInterval = dialogView.findViewById(R.id.layoutPollingInterval);

        EditText editPollingInterval = dialogView.findViewById(R.id.editPollingIntervalMin);



        boolean pollingEnabled = AlertPrefs.isPollingEnabled(this);

        int pollingMin = AlertPrefs.getPollingInterval(this);

        switchPolling.setChecked(pollingEnabled);

        layoutInterval.setVisibility(pollingEnabled ? View.VISIBLE : View.GONE);

        editPollingInterval.setText(String.valueOf(pollingMin));

        editPollingInterval.setSelection(editPollingInterval.getText().length());



        switchPolling.setOnCheckedChangeListener((button, checked) -> {

            AlertPrefs.prefs(this).edit().putBoolean(AlertPrefs.KEY_POLLING_ENABLED, checked).apply();

            layoutInterval.setVisibility(checked ? View.VISIBLE : View.GONE);

            savePollingIntervalFromInput(editPollingInterval, false);

            applyPollingSchedule(checked);

        });

        editPollingInterval.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                savePollingIntervalFromInput(editPollingInterval, true);
            }
        });

        editPollingInterval.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                savePollingIntervalFromInput(editPollingInterval, true);
                v.clearFocus();
                return true;
            }
            return false;
        });

        editPollingInterval.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                savePollingIntervalFromInput(editPollingInterval, true);
            }
        });



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

            TextView badgeView = itemView.findViewById(R.id.tvMarketConfigBadge);

            TextView statusView = itemView.findViewById(R.id.tvMarketConfigStatus);



            enabledCheckBox.setChecked(config.enabled);

            nameView.setText(config.buildMarketLabel());

            pathView.setText(buildMarketSourceText(config));

            bindCafe24MarketStatus(badgeView, statusView, resolveCafe24Status(config.key));



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

            View disableButton = itemView.findViewById(R.id.btnMarketConfigDisable);

            disableButton.setEnabled(config.enabled);

            disableButton.setAlpha(config.enabled ? 1f : 0.45f);

            disableButton.setOnClickListener(v -> {

                credentialStore.setCafe24MarketEnabled(config.key, false);

                dialog.dismiss();

                showToast(config.displayName + " 판매처를 목록에서 숨겼습니다. JSON은 보관됩니다.");

                refreshOrders();

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

        String sourceLabel = safeText(config.sourceLabel);

        builder.append("파일: " )
                .append(sourceLabel.isEmpty() ? "앱 내부 저장 JSON" : sourceLabel);

        if (!config.sourceUri.isEmpty()) {

            builder.append("\n위치: " ).append(config.sourceUri);

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



    private void bindCafe24MarketStatus(TextView badgeView, TextView statusView, String rawStatus) {

        MarketStatusUiState state = buildCafe24MarketStatusUiState(rawStatus);

        badgeView.setText(state.badgeText);

        badgeView.setBackgroundResource(state.badgeBackgroundRes);

        badgeView.setTextColor(ContextCompat.getColor(this, state.badgeTextColorRes));

        statusView.setText(state.detailText);

        statusView.setTextColor(ContextCompat.getColor(this, state.detailTextColorRes));

    }



    private MarketStatusUiState buildCafe24MarketStatusUiState(String rawStatus) {

        String status = safeText(rawStatus);

        String normalized = status.toLowerCase(Locale.ROOT);



        if (status.isEmpty()) {

            return new MarketStatusUiState("확인 필요", "상태 정보가 없습니다.",
                    R.drawable.bg_status_chip_warning, R.color.ship_warning, R.color.ship_text_secondary);

        }

        if (status.contains("JSON 미연결")) {

            return new MarketStatusUiState("JSON 미연결", "아직 JSON을 연결하지 않았습니다.",
                    R.drawable.bg_status_chip_warning, R.color.ship_warning, R.color.ship_warning);

        }

        if (status.contains("조회 성공")) {

            return new MarketStatusUiState("조회 가능", extractStatusLine(status, "조회 성공"),
                    R.drawable.bg_status_chip_online, R.color.ship_success, R.color.ship_success);

        }

        if (normalized.contains("invalid_grant")) {

            return new MarketStatusUiState("재연결 필요",
                    "외부 인증 프로그램에서 새 JSON을 발급한 뒤 다시 가져오세요.\n응답: " + simplifyCafe24ErrorReason(status),
                    R.drawable.bg_status_chip_offline, R.color.ship_error, R.color.ship_error);

        }

        if (normalized.contains("invalid access_token")
                || normalized.contains("access_token time expired")
                || normalized.contains("invalid_token")) {

            return new MarketStatusUiState("인증 필요",
                    "외부 인증 프로그램에서 새 JSON을 발급한 뒤 다시 가져오세요.\n응답: " + simplifyCafe24ErrorReason(status),
                    R.drawable.bg_status_chip_offline, R.color.ship_error, R.color.ship_error);

        }

        if (status.contains("필수 키가 부족")
                || status.contains("MallId 또는 AccessToken이 없습니다")
                || status.contains("MallId가 없습니다")
                || status.contains("형식 확인 필요")) {

            return new MarketStatusUiState("형식 오류",
                    "JSON 형식이 올바르지 않습니다.\nMallId와 AccessToken을 확인하세요.",
                    R.drawable.bg_status_chip_warning, R.color.ship_warning, R.color.ship_warning);

        }

        if (status.contains("MallId ")) {

            String mallId = extractStatusValue(status, "MallId " );

            String updatedAt = extractStatusValue(status, "업데이트 " );

            StringBuilder detail = new StringBuilder("저장된 JSON이 있습니다.");

            if (!mallId.isEmpty()) {

                detail.append("\nMallId " ).append(mallId);

            }

            if (!updatedAt.isEmpty()) {

                detail.append("\n업데이트 " ).append(updatedAt);

            }

            detail.append("\n아직 조회 전입니다.");

            return new MarketStatusUiState("연결됨", detail.toString(),
                    R.drawable.bg_status_chip_warning, R.color.ship_warning, R.color.ship_text_secondary);

        }

        if (status.contains("401")) {

            return new MarketStatusUiState("인증 실패",
                    "인증을 다시 해주세요.\nJSON 파일과 계정 권한을 확인하세요.\n응답: " + simplifyCafe24ErrorReason(status),
                    R.drawable.bg_status_chip_offline, R.color.ship_error, R.color.ship_error);

        }

        if (status.contains("인증을 다시")) {

            return new MarketStatusUiState("인증 필요",
                    "외부 인증 프로그램에서 새 JSON을 발급한 뒤 다시 가져오세요.\n응답: " + simplifyCafe24ErrorReason(status),
                    R.drawable.bg_status_chip_offline, R.color.ship_error, R.color.ship_error);

        }

        if (status.contains("확인 필요")) {

            return new MarketStatusUiState("확인 필요",
                    "최근 조회를 다시 확인하세요.\n응답: " + simplifyCafe24ErrorReason(status),
                    R.drawable.bg_status_chip_warning, R.color.ship_warning, R.color.ship_warning);

        }

        return new MarketStatusUiState("확인 필요", clipMessage(status),
                R.drawable.bg_status_chip_warning, R.color.ship_warning, R.color.ship_text_secondary);

    }



    private String extractStatusLine(String status, String token) {

        for (String line : status.split("\\n+")) {

            String trimmed = safeText(line);

            if (trimmed.contains(token)) {

                return trimmed;

            }

        }

        return token;

    }



    private String extractStatusValue(String status, String prefix) {

        for (String line : status.split("\\n+")) {

            String trimmed = safeText(line);

            if (trimmed.startsWith(prefix)) {

                return safeText(trimmed.substring(prefix.length()));

            }

        }

        return "";

    }



    private String simplifyCafe24ErrorReason(String status) {

        String normalized = status.toLowerCase(Locale.ROOT);
if (normalized.contains("invalid access_token")) {

            return "Invalid access_token";

        }

        if (normalized.contains("access_token time expired")) {

            return "access_token time expired";

        }

        if (normalized.contains("invalid_grant")) {

            return "invalid_grant";

        }

        for (String line : status.split("\\n+")) {

            String trimmed = safeText(line);

            if (trimmed.isEmpty() || trimmed.contains(" / Cafe24") || trimmed.equals("확인 필요")) {

                continue;

            }

            if (trimmed.startsWith("조회 실패")) {

                return clipMessage(trimmed);

            }

        }

        return clipMessage(status);

    }




    private void showAddCafe24MarketDialog() {

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);

        int padding = dpToPx(20);
        container.setPadding(padding, dpToPx(8), padding, 0);

        EditText nameInput = buildTextInput("판매처명(선택)", "");
        container.addView(nameInput);

        new MaterialAlertDialogBuilder(this)
                .setTitle("마켓 JSON 추가")
                .setMessage("Cafe24 JSON 키 파일을 선택해 판매처를 추가합니다.\n판매처명을 비우면 JSON 안 MallId를 사용합니다.")
                .setView(container)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("JSON 선택", (dialog, which) ->
                        launchCafe24CreateImport(safeText(nameInput.getText().toString())))
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



    private boolean savePollingIntervalFromInput(EditText input, boolean rescheduleIfEnabled) {

        String raw = input.getText() == null ? "" : input.getText().toString().trim();

        if (raw.isEmpty()) {

            input.setError("분을 입력하세요.");

            return false;

        }

        int intervalMinutes;

        try {

            intervalMinutes = Integer.parseInt(raw);

        } catch (NumberFormatException ex) {

            input.setError("숫자로 입력하세요.");

            return false;

        }

        if (intervalMinutes < AlertPrefs.MIN_POLLING_INTERVAL
                || intervalMinutes > AlertPrefs.MAX_POLLING_INTERVAL) {

            input.setError(AlertPrefs.MIN_POLLING_INTERVAL + "~"
                    + AlertPrefs.MAX_POLLING_INTERVAL + "분으로 입력하세요.");

            return false;

        }

        input.setError(null);

        int normalized = AlertPrefs.normalizePollingInterval(intervalMinutes);

        if (normalized != AlertPrefs.getPollingInterval(this)) {

            AlertPrefs.prefs(this).edit()

                    .putInt(AlertPrefs.KEY_POLLING_INTERVAL, normalized)

                    .apply();

            if (rescheduleIfEnabled && AlertPrefs.isPollingEnabled(this)) {

                applyPollingSchedule(true);

            }

        }

        return true;

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

        boolean coupangConnected = FeatureFlags.ENABLE_COUPANG && credentialStore.getCoupangCredentials().isComplete();



        if (registeredCafe24Count == 0 && !coupangConnected) {

            return getString(R.string.settings_summary_empty);

        }



        StringBuilder summary = new StringBuilder();

        summary.append("Cafe24 등록 ").append(registeredCafe24Count).append("개")

                .append(" / 사용 ").append(enabledCafe24Count).append("개")

                .append(" / JSON 연결 ").append(connectedCafe24Count).append("개");

        if (FeatureFlags.ENABLE_COUPANG) {

            summary.append(" / 쿠팡 ").append(coupangConnected ? "연결" : "미연결");

        }

        if (currentFetchedCount > 0) {

            summary.append(" / 조회 성공 ").append(currentFetchedCount).append("개");

        }

        if (currentTotalCount > 0) {

            summary.append(" / 출고 ").append(currentTotalCount).append("건");

        }

        return summary.toString();

    }



    private void launchCafe24Import(String slot, String displayName) {

        pendingCafe24CreateMode = false;
        pendingCafe24Slot = safeText(slot);
        pendingCafe24Name = safeText(displayName);
        cafe24ImportLauncher.launch(new String[]{"application/json", "text/plain", "*/*"});

    }

    private void launchCafe24CreateImport(String preferredDisplayName) {

        pendingCafe24CreateMode = true;
        pendingCafe24Slot = "";
        pendingCafe24Name = safeText(preferredDisplayName);
        cafe24ImportLauncher.launch(new String[]{"application/json", "text/plain", "*/*"});

    }

    private void clearPendingCafe24Import() {

        pendingCafe24CreateMode = false;
        pendingCafe24Slot = "";
        pendingCafe24Name = "";

    }

    private void handleCafe24ImportResult(Uri uri) {

        if (uri == null) {
            clearPendingCafe24Import();
            return;
        }

        if (pendingCafe24Slot.isEmpty() && !pendingCafe24CreateMode) {
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
            boolean createMode = pendingCafe24CreateMode;
            String slot = pendingCafe24Slot;
            String requestedName = pendingCafe24Name;
            String mallId = extractCafe24MallId(json);
            String marketName = requestedName;

            if (createMode) {
                if (marketName.isEmpty()) {
                    marketName = mallId;
                }
            } else if (marketName.isEmpty()) {
                Cafe24MarketConfig config = credentialStore.getCafe24Market(slot);
                marketName = config == null ? mallId : config.displayName;
            }

            final boolean finalCreateMode = createMode;
            final String finalSlot = slot;
            final String finalRequestedName = requestedName;
            final String finalMallId = mallId;
            final String finalMarketName = marketName;

            setLoading(true);
            executorService.execute(() -> {
                try {
                    String validatedJson = LiveShipmentRepository.normalizeCafe24JsonForStorage(json);
                    runOnUiThread(() -> {
                        Cafe24ImportTarget importTarget = finalCreateMode
                                ? resolveCafe24ImportTarget(finalMallId, finalRequestedName)
                                : new Cafe24ImportTarget(finalSlot, finalMarketName);

                        credentialStore.saveCafe24Json(importTarget.slot, validatedJson, sourceLabel, sourceUri);
                        credentialStore.setCafe24MarketEnabled(importTarget.slot, true);
                        clearPendingCafe24Import();
                        showToast(importTarget.marketName + " JSON 위치를 저장했습니다.");
                        refreshOrders();
                    });
                } catch (Exception ex) {
                    runOnUiThread(() -> {
                        clearPendingCafe24Import();
                        setLoading(false);
                        showCafe24ImportFailure(finalMarketName, ex.getMessage());
                    });
                }
            });
        } catch (Exception ex) {
            clearPendingCafe24Import();
            showCafe24ImportFailure("Cafe24", ex.getMessage());
        }

    }

    private void showCafe24ImportFailure(String marketName, String message) {

        String title = safeText(marketName).isEmpty() ? "Cafe24 JSON 검증 실패" : marketName + " JSON 검증 실패";

        String detail = safeText(message);

        if (detail.isEmpty()) {

            detail = "Cafe24 인증 검증에 실패했습니다.";

        }

        new MaterialAlertDialogBuilder(this)

                .setTitle(title)

                .setMessage(detail)

                .setPositiveButton("확인", null)

                .show();

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
        AlertPrefs.clearKnownOrderKeys(this);
        AlertPrefs.saveLastOrderCount(this, -1);
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

        extractCafe24MallId(json);

    }

    private String extractCafe24MallId(String json) throws Exception {

        JSONObject object = new JSONObject(json);
        String mallId = safeText(object.optString("MallId"));
        if (mallId.isEmpty()) {
            throw new IllegalArgumentException(getString(R.string.invalid_cafe24_json));
        }
        return mallId;

    }

    private Cafe24ImportTarget resolveCafe24ImportTarget(String mallId, String preferredDisplayName) {

        Cafe24MarketConfig existingByMallId = findCafe24MarketByMallId(mallId);
        if (existingByMallId != null) {
            String marketName = existingByMallId.displayName.isEmpty() ? mallId : existingByMallId.displayName;
            return new Cafe24ImportTarget(existingByMallId.key, marketName);
        }

        String preferredName = safeText(preferredDisplayName);
        if (!preferredName.isEmpty()) {
            Cafe24MarketConfig existingByName = findCafe24MarketByDisplayName(preferredName);
            if (existingByName != null && !existingByName.hasJson()) {
                return new Cafe24ImportTarget(existingByName.key, existingByName.displayName);
            }
        }

        String displayName = preferredName.isEmpty() ? mallId : preferredName;
        Cafe24MarketConfig created = credentialStore.createCafe24Market(displayName);
        return new Cafe24ImportTarget(created.key, created.displayName);

    }

    private Cafe24MarketConfig findCafe24MarketByDisplayName(String displayName) {

        String targetName = safeText(displayName);
        if (targetName.isEmpty()) {
            return null;
        }

        for (Cafe24MarketConfig config : credentialStore.getCafe24Markets()) {
            if (targetName.equalsIgnoreCase(safeText(config.displayName))) {
                return config;
            }
        }

        return null;

    }

    private Cafe24MarketConfig findCafe24MarketByMallId(String mallId) {

        String targetMallId = safeText(mallId);
        if (targetMallId.isEmpty()) {
            return null;
        }

        for (Cafe24MarketConfig config : credentialStore.getCafe24Markets()) {
            if (!config.hasJson()) {
                continue;
            }
            try {
                JSONObject object = new JSONObject(config.json);
                if (targetMallId.equalsIgnoreCase(safeText(object.optString("MallId")))) {
                    return config;
                }
            } catch (Exception ignored) {
            }
        }

        return null;

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

        setStatusFilterEnabled(!loading);

        importSpreadsheetButton.setEnabled(!loading && !allOrders.isEmpty());

        standbyPageButton.setEnabled(!loading && countReadyOrders() > 0);

        selectAllCheckBox.setEnabled(!loading && !displayedOrders.isEmpty());

        uploadSelectedButton.setEnabled(!loading && countReadyOrders() > 0);
        updatePaginationUi(getTotalPageCount());

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

        OrderDetailDialog.show(this, order, trackingNumber -> {
            adapter.notifyDataSetChanged();
            updateSelectionUi();
        });

    }



    private void showToast(String message) {

        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();

    }



    private int dpToPx(int dp) {

        return Math.round(dp * getResources().getDisplayMetrics().density);

    }

    private static class Cafe24ImportTarget {

        final String slot;
        final String marketName;

        Cafe24ImportTarget(String slot, String marketName) {
            this.slot = slot;
            this.marketName = marketName;
        }

    }



    private static class MarketStatusUiState {

        final String badgeText;

        final String detailText;

        final int badgeBackgroundRes;

        final int badgeTextColorRes;

        final int detailTextColorRes;



        MarketStatusUiState(String badgeText, String detailText, int badgeBackgroundRes, int badgeTextColorRes, int detailTextColorRes) {

            this.badgeText = badgeText;

            this.detailText = detailText;

            this.badgeBackgroundRes = badgeBackgroundRes;

            this.badgeTextColorRes = badgeTextColorRes;

            this.detailTextColorRes = detailTextColorRes;

        }

    }



    private static class DispatchLoadResult {

        final List<DispatchOrder> orders = new ArrayList<>();

        final List<String> errors = new ArrayList<>();

        final Map<String, String> cafe24Statuses = new LinkedHashMap<>();

        final List<String> activeCafe24Keys = new ArrayList<>();

        String coupangStatus = "";

        String updatedAt = "-";

        String marketCountSummary = "";

        int connectedCount;

        int fetchedCount;

        int totalCount;

    }

}






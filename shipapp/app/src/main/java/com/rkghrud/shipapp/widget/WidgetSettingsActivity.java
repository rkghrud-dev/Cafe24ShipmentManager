package com.rkghrud.shipapp.widget;

import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.rkghrud.shipapp.FeatureFlags;
import com.rkghrud.shipapp.MainActivity;
import com.rkghrud.shipapp.R;
import com.rkghrud.shipapp.data.Cafe24MarketConfig;
import com.rkghrud.shipapp.data.CredentialStore;
import com.rkghrud.shipapp.notifications.NotificationHelper;

import java.util.ArrayList;
import java.util.List;

public class WidgetSettingsActivity extends AppCompatActivity {
    private int appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private final List<String> labels = new ArrayList<>();
    private final List<String> keys = new ArrayList<>();

    private Spinner marketCountSpinner;
    private Spinner market1Spinner;
    private Spinner market2Spinner;
    private Spinner market3Spinner;
    private Spinner market4Spinner;
    private SeekBar opacitySeek;
    private TextView opacityView;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setResult(RESULT_CANCELED);

        Intent intent = getIntent();
        Bundle extras = intent == null ? null : intent.getExtras();
        if (extras != null) {
            appWidgetId = extras.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        }
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish();
            return;
        }

        setContentView(R.layout.activity_widget_settings);

        marketCountSpinner = findViewById(R.id.spinnerWidgetMarketCount);
        market1Spinner = findViewById(R.id.spinnerWidgetMarket1);
        market2Spinner = findViewById(R.id.spinnerWidgetMarket2);
        market3Spinner = findViewById(R.id.spinnerWidgetMarket3);
        market4Spinner = findViewById(R.id.spinnerWidgetMarket4);
        opacitySeek = findViewById(R.id.seekWidgetOpacity);
        opacityView = findViewById(R.id.tvWidgetOpacity);
        MaterialButton saveButton = findViewById(R.id.btnSaveWidgetSettings);

        loadMarketOptions();
        bindSpinners();
        bindExistingConfig();

        opacitySeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateOpacityLabel(progressToOpacity(progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        saveButton.setOnClickListener(v -> saveAndFinish());
    }

    private void loadMarketOptions() {
        CredentialStore store = new CredentialStore(this);
        for (Cafe24MarketConfig config : store.getActiveCafe24Markets()) {
            labels.add(shortName(config.displayName));
            keys.add(config.key);
        }
        if (FeatureFlags.ENABLE_COUPANG && store.getCoupangCredentials().isComplete()) {
            labels.add("쿠팡");
            keys.add("coupang");
        }
        addFallback("홈런", CredentialStore.SLOT_CAFE24_HOME);
        addFallback("준비", CredentialStore.SLOT_CAFE24_PREPARE);
        addFallback("쿠팡", "coupang");
        addFallback("전체", MainActivity.MARKET_FILTER_ALL);
    }

    private void addFallback(String label, String key) {
        if (keys.contains(key)) {
            return;
        }
        labels.add(label);
        keys.add(key);
    }

    private void bindSpinners() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.item_compact_spinner, labels);
        adapter.setDropDownViewResource(R.layout.item_compact_spinner_dropdown);
        market1Spinner.setAdapter(adapter);
        market2Spinner.setAdapter(adapter);
        market3Spinner.setAdapter(adapter);
        market4Spinner.setAdapter(adapter);

        ArrayAdapter<String> countAdapter = new ArrayAdapter<>(this, R.layout.item_compact_spinner,
                new String[] {"1개", "2개", "3개", "4개"});
        countAdapter.setDropDownViewResource(R.layout.item_compact_spinner_dropdown);
        marketCountSpinner.setAdapter(countAdapter);
        marketCountSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateMarketSlotVisibility(position + 1);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void bindExistingConfig() {
        WidgetSettingsStore.WidgetConfig config = WidgetSettingsStore.load(this, appWidgetId);
        int marketCount = WidgetSettingsStore.clampMarketCount(config.marketCount);
        marketCountSpinner.setSelection(marketCount - 1);
        market1Spinner.setSelection(indexOf(config.marketAt(0, keyAt(0))));
        market2Spinner.setSelection(indexOf(config.marketAt(1, keyAt(1))));
        market3Spinner.setSelection(indexOf(config.marketAt(2, keyAt(2))));
        market4Spinner.setSelection(indexOf(config.marketAt(3, keyAt(3))));
        updateMarketSlotVisibility(marketCount);
        int opacity = WidgetSettingsStore.clampOpacity(config.opacity);
        opacitySeek.setProgress(opacityToProgress(opacity));
        updateOpacityLabel(opacity);
    }

    private void saveAndFinish() {
        int marketCount = WidgetSettingsStore.clampMarketCount(marketCountSpinner.getSelectedItemPosition() + 1);
        WidgetSettingsStore.save(this, appWidgetId,
                marketCount,
                keyAt(market1Spinner.getSelectedItemPosition()),
                keyAt(market2Spinner.getSelectedItemPosition()),
                keyAt(market3Spinner.getSelectedItemPosition()),
                keyAt(market4Spinner.getSelectedItemPosition()),
                progressToOpacity(opacitySeek.getProgress()));
        NotificationHelper.updateWidget(this);

        Intent result = new Intent();
        result.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        setResult(RESULT_OK, result);
        Toast.makeText(this, "위젯 설정을 저장했습니다.", Toast.LENGTH_SHORT).show();
        finish();
    }

    private int indexOf(String key) {
        int index = keys.indexOf(key);
        return index >= 0 ? index : 0;
    }

    private String keyAt(int index) {
        if (index < 0 || index >= keys.size()) {
            return "";
        }
        return keys.get(index);
    }

    private int progressToOpacity(int progress) {
        return WidgetSettingsStore.clampOpacity(progress + 35);
    }

    private int opacityToProgress(int opacity) {
        return WidgetSettingsStore.clampOpacity(opacity) - 35;
    }

    private void updateOpacityLabel(int opacity) {
        opacityView.setText(opacity + "%");
    }

    private void updateMarketSlotVisibility(int marketCount) {
        int safeCount = WidgetSettingsStore.clampMarketCount(marketCount);
        market1Spinner.setVisibility(View.VISIBLE);
        market2Spinner.setVisibility(safeCount >= 2 ? View.VISIBLE : View.GONE);
        market3Spinner.setVisibility(safeCount >= 3 ? View.VISIBLE : View.GONE);
        market4Spinner.setVisibility(safeCount >= 4 ? View.VISIBLE : View.GONE);
    }

    private String shortName(String name) {
        String safe = name == null ? "" : name.trim();
        if (safe.endsWith("마켓")) {
            return safe.substring(0, safe.length() - 2);
        }
        return safe.isEmpty() ? "마켓" : safe;
    }
}

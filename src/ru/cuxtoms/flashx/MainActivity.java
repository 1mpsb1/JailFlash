package ru.cuxtoms.flashx;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity implements LedController.Listener {

    private static final int BG = 0xFF000000;
    private static final int CARD = 0xFF1C1C1E;
    private static final int CARD2 = 0xFF2C2C2E;
    private static final int ACCENT = 0xFF3482FF;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int DIM = 0xFF8E8E93;
    private static final int GREEN = 0xFF32D74B;
    private static final int RED = 0xFFFF453A;
    private static final int BOOST_OFF = 0xFF3A1C1C;
    private static final int BOOST_ON = 0xFFE53935;

    private static final String[] AUTO_OFF_LABELS = { "Не выключать", "1 минута", "5 минут", "10 минут", "30 минут" };
    private static final int[] AUTO_OFF_VALUES = {0, 1, 5, 10, 30};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<String, LedViews> ledViews = new HashMap<>();
    private final List<LedController.Led> shownLeds = new ArrayList<>();

    private LedController controller;
    private ScrollView scroll;
    private LinearLayout tabMain, tabSettings, ledContainer, experimentalCard, tempContainer, presetCard;
    private TextView rootStatus, summary, statusText, masterLabel, tempLabel, smoothMsLabel, tileLabel, tileTempLabel;
    private TextView tabBtnMain, tabBtnSettings;
    private ProgressBar busyBar;
    private Switch onSwitch, captureSwitch, autoHookSwitch, tempModeSwitch;
    private Button boostButton;
    private SeekBar masterSeek, tempSeek, smoothMsSeek, tileSeek, tileTempSeek;
    private Spinner autoOffSpinner;
    private CheckBox keepScreenCheck, smoothUpCheck, smoothDownCheck, tileBoostCheck, unlock750Check, tileTempModeCheck, snapToEdgesCheck;
    private TextView stockTorchStatus;
    private boolean stockTorchOn;
    private String stockCameraId;
    private boolean uiUpdating;
    private int diagClicks = 0;
    private boolean sessionWarned = false; // Предупреждение >50% один раз за сессию

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (TorchService.ACTION_STATE.equals(intent.getAction())) syncUi();
        }
    };

    private static final class LedViews {
        TextView value; Switch toggle; SeekBar seek;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        controller = LedController.get(this);
        controller.setListener(this);

        handleCommand(getIntent(), true);

        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        buildUi();
        syncUi();
        requestNotificationPermission();
        
        if (!controller.isStockWarnAck()) {
            new AlertDialog.Builder(this)
                .setTitle("Внимание")
                .setMessage("Использование этого приложения перехватывает управление светодиодом (хук).\n\nВ связи с этим стоковый фонарик из шторки и камеры может перестать работать до перезагрузки устройства или сброса сервиса камеры.\n\nПродолжая, вы берете ответственность на себя.")
                .setCancelable(false)
                .setPositiveButton("Я все понял", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        controller.setStockWarnAck(true);
                        controller.discover(); // Запускаем запрос рута только после согласия
                    }
                }).show();
        } else {
            controller.discover();
        }
        
        handleCommand(getIntent(), false);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleCommand(intent, false);
    }

    private void handleCommand(Intent intent, boolean coldStart) {
        if (intent != null && "android.service.quicksettings.action.QS_TILE_PREFERENCES".equals(intent.getAction())) {
            if (scroll != null && presetCard != null) {
                switchTab(false);
                presetCard.post(new Runnable() {
                    @Override
                    public void run() {
                        try { scroll.smoothScrollTo(0, presetCard.getTop()); } catch (Exception ignored) {}
                    }
                });
            }
            return;
        }

        String cmd = intent == null ? null : intent.getStringExtra("flashx");
        if ("dump".equals(cmd)) { controller.dumpNow(); return; }
        if ("off".equals(cmd)) {
            controller.setOn(false); stopTorchService(); if (onSwitch != null) syncUi(); return;
        }
        if ("on".equals(cmd)) {
            releaseStockTorch(); controller.setOn(true); startTorchService(); if (onSwitch != null) syncUi(); return;
        }
        boolean launcher = intent == null || intent.getAction() == null || Intent.ACTION_MAIN.equals(intent.getAction());
        if (coldStart && launcher) {
            controller.setOn(false);
            controller.requireCleanupOnDiscover();
            stopTorchService();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter(TorchService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(stateReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(stateReceiver, f);
        syncUi();
    }

    @Override
    protected void onStop() {
        try { unregisterReceiver(stateReceiver); } catch (Exception ignored) {}
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyKeepScreenOn();
        syncUi();
    }

    private void switchTab(boolean main) {
        tabMain.setVisibility(main ? View.VISIBLE : View.GONE);
        tabSettings.setVisibility(main ? View.GONE : View.VISIBLE);
        
        tabBtnMain.setTextColor(main ? TEXT : DIM);
        tabBtnMain.setBackground(main ? rounded(ACCENT, 16) : rounded(0x00000000, 16));
        
        tabBtnSettings.setTextColor(!main ? TEXT : DIM);
        tabBtnSettings.setBackground(!main ? rounded(ACCENT, 16) : rounded(0x00000000, 16));
    }

    private void buildUi() {
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        setContentView(scroll);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, dp(18), pad, dp(28));
        scroll.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView title = text("JailFlash", 29, TEXT, true);
        title.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                diagClicks++;
                if (diagClicks >= 7) {
                    diagClicks = 0;
                    if (experimentalCard != null) experimentalCard.setVisibility(View.VISIBLE);
                    Toast.makeText(MainActivity.this, "Экспериментальные настройки разблокированы", Toast.LENGTH_SHORT).show();
                }
            }
        });
        root.addView(title);

        TextView sub = text("by 1mpsb1 · v1.0 release", 13, DIM, false);
        root.addView(sub, withTopMargin(fullWrap(), dp(2)));

        // Вкладки
        LinearLayout tabContainer = new LinearLayout(this);
        tabContainer.setOrientation(LinearLayout.HORIZONTAL);
        tabContainer.setBackground(rounded(CARD2, 20));
        tabContainer.setPadding(dp(4), dp(4), dp(4), dp(4));
        LinearLayout.LayoutParams tcLp = fullWrap(); tcLp.topMargin = dp(16);
        root.addView(tabContainer, tcLp);

        tabBtnMain = text("Главная", 14, TEXT, true);
        tabBtnMain.setGravity(Gravity.CENTER);
        tabBtnMain.setPadding(0, dp(10), 0, dp(10));
        tabBtnMain.setBackground(rounded(ACCENT, 16));
        tabBtnMain.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { switchTab(true); }
        });
        tabContainer.addView(tabBtnMain, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        tabBtnSettings = text("Настройки", 14, DIM, true);
        tabBtnSettings.setGravity(Gravity.CENTER);
        tabBtnSettings.setPadding(0, dp(10), 0, dp(10));
        tabBtnSettings.setBackground(rounded(0x00000000, 16));
        tabBtnSettings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { switchTab(false); }
        });
        tabContainer.addView(tabBtnSettings, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // --- ВКЛАДКА: ГЛАВНАЯ ---
        tabMain = new LinearLayout(this);
        tabMain.setOrientation(LinearLayout.VERTICAL);
        root.addView(tabMain, fullWrap());

        LinearLayout statusCard = card();
        LinearLayout.LayoutParams scLp = fullWrap(); scLp.topMargin = dp(16);
        tabMain.addView(statusCard, scLp);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusCard.addView(statusRow, fullWrap());

        busyBar = new ProgressBar(this);
        busyBar.setIndeterminate(true);
        busyBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dp(20), dp(20));
        barLp.rightMargin = dp(10);
        statusRow.addView(busyBar, barLp);

        LinearLayout statusCol = new LinearLayout(this);
        statusCol.setOrientation(LinearLayout.VERTICAL);
        statusRow.addView(statusCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        rootStatus = text("Root: проверка…", 14, TEXT, true);
        statusCol.addView(rootStatus);
        summary = text("Поиск светодиодов…", 12, DIM, false);
        statusCol.addView(summary);

        Button refresh = button("Обновить", false);
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { controller.discover(); }
        });
        statusRow.addView(refresh, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));

        LinearLayout mainCard = card();
        LinearLayout.LayoutParams mcLp = fullWrap(); mcLp.topMargin = dp(12);
        tabMain.addView(mainCard, mcLp);

        LinearLayout onRow = new LinearLayout(this);
        onRow.setOrientation(LinearLayout.HORIZONTAL);
        onRow.setGravity(Gravity.CENTER_VERTICAL);
        mainCard.addView(onRow, fullWrap());

        onSwitch = new Switch(this);
        onSwitch.setText("Фонарик");
        onSwitch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        onSwitch.setTextColor(TEXT);
        onSwitch.setTypeface(Typeface.DEFAULT_BOLD);
        onSwitch.setPadding(0, 0, 0, 0);
        onRow.addView(onSwitch, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        statusText = text("ВЫКЛ", 13, DIM, true);
        onRow.addView(statusText);

        onSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (uiUpdating) return;
                if (isChecked && !controller.isCapture() && !controller.isAutoHook()) {
                    Toast.makeText(MainActivity.this, "Включите «Захватывать фонарик» или «Авто-хук»", Toast.LENGTH_SHORT).show();
                    uiUpdating = true; onSwitch.setChecked(false); uiUpdating = false;
                    return;
                }
                if (isChecked) releaseStockTorch();
                controller.setOn(isChecked);
                if (isChecked) startTorchService(); else stopTorchService();
                applyKeepScreenOn();
                syncUi();
            }
        });

        // Пресеты
        presetCard = card();
        LinearLayout.LayoutParams pcLp = fullWrap(); pcLp.topMargin = dp(12);
        tabMain.addView(presetCard, pcLp);
        presetCard.addView(text("Пресеты", 15, TEXT, true));

        HorizontalScrollView presetsScroll = new HorizontalScrollView(this);
        presetsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        presetsScroll.addView(presets, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        presetCard.addView(presetsScroll, withTopMargin(fullWrap(), dp(10)));

        boostButton = button("BOOST", false);
        updateBoostButton();
        boostButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                confirmDanger(true, 100, new Runnable() {
                    @Override public void run() { applyPreset(true, 100, null); }
                }, null);
            }
        });
        presets.addView(boostButton, presetParams());

        Button btn30 = button("30%", false);
        btn30.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { applyPreset(false, 30, null); }
        });
        presets.addView(btn30, presetParams());

        Button btn50 = button("50%", false);
        btn50.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { applyPreset(false, 50, null); }
        });
        presets.addView(btn50, presetParams());

        tempModeSwitch = new Switch(this);
        tempModeSwitch.setText("Режим температуры");
        tempModeSwitch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tempModeSwitch.setTextColor(ACCENT);
        tempModeSwitch.setTypeface(Typeface.DEFAULT_BOLD);
        tabMain.addView(tempModeSwitch, withTopMargin(fullWrap(), dp(12)));
        tempModeSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (uiUpdating) return;
                controller.setTempMode(isChecked);
                syncUi();
            }
        });

        masterLabel = text("Задать всем LED: 30%", 13, DIM, false);
        LinearLayout.LayoutParams mlLp = fullWrap(); mlLp.topMargin = dp(14);
        tabMain.addView(masterLabel, mlLp);

        masterSeek = seekBar(100, 30);
        tabMain.addView(masterSeek, fullWrap());
        masterSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || uiUpdating) return;
                masterLabel.setText("Задать всем LED: " + progress + "%");
                if (progress <= 50 || sessionWarned || controller.isWarnDismissed()) {
                    controller.setMasterPercent(progress);
                    updateAllLedSeekbars(progress);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                int progress = seekBar.getProgress();
                if (progress <= 50 || sessionWarned || controller.isWarnDismissed()) return;
                confirmDanger(false, progress, new Runnable() {
                    @Override public void run() { controller.setMasterPercent(progress); updateAllLedSeekbars(progress); }
                }, new Runnable() {
                    @Override public void run() {
                        uiUpdating = true; masterSeek.setProgress(50); uiUpdating = false;
                        masterLabel.setText("Задать всем LED: 50%");
                        controller.setMasterPercent(50); updateAllLedSeekbars(50);
                    }
                });
            }
        });

        tempContainer = new LinearLayout(this);
        tempContainer.setOrientation(LinearLayout.VERTICAL);
        tabMain.addView(tempContainer, fullWrap());
        
        tempLabel = text("Температура: 50% (Нейтральный)", 13, DIM, false);
        LinearLayout.LayoutParams tlLp = fullWrap(); tlLp.topMargin = dp(10);
        tempContainer.addView(tempLabel, tlLp);

        tempSeek = seekBar(100, 50);
        tempContainer.addView(tempSeek, fullWrap());
        tempSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (uiUpdating) return;
                String desc = progress < 40 ? "Тёплый" : progress > 60 ? "Холодный" : "Нейтральный";
                tempLabel.setText("Температура: " + progress + "% (" + desc + ")");
                if (fromUser) controller.setTempPercent(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        tabMain.addView(text("Светодиоды", 18, TEXT, true), withTopMargin(fullWrap(), dp(22)));
        tabMain.addView(text("Два канала: холодный и тёплый.", 12, DIM, false), withTopMargin(fullWrap(), dp(4)));

        ledContainer = new LinearLayout(this);
        ledContainer.setOrientation(LinearLayout.VERTICAL);
        tabMain.addView(ledContainer, withTopMargin(fullWrap(), dp(8)));

        // --- ВКЛАДКА: НАСТРОЙКИ ---
        tabSettings = new LinearLayout(this);
        tabSettings.setOrientation(LinearLayout.VERTICAL);
        tabSettings.setVisibility(View.GONE);
        LinearLayout.LayoutParams tsLp = fullWrap(); tsLp.topMargin = dp(16);
        root.addView(tabSettings, tsLp);

        LinearLayout settingsCard = card();
        tabSettings.addView(settingsCard, fullWrap());

        captureSwitch = new Switch(this);
        captureSwitch.setText("Захватывать фонарик (Root)");
        captureSwitch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        captureSwitch.setTextColor(TEXT);
        settingsCard.addView(captureSwitch, fullWrap());
        captureSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (uiUpdating) return;
                controller.setCapture(isChecked);
                if (!isChecked && !controller.isAutoHook()) { controller.setOn(false); stopTorchService(); }
                syncUi();
            }
        });

        autoHookSwitch = new Switch(this);
        autoHookSwitch.setText("Авто-хук (брать при вкл / отдавать при выкл)");
        autoHookSwitch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        autoHookSwitch.setTextColor(TEXT);
        settingsCard.addView(autoHookSwitch, withTopMargin(fullWrap(), dp(10)));
        autoHookSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (uiUpdating) return;
                controller.setAutoHook(isChecked);
                syncUi();
            }
        });
        
        TextView autoHookWarn = text("⚠ При включении из шторки может вызывать задержки", 12, 0xFFFFB86B, false);
        settingsCard.addView(autoHookWarn, withTopMargin(fullWrap(), dp(4)));

        settingsCard.addView(text("Автоотключение", 12, DIM, false), withTopMargin(fullWrap(), dp(14)));
        autoOffSpinner = spinner(AUTO_OFF_LABELS);
        settingsCard.addView(autoOffSpinner, fullWrap());
        autoOffSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (uiUpdating) return;
                controller.setAutoOffMin(AUTO_OFF_VALUES[position]);
                if (controller.isOn()) startTorchService();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        keepScreenCheck = new CheckBox(this);
        keepScreenCheck.setText("Не гасить экран");
        keepScreenCheck.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        keepScreenCheck.setTextColor(TEXT);
        settingsCard.addView(keepScreenCheck, withTopMargin(fullWrap(), dp(10)));
        keepScreenCheck.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (uiUpdating) return;
                controller.setKeepScreenOn(isChecked); applyKeepScreenOn();
            }
        });
        
        snapToEdgesCheck = new CheckBox(this);
        snapToEdgesCheck.setText("Примагничивание слайдеров к краям");
        snapToEdgesCheck.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        snapToEdgesCheck.setTextColor(TEXT);
        settingsCard.addView(snapToEdgesCheck, withTopMargin(fullWrap(), dp(10)));
        snapToEdgesCheck.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (!uiUpdating) controller.setSnapToEdges(isChecked);
            }
        });

        LinearLayout smoothCard = card();
        tabSettings.addView(smoothCard, withTopMargin(fullWrap(), dp(12)));
        smoothCard.addView(text("Плавное включение / выключение", 15, TEXT, true));

        smoothUpCheck = new CheckBox(this);
        smoothUpCheck.setText("Плавно включать");
        smoothUpCheck.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        smoothUpCheck.setTextColor(TEXT);
        smoothCard.addView(smoothUpCheck, withTopMargin(fullWrap(), dp(8)));
        smoothUpCheck.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (!uiUpdating) controller.setSmoothUp(isChecked);
            }
        });

        smoothDownCheck = new CheckBox(this);
        smoothDownCheck.setText("Плавно выключать");
        smoothDownCheck.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        smoothDownCheck.setTextColor(TEXT);
        smoothCard.addView(smoothDownCheck, withTopMargin(fullWrap(), dp(4)));
        smoothDownCheck.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (!uiUpdating) controller.setSmoothDown(isChecked);
            }
        });

        smoothMsLabel = text("Длительность: 700 мс", 13, DIM, false);
        smoothCard.addView(smoothMsLabel, withTopMargin(fullWrap(), dp(10)));
        smoothMsSeek = seekBar(4900, controller.getSmoothMs() - 100);
        smoothCard.addView(smoothMsSeek, fullWrap());
        smoothMsSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int ms = progress + 100;
                smoothMsLabel.setText("Длительность: " + ms + " мс");
                if (fromUser && !uiUpdating) controller.setSmoothMs(ms);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        LinearLayout tileCard = card();
        tabSettings.addView(tileCard, withTopMargin(fullWrap(), dp(12)));
        tileCard.addView(text("Пресет для плитки в шторке", 15, TEXT, true));
        
        tileLabel = text("Яркость: " + controller.getTilePercent() + "%", 13, DIM, false);
        tileCard.addView(tileLabel, withTopMargin(fullWrap(), dp(10)));
        tileSeek = seekBar(100, controller.getTilePercent());
        tileCard.addView(tileSeek, fullWrap());
        tileSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || uiUpdating) return;
                tileLabel.setText("Яркость: " + progress + "%"); 
                if (progress <= 50 || sessionWarned || controller.isWarnDismissed()) {
                    controller.setTilePercent(progress);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                int progress = seekBar.getProgress();
                if (progress <= 50 || sessionWarned || controller.isWarnDismissed()) return;
                confirmDanger(false, progress, new Runnable() {
                    @Override public void run() { controller.setTilePercent(progress); }
                }, new Runnable() {
                    @Override public void run() {
                        uiUpdating = true; tileSeek.setProgress(50); uiUpdating = false;
                        tileLabel.setText("Яркость: 50%");
                        controller.setTilePercent(50);
                    }
                });
            }
        });

        tileTempLabel = text("Температура: " + controller.getTileTemp() + "%", 13, DIM, false);
        tileCard.addView(tileTempLabel, withTopMargin(fullWrap(), dp(6)));
        tileTempSeek = seekBar(100, controller.getTileTemp());
        tileCard.addView(tileTempSeek, fullWrap());
        tileTempSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || uiUpdating) return;
                tileTempLabel.setText("Температура: " + progress + "%"); controller.setTileTemp(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        tileTempModeCheck = new CheckBox(this);
        tileTempModeCheck.setText("Использовать режим температуры");
        tileTempModeCheck.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tileTempModeCheck.setTextColor(TEXT);
        tileCard.addView(tileTempModeCheck, withTopMargin(fullWrap(), dp(6)));
        tileTempModeCheck.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (!uiUpdating) controller.setTileTempMode(isChecked);
            }
        });

        tileBoostCheck = new CheckBox(this);
        tileBoostCheck.setText("BOOST для плитки (100%)");
        tileBoostCheck.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tileBoostCheck.setTextColor(TEXT);
        tileCard.addView(tileBoostCheck, withTopMargin(fullWrap(), dp(6)));
        tileBoostCheck.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (!uiUpdating) controller.setTileBoost(isChecked);
            }
        });

        LinearLayout diagCard = card();
        tabSettings.addView(diagCard, withTopMargin(fullWrap(), dp(12)));
        diagCard.addView(text("Диагностика", 15, TEXT, true));
        
        LinearLayout diagRow = new LinearLayout(this);
        diagRow.setOrientation(LinearLayout.HORIZONTAL);
        diagCard.addView(diagRow, withTopMargin(fullWrap(), dp(10)));

        Button diagBtn = button("Открыть отчёт", false);
        diagBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showDiagnostics(); }
        });
        diagRow.addView(diagBtn, new LinearLayout.LayoutParams(0, dp(42), 1f));
        Button consoleBtn = button("Root-консоль", false);
        consoleBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showRootConsole(); }
        });
        diagRow.addView(consoleBtn, new LinearLayout.LayoutParams(0, dp(42), 1f));

        Button stockTorchBtn = button("Тест штатной вспышки", false);
        stockTorchBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleStockTorch(); }
        });
        diagCard.addView(stockTorchBtn, withTopMargin(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)), dp(10)));

        stockTorchStatus = text("Штатная вспышка: не проверялась", 12, DIM, false);
        diagCard.addView(stockTorchStatus, withTopMargin(fullWrap(), dp(6)));

        experimentalCard = card();
        experimentalCard.setVisibility(View.GONE);
        tabSettings.addView(experimentalCard, withTopMargin(fullWrap(), dp(12)));
        experimentalCard.addView(text("Экспериментальные настройки", 15, RED, true));
        unlock750Check = new CheckBox(this);
        unlock750Check.setText("Разблокировать 750 мА (Риск!)");
        unlock750Check.setTextColor(RED);
        experimentalCard.addView(unlock750Check);
        unlock750Check.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (uiUpdating) return;
                if (isChecked) {
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Внимание!")
                            .setMessage("Превышение штатного тока (500мА) может сжечь светодиод. Вы делаете это на свой страх и риск.")
                            .setPositiveButton("Я понимаю", new DialogInterface.OnClickListener() {
                                @Override public void onClick(DialogInterface d, int w) { controller.setUnlockedMax(true); }
                            })
                            .setNegativeButton("Отмена", new DialogInterface.OnClickListener() {
                                @Override public void onClick(DialogInterface d, int w) { uiUpdating = true; unlock750Check.setChecked(false); uiUpdating = false; }
                            }).show();
                } else controller.setUnlockedMax(false);
            }
        });
        
        // GitHub link
        TextView github = text("GitHub: 1mpsb1", 14, ACCENT, true);
        github.setPaintFlags(github.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        github.setGravity(Gravity.CENTER);
        github.setPadding(0, dp(20), 0, dp(10));
        github.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/1mpsb1"));
                    startActivity(i);
                } catch (Exception ignored) {}
            }
        });
        root.addView(github, fullWrap());
    }

    private void updateBoostButton() {
        if (boostButton == null) return;
        boolean active = controller.isBoost();
        boostButton.setBackground(rounded(active ? BOOST_ON : BOOST_OFF, 16));
        boostButton.setTextColor(TEXT);
    }

    private void rebuildLedCards() {
        if (ledContainer == null) return;
        ledContainer.removeAllViews();
        ledViews.clear();
        shownLeds.clear();
        shownLeds.addAll(controller.getLeds());
        if (shownLeds.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(text("LED-узлы не найдены", 15, RED, true));
            ledContainer.addView(empty, withTopMargin(fullWrap(), dp(4)));
            return;
        }

        for (LedController.Led led : shownLeds) {
            LinearLayout card = card();
            ledContainer.addView(card, withTopMargin(fullWrap(), dp(8)));

            LinearLayout header = new LinearLayout(this);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            card.addView(header, fullWrap());

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            header.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            col.addView(text("LED " + (led.channel + 1), 16, TEXT, true));
            TextView subtitle = text(led.path, 10, DIM, false);
            subtitle.setTypeface(Typeface.MONOSPACE);
            col.addView(subtitle);

            Switch toggle = new Switch(this);
            toggle.setChecked(led.enabled);
            header.addView(toggle);

            TextView value = text("", 12, ACCENT, false);
            card.addView(value, withTopMargin(fullWrap(), dp(8)));

            SeekBar seek = seekBar(100, led.percent);
            card.addView(seek, fullWrap());

            LedViews lv = new LedViews();
            lv.value = value; lv.toggle = toggle; lv.seek = seek;
            ledViews.put(led.path, lv);

            final String path = led.path;
            toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(CompoundButton b, boolean c) {
                    if (uiUpdating) return;
                    controller.setLedEnabled(path, c); syncUi();
                }
            });
            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                    if (!fromUser || uiUpdating) return;
                    if (progress <= 50 || sessionWarned || controller.isWarnDismissed()) {
                        controller.setLedPercent(path, progress); updateLedLabel(path);
                        if (progress == 0 && controller.isSnapToEdges()) { uiUpdating = true; toggle.setChecked(false); uiUpdating = false; }
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) {
                    int progress = s.getProgress();
                    if (progress <= 50 || sessionWarned || controller.isWarnDismissed()) return;
                    confirmDanger(false, progress, new Runnable() {
                        @Override public void run() { controller.setLedPercent(path, progress); updateLedLabel(path); }
                    }, new Runnable() {
                        @Override public void run() {
                            uiUpdating = true; s.setProgress(50); uiUpdating = false;
                            controller.setLedPercent(path, 50); updateLedLabel(path);
                        }
                    });
                }
            });
            updateLedLabel(path);
        }
    }

    private void updateLedLabel(String path) {
        LedViews lv = ledViews.get(path);
        if (lv == null) return;
        
        // Фикс: подтягиваем актуальные данные из контроллера, чтобы текст обновлялся
        for (LedController.Led led : controller.getLeds()) {
            if (led.path.equals(path)) {
                int actual = led.rawValue();
                lv.value.setText("Яркость: " + led.percent + "%  ·  " + actual + " / " + led.max + " мА");
                return;
            }
        }
    }

    private void updateAllLedSeekbars(int progress) {
        uiUpdating = true;
        for (LedController.Led led : shownLeds) {
            if (progress > 0) led.enabled = true;
            led.percent = progress;
            LedViews lv = ledViews.get(led.path);
            if (lv != null) { lv.seek.setProgress(progress); lv.toggle.setChecked(led.enabled); updateLedLabel(led.path); }
        }
        uiUpdating = false;
    }

    private void syncUi() {
        if (onSwitch == null) return;
        uiUpdating = true;
        boolean on = controller.isOn();
        onSwitch.setChecked(on);
        statusText.setText(on ? "ВКЛ" : "ВЫКЛ");
        statusText.setTextColor(on ? GREEN : DIM);

        boolean root = controller.isRootOk();
        rootStatus.setText(root ? "● Root: доступен" : "● Root: нет доступа");
        rootStatus.setTextColor(root ? GREEN : RED);

        int count = controller.getLeds().size();
        String err = controller.getRootError();
        if (count > 0) {
            summary.setText("Найдено LED: " + count + (err.length() > 0 ? "  ·  " + err : ""));
            summary.setTextColor(DIM);
        } else {
            summary.setText(err.length() > 0 ? err : "LED-узлы не найдены");
            summary.setTextColor(RED);
        }

        captureSwitch.setChecked(controller.isCapture());
        autoHookSwitch.setChecked(controller.isAutoHook());
        unlock750Check.setChecked(controller.isUnlockedMax());
        
        boolean temp = controller.isTempMode();
        tempModeSwitch.setChecked(temp);
        tempContainer.setVisibility(temp ? View.VISIBLE : View.GONE);
        ledContainer.setVisibility(temp ? View.GONE : View.VISIBLE);

        keepScreenCheck.setChecked(controller.isKeepScreenOn());
        snapToEdgesCheck.setChecked(controller.isSnapToEdges());
        smoothUpCheck.setChecked(controller.isSmoothUp());
        smoothDownCheck.setChecked(controller.isSmoothDown());
        int ms = controller.getSmoothMs();
        smoothMsSeek.setProgress(Math.max(0, Math.min(4900, ms - 100)));
        smoothMsLabel.setText("Длительность: " + ms + " мс");

        masterSeek.setProgress(controller.getMasterPercent());
        masterLabel.setText("Задать всем LED: " + controller.getMasterPercent() + "%");

        int t = controller.getTempPercent();
        tempSeek.setProgress(t);
        tempLabel.setText("Температура: " + t + "% (" + (t < 40 ? "Тёплый" : t > 60 ? "Холодный" : "Нейтральный") + ")");

        int autoIndex = 0;
        for (int i = 0; i < AUTO_OFF_VALUES.length; i++) {
            if (AUTO_OFF_VALUES[i] == controller.getAutoOffMin()) { autoIndex = i; break; }
        }
        autoOffSpinner.setSelection(autoIndex, false);
        
        tileTempModeCheck.setChecked(controller.isTileTempMode());
        tileBoostCheck.setChecked(controller.isTileBoost());

        updateBoostButton();

        for (LedController.Led led : controller.getLeds()) {
            LedViews lv = ledViews.get(led.path);
            if (lv != null) {
                lv.toggle.setChecked(led.enabled);
                lv.seek.setProgress(led.percent);
                updateLedLabel(led.path);
            }
        }
        uiUpdating = false;
    }

    private void applyKeepScreenOn() {
        if (controller.isOn() && controller.isKeepScreenOn())
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void applyPreset(boolean boost, int percent, String onlyPath) {
        releaseStockTorch();
        controller.preset(boost, LedController.METHOD_AUTO, true, percent, onlyPath, true);
        rebuildLedCards(); syncUi(); applyKeepScreenOn();
        startTorchService();
    }

    private void confirmDanger(boolean boost, int percent, final Runnable action, final Runnable cancel) {
        if (sessionWarned || controller.isWarnDismissed() || (!boost && percent <= 50)) { 
            action.run(); 
            return; 
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(18); box.setPadding(p, p, p, 0);
        box.addView(text("Внимание!\n\nBOOST и >50% сильно нагревают LED. Возможен перегрев, деградация и отключение вспышки системой.\n\nПриложение не несёт ответственности.", 14, TEXT, false));
        final CheckBox dont = new CheckBox(this);
        dont.setText("Не показывать снова");
        dont.setTextColor(DIM);
        box.addView(dont, withTopMargin(fullWrap(), dp(10)));

        new AlertDialog.Builder(this).setTitle("Предупреждение").setView(box)
            .setPositiveButton("Продолжить", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { 
                    if (dont.isChecked()) controller.setWarnDismissed(true); 
                    sessionWarned = true; // Запоминаем, что в этой сессии предупредили
                    action.run(); 
                }
            })
            .setNegativeButton("Отмена", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { if (cancel != null) cancel.run(); }
            })
            .setOnCancelListener(new DialogInterface.OnCancelListener() {
                @Override public void onCancel(DialogInterface d) { if (cancel != null) cancel.run(); }
            }).show();
    }

    private void toggleStockTorch() {
        if (controller.isOn()) { stockTorchStatus.setText("Сначала выключите «Фонарик»"); return; }
        CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) return;
        if (stockCameraId == null) {
            try {
                for (String id : cm.getCameraIdList()) {
                    if (Boolean.TRUE.equals(cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE))) {
                        stockCameraId = id; break;
                    }
                }
            } catch (Exception ignored) { return; }
        }
        if (stockCameraId == null) { stockTorchStatus.setText("Вспышка не найдена"); return; }
        boolean target = !stockTorchOn;
        try {
            cm.setTorchMode(stockCameraId, target);
            stockTorchOn = target;
            stockTorchStatus.setText(target ? "ВКЛ через камеру" : "выкл");
            stockTorchStatus.setTextColor(target ? GREEN : DIM);
        } catch (Exception e) {
            stockTorchStatus.setTextColor(RED);
            stockTorchStatus.setText("Ошибка: " + e.getMessage());
        }
    }

    private void releaseStockTorch() {
        if (!stockTorchOn || stockCameraId == null) return;
        try { ((CameraManager) getSystemService(Context.CAMERA_SERVICE)).setTorchMode(stockCameraId, false); } catch (Exception ignored) {}
        stockTorchOn = false;
        if (stockTorchStatus != null) { stockTorchStatus.setText("выкл"); stockTorchStatus.setTextColor(DIM); }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 42);
    }

    private void startTorchService() {
        if (!controller.isCapture()) return;
        Intent i = new Intent(this, TorchService.class);
        i.setAction(TorchService.ACTION_APPLY);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    }

    private void stopTorchService() { stopService(new Intent(this, TorchService.class)); }

    @Override public void onBusy(final boolean busy, final String status) {
        ui.post(new Runnable() { @Override public void run() { 
            if (busyBar != null) busyBar.setVisibility(busy ? View.VISIBLE : View.GONE); 
            if (busy && summary != null) summary.setText(status); 
        } });
    }
    @Override public void onLedsChanged() {
        ui.post(new Runnable() { @Override public void run() { 
            if (ledContainer != null) rebuildLedCards(); 
            syncUi(); 
            applyKeepScreenOn(); 
        } });
    }
    @Override public void onMessage(final String message) {
        ui.post(new Runnable() { @Override public void run() { Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show(); } });
    }

    private void showDiagnostics() {
        TextView tv = new TextView(this);
        tv.setTypeface(Typeface.MONOSPACE); tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        tv.setTextColor(TEXT); tv.setTextIsSelectable(true); tv.setPadding(dp(14), dp(14), dp(14), dp(14));
        tv.setText(controller.getDiagnostics());
        ScrollView sv = new ScrollView(this); sv.addView(tv);
        new AlertDialog.Builder(this).setTitle("Диагностика").setView(sv)
            .setPositiveButton("Копировать", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("diag", controller.getDiagnostics()));
                    Toast.makeText(MainActivity.this, "Скопировано", Toast.LENGTH_SHORT).show();
                }
            }).setNegativeButton("Закрыть", null).show();
    }

    private void showRootConsole() {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(16), dp(16), 0);
        final EditText input = new EditText(this);
        input.setHint("например: ls -l /sys/class/leds");
        input.setTypeface(Typeface.MONOSPACE); box.addView(input);
        final TextView output = new TextView(this); output.setTypeface(Typeface.MONOSPACE);
        output.setTextColor(DIM); output.setTextIsSelectable(true); output.setPadding(0, dp(10), 0, dp(10));
        ScrollView outScroll = new ScrollView(this); outScroll.addView(output);
        box.addView(outScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Root-консоль").setView(box)
            .setPositiveButton("Выполнить", null).setNegativeButton("Закрыть", null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String cmd = input.getText().toString();
                if (TextUtils.isEmpty(cmd)) return;
                output.setText("$ " + cmd + "\n…");
                controller.execRaw(cmd, new LedController.RawCallback() {
                    @Override public void onResult(String result) {
                        output.setText("$ " + input.getText().toString() + "\n" + (result == null ? "нет ответа" : result));
                    }
                });
            }
        });
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(rounded(CARD, 24)); int p = dp(16); l.setPadding(p, p, p, p); return l;
    }
    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(radiusDp)); return g;
    }
    private TextView text(String s, float sizeSp, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        t.setTextColor(color); if (bold) t.setTypeface(Typeface.DEFAULT_BOLD); return t;
    }
    private SeekBar seekBar(int max, int progress) {
        SeekBar s = new SeekBar(this); s.setMax(max); s.setProgress(progress);
        
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF3A3A3C); bg.setCornerRadius(dp(8)); bg.setSize(0, dp(10));
        
        GradientDrawable pr = new GradientDrawable();
        pr.setColor(ACCENT); pr.setCornerRadius(dp(8));
        
        ClipDrawable clip = new ClipDrawable(pr, Gravity.LEFT, ClipDrawable.HORIZONTAL);
        LayerDrawable layer = new LayerDrawable(new Drawable[]{bg, clip});
        layer.setId(0, android.R.id.background);
        layer.setId(1, android.R.id.progress);
        s.setProgressDrawable(layer);
        
        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setColor(TEXT);
        thumb.setSize(dp(20), dp(20));
        s.setThumb(thumb);
        s.setThumbOffset(dp(8));
        s.setSplitTrack(false);
        return s;
    }
    private Button button(String label, boolean accent) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTextColor(accent ? 0xFF16181D : TEXT); b.setBackground(rounded(accent ? ACCENT : CARD2, 20));
        b.setPadding(dp(16), 0, dp(16), 0); b.setMinHeight(0); b.setMinimumHeight(0); b.setStateListAnimator(null); return b;
    }
    private Spinner spinner(String[] items) {
        Spinner s = new Spinner(this); ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); s.setAdapter(a);
        s.setBackground(rounded(CARD2, 16)); s.setPadding(dp(12), 0, dp(12), 0); return s;
    }
    private LinearLayout.LayoutParams fullWrap() { return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); }
    private LinearLayout.LayoutParams withTopMargin(LinearLayout.LayoutParams lp, int top) { lp.topMargin = top; return lp; }
    private LinearLayout.LayoutParams presetParams() { LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)); lp.rightMargin = dp(8); return lp; }
    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
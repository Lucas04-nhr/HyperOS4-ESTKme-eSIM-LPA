package io.github.local.esimh4probe;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.OnBackPressedCallback;
import io.github.local.esimh4ui.BottomBar;
import io.github.local.esimh4ui.LanguageSetting;
import io.github.local.esimh4ui.Scaffold;
import io.github.local.esimh4ui.StatusBanner;
import io.github.local.esimh4ui.ThemedActivity;
import io.github.local.esimh4ui.UiKit;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/**
 * Control panel for the Xiaomi HyperOS 4 removable eSIM (SIM2) bridge.
 * Home tab: status banner and live diagnostics. Settings tab: feature switches, theme and about.
 */
public final class MainActivity extends ThemedActivity {
    private static final String TAG = "EsimH4Probe";
    private static final String ACTION_SCRIPT = "/data/adb/modules/XiaomiEsimLPAHyperOS4/action.sh";
    private static final String STATE_TAB = "tab";
    private static final int TAB_HOME = 0;
    private static final int TAB_SETTINGS = 1;

    private static final ExecutorService IO_EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "EsimH4-UIWorker");
        t.setDaemon(true);
        return t;
    });

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean refreshing = new AtomicBoolean(false);
    private boolean bindingUi = false;
    private JSONObject lastReport = null;

    private Scaffold scaffold;
    private BottomBar bottomBar;
    private int currentTab = TAB_HOME;
    /** Back on the settings tab returns to the home tab; it is enabled only there. */
    private final OnBackPressedCallback backToHome = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            scaffold.showPage(TAB_HOME, true);
        }
    };

    // Home: status banner
    private StatusBanner statusBanner;

    // Home: alert banner
    private LinearLayout cardAlertBanner;
    private TextView tvAlertMessage;

    // Home: SIM2 hardware & framework card
    private TextView tvSim2Meta;
    private TextView tvSim2Badge;
    private TextView tvBadgeMount;
    private TextView tvBadgePhoneHook;
    private TextView tvBadgeLpaHook;
    private TextView tvBadgeEuicc;
    private LinearLayout btnManageEsim;
    private LinearLayout btnLaunchTester;

    // Home: device info card
    private TextView tvInfoLpa;
    private TextView tvInfoCardId;

    // Settings
    private UiKit.SwitchRow rowMaster;
    private UiKit.ToggleSwitch switchMasterMode;
    private UiKit.SwitchRow rowSystemEsim;
    private UiKit.SwitchRow rowCarrierBridge;
    private UiKit.SwitchRow rowLpaUriBridge;
    private UiKit.SwitchRow rowRewriteText;
    private UiKit.SwitchRow rowUmountGuard;
    private UiKit.SwitchRow rowTesterApp;
    private UiKit.SwitchRow rowEstkSe1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        currentTab = savedInstanceState != null ? savedInstanceState.getInt(STATE_TAB, TAB_HOME) : TAB_HOME;

        scaffold = new Scaffold(this, palette, getString(R.string.app_name), null, false);
        scaffold.addPage(buildHomePage(), io.github.local.esimh4ui.R.id.ui_page_1, getString(R.string.app_name));
        scaffold.addPage(buildSettingsPage(), io.github.local.esimh4ui.R.id.ui_page_2, getString(R.string.tab_settings));

        bottomBar = new BottomBar(
                this,
                palette,
                new String[]{getString(R.string.tab_home), getString(R.string.tab_settings)},
                new int[]{R.drawable.ic_home_24, R.drawable.ic_settings_24});
        bottomBar.setOnSelectListener(tab -> scaffold.showPage(tab, true));
        scaffold.setBottomBar(bottomBar);
        scaffold.setOnPageChangeListener(this::onPageSelected);

        getOnBackPressedDispatcher().addCallback(this, backToHome);

        setContentView(scaffold.root);
        UiKit.applyEdgeToEdge(this, palette);
        setupSwitches();
        scaffold.showPage(currentTab, false);
        onPageSelected(currentTab);
        refreshDashboard();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isThemeRecreating()) return;
        refreshDashboard();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_TAB, currentTab);
    }

    @Override
    protected void onDestroy() {
        if (scaffold != null) scaffold.release();
        uiHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** Called when the pager settles on a page (swipe or bottom bar tap). */
    private void onPageSelected(int tab) {
        currentTab = tab;
        bottomBar.select(tab);
        scaffold.setTitle(getString(tab == TAB_HOME ? R.string.app_name : R.string.tab_settings));
        backToHome.setEnabled(tab != TAB_HOME);
    }

    // ---------------------------------------------------------------------------------------------
    // Home page
    // ---------------------------------------------------------------------------------------------

    private View buildHomePage() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        col.addView(buildStatusBanner());

        // Alert banner (shown only when something needs attention)
        cardAlertBanner = new LinearLayout(this);
        cardAlertBanner.setOrientation(LinearLayout.HORIZONTAL);
        cardAlertBanner.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams alertLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alertLp.topMargin = UiKit.dp(this, 10);
        cardAlertBanner.setLayoutParams(alertLp);
        int alertPad = UiKit.dp(this, 16);
        cardAlertBanner.setPadding(alertPad, UiKit.dp(this, 14), alertPad, UiKit.dp(this, 14));
        cardAlertBanner.setBackground(UiKit.roundedRect(this, palette.errorContainer, UiKit.radius(20f, 16f), 0, 0));
        cardAlertBanner.setVisibility(View.GONE);

        ImageView ivAlert = new ImageView(this);
        int alertIconPx = UiKit.dp(this, 20);
        LinearLayout.LayoutParams aiLp = new LinearLayout.LayoutParams(alertIconPx, alertIconPx);
        aiLp.rightMargin = UiKit.dp(this, 12);
        aiLp.topMargin = UiKit.dp(this, 2);
        ivAlert.setLayoutParams(aiLp);
        ivAlert.setImageResource(R.drawable.ic_error_24);
        ivAlert.setImageTintList(ColorStateList.valueOf(palette.onErrorContainer));
        cardAlertBanner.addView(ivAlert);

        tvAlertMessage = new TextView(this);
        tvAlertMessage.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tvAlertMessage.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.8f);
        tvAlertMessage.setLineSpacing(UiKit.dpf(this, 2.5f), 1.0f);
        tvAlertMessage.setTextColor(palette.onErrorContainer);
        cardAlertBanner.addView(tvAlertMessage);
        col.addView(cardAlertBanner);

        col.addView(UiKit.createSectionHeader(this, palette, getString(R.string.section_health)));
        col.addView(buildSimCard());

        col.addView(UiKit.createSectionHeader(this, palette, getString(R.string.section_device)));
        col.addView(buildInfoCard());

        // Utility buttons
        LinearLayout footerRow = new LinearLayout(this);
        footerRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams frLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        frLp.topMargin = UiKit.dp(this, 16);
        footerRow.setLayoutParams(frLp);

        LinearLayout btnReprobe = UiKit.createActionButton(
                this, palette, UiKit.ButtonStyle.OUTLINED, R.drawable.ic_refresh_24,
                getString(R.string.btn_reprobe), 42, this::triggerReprobeSim2);
        LinearLayout.LayoutParams rpLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rpLp.rightMargin = UiKit.dp(this, 5);
        footerRow.addView(btnReprobe, rpLp);

        LinearLayout btnShowDiag = UiKit.createActionButton(
                this, palette, UiKit.ButtonStyle.OUTLINED, R.drawable.ic_terminal_24,
                getString(R.string.btn_diagnostics), 42, this::showDiagnosticsDialog);
        LinearLayout.LayoutParams sdLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        sdLp.leftMargin = UiKit.dp(this, 5);
        footerRow.addView(btnShowDiag, sdLp);
        col.addView(footerRow);
        return col;
    }

    /** SukiSU-style status banner: green when running, red when off or needing attention. */
    private View buildStatusBanner() {
        statusBanner = new StatusBanner(this, palette);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = UiKit.dp(this, 4);
        statusBanner.setLayoutParams(lp);
        statusBanner.showLoading(getString(R.string.status_loading));
        statusBanner.setOnClickListener(v -> {
            refreshDashboard();
            showToast(getString(R.string.snack_refreshed));
        });
        return statusBanner;
    }

    private View buildSimCard() {
        LinearLayout simCard = UiKit.createCard(this, palette);

        LinearLayout simHead = new LinearLayout(this);
        simHead.setOrientation(LinearLayout.HORIZONTAL);
        simHead.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout simIconBox = new FrameLayout(this);
        int simBoxPx = UiKit.dp(this, 44);
        LinearLayout.LayoutParams sibLp = new LinearLayout.LayoutParams(simBoxPx, simBoxPx);
        sibLp.rightMargin = UiKit.dp(this, 14);
        simIconBox.setLayoutParams(sibLp);
        simIconBox.setBackground(UiKit.roundedRect(this, palette.secondaryContainer, UiKit.radius(14f, 12f), 0, 0));

        ImageView ivSim = new ImageView(this);
        int simIcPx = UiKit.dp(this, 22);
        ivSim.setLayoutParams(new FrameLayout.LayoutParams(simIcPx, simIcPx, Gravity.CENTER));
        ivSim.setImageResource(R.drawable.ic_sim_card_24);
        ivSim.setImageTintList(ColorStateList.valueOf(palette.onSecondaryContainer));
        simIconBox.addView(ivSim);
        simHead.addView(simIconBox);

        LinearLayout simTitleCol = new LinearLayout(this);
        simTitleCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams stcLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        stcLp.rightMargin = UiKit.dp(this, 8);
        simTitleCol.setLayoutParams(stcLp);

        TextView tvSimTitle = new TextView(this);
        tvSimTitle.setText(R.string.sim2_title);
        tvSimTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        tvSimTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvSimTitle.setTextColor(palette.onSurface);
        simTitleCol.addView(tvSimTitle);

        tvSim2Meta = new TextView(this);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaLp.topMargin = UiKit.dp(this, 2);
        tvSim2Meta.setLayoutParams(metaLp);
        tvSim2Meta.setText(getString(R.string.sim2_carrier, "…"));
        tvSim2Meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tvSim2Meta.setTextColor(palette.onSurfaceVariant);
        simTitleCol.addView(tvSim2Meta);
        simHead.addView(simTitleCol);

        tvSim2Badge = new TextView(this);
        tvSim2Badge.setText(R.string.badge_checking);
        tvSim2Badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tvSim2Badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvSim2Badge.setPadding(UiKit.dp(this, 11), UiKit.dp(this, 5), UiKit.dp(this, 11), UiKit.dp(this, 5));
        simHead.addView(tvSim2Badge);
        simCard.addView(simHead);

        // 2x2 health grid
        LinearLayout gridRow1 = new LinearLayout(this);
        gridRow1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams gr1Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gr1Lp.topMargin = UiKit.dp(this, 14);
        gridRow1.setLayoutParams(gr1Lp);
        tvBadgeMount = addHealthTile(gridRow1, getString(R.string.tile_mount), true);
        tvBadgePhoneHook = addHealthTile(gridRow1, getString(R.string.tile_phone_hook), false);
        simCard.addView(gridRow1);

        LinearLayout gridRow2 = new LinearLayout(this);
        gridRow2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams gr2Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gr2Lp.topMargin = UiKit.dp(this, 8);
        gridRow2.setLayoutParams(gr2Lp);
        tvBadgeLpaHook = addHealthTile(gridRow2, getString(R.string.tile_lpa_hook), true);
        tvBadgeEuicc = addHealthTile(gridRow2, getString(R.string.tile_euicc), false);
        simCard.addView(gridRow2);

        btnManageEsim = UiKit.createActionButton(
                this, palette, UiKit.ButtonStyle.FILLED, R.drawable.ic_qr_code_scanner_24,
                getString(R.string.btn_manage_esim), 48, this::openNativeEsimManager);
        LinearLayout.LayoutParams manageLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        manageLp.topMargin = UiKit.dp(this, 16);
        simCard.addView(btnManageEsim, manageLp);

        LinearLayout secBtnRow = new LinearLayout(this);
        secBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams sbrLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sbrLp.topMargin = UiKit.dp(this, 10);
        secBtnRow.setLayoutParams(sbrLp);

        LinearLayout btnMobileNetwork = UiKit.createActionButton(
                this, palette, UiKit.ButtonStyle.TONAL, R.drawable.ic_settings_cell_24,
                getString(R.string.btn_mobile_network), 44, () -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS));
                    } catch (Throwable e) {
                        showToast(getString(R.string.snack_mobile_network_failed));
                    }
                });
        LinearLayout.LayoutParams mnLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        secBtnRow.addView(btnMobileNetwork, mnLp);

        btnLaunchTester = UiKit.createActionButton(
                this, palette, UiKit.ButtonStyle.TONAL, R.drawable.ic_science_24,
                getString(R.string.btn_tester), 44, () -> {
                    try {
                        Intent launch = getPackageManager().getLaunchIntentForPackage(ProbeInspector.TESTER_PKG);
                        if (launch != null) {
                            startActivity(launch);
                        } else {
                            showToast(getString(R.string.snack_tester_hidden));
                        }
                    } catch (Throwable e) {
                        showToast(getString(R.string.snack_tester_failed));
                    }
                });
        LinearLayout.LayoutParams ltLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ltLp.leftMargin = UiKit.dp(this, 10);
        secBtnRow.addView(btnLaunchTester, ltLp);
        simCard.addView(secBtnRow);
        return simCard;
    }

    private View buildInfoCard() {
        LinearLayout card = UiKit.createCard(this, palette);
        UiKit.addInfoLine(this, palette, card, getString(R.string.info_device),
                Build.MANUFACTURER + " " + Build.MODEL);
        UiKit.addInfoLine(this, palette, card, getString(R.string.info_android),
                getString(R.string.info_android_value, Build.VERSION.RELEASE, Build.VERSION.SDK_INT), true);
        UiKit.addInfoLine(this, palette, card, getString(R.string.info_build), Build.DISPLAY, true);
        UiKit.addInfoLine(this, palette, card, getString(R.string.info_module), ProbeInspector.MODULE_VERSION, true);
        tvInfoLpa = UiKit.addInfoLine(this, palette, card, getString(R.string.info_lpa), "…", true);
        tvInfoCardId = UiKit.addInfoLine(this, palette, card, getString(R.string.info_card_id), "…", true);
        return card;
    }

    private TextView addHealthTile(LinearLayout parentRow, String label, boolean isLeft) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (isLeft) {
            lp.rightMargin = UiKit.dp(this, 4);
        } else {
            lp.leftMargin = UiKit.dp(this, 4);
        }
        tile.setLayoutParams(lp);
        int padH = UiKit.dp(this, 12);
        int padV = UiKit.dp(this, 10);
        tile.setPadding(padH, padV, padH, padV);
        tile.setBackground(UiKit.roundedRect(this, palette.tileBg, UiKit.radius(14f, 12f), 0, 0));

        TextView tvLabel = new TextView(this);
        tvLabel.setText(label);
        tvLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tvLabel.setTextColor(palette.onSurfaceVariant);
        tile.addView(tvLabel);

        TextView tvVal = new TextView(this);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.topMargin = UiKit.dp(this, 3);
        tvVal.setLayoutParams(vlp);
        tvVal.setText("○ " + getString(R.string.badge_checking));
        tvVal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        tvVal.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvVal.setTextColor(palette.onSurface);
        tile.addView(tvVal);

        parentRow.addView(tile);
        return tvVal;
    }

    // ---------------------------------------------------------------------------------------------
    // Settings page
    // ---------------------------------------------------------------------------------------------

    private View buildSettingsPage() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        col.addView(UiKit.createSectionHeader(this, palette, getString(R.string.section_bridge)));
        rowMaster = new UiKit.SwitchRow(
                this, palette, R.drawable.ic_power_24,
                getString(R.string.row_master), getString(R.string.status_loading_summary), 24f, 24f, 0);
        switchMasterMode = rowMaster.toggle;
        col.addView(rowMaster.card);

        col.addView(UiKit.createSectionHeader(this, palette, getString(R.string.section_core)));
        rowSystemEsim = new UiKit.SwitchRow(this, palette, R.drawable.ic_sync_alt_24,
                getString(R.string.row_system_esim), getString(R.string.row_system_esim_summary), 24f, 6f, 2);
        col.addView(rowSystemEsim.card);
        rowCarrierBridge = new UiKit.SwitchRow(this, palette, R.drawable.ic_verified_user_24,
                getString(R.string.row_carrier_bridge), getString(R.string.row_carrier_bridge_summary), 6f, 6f, 2);
        col.addView(rowCarrierBridge.card);
        rowLpaUriBridge = new UiKit.SwitchRow(this, palette, R.drawable.ic_link_24,
                getString(R.string.row_lpa_uri), getString(R.string.row_lpa_uri_summary), 6f, 6f, 2);
        col.addView(rowLpaUriBridge.card);
        rowRewriteText = new UiKit.SwitchRow(this, palette, R.drawable.ic_translate_24,
                getString(R.string.row_rewrite_text), getString(R.string.row_rewrite_text_summary), 6f, 24f, 0);
        col.addView(rowRewriteText.card);

        col.addView(UiKit.createSectionHeader(this, palette, getString(R.string.section_root)));
        rowUmountGuard = new UiKit.SwitchRow(this, palette, R.drawable.ic_shield_24,
                getString(R.string.row_umount_guard), getString(R.string.row_umount_guard_summary), 24f, 6f, 2);
        col.addView(rowUmountGuard.card);
        rowEstkSe1 = new UiKit.SwitchRow(this, palette, R.drawable.ic_sync_alt_24,
                getString(R.string.row_estk_se1), getString(R.string.row_estk_se1_summary), 6f, 6f, 2);
        col.addView(rowEstkSe1.card);
        rowTesterApp = new UiKit.SwitchRow(this, palette, R.drawable.ic_science_24,
                getString(R.string.row_tester), getString(R.string.row_tester_summary), 6f, 24f, 0);
        col.addView(rowTesterApp.card);

        col.addView(UiKit.createSectionHeader(this, palette, getString(R.string.section_personalize)));
        UiKit.NavRow rowLanguage = new UiKit.NavRow(
                this, palette, io.github.local.esimh4ui.R.drawable.ic_language_24,
                getString(io.github.local.esimh4ui.R.string.lang_title), null,
                24f, 6f, 2, () -> LanguageSetting.showPicker(this, palette));
        rowLanguage.setValue(LanguageSetting.currentLabel(this));
        col.addView(rowLanguage.card);
        UiKit.NavRow rowTheme = new UiKit.NavRow(
                this, palette, io.github.local.esimh4ui.R.drawable.ic_palette_24,
                getString(io.github.local.esimh4ui.R.string.theme_title), getString(R.string.row_theme_summary),
                6f, 24f, 0, () -> openThemeSettings(false));
        rowTheme.setValue(getString(UiKit.isMiuix()
                ? io.github.local.esimh4ui.R.string.theme_style_miuix
                : io.github.local.esimh4ui.R.string.theme_style_material3));
        col.addView(rowTheme.card);

        AboutSection.build(this, palette, IO_EXEC, col, this::showToast);
        return col;
    }

    // ---------------------------------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------------------------------

    private void openNativeEsimManager() {
        if (lastReport != null) {
            if (!lastReport.optBoolean("moduleActive", true)) {
                showToast(getString(R.string.snack_enable_master_first));
                return;
            }
            if (!lastReport.optBoolean("lpaPrivMounted", true)) {
                showToast(getString(R.string.snack_lpa_not_mounted));
                return;
            }
            int cardType = lastReport.optInt("cardType", -1);
            if (cardType == 0) {
                showToast(getString(R.string.snack_no_esim_card));
                return;
            }
            int esimSwitch = lastReport.optInt("esimSwitch", 1);
            if (cardType == 1 && esimSwitch == 0) {
                UiKit.showDialog(
                        this,
                        palette,
                        getString(R.string.dialog_esim_off_title),
                        getString(R.string.dialog_esim_off_message),
                        false,
                        getString(R.string.dialog_esim_off_confirm),
                        () -> {
                            setBusy(true);
                            IO_EXEC.execute(() -> {
                                Bundle extras = new Bundle();
                                extras.putBoolean("enabled", true);
                                Bundle res = ProbeInspector.callPhoneCtrl(MainActivity.this, "set_esim_state", extras);
                                boolean ok = res != null && res.getBoolean("ok", false);
                                runOnUiThread(() -> {
                                    setBusy(false);
                                    if (ok) {
                                        refreshDashboard();
                                        launchMiuiEsimProfileList();
                                    } else {
                                        showToast(getString(R.string.snack_wake_failed));
                                    }
                                });
                            });
                        },
                        getString(io.github.local.esimh4ui.R.string.ui_cancel));
                return;
            }
        }
        launchMiuiEsimProfileList();
    }

    private void launchMiuiEsimProfileList() {
        // Primary: direct foreground launch of MIUIEsimLPA's exported ProfileListActivity
        // (exported=true with no MODIFY_PHONE_STATE / BIND_EUICC_SERVICE permission gate,
        // avoiding EuiccUiDispatcherActivity's SecurityException for normal app UIDs while
        // preserving foreground BAL_ALLOW_VISIBLE_WINDOW transition).
        try {
            Intent direct = new Intent();
            direct.setComponent(new ComponentName(
                    ProbeInspector.LPA_PKG,
                    "com.miui.euicc.ui.main.ProfileListActivity"));
            direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(direct);
            return;
        } catch (Throwable primaryErr) {
            Log.w(TAG, "Direct ProfileListActivity launch failed, trying phone dispatcher", primaryErr);
        }

        // Fallback: ask privileged com.android.phone (uid 1001, holds MODIFY_PHONE_STATE & BIND_EUICC_SERVICE)
        // to dispatch ACTION_MANAGE_EMBEDDED_SUBSCRIPTIONS.
        IO_EXEC.execute(() -> {
            Bundle res = ProbeInspector.callPhoneCtrl(MainActivity.this, "open_esim_manager", null);
            boolean ok = res != null && res.getBoolean("ok", false);
            if (!ok) {
                String err = res != null ? res.getString("error", "") : "";
                runOnUiThread(() -> showToast(err.isEmpty()
                        ? getString(R.string.snack_open_lpa_failed)
                        : getString(R.string.snack_open_lpa_failed_detail, err)));
            }
        });
    }

    private void triggerReprobeSim2() {
        setBusy(true);
        IO_EXEC.execute(() -> {
            Bundle res = ProbeInspector.callPhoneCtrl(MainActivity.this, "reprobe", null);
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
            }
            runOnUiThread(() -> {
                setBusy(false);
                boolean ok = res != null && res.getBoolean("ok", false);
                showToast(getString(ok ? R.string.snack_reprobe_ok : R.string.snack_reprobe_failed));
                refreshDashboard();
            });
        });
    }

    private void bindRowClick(UiKit.SwitchRow row, String disabledHint) {
        row.card.setOnClickListener(v -> {
            if (row.toggle.isEnabled()) {
                row.toggle.performUserToggle();
            } else if (disabledHint != null) {
                showToast(disabledHint);
            }
        });
    }

    private void setupSwitches() {
        String hintHook = getString(R.string.hint_need_hook);
        String hintMaster = getString(R.string.hint_need_master);
        bindRowClick(rowMaster, null);
        bindRowClick(rowSystemEsim, getString(R.string.hint_need_esim_card));
        bindRowClick(rowCarrierBridge, hintHook);
        bindRowClick(rowLpaUriBridge, null);
        bindRowClick(rowRewriteText, hintHook);
        bindRowClick(rowUmountGuard, getString(R.string.hint_need_mount));
        bindRowClick(rowTesterApp, hintMaster);
        bindRowClick(rowEstkSe1, hintMaster);

        switchMasterMode.setOnCheckedChangeListener((btn, isChecked) -> {
            if (bindingUi) return;
            rowMaster.refreshIconTint(isChecked);
            runRootSwitchAction(
                    switchMasterMode,
                    rowMaster,
                    isChecked,
                    new String[]{isChecked ? "on" : "off"},
                    output -> {
                        if (output.contains("RESULT=MISSING_METAMODULE")) {
                            showInfoDialog(getString(R.string.dialog_missing_metamodule_title),
                                    getString(R.string.dialog_missing_metamodule_message));
                        } else if (output.contains("RESULT=NEED_REBOOT")) {
                            showInfoDialog(getString(R.string.dialog_need_reboot_title),
                                    getString(R.string.dialog_need_reboot_message));
                        } else if (output.contains("RESULT=ACTIVE_NOW")) {
                            showToast(getString(R.string.snack_master_on));
                        } else if (output.contains("RESULT=OFF_APPLIED")) {
                            showInfoDialog(getString(R.string.dialog_master_off_title),
                                    getString(R.string.dialog_master_off_message));
                        } else {
                            showToast(getString(isChecked ? R.string.snack_master_on_sent : R.string.snack_master_off_sent));
                        }
                        refreshDashboard();
                    });
        });

        rowSystemEsim.toggle.setOnCheckedChangeListener((btn, isChecked) -> {
            if (bindingUi) return;
            rowSystemEsim.refreshIconTint(isChecked);
            setBusy(true);
            rowSystemEsim.toggle.setEnabled(false);
            IO_EXEC.execute(() -> {
                Bundle extras = new Bundle();
                extras.putBoolean("enabled", isChecked);
                Bundle res = ProbeInspector.callPhoneCtrl(MainActivity.this, "set_esim_state", extras);
                boolean ok = res != null && res.getBoolean("ok", false);
                runOnUiThread(() -> {
                    setBusy(false);
                    rowSystemEsim.toggle.setEnabled(true);
                    if (!ok) {
                        setSwitchSilently(rowSystemEsim.toggle, rowSystemEsim, !isChecked);
                        showToast(getString(R.string.snack_esim_toggle_failed));
                    } else {
                        showToast(getString(isChecked ? R.string.snack_esim_on : R.string.snack_esim_off));
                        refreshDashboard();
                    }
                });
            });
        });

        rowCarrierBridge.toggle.setOnCheckedChangeListener((btn, isChecked) -> {
            if (bindingUi) return;
            rowCarrierBridge.refreshIconTint(isChecked);
            updatePhonePref(rowCarrierBridge, CardTypeGuard.KEY_CARRIER_BRIDGE, isChecked,
                    getString(isChecked ? R.string.snack_carrier_on : R.string.snack_carrier_off));
        });

        rowLpaUriBridge.toggle.setOnCheckedChangeListener((btn, isChecked) -> {
            if (bindingUi) return;
            rowLpaUriBridge.refreshIconTint(isChecked);
            try {
                ComponentName comp = new ComponentName(this, LpaBridgeActivity.class);
                getPackageManager().setComponentEnabledSetting(
                        comp,
                        isChecked
                                ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP);
            } catch (Throwable e) {
                setSwitchSilently(rowLpaUriBridge.toggle, rowLpaUriBridge, !isChecked);
                showToast(getString(R.string.snack_component_failed, e.getClass().getSimpleName()));
                return;
            }
            IO_EXEC.execute(() -> {
                Bundle extras = new Bundle();
                extras.putString("key", CardTypeGuard.KEY_LPA_URI_BRIDGE);
                extras.putBoolean("enabled", isChecked);
                ProbeInspector.callPhoneCtrl(MainActivity.this, "set_pref", extras);
                runOnUiThread(() -> {
                    showToast(getString(isChecked ? R.string.snack_lpa_uri_on : R.string.snack_lpa_uri_off));
                    refreshDashboard();
                });
            });
        });

        rowRewriteText.toggle.setOnCheckedChangeListener((btn, isChecked) -> {
            if (bindingUi) return;
            rowRewriteText.refreshIconTint(isChecked);
            updatePhonePref(rowRewriteText, CardTypeGuard.KEY_REWRITE_TEXT, isChecked,
                    getString(isChecked ? R.string.snack_rewrite_on : R.string.snack_rewrite_off));
        });

        rowUmountGuard.toggle.setOnCheckedChangeListener((btn, isChecked) -> {
            if (bindingUi) return;
            rowUmountGuard.refreshIconTint(isChecked);
            runRootSwitchAction(
                    rowUmountGuard.toggle,
                    rowUmountGuard,
                    isChecked,
                    new String[]{"umount_guard", isChecked ? "on" : "off"},
                    output -> {
                        showToast(getString(isChecked ? R.string.snack_umount_on : R.string.snack_umount_off));
                        refreshDashboard();
                    });
        });

        rowEstkSe1.toggle.setOnCheckedChangeListener((btn, isChecked) -> {
            if (bindingUi) return;
            rowEstkSe1.refreshIconTint(isChecked);
            runRootSwitchAction(
                    rowEstkSe1.toggle,
                    rowEstkSe1,
                    isChecked,
                    new String[]{"isdr_se", isChecked ? "se1" : "auto"},
                    output -> {
                        showInfoDialog(
                                getString(isChecked ? R.string.dialog_se1_title : R.string.dialog_se0_title),
                                getString(R.string.dialog_se_message));
                        refreshDashboard();
                    });
        });

        rowTesterApp.toggle.setOnCheckedChangeListener((btn, isChecked) -> {
            if (bindingUi) return;
            rowTesterApp.refreshIconTint(isChecked);
            runRootSwitchAction(
                    rowTesterApp.toggle,
                    rowTesterApp,
                    isChecked,
                    new String[]{"tester", isChecked ? "on" : "off"},
                    output -> {
                        showToast(getString(isChecked ? R.string.snack_tester_on : R.string.snack_tester_off));
                        refreshDashboard();
                    });
        });
    }

    private void updatePhonePref(UiKit.SwitchRow row, String key, boolean isChecked, String okMsg) {
        IO_EXEC.execute(() -> {
            Bundle extras = new Bundle();
            extras.putString("key", key);
            extras.putBoolean("enabled", isChecked);
            Bundle res = ProbeInspector.callPhoneCtrl(MainActivity.this, "set_pref", extras);
            boolean ok = res != null && res.getBoolean("ok", false);
            runOnUiThread(() -> {
                if (!ok) {
                    setSwitchSilently(row.toggle, row, !isChecked);
                    showToast(getString(R.string.snack_pref_failed));
                } else {
                    showToast(okMsg);
                    refreshDashboard();
                }
            });
        });
    }

    private interface ActionCallback {
        void onSuccess(String stdout);
    }

    private void runRootSwitchAction(
            UiKit.ToggleSwitch sw,
            UiKit.SwitchRow ownerRow,
            boolean desiredState,
            String[] args,
            ActionCallback callback) {
        setBusy(true);
        sw.setEnabled(false);
        IO_EXEC.execute(() -> {
            StringBuilder cmd = new StringBuilder("sh ").append(ACTION_SCRIPT);
            for (String a : args) {
                cmd.append(' ').append(a);
            }
            boolean rootOk = false;
            String outText = "";
            try {
                java.lang.Process p = new ProcessBuilder("su", "-c", cmd.toString())
                        .redirectErrorStream(true)
                        .start();
                if (p.waitFor(25, TimeUnit.SECONDS)) {
                    outText = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    rootOk = (p.exitValue() == 0);
                } else {
                    p.destroyForcibly();
                }
            } catch (Throwable ignored) {
            }
            final boolean success = rootOk;
            final String finalOut = outText;
            runOnUiThread(() -> {
                setBusy(false);
                sw.setEnabled(true);
                if (!success) {
                    setSwitchSilently(sw, ownerRow, !desiredState);
                    showRootPermissionDialog();
                } else {
                    callback.onSuccess(finalOut);
                }
            });
        });
    }

    private void showRootPermissionDialog() {
        UiKit.showDialog(
                this,
                palette,
                getString(R.string.dialog_root_title),
                getString(R.string.dialog_root_message, getString(R.string.app_name)),
                false,
                null,
                null,
                getString(R.string.dialog_got_it));
    }

    private void showInfoDialog(String title, String message) {
        UiKit.showDialog(this, palette, title, message, false, null, null, null);
    }

    private void showDiagnosticsDialog() {
        String pretty;
        try {
            JSONObject obj = lastReport != null
                    ? lastReport
                    : new JSONObject(ProbeInspector.inspect(this));
            pretty = obj.toString(2);
        } catch (Throwable e) {
            pretty = getString(R.string.diag_read_failed, String.valueOf(e.getMessage()));
        }
        final String reportText = pretty;
        UiKit.showDialog(
                this,
                palette,
                getString(R.string.diag_title),
                reportText,
                true,
                getString(R.string.diag_copy),
                () -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("EsimH4Diagnostics", reportText));
                        showToast(getString(R.string.snack_diag_copied));
                    }
                },
                getString(R.string.diag_close));
    }

    private void setBusy(boolean busy) {
        if (scaffold != null) scaffold.setBusy(busy);
    }

    private void setSwitchSilently(UiKit.ToggleSwitch sw, UiKit.SwitchRow ownerRow, boolean checked) {
        boolean prev = bindingUi;
        bindingUi = true;
        try {
            sw.setChecked(checked, true);
            if (ownerRow != null) {
                ownerRow.refreshIconTint(checked);
            }
        } finally {
            bindingUi = prev;
        }
    }

    private void showToast(String msg) {
        if (scaffold != null) scaffold.showToast(msg);
    }

    // ---------------------------------------------------------------------------------------------
    // Dashboard binding
    // ---------------------------------------------------------------------------------------------

    private void refreshDashboard() {
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }
        setBusy(true);
        IO_EXEC.execute(() -> {
            try {
                String result = ProbeInspector.inspect(MainActivity.this);
                Log.i(TAG, result);
                JSONObject report = new JSONObject(result);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    lastReport = report;
                    bindDashboard(report);
                });
            } catch (Throwable e) {
                Log.e(TAG, "inspect failed", e);
            } finally {
                refreshing.set(false);
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        setBusy(false);
                    }
                });
            }
        });
    }

    private void bindDashboard(JSONObject r) {
        bindingUi = true;
        try {
            boolean moduleActive = r.optBoolean("moduleActive", false);
            boolean lpaPrivMounted = r.optBoolean("lpaPrivMounted", false);
            boolean lpaUpdatedSys = r.optBoolean("lpaUpdatedSystemApp", false);
            boolean phoneHook = r.optBoolean("phoneHookActive", false);
            boolean lpaHook = r.optBoolean("lpaHookActive", false);
            boolean hasFeature = r.optBoolean("feature", false);
            boolean euiccEnabled = r.optBoolean("enabled", false);
            boolean testerInstalled = r.optBoolean("testerInstalled", false);

            int cardType = r.optInt("cardType", -1);
            int esimSwitch = r.optInt("esimSwitch", -1);
            int sim2State = r.optInt("sim2SimState", TelephonyManager.SIM_STATE_UNKNOWN);
            int defaultCardId = r.optInt("defaultCardId", -1);
            boolean hasActiveSub = r.optBoolean("sim2HasActiveSub", false);
            String carrierName = r.optString("sim2CarrierName", "");
            String lpaVer = r.has("lpaVersion") ? r.optString("lpaVersion") : getString(R.string.lpa_not_installed);

            boolean carrierBridge = r.optBoolean("carrierBridgeEnabled", true);
            boolean lpaUriBridge = r.optBoolean("lpaUriBridgeEnabled", true);
            boolean rewriteText = r.optBoolean("rewriteTextEnabled", true);

            // 1. Status banner + master switch (SukiSU style: green card when working,
            //    plain card with a red hint and an action otherwise)
            boolean se1 = "se1".equals(EuiccCardReader.isdrSePref());
            if (!moduleActive) {
                statusBanner.showError(getString(R.string.status_off_hint), getString(R.string.status_off),
                        getString(R.string.btn_turn_on), () -> scaffold.showPage(TAB_SETTINGS, true));
            } else if (!lpaPrivMounted) {
                statusBanner.showError(getString(R.string.status_wait_mount), getString(R.string.status_attention),
                        getString(R.string.btn_diagnostics), this::showDiagnosticsDialog);
            } else if (!phoneHook) {
                statusBanner.showError(getString(R.string.status_wait_lsposed), getString(R.string.status_attention),
                        getString(R.string.btn_diagnostics), this::showDiagnosticsDialog);
            } else {
                List<StatusBanner.Tag> tags = new ArrayList<>();
                tags.add(new StatusBanner.Tag(getString(cardType == 1 ? R.string.tag_esim : R.string.tag_no_esim),
                        cardType == 1));
                if (se1) tags.add(new StatusBanner.Tag(getString(R.string.tag_se1), false));
                if (lpaUpdatedSys) tags.add(new StatusBanner.Tag(getString(R.string.tag_umount), false));
                statusBanner.showOk(getString(R.string.status_running),
                        getString(R.string.status_version_lpa, ProbeInspector.MODULE_VERSION, lpaVer), tags);
            }

            switchMasterMode.setChecked(moduleActive, false);
            rowMaster.setRowState(moduleActive, true, getString(moduleActive
                    ? R.string.row_master_on
                    : R.string.row_master_off));

            // 2. Alert banner
            if (moduleActive && !lpaPrivMounted) {
                cardAlertBanner.setVisibility(View.VISIBLE);
                tvAlertMessage.setText(R.string.alert_mount);
            } else if (moduleActive && !phoneHook) {
                cardAlertBanner.setVisibility(View.VISIBLE);
                tvAlertMessage.setText(getString(R.string.alert_hook, getString(R.string.app_name)));
            } else {
                cardAlertBanner.setVisibility(View.GONE);
            }

            // 3. SIM2 card
            tvSim2Meta.setText(getString(R.string.sim2_carrier,
                    carrierName == null || carrierName.isEmpty() ? "—" : carrierName));
            bindSim2Badge(cardType, esimSwitch, sim2State, hasActiveSub, lpaPrivMounted, moduleActive);

            setHealthTile(tvBadgeMount, lpaPrivMounted,
                    getString(lpaPrivMounted ? R.string.health_active : R.string.health_not_mounted), !moduleActive);
            setHealthTile(tvBadgePhoneHook, phoneHook,
                    getString(phoneHook ? R.string.health_hooked : R.string.health_inactive), false);
            setHealthTile(tvBadgeLpaHook, lpaHook,
                    getString(lpaHook ? R.string.health_hooked
                            : (lpaPrivMounted ? R.string.health_check : R.string.health_not_mounted)), !moduleActive);
            setHealthTile(tvBadgeEuicc, hasFeature && euiccEnabled,
                    getString(euiccEnabled ? R.string.health_enabled
                            : (hasFeature ? R.string.health_pending : R.string.health_undeclared)), !moduleActive);

            UiKit.setButtonEnabled(btnManageEsim, moduleActive && lpaPrivMounted && cardType != 0);
            btnLaunchTester.setVisibility(testerInstalled ? View.VISIBLE : View.GONE);

            // 4. Device info
            tvInfoLpa.setText(lpaVer);
            tvInfoCardId.setText(defaultCardId >= 0 ? String.valueOf(defaultCardId) : "—");

            // 5. Core switches
            boolean canToggleEsim = moduleActive && lpaPrivMounted && phoneHook && cardType == 1;
            String subEsim;
            if (!moduleActive) {
                subEsim = getString(R.string.esim_sub_module_off);
            } else if (cardType == 1) {
                subEsim = getString(esimSwitch == 1 ? R.string.esim_sub_on : R.string.esim_sub_off);
            } else if (cardType == 0) {
                subEsim = getString(R.string.esim_sub_isolated);
            } else {
                subEsim = getString(R.string.esim_sub_detecting);
            }
            rowSystemEsim.setRowState(cardType == 1 && esimSwitch == 1, canToggleEsim, subEsim);

            boolean canUpdatePhonePrefs = moduleActive && phoneHook;
            rowCarrierBridge.setRowState(carrierBridge, canUpdatePhonePrefs,
                    getString(carrierBridge ? R.string.carrier_sub_on : R.string.carrier_sub_off));
            rowLpaUriBridge.setRowState(lpaUriBridge, true,
                    getString(lpaUriBridge ? R.string.lpa_uri_sub_on : R.string.lpa_uri_sub_off));
            rowRewriteText.setRowState(rewriteText, canUpdatePhonePrefs,
                    getString(rewriteText ? R.string.rewrite_sub_on : R.string.rewrite_sub_off));

            // 6. Root / component switches
            String subUmount;
            if (lpaUpdatedSys) {
                subUmount = getString(R.string.umount_sub_active);
            } else if (lpaPrivMounted) {
                subUmount = getString(R.string.umount_sub_overlay);
            } else {
                subUmount = getString(R.string.umount_sub_waiting);
            }
            rowUmountGuard.setRowState(lpaUpdatedSys, moduleActive && lpaPrivMounted, subUmount);

            rowEstkSe1.setRowState(se1, moduleActive,
                    getString(se1 ? R.string.se1_sub_on : R.string.se1_sub_off));

            rowTesterApp.setRowState(testerInstalled, moduleActive,
                    getString(testerInstalled ? R.string.tester_sub_on : R.string.tester_sub_off));
        } finally {
            bindingUi = false;
        }
    }

    private void setHealthTile(TextView tv, boolean ok, String text, boolean muted) {
        tv.setText((ok ? "● " : "○ ") + text);
        int colorRes;
        if (ok) {
            colorRes = R.color.status_ok_fg;
        } else if (muted) {
            colorRes = R.color.status_off_fg;
        } else {
            colorRes = R.color.status_err_fg;
        }
        tv.setTextColor(getColor(colorRes));
    }

    private void bindSim2Badge(
            int cardType,
            int esimSwitch,
            int sim2State,
            boolean hasActiveSub,
            boolean lpaPrivMounted,
            boolean moduleActive) {
        int pillBgRes;
        int pillFgRes;
        int badge;
        if (!moduleActive) {
            badge = R.string.badge_disabled;
            pillBgRes = R.color.status_off_bg;
            pillFgRes = R.color.status_off_fg;
        } else if (cardType == 1) {
            boolean profileReady = hasActiveSub
                    || sim2State == TelephonyManager.SIM_STATE_READY
                    || sim2State == 10;
            if (profileReady && esimSwitch != 0) {
                badge = R.string.badge_profile_on;
                pillBgRes = R.color.status_ok_bg;
                pillFgRes = R.color.status_ok_fg;
            } else if (esimSwitch == 0) {
                badge = R.string.badge_stopped;
                pillBgRes = R.color.status_warn_bg;
                pillFgRes = R.color.status_warn_fg;
            } else {
                badge = R.string.badge_empty;
                pillBgRes = R.color.status_ok_bg;
                pillFgRes = R.color.status_ok_fg;
            }
        } else if (cardType == 0) {
            boolean absent = sim2State == TelephonyManager.SIM_STATE_ABSENT;
            badge = absent ? R.string.badge_no_card : R.string.badge_normal_sim;
            pillBgRes = absent ? R.color.status_off_bg : R.color.status_warn_bg;
            pillFgRes = absent ? R.color.status_off_fg : R.color.status_warn_fg;
        } else if (!lpaPrivMounted) {
            badge = R.string.badge_wait_mount;
            pillBgRes = R.color.status_err_bg;
            pillFgRes = R.color.status_err_fg;
        } else {
            badge = R.string.badge_detecting;
            pillBgRes = R.color.status_off_bg;
            pillFgRes = R.color.status_off_fg;
        }
        tvSim2Badge.setText(badge);
        tvSim2Badge.setTextColor(getColor(pillFgRes));
        tvSim2Badge.setBackground(UiKit.roundedRect(this, getColor(pillBgRes), 999f, 0, 0));
    }

    public static String inspect(Context ctx) {
        return ProbeInspector.inspect(ctx);
    }

    public static void main(String[] args) throws Exception {
        ProbeInspector.main(args);
    }
}

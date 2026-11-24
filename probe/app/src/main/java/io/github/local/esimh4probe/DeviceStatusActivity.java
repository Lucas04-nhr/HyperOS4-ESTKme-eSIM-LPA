package io.github.local.esimh4probe;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.telephony.euicc.EuiccManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import io.github.local.esimh4ui.RefreshScrollView;
import io.github.local.esimh4ui.Scaffold;
import io.github.local.esimh4ui.ThemedActivity;
import io.github.local.esimh4ui.UiKit;

/**
 * Device and eUICC status, opened from the settings tab (below the bridge master switch).
 * Pull down to read the live values again.
 */
public final class DeviceStatusActivity extends ThemedActivity {
    private static final String KEY_CARD_TYPE = "xiaomi_esim_h4_card_type";
    private static final String KEY_ENABLE_ESIM = "is_enable_esim_for_user";
    /** Probe target for the "LPA:// links" line; any activation code works. */
    private static final String SAMPLE_LPA_CODE = "LPA:1$test.esim.local$MOCK-TEST-001";

    private Scaffold scaffold;
    private TextView tvLpa;
    private TextView stMount;
    private TextView stFeature;
    private TextView stEnabled;
    private TextView stCardId;
    private TextView stCard;
    private TextView stLpaScheme;
    private TextView stActivation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String title = getString(R.string.row_device_status);
        scaffold = new Scaffold(this, palette, title, null, true);
        RefreshScrollView page = scaffold.addPage(buildContent(), io.github.local.esimh4ui.R.id.ui_page_1, title);
        page.setOnRefreshListener(() -> {
            refresh();
            // The values are read at once; keep the ring visible briefly so the pull registers.
            page.postDelayed(() -> page.setRefreshing(false), 400);
        });
        setContentView(scaffold.root);
        UiKit.applyEdgeToEdge(this, palette);
        // onResume reads the values.
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isThemeRecreating()) return;
        refresh();
    }

    @Override
    protected void onDestroy() {
        if (scaffold != null) scaffold.release();
        super.onDestroy();
    }

    private LinearLayout buildContent() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        col.addView(UiKit.createSectionHeader(this, palette, getString(R.string.section_device)));
        LinearLayout device = UiKit.createCard(this, palette);
        UiKit.addInfoLine(this, palette, device, getString(R.string.info_device),
                Build.MANUFACTURER + " " + Build.MODEL);
        UiKit.addInfoLine(this, palette, device, getString(R.string.info_android),
                getString(R.string.info_android_value, Build.VERSION.RELEASE, Build.VERSION.SDK_INT), true);
        UiKit.addInfoLine(this, palette, device, getString(R.string.info_build), Build.DISPLAY, true);
        UiKit.addInfoLine(this, palette, device, getString(R.string.info_module), ProbeInspector.MODULE_VERSION, true);
        tvLpa = UiKit.addInfoLine(this, palette, device, getString(R.string.info_lpa), "…", true);
        col.addView(device);

        col.addView(UiKit.createSectionHeader(this, palette, getString(R.string.tester_status_title)));
        LinearLayout status = UiKit.createCard(this, palette);
        stMount = UiKit.addInfoLine(this, palette, status, getString(R.string.st_mount), "…");
        stFeature = UiKit.addInfoLine(this, palette, status, getString(R.string.st_feature), "…");
        stEnabled = UiKit.addInfoLine(this, palette, status, getString(R.string.st_enabled), "…");
        stCardId = UiKit.addInfoLine(this, palette, status, getString(R.string.st_card_id), "…", true);
        stCard = UiKit.addInfoLine(this, palette, status, getString(R.string.st_card), "…");
        stLpaScheme = UiKit.addInfoLine(this, palette, status, getString(R.string.st_lpa_scheme), "…");
        stActivation = UiKit.addInfoLine(this, palette, status, getString(R.string.st_activation), "…");
        col.addView(status);
        return col;
    }

    private void refresh() {
        try {
            PackageManager pm = getPackageManager();
            String lpaVer;
            try {
                lpaVer = pm.getPackageInfo(ProbeInspector.LPA_PKG, 0).versionName;
            } catch (Throwable e) {
                lpaVer = getString(R.string.lpa_not_installed);
            }
            tvLpa.setText(lpaVer);

            boolean lpaPrivMounted = ProbeInspector.isLpaPrivMounted(pm);
            boolean hasFeature = pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_EUICC);
            EuiccManager em = getSystemService(EuiccManager.class);
            boolean enabled = em != null && em.isEnabled();
            TelephonyManager tm = getSystemService(TelephonyManager.class);
            int defaultCardId = ProbeInspector.defaultEuiccCardId(tm);
            boolean lpaSchemeOk = pm.resolveActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(SAMPLE_LPA_CODE)), 0) != null;
            boolean startActOk = pm.resolveActivity(new Intent(EuiccManager.ACTION_START_EUICC_ACTIVATION), 0) != null;
            int cardType = Settings.Secure.getInt(getContentResolver(), KEY_CARD_TYPE, -1);

            setStatus(stMount, getString(lpaPrivMounted ? R.string.st_mount_ok : R.string.st_mount_bad), lpaPrivMounted);
            setStatus(stFeature, getString(hasFeature ? R.string.st_feature_yes : R.string.st_feature_no), hasFeature);
            setStatus(stEnabled, getString(enabled ? R.string.st_enabled_yes : R.string.st_enabled_no), enabled);
            setStatus(stCardId, defaultCardId >= 0 ? String.valueOf(defaultCardId) : "—", defaultCardId >= 0);
            setStatus(stCard, describeCard(cardType, lpaPrivMounted, tm), cardType == 1);
            setStatus(stLpaScheme, getString(lpaSchemeOk ? R.string.st_lpa_scheme_on : R.string.st_lpa_scheme_off), lpaSchemeOk);
            setStatus(stActivation, getString(startActOk ? R.string.st_activation_ok : R.string.st_activation_bad), startActOk);
        } catch (Throwable t) {
            setStatus(stMount, getString(R.string.st_error, String.valueOf(t.getMessage())), false);
        }
    }

    private String describeCard(int cardType, boolean lpaPrivMounted, TelephonyManager tm) {
        int esimSwitch = Settings.Secure.getInt(getContentResolver(), KEY_ENABLE_ESIM, -1);
        int sim2State = TelephonyManager.SIM_STATE_UNKNOWN;
        if (tm != null) {
            try {
                sim2State = tm.getSimState(1);
            } catch (Throwable ignored) {
            }
        }
        if (cardType == 1) {
            if (sim2State == TelephonyManager.SIM_STATE_READY || sim2State == 10) {
                return getString(R.string.card_esim_active);
            }
            return getString(esimSwitch == 0 ? R.string.card_esim_switch_off : R.string.card_esim_empty);
        }
        if (cardType == 0) {
            return getString(sim2State == TelephonyManager.SIM_STATE_ABSENT ? R.string.card_absent : R.string.card_regular);
        }
        return getString(lpaPrivMounted ? R.string.card_detecting : R.string.card_wait_mount);
    }

    /** Sets a status value; green when fine, red when not. */
    private void setStatus(TextView tv, String text, boolean ok) {
        tv.setText(text);
        tv.setTextColor(ok ? (UiKit.isMiuix() ? palette.success : palette.onSurface) : palette.error);
    }
}

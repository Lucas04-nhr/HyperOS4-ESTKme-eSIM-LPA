package io.github.local.esimh4tester;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.telephony.euicc.DownloadableSubscription;
import android.telephony.euicc.EuiccManager;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import io.github.local.esimh4ui.Scaffold;
import io.github.local.esimh4ui.ThemedActivity;
import io.github.local.esimh4ui.UiKit;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Mock carrier app to verify EuiccManager one-click eSIM installation, LPA:// URI bridging and
 * the authorization dialog safely. Shares the Material 3 / Miuix UI kit with the main app.
 */
public final class TesterActivity extends ThemedActivity {
    private static final String TAG = "EsimH4Tester";
    private static final String MODULE_VERSION = "v0.6.1";
    private static final String LPA_PKG = "com.miui.euicc";
    private static final String KEY_CARD_TYPE = "xiaomi_esim_h4_card_type";
    private static final String KEY_ENABLE_ESIM = "is_enable_esim_for_user";
    private static final String PERM_BIND_EUICC = "android.permission.BIND_EUICC_SERVICE";
    private static final String PERM_SE_PRIVILEGED = "android.permission.SECURE_ELEMENT_PRIVILEGED_OPERATION";
    private static final String ACTION_DOWNLOAD_CB = "io.github.local.esimh4tester.ACTION_DOWNLOAD_CB";
    private static final String DEFAULT_MOCK_CODE = "LPA:1$test.esim.local$MOCK-TEST-001";
    private static final String OK = "● ";
    private static final String BAD = "○ ";
    private static final int REQ_RESOLVE = 1001;
    private static final int REQ_ACTIVATE = 1002;
    private static final java.lang.reflect.Method GET_DEFAULT_CARD_ID;

    static {
        java.lang.reflect.Method m = null;
        try {
            m = TelephonyManager.class.getMethod("getCardIdForDefaultEuicc");
        } catch (Throwable ignored) {
        }
        GET_DEFAULT_CARD_ID = m;
    }

    private Scaffold scaffold;
    private TextView statusView;
    private EditText codeInput;
    private TextView logView;
    private final StringBuilder logBuffer = new StringBuilder();
    private int requestSeq = 1;

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !ACTION_DOWNLOAD_CB.equals(intent.getAction())) return;
            int resultCode = getResultCode();
            String phase = intent.getStringExtra("phase");
            int detailed = intent.getIntExtra(EuiccManager.EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE, 0);
            boolean mockIntercepted = intent.getBooleanExtra("io.github.local.esimh4.MOCK_INTERCEPTED", false);
            Log.i(TAG, "Callback received: phase=" + phase + ", resultCode=" + resultCode
                    + ", detailed=" + detailed + ", mock=" + mockIntercepted);

            if ("initial".equals(phase)) {
                if (resultCode == EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR) {
                    appendLog(getString(R.string.log_resolvable));
                    appendLog(getString(R.string.log_resolvable_note));
                    try {
                        EuiccManager em = getSystemService(EuiccManager.class);
                        Intent cb = new Intent(ACTION_DOWNLOAD_CB)
                                .setPackage(getPackageName())
                                .putExtra("phase", "resolved");
                        PendingIntent pi = PendingIntent.getBroadcast(
                                TesterActivity.this,
                                ++requestSeq,
                                cb,
                                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE
                        );
                        em.startResolutionActivity(TesterActivity.this, REQ_RESOLVE, intent, pi);
                    } catch (Throwable t) {
                        appendLog(getString(R.string.log_resolution_failed,
                                t.getClass().getSimpleName() + ": " + t.getMessage()));
                    }
                } else if (resultCode == EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_OK) {
                    appendLog(getString(R.string.log_direct_ok));
                } else {
                    appendLog(getString(R.string.log_request_error, resultCode, detailed));
                }
            } else {
                if (resultCode == EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_OK) {
                    if (mockIntercepted) {
                        appendLog(getString(R.string.log_after_auth_ok));
                        appendLog(getString(R.string.log_mock_intercepted));
                    } else {
                        appendLog(getString(R.string.log_real_ok));
                    }
                } else {
                    appendLog(getString(R.string.log_after_auth_end, resultCode));
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        registerReceiver(downloadReceiver, new IntentFilter(ACTION_DOWNLOAD_CB), Context.RECEIVER_EXPORTED);

        scaffold = new Scaffold(this, palette, getString(R.string.app_name),
                getString(R.string.tester_subtitle, MODULE_VERSION), false);
        scaffold.addAction(UiKit.createIconButton(
                this, palette, R.drawable.ic_refresh_24, getString(R.string.tester_refresh), this::refreshStatus));
        scaffold.addAction(UiKit.createIconButton(
                this, palette, io.github.local.esimh4ui.R.drawable.ic_palette_24,
                getString(io.github.local.esimh4ui.R.string.theme_title), this::openThemeSettings));
        scaffold.addPage(buildContent(), io.github.local.esimh4ui.R.id.ui_page_1);
        setContentView(scaffold.root);
        UiKit.applyEdgeToEdge(this, palette);

        refreshStatus();
        appendLog(getString(R.string.log_ready, MODULE_VERSION));

        if (getIntent() != null && getIntent().getBooleanExtra("auto_test", false)) {
            scaffold.root.postDelayed(this::startMockDownload, 400);
        }
    }

    private View buildContent() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        boolean miuix = UiKit.isMiuix();

        // Card 1: live eUICC status
        col.addView(UiKit.createSectionHeader(this, palette, getString(R.string.tester_status_title)));
        LinearLayout statusCard = UiKit.createCard(this, palette);
        statusView = new TextView(this);
        int tilePad = UiKit.dp(this, 14);
        statusView.setPadding(tilePad, UiKit.dp(this, 12), tilePad, UiKit.dp(this, 12));
        statusView.setBackground(UiKit.roundedRect(this, palette.tileBg, UiKit.radius(16f, 12f), 0, 0));
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        statusView.setLineSpacing(UiKit.dpf(this, 4f), 1.0f);
        statusView.setTextColor(palette.onSurface);
        statusCard.addView(statusView);
        col.addView(statusCard);

        // Card 2: mock carrier actions
        col.addView(UiKit.createSectionHeader(this, palette, getString(R.string.tester_mock_title)));
        LinearLayout testCard = UiKit.createCard(this, palette);

        TextView tvSafety = new TextView(this);
        tvSafety.setText(R.string.tester_safety_note);
        tvSafety.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tvSafety.setLineSpacing(UiKit.dpf(this, 2f), 1.0f);
        tvSafety.setTextColor(palette.onSurfaceVariant);
        testCard.addView(tvSafety);

        codeInput = new EditText(this);
        LinearLayout.LayoutParams ciLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ciLp.topMargin = UiKit.dp(this, 12);
        codeInput.setLayoutParams(ciLp);
        codeInput.setText(DEFAULT_MOCK_CODE);
        codeInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        codeInput.setSingleLine(true);
        codeInput.setTypeface(Typeface.MONOSPACE);
        codeInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        codeInput.setTextColor(palette.onSurface);
        codeInput.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 12), UiKit.dp(this, 14), UiKit.dp(this, 12));
        codeInput.setBackground(miuix
                ? UiKit.roundedRect(this, palette.tileBg, 12f, 0, 0)
                : UiKit.roundedRect(this, palette.tileBg, 14f, Math.max(1, UiKit.dp(this, 1)), palette.outlineVariant));
        testCard.addView(codeInput);

        LinearLayout btnReset = UiKit.createActionButton(
                this, palette, UiKit.ButtonStyle.TONAL, 0, getString(R.string.tester_reset_code), 36, () -> {
                    codeInput.setText(DEFAULT_MOCK_CODE);
                    appendLog(getString(R.string.log_reset_code, DEFAULT_MOCK_CODE));
                });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = UiKit.dp(this, 8);
        rlp.gravity = Gravity.END;
        testCard.addView(btnReset, rlp);

        addFullWidth(testCard, UiKit.createActionButton(
                this, palette, UiKit.ButtonStyle.FILLED, R.drawable.ic_science_24,
                getString(R.string.tester_test1), 48, this::startMockDownload), 12);
        addFullWidth(testCard, UiKit.createActionButton(
                this, palette, UiKit.ButtonStyle.TONAL, 0,
                getString(R.string.tester_test2), 44, this::startLpaUriTest), 10);
        addFullWidth(testCard, UiKit.createActionButton(
                this, palette, UiKit.ButtonStyle.OUTLINED, 0,
                getString(R.string.tester_test3), 44, this::startStandardActivationTest), 10);
        col.addView(testCard);

        // Card 3: callback console
        LinearLayout logHdr = new LinearLayout(this);
        logHdr.setOrientation(LinearLayout.HORIZONTAL);
        logHdr.setGravity(Gravity.CENTER_VERTICAL);
        TextView hdr = UiKit.createSectionHeader(this, palette, getString(R.string.tester_console_title));
        LinearLayout.LayoutParams hlp = (LinearLayout.LayoutParams) hdr.getLayoutParams();
        hlp.width = 0;
        hlp.weight = 1f;
        logHdr.addView(hdr);
        LinearLayout btnClear = UiKit.createActionButton(
                this, palette, UiKit.ButtonStyle.TONAL, 0, getString(R.string.tester_clear_log), 32, () -> {
                    logBuffer.setLength(0);
                    logView.setText(getString(R.string.log_cleared) + "\n");
                });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = UiKit.dp(this, 10);
        logHdr.addView(btnClear, clp);
        col.addView(logHdr);

        LinearLayout logCard = UiKit.createCard(this, palette);
        logView = new TextView(this);
        logView.setMinimumHeight(UiKit.dp(this, 120));
        logView.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 12), UiKit.dp(this, 14), UiKit.dp(this, 12));
        logView.setBackground(UiKit.roundedRect(this, 0xFF12151C, UiKit.radius(16f, 12f), 0, 0));
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        logView.setLineSpacing(UiKit.dpf(this, 3f), 1.0f);
        logView.setTextColor(0xFFD8E4F8);
        logView.setTextIsSelectable(true);
        logCard.addView(logView);
        col.addView(logCard);
        return col;
    }

    private void addFullWidth(LinearLayout parent, View child, int topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = UiKit.dp(this, topMarginDp);
        parent.addView(child, lp);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isThemeRecreating()) return;
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(downloadReceiver);
        } catch (Throwable ignored) {
        }
        if (scaffold != null) scaffold.release();
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_RESOLVE) {
            appendLog(getString(resultCode == Activity.RESULT_OK
                    ? R.string.log_auth_result_ok
                    : R.string.log_auth_result_cancel));
        } else if (requestCode == REQ_ACTIVATE) {
            appendLog(getString(R.string.log_activate_result, resultCode));
        }
    }

    private boolean isLpaPrivMounted(PackageManager pm) {
        try {
            ApplicationInfo appInfo = pm.getApplicationInfo(LPA_PKG, 0);
            if (appInfo == null || (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                return false;
            }
            return pm.checkPermission(PERM_BIND_EUICC, LPA_PKG) == PackageManager.PERMISSION_GRANTED
                    && pm.checkPermission(PERM_SE_PRIVILEGED, LPA_PKG) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String describeCardStatus(boolean lpaPrivMounted, TelephonyManager tm) {
        int cardType = Settings.Secure.getInt(getContentResolver(), KEY_CARD_TYPE, -1);
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
                return OK + getString(R.string.card_esim_active);
            }
            if (esimSwitch == 0) {
                return BAD + getString(R.string.card_esim_switch_off);
            }
            return OK + getString(R.string.card_esim_empty);
        }
        if (cardType == 0) {
            if (sim2State == TelephonyManager.SIM_STATE_ABSENT) {
                return BAD + getString(R.string.card_absent);
            }
            return BAD + getString(R.string.card_regular);
        }
        if (!lpaPrivMounted) {
            return BAD + getString(R.string.card_wait_mount);
        }
        return "◌ " + getString(R.string.card_detecting);
    }

    private void refreshStatus() {
        try {
            PackageManager pm = getPackageManager();
            boolean lpaPrivMounted = isLpaPrivMounted(pm);
            boolean hasFeature = pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_EUICC);
            EuiccManager em = getSystemService(EuiccManager.class);
            boolean enabled = em != null && em.isEnabled();
            TelephonyManager tm = getSystemService(TelephonyManager.class);
            int defaultCardId = -2;
            try {
                if (tm != null && GET_DEFAULT_CARD_ID != null) {
                    defaultCardId = (int) GET_DEFAULT_CARD_ID.invoke(tm);
                }
            } catch (Throwable ignored) {
            }
            boolean lpaSchemeOk = pm.resolveActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(DEFAULT_MOCK_CODE)), 0) != null;
            boolean startActOk = pm.resolveActivity(new Intent(EuiccManager.ACTION_START_EUICC_ACTIVATION), 0) != null;
            String cardStatus = describeCardStatus(lpaPrivMounted, tm);

            String text = line(R.string.st_mount, lpaPrivMounted
                            ? OK + getString(R.string.st_mount_ok)
                            : BAD + getString(R.string.st_mount_bad))
                    + line(R.string.st_feature, (hasFeature ? OK : BAD) + hasFeature)
                    + line(R.string.st_enabled, (enabled ? OK : BAD) + enabled)
                    + line(R.string.st_card_id, defaultCardId >= 0
                            ? OK + "cardId = " + defaultCardId
                            : BAD + defaultCardId)
                    + line(R.string.st_card, cardStatus)
                    + line(R.string.st_lpa_scheme, lpaSchemeOk
                            ? OK + getString(R.string.st_lpa_scheme_on)
                            : BAD + getString(R.string.st_lpa_scheme_off))
                    + getString(R.string.st_activation) + (startActOk
                            ? OK + getString(R.string.st_activation_ok)
                            : BAD + getString(R.string.st_activation_bad));
            statusView.setText(text);
        } catch (Throwable t) {
            statusView.setText(getString(R.string.st_error, String.valueOf(t.getMessage())));
        }
    }

    private String line(int labelRes, String value) {
        return getString(labelRes) + value + "\n";
    }

    private void startMockDownload() {
        try {
            if (!isLpaPrivMounted(getPackageManager())) {
                appendLog(getString(R.string.log_err_mount));
                return;
            }
            EuiccManager em = getSystemService(EuiccManager.class);
            if (em == null || !em.isEnabled()) {
                appendLog(getString(R.string.log_err_euicc));
                return;
            }
            String raw = codeInput.getText() != null ? codeInput.getText().toString().trim() : "";
            if (raw.isEmpty()) {
                raw = DEFAULT_MOCK_CODE;
                codeInput.setText(raw);
            }
            appendLog("----------------------------------------");
            appendLog(getString(R.string.log_start_download));
            appendLog(getString(R.string.log_code, raw));
            DownloadableSubscription sub = DownloadableSubscription.forActivationCode(raw);
            Intent cb = new Intent(ACTION_DOWNLOAD_CB).setPackage(getPackageName()).putExtra("phase", "initial");
            PendingIntent pi = PendingIntent.getBroadcast(
                    this,
                    ++requestSeq,
                    cb,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE
            );
            em.downloadSubscription(sub, true, pi);
        } catch (Throwable t) {
            appendLog(getString(R.string.log_call_error, t.getClass().getSimpleName() + ": " + t.getMessage()));
        }
    }

    private void startLpaUriTest() {
        try {
            String raw = codeInput.getText() != null ? codeInput.getText().toString().trim() : DEFAULT_MOCK_CODE;
            if (!raw.regionMatches(true, 0, "LPA:", 0, 4)) {
                raw = "LPA:" + raw;
            }
            appendLog("----------------------------------------");
            appendLog(getString(R.string.log_start_view, raw));
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(raw));
            startActivity(intent);
        } catch (Throwable t) {
            appendLog(getString(R.string.log_view_failed, t.getClass().getSimpleName()));
        }
    }

    @SuppressWarnings("deprecation")
    private void startStandardActivationTest() {
        try {
            appendLog("----------------------------------------");
            appendLog(getString(R.string.log_start_activation));
            Intent intent = new Intent(EuiccManager.ACTION_START_EUICC_ACTIVATION);
            startActivityForResult(intent, REQ_ACTIVATE);
        } catch (Throwable t) {
            appendLog(getString(R.string.log_activation_failed, t.getClass().getSimpleName()));
        }
    }

    private void appendLog(String msg) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
        logBuffer.append("[").append(ts).append("] ").append(msg).append("\n");
        if (logView != null) {
            logView.setText(logBuffer.toString());
        }
        Log.i(TAG, msg);
    }
}

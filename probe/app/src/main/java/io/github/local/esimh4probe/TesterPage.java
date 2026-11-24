package io.github.local.esimh4probe;

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
import io.github.local.esimh4ui.StatusBanner;
import io.github.local.esimh4ui.UiKit;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Test tab: acts as a mock carrier app to verify EuiccManager one-click eSIM installation,
 * LPA:// URI bridging and the authorization dialog safely. Codes on {@code test.esim.local} are
 * stopped by the compat module after the user confirms, so nothing is written to the card.
 *
 * <p>The host activity forwards {@link #onResume}, {@link #release} and
 * {@link #onActivityResult} to this page.
 */
final class TesterPage {
    /** Shows or hides the Test tab; kept in the app's own preferences (no root needed). */
    static final String PREFS = "ui";
    static final String KEY_SHOW_TESTER = "show_tester_tab";

    private static final String TAG = "EsimH4Tester";
    private static final String KEY_CARD_TYPE = "xiaomi_esim_h4_card_type";
    private static final String KEY_ENABLE_ESIM = "is_enable_esim_for_user";
    private static final String PERM_BIND_EUICC = "android.permission.BIND_EUICC_SERVICE";
    private static final String PERM_SE_PRIVILEGED = "android.permission.SECURE_ELEMENT_PRIVILEGED_OPERATION";
    private static final String ACTION_DOWNLOAD_CB = "io.github.local.esimh4probe.ACTION_TESTER_DOWNLOAD_CB";
    private static final String DEFAULT_MOCK_CODE = "LPA:1$test.esim.local$MOCK-TEST-001";
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

    static boolean isEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SHOW_TESTER, true);
    }

    static void setEnabled(Context ctx, boolean enabled) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SHOW_TESTER, enabled).apply();
    }

    private final Activity activity;
    private final UiKit.Palette palette;
    private StatusBanner banner;
    private TextView stMount;
    private TextView stFeature;
    private TextView stEnabled;
    private TextView stCardId;
    private TextView stCard;
    private TextView stLpaScheme;
    private TextView stActivation;
    private EditText codeInput;
    private TextView logView;
    private final StringBuilder logBuffer = new StringBuilder();
    private int requestSeq = 1;
    private boolean receiverRegistered;

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
                    appendLog(str(R.string.log_resolvable));
                    appendLog(str(R.string.log_resolvable_note));
                    try {
                        EuiccManager em = activity.getSystemService(EuiccManager.class);
                        Intent cb = new Intent(ACTION_DOWNLOAD_CB)
                                .setPackage(activity.getPackageName())
                                .putExtra("phase", "resolved");
                        PendingIntent pi = PendingIntent.getBroadcast(
                                activity,
                                ++requestSeq,
                                cb,
                                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE
                        );
                        em.startResolutionActivity(activity, REQ_RESOLVE, intent, pi);
                    } catch (Throwable t) {
                        appendLog(activity.getString(R.string.log_resolution_failed,
                                t.getClass().getSimpleName() + ": " + t.getMessage()));
                    }
                } else if (resultCode == EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_OK) {
                    appendLog(str(R.string.log_direct_ok));
                } else {
                    appendLog(activity.getString(R.string.log_request_error, resultCode, detailed));
                }
            } else {
                if (resultCode == EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_OK) {
                    if (mockIntercepted) {
                        appendLog(str(R.string.log_after_auth_ok));
                        appendLog(str(R.string.log_mock_intercepted));
                    } else {
                        appendLog(str(R.string.log_real_ok));
                    }
                } else {
                    appendLog(activity.getString(R.string.log_after_auth_end, resultCode));
                }
            }
        }
    };

    TesterPage(Activity activity, UiKit.Palette palette) {
        this.activity = activity;
        this.palette = palette;
    }

    private String str(int res) {
        return activity.getString(res);
    }

    /** Builds the page content and starts listening for download callbacks. */
    View build() {
        activity.registerReceiver(downloadReceiver, new IntentFilter(ACTION_DOWNLOAD_CB), Context.RECEIVER_EXPORTED);
        receiverRegistered = true;

        Activity ctx = activity;
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        boolean miuix = UiKit.isMiuix();

        // Red / green readiness banner
        banner = new StatusBanner(ctx, palette);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = UiKit.dp(ctx, 4);
        banner.setLayoutParams(blp);
        banner.showLoading(str(R.string.tester_state_checking));
        banner.setOnClickListener(v -> refreshStatus());
        col.addView(banner);

        // Card 1: live eUICC status, laid out like the home tab's device card
        col.addView(UiKit.createSectionHeader(ctx, palette, str(R.string.tester_status_title)));
        LinearLayout statusCard = UiKit.createCard(ctx, palette);
        stMount = UiKit.addInfoLine(ctx, palette, statusCard, str(R.string.st_mount), "…");
        stFeature = UiKit.addInfoLine(ctx, palette, statusCard, str(R.string.st_feature), "…");
        stEnabled = UiKit.addInfoLine(ctx, palette, statusCard, str(R.string.st_enabled), "…");
        stCardId = UiKit.addInfoLine(ctx, palette, statusCard, str(R.string.st_card_id), "…", true);
        stCard = UiKit.addInfoLine(ctx, palette, statusCard, str(R.string.st_card), "…");
        stLpaScheme = UiKit.addInfoLine(ctx, palette, statusCard, str(R.string.st_lpa_scheme), "…");
        stActivation = UiKit.addInfoLine(ctx, palette, statusCard, str(R.string.st_activation), "…");
        col.addView(statusCard);

        // Card 2: mock carrier actions
        col.addView(UiKit.createSectionHeader(ctx, palette, str(R.string.tester_mock_title)));
        LinearLayout testCard = UiKit.createCard(ctx, palette);

        TextView tvSafety = new TextView(ctx);
        tvSafety.setText(R.string.tester_safety_note);
        tvSafety.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tvSafety.setLineSpacing(UiKit.dpf(ctx, 2f), 1.0f);
        tvSafety.setTextColor(palette.onSurfaceVariant);
        testCard.addView(tvSafety);

        codeInput = new EditText(ctx);
        LinearLayout.LayoutParams ciLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ciLp.topMargin = UiKit.dp(ctx, 12);
        codeInput.setLayoutParams(ciLp);
        codeInput.setText(DEFAULT_MOCK_CODE);
        codeInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        codeInput.setSingleLine(true);
        codeInput.setTypeface(Typeface.MONOSPACE);
        codeInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        codeInput.setTextColor(palette.onSurface);
        codeInput.setPadding(UiKit.dp(ctx, 14), UiKit.dp(ctx, 12), UiKit.dp(ctx, 14), UiKit.dp(ctx, 12));
        codeInput.setBackground(miuix
                ? UiKit.roundedRect(ctx, palette.tileBg, 12f, 0, 0)
                : UiKit.roundedRect(ctx, palette.tileBg, 14f, Math.max(1, UiKit.dp(ctx, 1)), palette.outlineVariant));
        testCard.addView(codeInput);

        addFullWidth(testCard, UiKit.createActionButton(
                ctx, palette, UiKit.ButtonStyle.TONAL, 0, str(R.string.tester_reset_code), 44, () -> {
                    codeInput.setText(DEFAULT_MOCK_CODE);
                    appendLog(activity.getString(R.string.log_reset_code, DEFAULT_MOCK_CODE));
                }), 10);

        addFullWidth(testCard, UiKit.createActionButton(
                ctx, palette, UiKit.ButtonStyle.FILLED, R.drawable.ic_science_24,
                str(R.string.tester_test1), 48, this::startMockDownload), 12);
        addFullWidth(testCard, UiKit.createActionButton(
                ctx, palette, UiKit.ButtonStyle.TONAL, 0,
                str(R.string.tester_test2), 44, this::startLpaUriTest), 10);
        addFullWidth(testCard, UiKit.createActionButton(
                ctx, palette, UiKit.ButtonStyle.OUTLINED, 0,
                str(R.string.tester_test3), 44, this::startStandardActivationTest), 10);
        col.addView(testCard);

        // Card 3: callback console
        // Header row: section title on the left, Clear button on the right, with room
        // above and below so the button does not touch the log card.
        LinearLayout logHdr = new LinearLayout(ctx);
        logHdr.setOrientation(LinearLayout.HORIZONTAL);
        logHdr.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = UiKit.dp(ctx, 14);
        rowLp.bottomMargin = UiKit.dp(ctx, 10);
        logHdr.setLayoutParams(rowLp);
        TextView hdr = UiKit.createSectionHeader(ctx, palette, str(R.string.tester_console_title));
        LinearLayout.LayoutParams hlp = (LinearLayout.LayoutParams) hdr.getLayoutParams();
        hlp.width = 0;
        hlp.weight = 1f;
        hlp.topMargin = 0;
        hlp.bottomMargin = 0;
        logHdr.addView(hdr);
        LinearLayout btnClear = UiKit.createActionButton(
                ctx, palette, UiKit.ButtonStyle.TONAL, 0, str(R.string.tester_clear_log), 34, () -> {
                    logBuffer.setLength(0);
                    logView.setText(str(R.string.log_cleared) + "\n");
                });
        btnClear.setPadding(UiKit.dp(ctx, 18), btnClear.getPaddingTop(), UiKit.dp(ctx, 18), btnClear.getPaddingBottom());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.rightMargin = UiKit.dp(ctx, 4);
        logHdr.addView(btnClear, clp);
        col.addView(logHdr);

        LinearLayout logCard = UiKit.createCard(ctx, palette);
        logView = new TextView(ctx);
        logView.setMinimumHeight(UiKit.dp(ctx, 120));
        logView.setPadding(UiKit.dp(ctx, 14), UiKit.dp(ctx, 12), UiKit.dp(ctx, 14), UiKit.dp(ctx, 12));
        logView.setBackground(UiKit.roundedRect(ctx, 0xFF12151C, UiKit.radius(16f, 12f), 0, 0));
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        logView.setLineSpacing(UiKit.dpf(ctx, 3f), 1.0f);
        logView.setTextColor(0xFFD8E4F8);
        logView.setTextIsSelectable(true);
        logCard.addView(logView);
        col.addView(logCard);

        refreshStatus();
        appendLog(activity.getString(R.string.log_ready, ProbeInspector.MODULE_VERSION));
        return col;
    }

    private void addFullWidth(LinearLayout parent, View child, int topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = UiKit.dp(activity, topMarginDp);
        parent.addView(child, lp);
    }

    void onResume() {
        refreshStatus();
    }

    /** Call from the host's {@code onDestroy}. */
    void release() {
        if (!receiverRegistered) return;
        receiverRegistered = false;
        try {
            activity.unregisterReceiver(downloadReceiver);
        } catch (Throwable ignored) {
        }
    }

    /** Returns true when the result belongs to this page. */
    boolean onActivityResult(int requestCode, int resultCode) {
        if (requestCode == REQ_RESOLVE) {
            appendLog(str(resultCode == Activity.RESULT_OK
                    ? R.string.log_auth_result_ok
                    : R.string.log_auth_result_cancel));
            return true;
        } else if (requestCode == REQ_ACTIVATE) {
            appendLog(activity.getString(R.string.log_activate_result, resultCode));
            return true;
        }
        return false;
    }

    private boolean isLpaPrivMounted(PackageManager pm) {
        try {
            ApplicationInfo appInfo = pm.getApplicationInfo(ProbeInspector.LPA_PKG, 0);
            if (appInfo == null || (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                return false;
            }
            return pm.checkPermission(PERM_BIND_EUICC, ProbeInspector.LPA_PKG) == PackageManager.PERMISSION_GRANTED
                    && pm.checkPermission(PERM_SE_PRIVILEGED, ProbeInspector.LPA_PKG) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String describeCardStatus(boolean lpaPrivMounted, TelephonyManager tm) {
        int cardType = Settings.Secure.getInt(activity.getContentResolver(), KEY_CARD_TYPE, -1);
        int esimSwitch = Settings.Secure.getInt(activity.getContentResolver(), KEY_ENABLE_ESIM, -1);
        int sim2State = TelephonyManager.SIM_STATE_UNKNOWN;
        if (tm != null) {
            try {
                sim2State = tm.getSimState(1);
            } catch (Throwable ignored) {
            }
        }
        if (cardType == 1) {
            if (sim2State == TelephonyManager.SIM_STATE_READY || sim2State == 10) {
                return str(R.string.card_esim_active);
            }
            return str(esimSwitch == 0 ? R.string.card_esim_switch_off : R.string.card_esim_empty);
        }
        if (cardType == 0) {
            return str(sim2State == TelephonyManager.SIM_STATE_ABSENT ? R.string.card_absent : R.string.card_regular);
        }
        return str(lpaPrivMounted ? R.string.card_detecting : R.string.card_wait_mount);
    }

    /** Sets a status value; green when fine, red when not. */
    private void setStatus(TextView tv, String text, boolean ok) {
        tv.setText(text);
        tv.setTextColor(ok ? (UiKit.isMiuix() ? palette.success : palette.onSurface) : palette.error);
    }

    void refreshStatus() {
        if (banner == null) return;
        try {
            PackageManager pm = activity.getPackageManager();
            boolean lpaPrivMounted = isLpaPrivMounted(pm);
            boolean hasFeature = pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_EUICC);
            EuiccManager em = activity.getSystemService(EuiccManager.class);
            boolean enabled = em != null && em.isEnabled();
            TelephonyManager tm = activity.getSystemService(TelephonyManager.class);
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
            int cardType = Settings.Secure.getInt(activity.getContentResolver(), KEY_CARD_TYPE, -1);
            String version = defaultCardId >= 0
                    ? ProbeInspector.MODULE_VERSION + " · cardId " + defaultCardId
                    : ProbeInspector.MODULE_VERSION;
            if (!lpaPrivMounted) {
                banner.showError(str(R.string.tester_reason_mount), str(R.string.tester_state_not_ready),
                        str(R.string.tester_refresh), this::refreshStatus);
            } else if (!enabled) {
                banner.showError(str(R.string.tester_reason_euicc), str(R.string.tester_state_not_ready),
                        str(R.string.tester_refresh), this::refreshStatus);
            } else if (cardType != 1) {
                banner.showError(str(R.string.tester_reason_card), str(R.string.tester_state_not_ready),
                        str(R.string.tester_refresh), this::refreshStatus);
            } else {
                List<StatusBanner.Tag> tags = new ArrayList<>();
                tags.add(new StatusBanner.Tag(str(R.string.tag_sim2_esim), true));
                tags.add(new StatusBanner.Tag("LPA", false));
                tags.add(new StatusBanner.Tag("eUICC", false));
                banner.showOk(str(R.string.tester_state_ready), version, tags);
            }

            setStatus(stMount, str(lpaPrivMounted ? R.string.st_mount_ok : R.string.st_mount_bad), lpaPrivMounted);
            setStatus(stFeature, str(hasFeature ? R.string.st_feature_yes : R.string.st_feature_no), hasFeature);
            setStatus(stEnabled, str(enabled ? R.string.st_enabled_yes : R.string.st_enabled_no), enabled);
            setStatus(stCardId, defaultCardId >= 0 ? String.valueOf(defaultCardId) : "—", defaultCardId >= 0);
            setStatus(stCard, cardStatus, cardType == 1);
            setStatus(stLpaScheme, str(lpaSchemeOk ? R.string.st_lpa_scheme_on : R.string.st_lpa_scheme_off), lpaSchemeOk);
            setStatus(stActivation, str(startActOk ? R.string.st_activation_ok : R.string.st_activation_bad), startActOk);
        } catch (Throwable t) {
            setStatus(stMount, activity.getString(R.string.st_error, String.valueOf(t.getMessage())), false);
        }
    }

    void startMockDownload() {
        try {
            if (!isLpaPrivMounted(activity.getPackageManager())) {
                appendLog(str(R.string.log_err_mount));
                return;
            }
            EuiccManager em = activity.getSystemService(EuiccManager.class);
            if (em == null || !em.isEnabled()) {
                appendLog(str(R.string.log_err_euicc));
                return;
            }
            String raw = codeInput.getText() != null ? codeInput.getText().toString().trim() : "";
            if (raw.isEmpty()) {
                raw = DEFAULT_MOCK_CODE;
                codeInput.setText(raw);
            }
            appendLog("----------------------------------------");
            appendLog(str(R.string.log_start_download));
            appendLog(activity.getString(R.string.log_code, raw));
            DownloadableSubscription sub = DownloadableSubscription.forActivationCode(raw);
            Intent cb = new Intent(ACTION_DOWNLOAD_CB).setPackage(activity.getPackageName()).putExtra("phase", "initial");
            PendingIntent pi = PendingIntent.getBroadcast(
                    activity,
                    ++requestSeq,
                    cb,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE
            );
            em.downloadSubscription(sub, true, pi);
        } catch (Throwable t) {
            appendLog(activity.getString(R.string.log_call_error, t.getClass().getSimpleName() + ": " + t.getMessage()));
        }
    }

    private void startLpaUriTest() {
        try {
            String raw = codeInput.getText() != null ? codeInput.getText().toString().trim() : DEFAULT_MOCK_CODE;
            if (!raw.regionMatches(true, 0, "LPA:", 0, 4)) {
                raw = "LPA:" + raw;
            }
            appendLog("----------------------------------------");
            appendLog(activity.getString(R.string.log_start_view, raw));
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(raw));
            activity.startActivity(intent);
        } catch (Throwable t) {
            appendLog(activity.getString(R.string.log_view_failed, t.getClass().getSimpleName()));
        }
    }

    @SuppressWarnings("deprecation")
    private void startStandardActivationTest() {
        try {
            appendLog("----------------------------------------");
            appendLog(str(R.string.log_start_activation));
            Intent intent = new Intent(EuiccManager.ACTION_START_EUICC_ACTIVATION);
            activity.startActivityForResult(intent, REQ_ACTIVATE);
        } catch (Throwable t) {
            appendLog(activity.getString(R.string.log_activation_failed, t.getClass().getSimpleName()));
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

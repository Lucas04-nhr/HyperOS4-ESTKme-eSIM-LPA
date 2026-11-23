package io.github.local.esimh4ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * SukiSU-style status card: green when working, red when something is off or needs attention,
 * neutral while loading. Large state icon, title, summary, a monospace version line and a faded
 * glyph in the corner.
 */
public final class StatusBanner extends FrameLayout {
    public enum State {
        LOADING,
        OK,
        ERROR
    }

    private final UiKit.Palette palette;
    private final FrameLayout iconWrap;
    private final ImageView icon;
    private final ImageView decor;
    private final TextView title;
    private final TextView summary;
    private final TextView footer;

    public StatusBanner(Context ctx, UiKit.Palette p) {
        super(ctx);
        this.palette = p;
        boolean miuix = UiKit.isMiuix();
        setMinimumHeight(UiKit.dp(ctx, 120));
        setClipToOutline(true);

        decor = new ImageView(ctx);
        int decorPx = UiKit.dp(ctx, 132);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(decorPx, decorPx, Gravity.END | Gravity.BOTTOM);
        dlp.rightMargin = -UiKit.dp(ctx, 28);
        dlp.bottomMargin = -UiKit.dp(ctx, 34);
        decor.setAlpha(0.13f);
        addView(decor, dlp);

        LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        int pad = UiKit.dp(ctx, 20);
        content.setPadding(pad, UiKit.dp(ctx, 22), pad, UiKit.dp(ctx, 22));
        addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

        iconWrap = new FrameLayout(ctx);
        int wrap = UiKit.dp(ctx, 56);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(wrap, wrap);
        wlp.rightMargin = UiKit.dp(ctx, 18);
        content.addView(iconWrap, wlp);
        icon = new ImageView(ctx);
        int iconPx = UiKit.dp(ctx, miuix ? 40 : 30);
        iconWrap.addView(icon, new FrameLayout.LayoutParams(iconPx, iconPx, Gravity.CENTER));

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        content.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        title = new TextView(ctx);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, miuix ? 22f : 21f);
        title.setTypeface(Typeface.create("sans-serif-medium", miuix ? Typeface.NORMAL : Typeface.BOLD));
        texts.addView(title);

        summary = new TextView(ctx);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = UiKit.dp(ctx, 4);
        summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        summary.setLineSpacing(UiKit.dpf(ctx, 2f), 1.0f);
        texts.addView(summary, slp);

        footer = new TextView(ctx);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = UiKit.dp(ctx, 6);
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        footer.setTypeface(Typeface.MONOSPACE);
        texts.addView(footer, flp);
    }

    public void setTexts(CharSequence titleText, CharSequence summaryText, CharSequence footerText) {
        title.setText(titleText);
        summary.setText(summaryText);
        footer.setText(footerText);
    }

    public void setState(State state, int iconRes) {
        boolean miuix = UiKit.isMiuix();
        int bg;
        int fg;
        switch (state) {
            case OK:
                bg = miuix ? palette.success : palette.successContainer;
                fg = miuix ? 0xFFFFFFFF : palette.onSuccessContainer;
                break;
            case ERROR:
                bg = miuix ? palette.error : palette.errorContainer;
                fg = miuix ? 0xFFFFFFFF : palette.onErrorContainer;
                break;
            case LOADING:
            default:
                bg = miuix ? palette.cardBg : palette.surfaceHigh;
                fg = palette.onSurface;
                break;
        }
        setBackground(UiKit.roundedRect(getContext(), bg, UiKit.radius(28f, 16f), 0, 0));
        title.setTextColor(fg);
        summary.setTextColor(UiKit.withAlpha(fg, 0xDB));
        footer.setTextColor(UiKit.withAlpha(fg, 0xB0));
        icon.setImageResource(iconRes);
        icon.setImageTintList(ColorStateList.valueOf(fg));
        decor.setImageResource(iconRes);
        decor.setImageTintList(ColorStateList.valueOf(fg));
        // Material 3 puts the icon on a tonal circle; Miuix shows the bare glyph.
        iconWrap.setBackground(miuix ? null : UiKit.oval(UiKit.withAlpha(fg, 0x1A)));
    }
}

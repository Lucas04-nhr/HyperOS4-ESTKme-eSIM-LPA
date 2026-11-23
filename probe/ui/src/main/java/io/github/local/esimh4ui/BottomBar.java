package io.github.local.esimh4ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Bottom navigation bar.
 *
 * <ul>
 *   <li>Material 3: 80dp navigation bar with a pill indicator behind the selected icon.</li>
 *   <li>Miuix: 64dp bar; optionally a floating capsule (SukiSU "floating bottom bar") and/or a
 *       frosted glass background (see {@link GlassLinearLayout}).</li>
 * </ul>
 */
public final class BottomBar extends GlassLinearLayout {
    public interface OnSelectListener {
        void onSelect(int index);
    }

    private final boolean miuix;
    private final boolean floating;
    private final LinearLayout[] items;
    private final ImageView[] icons;
    private final TextView[] labels;
    private final FrameLayout[] indicators;
    private OnSelectListener listener;
    private int selected = -1;

    public BottomBar(Context ctx, UiKit.Palette p, String[] titles, int[] iconRes) {
        super(ctx, p);
        this.miuix = UiKit.isMiuix();
        this.floating = miuix && ThemeConfig.barFloating;
        boolean glass = miuix && ThemeConfig.barGlass;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER);
        setClickable(true); // swallow touches so they do not reach the page underneath

        int n = titles.length;
        items = new LinearLayout[n];
        icons = new ImageView[n];
        labels = new TextView[n];
        indicators = new FrameLayout[n];
        for (int i = 0; i < n; i++) {
            buildItem(ctx, i, titles[i], iconRes[i]);
        }

        if (floating) {
            setPadding(UiKit.dp(ctx, 6), UiKit.dp(ctx, 6), UiKit.dp(ctx, 6), UiKit.dp(ctx, 6));
            setElevation(UiKit.dpf(ctx, 8f));
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), view.getHeight() / 2f);
                }
            });
            setClipToOutline(true);
        }
        int solid = barColor() | 0xFF000000;
        if (glass) {
            setGlass(true, UiKit.withAlpha(barColor(), p.isNight ? 0xA6 : 0xB8), solid);
            setLiquid(ThemeConfig.liquidGlass, floating, true);
        } else {
            onGlassFailed(solid);
        }
    }

    @Override
    protected void onGlassFailed(int fallback) {
        setBackground(floating
                ? UiKit.roundedRect(getContext(), fallback, 999f, 0, 0)
                : new ColorDrawable(fallback));
    }

    private int barColor() {
        if (miuix) return palette.surfaceHigh;
        return palette.tileBg;
    }

    /** Height of the bar's content, without the navigation bar inset. */
    public int contentHeightPx() {
        Context ctx = getContext();
        if (floating) return UiKit.dp(ctx, 64);
        return UiKit.dp(ctx, miuix ? 64 : 80);
    }

    public boolean isFloating() {
        return floating;
    }

    private void buildItem(Context ctx, int index, String title, int iconRes) {
        LinearLayout item = new LinearLayout(ctx);
        item.setOrientation(VERTICAL);
        item.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = floating
                ? new LinearLayout.LayoutParams(UiKit.dp(ctx, 96), ViewGroup.LayoutParams.MATCH_PARENT)
                : new LinearLayout.LayoutParams(0, contentHeightPx(), 1f);
        item.setLayoutParams(lp);
        item.setClickable(true);
        item.setFocusable(true);
        item.setContentDescription(title);
        item.setOnClickListener(v -> {
            if (index != selected) {
                select(index);
                if (listener != null) listener.onSelect(index);
            }
        });

        FrameLayout indicator = new FrameLayout(ctx);
        int iconPx = UiKit.dp(ctx, miuix ? 26 : 24);
        LinearLayout.LayoutParams ilp = miuix
                ? new LinearLayout.LayoutParams(iconPx, iconPx)
                : new LinearLayout.LayoutParams(UiKit.dp(ctx, 64), UiKit.dp(ctx, 32));
        indicator.setLayoutParams(ilp);
        ImageView icon = new ImageView(ctx);
        icon.setImageResource(iconRes);
        indicator.addView(icon, new FrameLayout.LayoutParams(
                UiKit.dp(ctx, 24), UiKit.dp(ctx, 24), Gravity.CENTER));
        item.addView(indicator);

        TextView label = new TextView(ctx);
        label.setText(title);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setMaxLines(1);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = UiKit.dp(ctx, miuix ? 2 : 4);
        item.addView(label, tlp);

        items[index] = item;
        icons[index] = icon;
        labels[index] = label;
        indicators[index] = indicator;
        addView(item);
    }

    public void setOnSelectListener(OnSelectListener l) {
        this.listener = l;
    }

    /** Highlights {@code index} without notifying the listener. */
    public void select(int index) {
        selected = index;
        Context ctx = getContext();
        for (int i = 0; i < items.length; i++) {
            boolean sel = i == index;
            if (miuix) {
                int fg = sel ? (floating ? palette.primary : palette.onSurface) : palette.onSurfaceVariant;
                items[i].setBackground(floating && sel
                        ? UiKit.roundedRect(ctx, UiKit.withAlpha(palette.primary, 0x1F), 999f, 0, 0)
                        : null);
                labels[i].setTextColor(fg);
                icons[i].setImageTintList(ColorStateList.valueOf(fg));
            } else {
                indicators[i].setBackground(sel
                        ? UiKit.roundedRect(ctx, palette.secondaryContainer, 16f, 0, 0)
                        : UiKit.roundedRect(ctx, Color.TRANSPARENT, 16f, 0, 0));
                icons[i].setImageTintList(ColorStateList.valueOf(
                        sel ? palette.onSecondaryContainer : palette.onSurfaceVariant));
                labels[i].setTextColor(sel ? palette.onSurface : palette.onSurfaceVariant);
            }
            labels[i].setTypeface(Typeface.create("sans-serif-medium", sel ? Typeface.BOLD : Typeface.NORMAL));
        }
    }
}

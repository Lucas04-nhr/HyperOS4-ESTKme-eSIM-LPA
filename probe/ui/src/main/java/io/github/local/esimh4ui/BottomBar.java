package io.github.local.esimh4ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
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
 *       frosted glass background that blurs the page scrolling underneath.</li>
 * </ul>
 *
 * Glass works by redrawing the page ({@code source}) into a 1/4-scale bitmap whenever the page
 * changed, then drawing that bitmap through a {@link RenderEffect} blur behind a translucent tint.
 */
public final class BottomBar extends LinearLayout {
    public interface OnSelectListener {
        void onSelect(int index);
    }

    private static final int CAPTURE_SCALE = 4;

    private final UiKit.Palette palette;
    private final boolean miuix;
    private final boolean glass;
    private final boolean floating;
    private final LinearLayout[] items;
    private final ImageView[] icons;
    private final TextView[] labels;
    private final FrameLayout[] indicators;
    private OnSelectListener listener;
    private int selected = -1;

    private View source;
    private Bitmap captureBitmap;
    private Canvas captureCanvas;
    private RenderNode blurNode;
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final int[] srcLoc = new int[2];
    private final int[] selfLoc = new int[2];
    private boolean captureFailed = false;

    private final ViewTreeObserver.OnPreDrawListener preDrawListener = this::onPreDrawCapture;

    /** Re-captures the page only when it changed since the last frame. */
    private boolean onPreDrawCapture() {
        if (glass && source != null && !captureFailed && (source.isDirty() || captureBitmap == null)) {
            capture();
            invalidate();
        }
        return true;
    }

    public BottomBar(Context ctx, UiKit.Palette p, String[] titles, int[] iconRes) {
        super(ctx);
        this.palette = p;
        this.miuix = UiKit.isMiuix();
        this.glass = miuix && ThemeConfig.barGlass;
        this.floating = miuix && ThemeConfig.barFloating;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER);
        setWillNotDraw(false);
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
        if (!glass) {
            int solid = barColor() | 0xFF000000;
            setBackground(floating
                    ? UiKit.roundedRect(ctx, solid, 999f, 0, 0)
                    : new android.graphics.drawable.ColorDrawable(solid));
        }
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

    public void select(int index) {
        selected = index;
        Context ctx = getContext();
        for (int i = 0; i < items.length; i++) {
            boolean sel = i == index;
            int fg;
            if (miuix) {
                fg = sel ? (floating ? palette.primary : palette.onSurface) : palette.onSurfaceVariant;
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

    /** Sets the view whose content is blurred behind the bar in glass mode. */
    public void setBlurSource(View source) {
        this.source = source;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnPreDrawListener(preDrawListener);
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(preDrawListener);
        if (captureBitmap != null) {
            captureBitmap.recycle();
            captureBitmap = null;
        }
        super.onDetachedFromWindow();
    }

    private void capture() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        int bw = Math.max(1, w / CAPTURE_SCALE);
        int bh = Math.max(1, h / CAPTURE_SCALE);
        if (captureBitmap == null || captureBitmap.getWidth() != bw || captureBitmap.getHeight() != bh) {
            if (captureBitmap != null) captureBitmap.recycle();
            captureBitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            captureCanvas = new Canvas(captureBitmap);
        }
        captureBitmap.eraseColor(palette.pageBg | 0xFF000000);
        source.getLocationInWindow(srcLoc);
        getLocationInWindow(selfLoc);
        int save = captureCanvas.save();
        try {
            captureCanvas.scale(bw / (float) w, bh / (float) h);
            captureCanvas.translate(srcLoc[0] - selfLoc[0], srcLoc[1] - selfLoc[1]);
            source.draw(captureCanvas);
        } catch (Throwable t) {
            // Some content cannot be drawn in software; fall back to the solid style.
            captureFailed = true;
            setBackground(new android.graphics.drawable.ColorDrawable(barColor() | 0xFF000000));
        } finally {
            captureCanvas.restoreToCount(save);
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (glass && !captureFailed && captureBitmap != null) {
            int w = getWidth();
            int h = getHeight();
            float sx = w / (float) captureBitmap.getWidth();
            float sy = h / (float) captureBitmap.getHeight();
            if (canvas.isHardwareAccelerated()) {
                if (blurNode == null) {
                    blurNode = new RenderNode("BottomBarGlass");
                    float r = UiKit.dpf(getContext(), 24f);
                    blurNode.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP));
                }
                blurNode.setPosition(0, 0, w, h);
                RecordingCanvas rc = blurNode.beginRecording(w, h);
                rc.scale(sx, sy);
                rc.drawBitmap(captureBitmap, 0f, 0f, bitmapPaint);
                blurNode.endRecording();
                canvas.drawRenderNode(blurNode);
            } else {
                int save = canvas.save();
                canvas.scale(sx, sy);
                canvas.drawBitmap(captureBitmap, 0f, 0f, bitmapPaint);
                canvas.restoreToCount(save);
            }
            // Frosted tint on top of the blurred page.
            canvas.drawColor(UiKit.withAlpha(barColor(), palette.isNight ? 0xA6 : 0xB8));
        }
        super.dispatchDraw(canvas);
    }
}

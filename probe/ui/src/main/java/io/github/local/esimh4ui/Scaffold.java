package io.github.local.esimh4ui;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * Standard page frame shared by all screens.
 *
 * <pre>
 * root
 *  ├─ stage (blurred by the bars): background image + swipeable pages
 *  ├─ header (overlay): top bar + progress line, frosted glass in Miuix
 *  ├─ bottom bar (overlay, optional)
 *  └─ snackbar
 * </pre>
 */
public final class Scaffold {
    public final FrameLayout root;
    /** Background + pages; this is what the glass bars blur. */
    public final FrameLayout stage;
    public final BackgroundLayer background;
    public final GlassLinearLayout header;
    public final LinearLayout topBar;
    public final TextView titleView;
    public final TextView subtitleView;
    public final PagerHost pager;

    private final Activity activity;
    private final LinearLayout actions;
    private final ProgressLine progress;
    private final TextView snackView;
    private final List<ScrollView> pages = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable hideSnack;
    private BottomBar bottomBar;
    private Insets bars = Insets.NONE;
    private int headerHeight = 0;

    public Scaffold(Activity activity, UiKit.Palette p, String title, String subtitle, boolean showBack) {
        this.activity = activity;
        boolean miuix = UiKit.isMiuix();

        root = new FrameLayout(activity);
        root.setBackgroundColor(p.pageBg);

        stage = new FrameLayout(activity);
        stage.setBackgroundColor(p.pageBg);
        root.addView(stage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        background = BackgroundLayer.createIfSet(activity, p);
        if (background != null) {
            stage.addView(background);
        }

        pager = new PagerHost(activity);
        stage.addView(pager, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Header overlay: pages scroll underneath it.
        header = new GlassLinearLayout(activity, p);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setClickable(true);
        int headerSolid = p.pageBg | 0xFF000000;
        if (miuix && ThemeConfig.barGlass) {
            header.setGlass(true, UiKit.withAlpha(p.pageBg, p.isNight ? 0xB3 : 0xC7), headerSolid);
            header.setBlurSource(stage);
            header.setLiquid(ThemeConfig.liquidGlass, false, false);
        } else {
            header.setBackgroundColor(headerSolid);
        }
        root.addView(header, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        header.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            if (h != headerHeight) {
                headerHeight = h;
                header.post(this::applyInsets);
            }
        });

        topBar = new LinearLayout(activity);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (showBack) {
            ImageView back = UiKit.createIconButton(
                    activity, p, R.drawable.ic_arrow_back_24, activity.getString(R.string.ui_back), activity::finish);
            LinearLayout.LayoutParams blp = (LinearLayout.LayoutParams) back.getLayoutParams();
            blp.rightMargin = UiKit.dp(activity, 8);
            blp.leftMargin = -UiKit.dp(activity, 6);
            topBar.addView(back);
        }

        LinearLayout titleWrap = new LinearLayout(activity);
        titleWrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams twLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        twLp.rightMargin = UiKit.dp(activity, 10);
        topBar.addView(titleWrap, twLp);

        titleView = new TextView(activity);
        titleView.setText(title);
        titleView.setTextColor(p.onSurface);
        titleWrap.addView(titleView);
        if (miuix) {
            titleView.setSingleLine(true);
            titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f);
            titleView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (r - l != or - ol) fitTitle();
            });
        } else {
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, showBack ? 21f : 19f);
            titleView.setTypeface(Typeface.create("sans-serif-medium", showBack ? Typeface.NORMAL : Typeface.BOLD));
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
        }

        subtitleView = new TextView(activity);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.topMargin = UiKit.dp(activity, 3);
        subtitleView.setLayoutParams(vlp);
        if (miuix) {
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            subtitleView.setTextColor(p.onSurfaceVariant);
        } else {
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            subtitleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            subtitleView.setTextColor(p.onSecondaryContainer);
            subtitleView.setPadding(UiKit.dp(activity, 8), UiKit.dp(activity, 2), UiKit.dp(activity, 8), UiKit.dp(activity, 2));
            subtitleView.setBackground(UiKit.roundedRect(activity, p.secondaryContainer, 999f, 0, 0));
        }
        titleWrap.addView(subtitleView);
        setSubtitle(subtitle);

        actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        topBar.addView(actions);

        progress = new ProgressLine(activity, p);
        progress.setVisibility(View.INVISIBLE);
        header.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(activity, 3)));

        // Snackbar
        snackView = new TextView(activity);
        FrameLayout.LayoutParams snackLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        snackLp.leftMargin = UiKit.dp(activity, 18);
        snackLp.rightMargin = UiKit.dp(activity, 18);
        snackView.setLayoutParams(snackLp);
        snackView.setPadding(UiKit.dp(activity, 16), UiKit.dp(activity, 13), UiKit.dp(activity, 16), UiKit.dp(activity, 13));
        snackView.setBackground(UiKit.roundedRect(activity, p.inverseSurface, UiKit.radius(14f, 16f), 0, 0));
        snackView.setTextColor(p.inverseOnSurface);
        snackView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        snackView.setElevation(UiKit.dpf(activity, 6f));
        snackView.setVisibility(View.GONE);
        root.addView(snackView);

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            bars = insets.getInsets(WindowInsets.Type.systemBars());
            applyInsets();
            return WindowInsets.CONSUMED;
        });
    }

    private void fitTitle() {
        float avail = titleView.getWidth() - titleView.getPaddingLeft() - titleView.getPaddingRight();
        if (avail <= 0) return;
        TextPaint tp = new TextPaint(titleView.getPaint());
        String text = titleView.getText().toString();
        float sizeSp = 28f;
        while (sizeSp > 16f) {
            tp.setTextSize(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, sizeSp, activity.getResources().getDisplayMetrics()));
            if (tp.measureText(text) <= avail) break;
            sizeSp -= 1f;
        }
        final float fit = sizeSp;
        titleView.post(() -> titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, fit));
    }

    public void setTitle(String title) {
        titleView.setText(title);
        if (UiKit.isMiuix()) {
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f);
            titleView.post(this::fitTitle);
        }
    }

    public void setSubtitle(String subtitle) {
        subtitleView.setText(subtitle);
        subtitleView.setVisibility(TextUtils.isEmpty(subtitle) ? View.GONE : View.VISIBLE);
    }

    public void addAction(View action) {
        if (actions.getChildCount() > 0) {
            ((LinearLayout.LayoutParams) action.getLayoutParams()).leftMargin = UiKit.dp(activity, 8);
        }
        actions.addView(action);
    }

    public void setActionsVisible(boolean visible) {
        actions.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /** Adds a scrolling page. A stable {@code scrollId} lets Android restore the scroll position. */
    public ScrollView addPage(View content, int scrollId) {
        ScrollView scroll = new ScrollView(activity);
        scroll.setId(scrollId);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        pager.addView(scroll, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pages.add(scroll);
        applyInsets();
        return scroll;
    }

    public void showPage(int index, boolean animate) {
        pager.setPage(index, animate);
    }

    public void setOnPageChangeListener(PagerHost.OnPageChangeListener l) {
        pager.setOnPageChangeListener(l);
    }

    public void setBottomBar(BottomBar bar) {
        this.bottomBar = bar;
        bar.setBlurSource(stage);
        FrameLayout.LayoutParams lp = bar.isFloating()
                ? new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, bar.contentHeightPx(),
                        Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL)
                : new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, bar.contentHeightPx(), Gravity.BOTTOM);
        root.addView(bar, root.indexOfChild(snackView), lp);
        applyInsets();
    }

    /** Space taken at the bottom by the bottom bar (without the system navigation inset). */
    private int bottomBarSpace() {
        if (bottomBar == null) return 0;
        return bottomBar.contentHeightPx() + (bottomBar.isFloating() ? UiKit.dp(activity, 16) : 0);
    }

    private void applyInsets() {
        Context ctx = activity;
        int side = UiKit.dp(ctx, 18);
        int vert = UiKit.dp(ctx, 12);
        topBar.setPadding(side + bars.left, vert + bars.top, side + bars.right, vert);

        int pageSide = UiKit.dp(ctx, 16);
        int pageTop = headerHeight + UiKit.dp(ctx, 4);
        int pageBottom = UiKit.dp(ctx, 28) + bars.bottom + bottomBarSpace();
        for (ScrollView page : pages) {
            if (page.getPaddingTop() != pageTop || page.getPaddingBottom() != pageBottom
                    || page.getPaddingLeft() != pageSide + bars.left) {
                page.setPadding(pageSide + bars.left, pageTop, pageSide + bars.right, pageBottom);
            }
        }

        if (bottomBar != null) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) bottomBar.getLayoutParams();
            if (bottomBar.isFloating()) {
                lp.height = bottomBar.contentHeightPx();
                lp.bottomMargin = bars.bottom + UiKit.dp(ctx, 12);
                bottomBar.setPadding(bottomBar.getPaddingLeft(), bottomBar.getPaddingTop(),
                        bottomBar.getPaddingRight(), bottomBar.getPaddingTop());
            } else {
                lp.height = bottomBar.contentHeightPx() + bars.bottom;
                bottomBar.setPadding(bars.left, 0, bars.right, bars.bottom);
            }
            bottomBar.setLayoutParams(lp);
        }

        FrameLayout.LayoutParams slp = (FrameLayout.LayoutParams) snackView.getLayoutParams();
        slp.bottomMargin = UiKit.dp(ctx, 20) + bars.bottom + bottomBarSpace();
        snackView.setLayoutParams(slp);
    }

    public void setBusy(boolean busy) {
        if (busy) {
            progress.setVisibility(View.VISIBLE);
            progress.start();
        } else {
            progress.stop();
            progress.setVisibility(View.INVISIBLE);
        }
    }

    public void showSnack(String msg) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (hideSnack != null) handler.removeCallbacks(hideSnack);
        snackView.setText(msg);
        if (snackView.getVisibility() != View.VISIBLE) {
            snackView.setAlpha(0f);
            snackView.setTranslationY(UiKit.dpf(activity, 14f));
            snackView.setVisibility(View.VISIBLE);
        }
        snackView.animate().alpha(1f).translationY(0f).setDuration(180L)
                .setInterpolator(UiKit.STANDARD_EASING).start();
        hideSnack = () -> snackView.animate().alpha(0f).translationY(UiKit.dpf(activity, 10f))
                .setDuration(160L).withEndAction(() -> snackView.setVisibility(View.GONE)).start();
        handler.postDelayed(hideSnack, 2600L);
    }

    /** Call from {@code onDestroy}. */
    public void release() {
        progress.stop();
        handler.removeCallbacksAndMessages(null);
    }

    /** Thin 3dp indeterminate progress line. */
    private static final class ProgressLine extends View {
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private ValueAnimator anim;
        private float phase = 0f;

        ProgressLine(Context ctx, UiKit.Palette p) {
            super(ctx);
            trackPaint.setColor(UiKit.isMiuix() ? p.surfaceHighest : p.secondaryContainer);
            barPaint.setColor(p.primary);
        }

        void start() {
            if (anim != null && anim.isRunning()) return;
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(1050L);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setInterpolator(new LinearInterpolator());
            anim.addUpdateListener(a -> {
                phase = (float) a.getAnimatedValue();
                invalidate();
            });
            anim.start();
        }

        void stop() {
            if (anim != null) {
                anim.cancel();
                anim = null;
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float r = h / 2f;
            rect.set(0, 0, w, h);
            canvas.drawRoundRect(rect, r, r, trackPaint);
            float segW = w * 0.36f;
            float left = -segW + (w + segW) * phase;
            rect.set(left, 0, left + segW, h);
            canvas.drawRoundRect(rect, r, r, barPaint);
        }
    }
}

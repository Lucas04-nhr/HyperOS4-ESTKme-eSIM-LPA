package io.github.local.esimh4ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;

/**
 * LinearLayout with an optional glass background, modelled on SukiSU-Nova's {@code BlurredBar}
 * and floating bottom bar.
 *
 * <ul>
 *   <li>Frosted: the page underneath is blurred (radius 25) under a translucent tint.</li>
 *   <li>Progressive: the frosted layer fades out from the strong edge (screen edge) toward the
 *       content, like the HyperOS 4 bars.</li>
 *   <li>Liquid: a light 4dp blur with lens refraction near the edges and a light rim (floating
 *       bar only).</li>
 * </ul>
 *
 * The page ({@code source}) is redrawn into a 1/4-scale bitmap whenever any view scrolls or the
 * layout changes, so the bars follow the content without redrawing on idle frames.
 */
public class GlassLinearLayout extends LinearLayout {
    public enum Fade {
        NONE,
        /** Strongest at the top edge (top bar). */
        TOP,
        /** Strongest at the bottom edge (bottom bar). */
        BOTTOM
    }

    private static final int CAPTURE_SCALE = 4;

    /** Lens refraction near the edges of a (rounded) rectangle plus a specular rim. */
    private static final String LENS_AGSL =
            "uniform shader content;\n"
            + "uniform float2 size;\n"
            + "uniform float radius;\n"
            + "uniform float bezel;\n"
            + "uniform float strength;\n"
            + "uniform float rimLight;\n"
            + "half4 main(float2 p) {\n"
            + "  float2 c = size * 0.5;\n"
            + "  float2 q = abs(p - c) - (c - float2(radius));\n"
            + "  float d = length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - radius;\n"
            + "  float inside = max(-d, 0.0);\n"
            + "  float t = clamp(1.0 - inside / bezel, 0.0, 1.0);\n"
            + "  float2 dir = (p - c) / (length(p - c) + 0.0001);\n"
            + "  float2 sp = clamp(p - dir * strength * t * t, float2(0.5), size - float2(0.5));\n"
            + "  half4 col = content.eval(sp);\n"
            + "  float rim = 1.0 - smoothstep(0.0, 1.5, inside);\n"
            + "  float light = clamp(dot(dir, float2(-0.6, -0.8)) * 0.5 + 0.5, 0.0, 1.0);\n"
            + "  col.rgb = col.rgb + half3(rim * rimLight * (0.35 + 0.65 * light));\n"
            + "  return col;\n"
            + "}\n";

    protected final UiKit.Palette palette;
    private boolean glass;
    private int tintColor;
    private int fallbackColor;
    private float blurRadiusDp = 25f;
    private Fade fade = Fade.NONE;
    private boolean liquid;
    private boolean pillShape;
    private float glassAlpha = 1f;

    private View source;
    private Bitmap captureBitmap;
    private Canvas captureCanvas;
    private RenderNode blurNode;
    private int effectW = -1;
    private int effectH = -1;
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint maskPaint = new Paint();
    private final int[] srcLoc = new int[2];
    private final int[] selfLoc = new int[2];
    private boolean captureFailed = false;
    private boolean needCapture = true;

    private final ViewTreeObserver.OnPreDrawListener preDrawListener = this::onPreDraw;
    private final ViewTreeObserver.OnScrollChangedListener scrollListener = this::captureNow;
    private final ViewTreeObserver.OnGlobalLayoutListener layoutListener = this::captureNow;

    public GlassLinearLayout(Context ctx, UiKit.Palette p) {
        super(ctx);
        this.palette = p;
        setWillNotDraw(false);
        maskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
    }

    /**
     * Turns the glass background on or off.
     *
     * @param tint     translucent color drawn over the blurred page
     * @param fallback opaque color used if the page cannot be captured
     */
    public void setGlass(boolean enabled, int tint, int fallback) {
        this.glass = enabled;
        this.tintColor = tint;
        this.fallbackColor = fallback;
        if (enabled) setBackground(null);
        effectW = -1;
        invalidate();
    }

    public void setBlurRadius(float dp) {
        this.blurRadiusDp = dp;
        effectW = -1;
        invalidate();
    }

    public void setFade(Fade fade) {
        this.fade = fade;
        invalidate();
    }

    /** Liquid glass; {@code pill} rounds the lens to a capsule. */
    public void setLiquid(boolean liquid, boolean pill) {
        this.liquid = liquid;
        this.pillShape = pill;
        effectW = -1;
        invalidate();
    }

    /** 0 hides the glass (fully transparent bar), 1 shows it. Used to blur only after scrolling. */
    public void setGlassAlpha(float alpha) {
        float a = Math.max(0f, Math.min(1f, alpha));
        if (a == glassAlpha) return;
        boolean wasHidden = glassAlpha <= 0.01f;
        glassAlpha = a;
        if (wasHidden && a > 0.01f) needCapture = true;
        invalidate();
    }

    public boolean isGlass() {
        return glass && !captureFailed;
    }

    /** Sets the view whose content is blurred behind this layout. */
    public void setBlurSource(View source) {
        this.source = source;
        needCapture = true;
    }

    /** Called when software capture fails; the default switches to an opaque background. */
    protected void onGlassFailed(int fallback) {
        setBackground(new ColorDrawable(fallback));
    }

    private boolean onPreDraw() {
        if (needCapture) captureNow();
        return true;
    }

    private void captureNow() {
        if (!glass || source == null || captureFailed || glassAlpha <= 0.01f) return;
        capture();
        needCapture = captureBitmap == null;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ViewTreeObserver vto = getViewTreeObserver();
        vto.addOnPreDrawListener(preDrawListener);
        vto.addOnScrollChangedListener(scrollListener);
        vto.addOnGlobalLayoutListener(layoutListener);
        needCapture = true;
    }

    @Override
    protected void onDetachedFromWindow() {
        ViewTreeObserver vto = getViewTreeObserver();
        vto.removeOnPreDrawListener(preDrawListener);
        vto.removeOnScrollChangedListener(scrollListener);
        vto.removeOnGlobalLayoutListener(layoutListener);
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
            // Some content cannot be drawn in software; fall back to a solid background.
            captureFailed = true;
            onGlassFailed(fallbackColor);
        } finally {
            captureCanvas.restoreToCount(save);
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (isGlass() && captureBitmap != null && glassAlpha > 0.01f) {
            int w = getWidth();
            int h = getHeight();
            int layer = canvas.saveLayerAlpha(0, 0, w, h, Math.round(255 * glassAlpha));
            float sx = w / (float) captureBitmap.getWidth();
            float sy = h / (float) captureBitmap.getHeight();
            if (canvas.isHardwareAccelerated()) {
                ensureEffect(w, h);
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
            canvas.drawColor(tintColor);
            if (fade != Fade.NONE) {
                // Keep the glass solid near the strong edge and fade it out toward the content.
                float y0 = fade == Fade.TOP ? 0f : h;
                float y1 = fade == Fade.TOP ? h : 0f;
                maskPaint.setShader(new LinearGradient(0f, y0, 0f, y1,
                        new int[]{0xFF000000, 0xFF000000, 0x00000000},
                        new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
                canvas.drawRect(0, 0, w, h, maskPaint);
            }
            canvas.restoreToCount(layer);
        }
        super.dispatchDraw(canvas);
    }

    private void ensureEffect(int w, int h) {
        if (blurNode == null) blurNode = new RenderNode("Glass");
        if (w == effectW && h == effectH) return;
        effectW = w;
        effectH = h;
        Context ctx = getContext();
        float r = UiKit.dpf(ctx, liquid ? 4f : blurRadiusDp);
        RenderEffect blur = RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP);
        if (!liquid) {
            blurNode.setRenderEffect(blur);
            return;
        }
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(1.3f);
        RenderEffect vivid = RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(cm), blur);
        RenderEffect effect = vivid;
        try {
            RuntimeShader lens = new RuntimeShader(LENS_AGSL);
            lens.setFloatUniform("size", w, h);
            lens.setFloatUniform("radius", pillShape ? h / 2f : 0f);
            lens.setFloatUniform("bezel", UiKit.dpf(ctx, 24f));
            lens.setFloatUniform("strength", UiKit.dpf(ctx, 24f));
            lens.setFloatUniform("rimLight", palette.isNight ? 0.22f : 0.45f);
            effect = RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(lens, "content"), vivid);
        } catch (Throwable t) {
            // Shader not supported on this device: keep blur + saturation only.
        }
        blurNode.setRenderEffect(effect);
    }
}

package io.github.local.esimh4ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
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
 * LinearLayout with an optional frosted-glass background (HyperOS 4 style bars).
 *
 * The page underneath ({@code source}) is redrawn into a 1/4-scale bitmap whenever it changed,
 * then drawn through a {@link RenderEffect} blur behind a translucent tint. Nothing is redrawn
 * while the page is unchanged.
 *
 * Liquid glass mode uses a lighter blur, boosted saturation and an AGSL lens shader that bends
 * the content near the edges and adds a light rim. If the shader cannot be created, the layout
 * falls back to the frosted look.
 */
public class GlassLinearLayout extends LinearLayout {
    private static final int CAPTURE_SCALE = 4;

    /** Lens refraction near the edges of a (rounded) rectangle plus a specular rim. */
    private static final String LENS_AGSL =
            "uniform shader content;\n"
            + "uniform float2 size;\n"
            + "uniform float radius;\n"
            + "uniform float bezel;\n"
            + "uniform float strength;\n"
            + "uniform float rimLight;\n"
            + "uniform float topRim;\n"
            + "half4 main(float2 p) {\n"
            + "  float2 c = size * 0.5;\n"
            + "  float2 q = abs(p - c) - (c - float2(radius));\n"
            + "  float d = length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - radius;\n"
            + "  float inside = max(-d, 0.0);\n"
            + "  float t = clamp(1.0 - inside / bezel, 0.0, 1.0);\n"
            + "  float2 dir = (p - c) / (length(p - c) + 0.0001);\n"
            + "  float2 sp = clamp(p - dir * strength * t * t, float2(0.5), size - float2(0.5));\n"
            + "  half4 col = content.eval(sp);\n"
            + "  float rim = 1.0 - smoothstep(0.0, 2.0, inside);\n"
            + "  if (p.y < c.y) { rim = rim * topRim; }\n"
            + "  float light = clamp(dot(dir, float2(-0.6, -0.8)) * 0.5 + 0.5, 0.0, 1.0);\n"
            + "  col.rgb = col.rgb + half3(rim * rimLight * (0.4 + 0.6 * light));\n"
            + "  return col;\n"
            + "}\n";

    protected final UiKit.Palette palette;
    private boolean glass;
    private int tintColor;
    private int fallbackColor;
    private View source;
    private Bitmap captureBitmap;
    private Canvas captureCanvas;
    private RenderNode blurNode;
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final int[] srcLoc = new int[2];
    private final int[] selfLoc = new int[2];
    private boolean captureFailed = false;
    private boolean liquid = false;
    private boolean pillShape = false;
    private boolean topRim = true;
    private int effectW = -1;
    private int effectH = -1;
    private final ViewTreeObserver.OnPreDrawListener preDrawListener = this::onPreDrawCapture;

    public GlassLinearLayout(Context ctx, UiKit.Palette p) {
        super(ctx);
        this.palette = p;
        setWillNotDraw(false);
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
        invalidate();
    }

    /**
     * Switches to liquid glass. {@code pill} rounds the lens to a capsule (floating bar);
     * {@code rimOnTop} draws the light rim on the top edge too (off for the status-bar header).
     */
    public void setLiquid(boolean liquid, boolean pill, boolean rimOnTop) {
        this.liquid = liquid;
        this.pillShape = pill;
        this.topRim = rimOnTop;
        effectW = -1;
        invalidate();
    }

    public boolean isGlass() {
        return glass && !captureFailed;
    }

    /** Sets the view whose content is blurred behind this layout. */
    public void setBlurSource(View source) {
        this.source = source;
    }

    /** Called when software capture fails; the default switches to an opaque background. */
    protected void onGlassFailed(int fallback) {
        setBackground(new ColorDrawable(fallback));
    }

    private boolean onPreDrawCapture() {
        if (glass && source != null && !captureFailed && (source.isDirty() || captureBitmap == null)) {
            capture();
            invalidate();
        }
        return true;
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
            // Some content cannot be drawn in software; fall back to a solid background.
            captureFailed = true;
            onGlassFailed(fallbackColor);
        } finally {
            captureCanvas.restoreToCount(save);
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (isGlass() && captureBitmap != null) {
            int w = getWidth();
            int h = getHeight();
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
            // Liquid glass is clearer: use a lighter tint.
            canvas.drawColor(liquid ? UiKit.withAlpha(tintColor, (tintColor >>> 24) * 3 / 5) : tintColor);
        }
        super.dispatchDraw(canvas);
    }

    private void ensureEffect(int w, int h) {
        if (blurNode == null) blurNode = new RenderNode("Glass");
        if (w == effectW && h == effectH) return;
        effectW = w;
        effectH = h;
        Context ctx = getContext();
        if (!liquid) {
            float r = UiKit.dpf(ctx, 24f);
            blurNode.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP));
            return;
        }
        float r = UiKit.dpf(ctx, 10f);
        RenderEffect blur = RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP);
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(1.45f);
        RenderEffect saturated = RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(cm), blur);
        RenderEffect effect = saturated;
        try {
            RuntimeShader lens = new RuntimeShader(LENS_AGSL);
            lens.setFloatUniform("size", w, h);
            lens.setFloatUniform("radius", pillShape ? h / 2f : 0f);
            lens.setFloatUniform("bezel", UiKit.dpf(ctx, 14f));
            lens.setFloatUniform("strength", UiKit.dpf(ctx, 10f));
            lens.setFloatUniform("rimLight", palette.isNight ? 0.18f : 0.32f);
            lens.setFloatUniform("topRim", topRim ? 1f : 0f);
            effect = RenderEffect.createChainEffect(
                    RenderEffect.createRuntimeShaderEffect(lens, "content"), saturated);
        } catch (Throwable t) {
            // Shader not supported on this device: keep blur + saturation only.
        }
        blurNode.setRenderEffect(effect);
    }
}

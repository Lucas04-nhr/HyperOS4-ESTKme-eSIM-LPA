package io.github.local.esimh4ui;

import android.app.UiModeManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Persistent appearance settings (UI style, dark mode, dynamic color, background, bottom bar),
 * modelled after the theme page of SukiSU managers.
 */
public final class ThemeConfig {
    public static final int NIGHT_FOLLOW_SYSTEM = 0;
    public static final int NIGHT_LIGHT = 1;
    public static final int NIGHT_DARK = 2;

    private static final String PREFS_NAME = "ui_prefs";
    private static final String KEY_STYLE = "ui_style";
    private static final String KEY_NIGHT = "night_mode";
    private static final String KEY_DYNAMIC_PREFIX = "dynamic_color_";
    private static final String KEY_BG_BLUR = "bg_blur";
    private static final String KEY_BG_DIM = "bg_dim";
    private static final String KEY_CARD_ALPHA = "card_alpha";
    private static final String KEY_BAR_GLASS = "bar_glass";
    private static final String KEY_BAR_FLOATING = "bar_floating";
    private static final String KEY_LIQUID_GLASS = "liquid_glass";
    private static final String BG_FILE = "app_background.jpg";

    public static UiKit.Style style = UiKit.Style.MATERIAL3;
    public static int nightMode = NIGHT_FOLLOW_SYSTEM;
    /** Dynamic (Monet) color; stored per style: on by default for Material 3, off for Miuix. */
    public static boolean dynamicColor = true;
    /** Background blur radius in dp (0..25). */
    public static int bgBlur = 0;
    /** Background dim in percent (0..80). */
    public static int bgDim = 20;
    /** Card opacity in percent (40..100); only applied over a custom background. */
    public static int cardAlpha = 85;
    public static boolean barGlass = true;
    public static boolean barFloating = false;
    /** Liquid glass: refraction at the edges and a light rim instead of a plain frosted blur. */
    public static boolean liquidGlass = false;

    /** Bumped on every change so other activities can tell they must rebuild. */
    private static int version = 0;
    private static boolean loaded = false;

    private ThemeConfig() {}

    public static int version() {
        return version;
    }

    public static void load(Context ctx) {
        if (loaded) return;
        SharedPreferences sp = prefs(ctx);
        style = UiKit.Style.MIUIX.name().equals(sp.getString(KEY_STYLE, null))
                ? UiKit.Style.MIUIX
                : UiKit.Style.MATERIAL3;
        nightMode = sp.getInt(KEY_NIGHT, NIGHT_FOLLOW_SYSTEM);
        dynamicColor = sp.getBoolean(KEY_DYNAMIC_PREFIX + style.name(), style == UiKit.Style.MATERIAL3);
        bgBlur = sp.getInt(KEY_BG_BLUR, 0);
        bgDim = sp.getInt(KEY_BG_DIM, 20);
        cardAlpha = sp.getInt(KEY_CARD_ALPHA, 85);
        barGlass = sp.getBoolean(KEY_BAR_GLASS, true);
        barFloating = sp.getBoolean(KEY_BAR_FLOATING, false);
        liquidGlass = sp.getBoolean(KEY_LIQUID_GLASS, false);
        loaded = true;
    }

    public static void setStyle(Context ctx, UiKit.Style value) {
        style = value;
        dynamicColor = prefs(ctx).getBoolean(KEY_DYNAMIC_PREFIX + value.name(), value == UiKit.Style.MATERIAL3);
        prefs(ctx).edit().putString(KEY_STYLE, value.name()).apply();
        version++;
    }

    public static void setNightMode(Context ctx, int mode) {
        nightMode = mode;
        prefs(ctx).edit().putInt(KEY_NIGHT, mode).apply();
        version++;
        applyNightMode(ctx);
    }

    /** Applies the per-app night mode through the platform (Android 12+); the activity recreates itself. */
    public static void applyNightMode(Context ctx) {
        UiModeManager umm = ctx.getSystemService(UiModeManager.class);
        if (umm == null) return;
        int target = nightMode == NIGHT_LIGHT
                ? UiModeManager.MODE_NIGHT_NO
                : nightMode == NIGHT_DARK ? UiModeManager.MODE_NIGHT_YES : UiModeManager.MODE_NIGHT_AUTO;
        try {
            umm.setApplicationNightMode(target);
        } catch (Throwable ignored) {
        }
    }

    public static void setDynamicColor(Context ctx, boolean value) {
        dynamicColor = value;
        prefs(ctx).edit().putBoolean(KEY_DYNAMIC_PREFIX + style.name(), value).apply();
        version++;
    }

    public static void setBgBlur(Context ctx, int value) {
        bgBlur = value;
        prefs(ctx).edit().putInt(KEY_BG_BLUR, value).apply();
        version++;
    }

    public static void setBgDim(Context ctx, int value) {
        bgDim = value;
        prefs(ctx).edit().putInt(KEY_BG_DIM, value).apply();
        version++;
    }

    public static void setCardAlpha(Context ctx, int value) {
        cardAlpha = value;
        prefs(ctx).edit().putInt(KEY_CARD_ALPHA, value).apply();
        version++;
    }

    public static void setBarGlass(Context ctx, boolean value) {
        barGlass = value;
        prefs(ctx).edit().putBoolean(KEY_BAR_GLASS, value).apply();
        version++;
    }

    public static void setBarFloating(Context ctx, boolean value) {
        barFloating = value;
        prefs(ctx).edit().putBoolean(KEY_BAR_FLOATING, value).apply();
        version++;
    }

    public static void setLiquidGlass(Context ctx, boolean value) {
        liquidGlass = value;
        prefs(ctx).edit().putBoolean(KEY_LIQUID_GLASS, value).apply();
        version++;
    }

    public static File backgroundFile(Context ctx) {
        return new File(ctx.getFilesDir(), BG_FILE);
    }

    public static boolean hasBackground(Context ctx) {
        return backgroundFile(ctx).isFile();
    }

    /** Decodes the stored background as a software bitmap (the glass bar redraws it on a software canvas). */
    public static Bitmap loadBackground(Context ctx) {
        File f = backgroundFile(ctx);
        if (!f.isFile()) return null;
        try {
            return BitmapFactory.decodeFile(f.getAbsolutePath());
        } catch (Throwable e) {
            return null;
        }
    }

    /** Copies the picked image into app storage, downscaled so its longest side is at most 2160 px. */
    public static boolean importBackground(Context ctx, Uri uri) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false;
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2160) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap bmp;
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                bmp = BitmapFactory.decodeStream(in, null, opts);
            }
            if (bmp == null) return false;
            int longest = Math.max(bmp.getWidth(), bmp.getHeight());
            if (longest > 2160) {
                float k = 2160f / longest;
                Bitmap scaled = Bitmap.createScaledBitmap(
                        bmp, Math.round(bmp.getWidth() * k), Math.round(bmp.getHeight() * k), true);
                bmp.recycle();
                bmp = scaled;
            }
            File tmp = new File(ctx.getFilesDir(), BG_FILE + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, out);
            }
            bmp.recycle();
            boolean ok = tmp.renameTo(backgroundFile(ctx));
            version++;
            return ok;
        } catch (Throwable e) {
            return false;
        }
    }

    public static void clearBackground(Context ctx) {
        //noinspection ResultOfMethodCallIgnored
        backgroundFile(ctx).delete();
        version++;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}

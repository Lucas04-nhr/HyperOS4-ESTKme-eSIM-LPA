package io.github.local.esimh4ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Base activity that loads the theme settings before the views are built and rebuilds itself
 * when the settings changed while it was in the background (for example in {@link ThemeActivity}).
 */
public abstract class ThemedActivity extends Activity {
    protected UiKit.Palette palette;
    private int themeVersion;
    private boolean themeRecreating;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeConfig.load(this);
        super.onCreate(savedInstanceState);
        themeVersion = ThemeConfig.version();
        palette = UiKit.Palette.resolve(this);
    }

    /** Returns true when the activity is being recreated because the theme changed. */
    protected boolean recreateIfThemeChanged() {
        if (themeVersion != ThemeConfig.version()) {
            themeRecreating = true;
            recreate();
            return true;
        }
        return false;
    }

    /** True after {@link #recreateIfThemeChanged()} scheduled a rebuild; skip further work then. */
    protected boolean isThemeRecreating() {
        return themeRecreating;
    }

    @Override
    protected void onResume() {
        super.onResume();
        recreateIfThemeChanged();
    }

    protected void openThemeSettings() {
        startActivity(new Intent(this, ThemeActivity.class));
    }
}

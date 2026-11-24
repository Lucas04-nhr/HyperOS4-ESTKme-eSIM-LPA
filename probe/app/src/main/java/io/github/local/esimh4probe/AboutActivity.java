package io.github.local.esimh4probe;

import android.os.Bundle;
import android.widget.LinearLayout;
import io.github.local.esimh4ui.Scaffold;
import io.github.local.esimh4ui.ThemedActivity;
import io.github.local.esimh4ui.UiKit;

/**
 * About page, opened from the settings tab. The Miuix style shows the Compose page with the
 * HyperOS background effect ({@code AboutScreen.kt}); the Material 3 style shows a View page
 * ({@link AboutSection}).
 */
public final class AboutActivity extends ThemedActivity {
    private Scaffold scaffold;
    private final HiddenEntryGesture exportGesture = new HiddenEntryGesture();
    private boolean exporting;

    void onHeaderTap() {
        if (exporting) return;
        if (!exportGesture.tap(this, R.string.export_taps_remaining, R.string.export_started)) return;
        exporting = true;
        android.content.Context context = getApplicationContext();
        new Thread(() -> {
            String message;
            try {
                message = context.getString(R.string.export_saved, ModuleLogExporter.export(context));
            } catch (Exception e) {
                message = context.getString(R.string.export_failed, e.getMessage());
            }
            String result = message;
            runOnUiThread(() -> {
                exporting = false;
                android.widget.Toast.makeText(context, result, android.widget.Toast.LENGTH_LONG).show();
            });
        }, "EsimH4-LogExport").start();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (UiKit.isMiuix()) {
            UiKit.applyEdgeToEdge(this, palette);
            AboutScreen.setAboutContent(this, palette.isNight);
            return;
        }
        String title = getString(R.string.section_about);
        scaffold = new Scaffold(this, palette, title, null, true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        AboutSection.build(this, palette, col, msg -> scaffold.showToast(msg));
        scaffold.addPage(col, io.github.local.esimh4ui.R.id.ui_page_1, title);
        setContentView(scaffold.root);
        UiKit.applyEdgeToEdge(this, palette);
    }

    @Override
    protected void onDestroy() {
        if (scaffold != null) scaffold.release();
        super.onDestroy();
    }
}

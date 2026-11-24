package io.github.local.esimh4probe;

import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import io.github.local.esimh4ui.Scaffold;
import io.github.local.esimh4ui.ThemedActivity;
import io.github.local.esimh4ui.UiKit;

/**
 * Open source licenses page, opened from the About page: the license of this project and of each
 * library in the app. The Miuix style shows the Compose page ({@code LicensesScreen.kt}); the
 * Material 3 style shows the View page below, with each license text in a dialog.
 */
public final class LicensesActivity extends ThemedActivity {
    private Scaffold scaffold;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (UiKit.isMiuix()) {
            UiKit.applyEdgeToEdge(this, palette);
            LicensesScreen.setLicensesContent(this);
            return;
        }
        String title = getString(R.string.licenses_title);
        scaffold = new Scaffold(this, palette, title, null, true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        scaffold.addPage(col, io.github.local.esimh4ui.R.id.ui_page_1, title);
        setContentView(scaffold.root);
        UiKit.applyEdgeToEdge(this, palette);

        Thread loader = new Thread(() -> {
            LicenseData data = LicenseData.load(this);
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) bind(col, data);
            });
        }, "EsimH4-Licenses");
        loader.setDaemon(true);
        loader.start();
    }

    @Override
    protected void onDestroy() {
        if (scaffold != null) scaffold.release();
        super.onDestroy();
    }

    private void bind(LinearLayout col, LicenseData data) {
        col.addView(UiKit.createSectionHeader(this, palette, getString(R.string.licenses_section_project)));
        col.addView(row(data.project, 24f, 24f, 0));

        col.addView(UiKit.createSectionHeader(this, palette, getString(R.string.licenses_section_libraries)));
        int count = data.libraries.size();
        for (int i = 0; i < count; i++) {
            boolean last = i == count - 1;
            col.addView(row(data.libraries.get(i), i == 0 ? 24f : 6f, last ? 24f : 6f, last ? 0 : 2));
        }
    }

    private View row(LicenseData.Entry entry, float top, float bottom, int gap) {
        String summary = entry.coordinate != null ? entry.coordinate + "\n" + entry.licenses : entry.licenses;
        UiKit.NavRow row = new UiKit.NavRow(this, palette, R.drawable.ic_code_24, entry.title, summary,
                top, bottom, gap, () -> showLicense(entry));
        return row.card;
    }

    private void showLicense(LicenseData.Entry entry) {
        String text = entry.text != null ? entry.text : getString(R.string.licenses_no_text, entry.licenses);
        UiKit.showDialog(this, palette, entry.title, text, false,
                entry.url != null ? getString(R.string.licenses_open_web) : null,
                entry.url != null ? () -> AboutSection.openUrl(this, entry.url, msg -> scaffold.showToast(msg)) : null,
                getString(R.string.diag_close));
    }
}

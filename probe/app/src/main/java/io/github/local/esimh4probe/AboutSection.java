package io.github.local.esimh4probe;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import io.github.local.esimh4ui.UiKit;
import java.util.function.Consumer;

/**
 * Content of the About page in the Material 3 style (View based): version, repository, authors and
 * copyright. The Miuix style shows the Compose page in {@code AboutScreen.kt} instead.
 */
final class AboutSection {
    private AboutSection() {
    }

    /** Fills {@code col}; repository and authors appear once {@link AboutInfo} is read. */
    static void build(Activity activity, UiKit.Palette palette, LinearLayout col, Consumer<String> toast) {
        LinearLayout card = UiKit.createCard(activity, palette);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = UiKit.dp(activity, 8);
        android.view.View.OnClickListener headerTap = v -> ((AboutActivity) activity).onHeaderTap();
        android.widget.ImageView icon = new android.widget.ImageView(activity);
        icon.setImageDrawable(activity.getApplicationInfo().loadIcon(activity.getPackageManager()));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(UiKit.dp(activity, 88), UiKit.dp(activity, 88));
        iconLp.gravity = Gravity.CENTER_HORIZONTAL;
        icon.setOnClickListener(headerTap);
        icon.setContentDescription(activity.getString(R.string.app_name));
        card.addView(icon, iconLp);
        TextView name = new TextView(activity);
        name.setText(R.string.app_name);
        name.setTextColor(palette.onSurface);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, UiKit.dp(activity, 14), 0, UiKit.dp(activity, 6));
        name.setOnClickListener(headerTap);
        card.addView(name);
        TextView versionView = UiKit.addInfoLine(activity, palette, card, activity.getString(R.string.about_version), "…", true);
        versionView.setOnClickListener(headerTap);
        TextView commitView = UiKit.addInfoLine(activity, palette, card, activity.getString(R.string.about_commit), "—", true);
        col.addView(card, cardLp);

        UiKit.NavRow licensesRow = new UiKit.NavRow(
                activity, palette, R.drawable.ic_code_24, activity.getString(R.string.licenses_title), null,
                24f, 24f, 0, () -> activity.startActivity(new Intent(activity, LicensesActivity.class)));
        ((LinearLayout.LayoutParams) licensesRow.card.getLayoutParams()).topMargin = UiKit.dp(activity, 10);
        col.addView(licensesRow.card);

        UiKit.NavRow channelRow = new UiKit.NavRow(
                activity, palette, R.drawable.ic_link_24, activity.getString(R.string.update_channel),
                null, 24f, 6f, 2, null);
        channelRow.setValue(UpdateChecker.isNightly(activity) ? "Nightly" : "Stable");
        channelRow.card.setOnClickListener(v -> UiKit.showChoiceDialog(activity, palette,
                activity.getString(R.string.update_channel), new String[]{"Stable", "Nightly"},
                UpdateChecker.isNightly(activity) ? 1 : 0, index -> {
                    UpdateChecker.setNightly(activity, index == 1);
                    channelRow.setValue(index == 1 ? "Nightly" : "Stable");
                }));
        ((LinearLayout.LayoutParams) channelRow.card.getLayoutParams()).topMargin = UiKit.dp(activity, 10);
        col.addView(channelRow.card);
        UiKit.NavRow checkRow = new UiKit.NavRow(
                activity, palette, R.drawable.ic_link_24, activity.getString(R.string.update_check),
                null, 6f, 6f, 2, () -> UpdateChecker.check(activity));
        col.addView(checkRow.card);
        UiKit.SwitchRow startupRow = new UiKit.SwitchRow(activity, palette, R.drawable.ic_link_24,
                activity.getString(R.string.update_startup), null, 6f, 24f, 0);
        startupRow.setRowState(UpdateChecker.checkAtStartup(activity), true, null);
        startupRow.toggle.setOnCheckedChangeListener((view, enabled) -> {
            UpdateChecker.setCheckAtStartup(activity, enabled);
            startupRow.refreshIconTint(enabled);
        });
        col.addView(startupRow.card);

        LinearLayout repoGroup = new LinearLayout(activity);
        repoGroup.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rgLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rgLp.topMargin = UiKit.dp(activity, 10);
        col.addView(repoGroup, rgLp);

        col.addView(UiKit.createSectionHeader(activity, palette, activity.getString(R.string.section_authors)));
        LinearLayout authorGroup = new LinearLayout(activity);
        authorGroup.setOrientation(LinearLayout.VERTICAL);
        col.addView(authorGroup, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView copyright = new TextView(activity);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp.topMargin = UiKit.dp(activity, 20);
        cLp.bottomMargin = UiKit.dp(activity, 8);
        copyright.setGravity(Gravity.CENTER_HORIZONTAL);
        copyright.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        copyright.setTextColor(palette.onSurfaceVariant);
        col.addView(copyright, cLp);

        Thread loader = new Thread(() -> {
            AboutInfo info = AboutInfo.load(activity);
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                bind(activity, palette, info, versionView, commitView, repoGroup, authorGroup, copyright, toast);
            });
        }, "EsimH4-About");
        loader.setDaemon(true);
        loader.start();
    }

    private static void bind(Activity activity, UiKit.Palette palette, AboutInfo info, TextView versionView,
            TextView commitView, LinearLayout repoGroup, LinearLayout authorGroup, TextView copyright,
            Consumer<String> toast) {
        versionView.setText(info.versionText());
        String commit = info.shortCommit();
        if (commit != null) commitView.setText(commit);

        boolean showParent = info.fork && info.parent != null;
        UiKit.NavRow repoRow = new UiKit.NavRow(
                activity, palette, R.drawable.ic_link_24, activity.getString(R.string.about_repo), info.repo,
                24f, showParent ? 6f : 24f, showParent ? 2 : 0, () -> openUrl(activity, info.repoUrl, toast));
        repoRow.setValue(activity.getString(info.fork ? R.string.about_repo_fork : R.string.about_repo_original));
        repoGroup.addView(repoRow.card);
        if (showParent) {
            UiKit.NavRow parentRow = new UiKit.NavRow(
                    activity, palette, R.drawable.ic_link_24, activity.getString(R.string.about_upstream), info.parent,
                    6f, 24f, 0, () -> openUrl(activity, info.parentUrl, toast));
            repoGroup.addView(parentRow.card);
        }

        int count = info.people.size();
        for (int i = 0; i < count; i++) {
            AboutInfo.Person person = info.people.get(i);
            boolean last = i == count - 1;
            UiKit.NavRow row = new UiKit.NavRow(
                    activity, palette, R.drawable.ic_person_24, person.name, personSummary(activity, person),
                    i == 0 ? 24f : 6f, last ? 24f : 6f, last ? 0 : 2,
                    () -> openUrl(activity, person.url, toast));
            if (person.avatar != null) row.setIconBitmap(person.avatar);
            authorGroup.addView(row.card);
        }

        copyright.setText(activity.getString(R.string.about_copyright, info.years()));
    }

    /** "@login" */
    static String personSummary(Activity activity, AboutInfo.Person person) {
        return "@" + person.login;
    }

    static void openUrl(Activity activity, String url, Consumer<String> toast) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            toast.accept(activity.getString(R.string.snack_open_link_failed));
        }
    }
}

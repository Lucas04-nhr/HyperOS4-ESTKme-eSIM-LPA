package io.github.local.esimh4probe;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import io.github.local.esimh4ui.UiKit;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * About section at the bottom of the settings page: version, repository, authors and copyright.
 *
 * <p>The nightly workflow writes {@code assets/about/about.json} and the contributors' avatars
 * (.github/scripts/about_info.sh). A build without them (a local build) falls back to the original
 * repository and the two fixed authors, without avatars or commit counts.
 */
final class AboutSection {
    private static final String TAG = "EsimH4About";
    private static final String ASSET_DIR = "about/";
    private static final String GITHUB = "https://github.com/";
    private static final String ORIGINAL_REPO = "jabe1122/HyperOS4-ESTKme-eSIM-LPA";
    /** The original author and the fork maintainer, always listed first and in this order. */
    private static final String[] FIXED_AUTHORS = {"jabe1122", "Lucas04-nhr"};
    private static final int FIRST_YEAR = 2026;

    private static final class Person {
        String login;
        String name;
        String url;
        int contributions;
        Bitmap avatar;
    }

    private static final class Info {
        String repo = ORIGINAL_REPO;
        String repoUrl = GITHUB + ORIGINAL_REPO;
        boolean fork;
        String parent;
        String parentUrl;
        String commit;
        int year = FIRST_YEAR;
        final List<Person> people = new ArrayList<>();
    }

    private AboutSection() {
    }

    /** Appends the section to {@code col}; repository and authors fill in once the assets are read. */
    static void build(Activity activity, UiKit.Palette palette, ExecutorService io, LinearLayout col,
            Consumer<String> toast) {
        col.addView(UiKit.createSectionHeader(activity, palette, activity.getString(R.string.section_about)));
        LinearLayout card = UiKit.createCard(activity, palette);
        UiKit.addInfoLine(activity, palette, card, activity.getString(R.string.about_version), versionText(activity), true);
        TextView commitView = UiKit.addInfoLine(activity, palette, card, activity.getString(R.string.about_commit), "—", true);
        UiKit.addInfoLine(activity, palette, card, activity.getString(R.string.about_license),
                activity.getString(R.string.about_license_value));
        col.addView(card);

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

        io.execute(() -> {
            Info info = load(activity);
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                bind(activity, palette, info, commitView, repoGroup, authorGroup, copyright, toast);
            });
        });
    }

    private static void bind(Activity activity, UiKit.Palette palette, Info info, TextView commitView,
            LinearLayout repoGroup, LinearLayout authorGroup, TextView copyright, Consumer<String> toast) {
        if (info.commit != null && !info.commit.isEmpty()) {
            commitView.setText(info.commit.length() > 7 ? info.commit.substring(0, 7) : info.commit);
        }

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
            Person person = info.people.get(i);
            String subtitle = "@" + person.login;
            if (person.contributions > 0) {
                subtitle += " · " + activity.getResources().getQuantityString(
                        R.plurals.about_commits, person.contributions, person.contributions);
            }
            boolean last = i == count - 1;
            UiKit.NavRow row = new UiKit.NavRow(
                    activity, palette, R.drawable.ic_person_24, person.name, subtitle,
                    i == 0 ? 24f : 6f, last ? 24f : 6f, last ? 0 : 2,
                    () -> openUrl(activity, person.url, toast));
            if (person.avatar != null) row.setIconBitmap(person.avatar);
            authorGroup.addView(row.card);
        }

        String years = info.year > FIRST_YEAR ? FIRST_YEAR + "–" + info.year : String.valueOf(FIRST_YEAR);
        copyright.setText(activity.getString(R.string.about_copyright, years));
    }

    /** Reads the workflow-generated data; runs on the I/O thread. */
    private static Info load(Activity activity) {
        Info info = new Info();
        JSONObject json = null;
        try (InputStream in = activity.getAssets().open(ASSET_DIR + "about.json")) {
            json = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Throwable e) {
            Log.i(TAG, "No about.json (local build): " + e.getClass().getSimpleName());
        }
        if (json != null) {
            info.repo = json.optString("repository", info.repo);
            info.repoUrl = json.optString("url", GITHUB + info.repo);
            info.fork = json.optBoolean("fork", false);
            info.parent = json.isNull("parent") ? null : json.optString("parent", null);
            info.parentUrl = json.isNull("parentUrl") ? GITHUB + info.parent : json.optString("parentUrl");
            info.commit = json.optString("commit", null);
            info.year = Math.max(FIRST_YEAR, json.optInt("year", FIRST_YEAR));
            JSONArray arr = json.optJSONArray("contributors");
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null || o.optString("login").isEmpty()) continue;
                Person person = new Person();
                person.login = o.optString("login");
                person.name = o.optString("name", person.login);
                if (person.name.isEmpty()) person.name = person.login;
                person.url = o.optString("url", GITHUB + person.login);
                person.contributions = o.optInt("contributions", 0);
                String avatar = o.optString("avatar", "");
                if (!avatar.isEmpty()) person.avatar = readBitmap(activity, ASSET_DIR + avatar);
                info.people.add(person);
            }
        }
        if (info.people.isEmpty()) {
            for (String login : FIXED_AUTHORS) {
                Person person = new Person();
                person.login = login;
                person.name = login;
                person.url = GITHUB + login;
                info.people.add(person);
            }
        }
        return info;
    }

    private static Bitmap readBitmap(Activity activity, String path) {
        try (InputStream in = activity.getAssets().open(path)) {
            return BitmapFactory.decodeStream(in);
        } catch (Throwable e) {
            Log.w(TAG, "Could not read " + path, e);
            return null;
        }
    }

    private static String versionText(Activity activity) {
        try {
            PackageInfo pi = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            return pi.versionName + " (" + pi.getLongVersionCode() + ")";
        } catch (Throwable e) {
            return "—";
        }
    }

    private static void openUrl(Activity activity, String url, Consumer<String> toast) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            toast.accept(activity.getString(R.string.snack_open_link_failed));
        }
    }
}

package io.github.local.esimh4probe;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Shared update policy for both About layouts. No installation or privileged operations. */
public final class UpdateChecker {
    private static final String API = "https://api.github.com/repos/";
    private static final String NIGHTLY_REPO = "Lucas04-nhr/HyperOS4-ESTKme-eSIM-LPA";
    private static final String STABLE_REPO = "Xposed-Modules-Repo/io.github.jabe1122.esimh4probe";
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private UpdateChecker() {}

    public static void check(Context context, boolean nightly) {
        Context app = context.getApplicationContext();
        if (!BUSY.compareAndSet(false, true)) {
            toast(app, app.getString(R.string.update_checking));
            return;
        }
        toast(app, app.getString(R.string.update_checking));
        new Thread(() -> {
            try {
                AboutInfo installed = AboutInfo.load(app);
                Download update = nightly ? nightly(app, installed) : stable(installed);
                if (update == null) {
                    toast(app, app.getString(R.string.update_current));
                } else {
                    DownloadManager manager = app.getSystemService(DownloadManager.class);
                    if (manager == null) throw new IOException("Download Manager unavailable");
                    DownloadManager.Request request = new DownloadManager.Request(Uri.parse(update.url))
                            .setTitle(update.name)
                            .setMimeType(update.name.endsWith(".apk")
                                    ? "application/vnd.android.package-archive" : "application/zip")
                            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, update.name);
                    manager.enqueue(request);
                    toast(app, app.getString(R.string.update_download_started, update.name));
                }
            } catch (Exception e) {
                toast(app, app.getString(R.string.update_failed, e.getMessage()));
            } finally {
                BUSY.set(false);
            }
        }, "EsimH4-Updates").start();
    }

    private static Download stable(AboutInfo installed) throws Exception {
        JSONObject release = get(API + STABLE_REPO + "/releases/latest");
        // LSPosed repository tags use versionCode-versionName.
        Matcher tag = Pattern.compile("^(\\d+)-(.+)$").matcher(release.getString("tag_name"));
        if (!tag.matches()) throw new IOException("Unrecognized LSPosed release version");
        if (Long.parseLong(tag.group(1)) <= installed.versionCode) return null;
        JSONArray assets = release.getJSONArray("assets");
        Download apk = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            String name = asset.getString("name");
            String url = asset.getString("browser_download_url");
            if (name.startsWith("XiaomiEsimLPA-HyperOS4-") && name.endsWith(".zip")) {
                return new Download(name, url);
            }
            if (name.endsWith(".apk") && !name.contains("armeabi-v7a")
                    && (apk == null || name.contains("universal"))) apk = new Download(name, url);
        }
        if (apk != null) return apk;
        throw new IOException("No compatible release asset");
    }

    private static Download nightly(Context context, AboutInfo installed) throws Exception {
        long installedRun = 0;
        try (InputStream in = context.getAssets().open("about/about.json")) {
            JSONObject build = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            if (NIGHTLY_REPO.equals(build.optString("repository"))) {
                installedRun = build.optLong("runId", 0);
            }
        } catch (IOException ignored) {
            // Local builds have no workflow identity; offer the newest available artifact.
        }
        // No branch, event or conclusion filter: even a run with a later failed step can
        // have a valid uploaded module. Skip expired artifacts and runs without a module.
        for (int page = 1; page <= 5; page++) {
            JSONArray runs = get(API + NIGHTLY_REPO
                    + "/actions/workflows/nightly.yml/runs?per_page=100&page=" + page)
                    .getJSONArray("workflow_runs");
            for (int i = 0; i < runs.length(); i++) {
                JSONObject run = runs.getJSONObject(i);
                long id = run.getLong("id");
                if (installedRun > 0 && id <= installedRun) return null;
                JSONArray artifacts = get(API + NIGHTLY_REPO + "/actions/runs/" + id
                        + "/artifacts?per_page=100").getJSONArray("artifacts");
                JSONObject newest = null;
                for (int j = 0; j < artifacts.length(); j++) {
                    JSONObject artifact = artifacts.getJSONObject(j);
                    String name = artifact.getString("name");
                    if (!artifact.optBoolean("expired", true)
                            && name.startsWith("XiaomiEsimLPA-HyperOS4-") && name.contains("-nightly")
                            && (newest == null || artifact.getString("created_at")
                            .compareTo(newest.getString("created_at")) > 0)) newest = artifact;
                }
                if (newest != null) {
                    if (installedRun == 0 && run.optString("head_sha").equals(installed.commit)) return null;
                    String name = newest.getString("name");
                    if (!name.endsWith(".zip")) name += ".zip";
                    return new Download(name, "https://nightly.link/" + NIGHTLY_REPO
                            + "/actions/artifacts/" + newest.getLong("id") + ".zip");
                }
            }
            if (runs.length() < 100) throw new IOException("No available nightly module artifact");
        }
        throw new IOException("No nightly module found in the latest 500 runs");
    }

    private static JSONObject get(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        connection.setRequestProperty("User-Agent", "eSIM-Probe-UpdateChecker");
        try {
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("GitHub HTTP " + status
                    + ((status == 403 || status == 429) ? " (rate limit or access denied)" : ""));
            try (InputStream in = connection.getInputStream()) {
                return new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void toast(Context context, String text) {
        MAIN.post(() -> Toast.makeText(context, text, Toast.LENGTH_LONG).show());
    }

    private static final class Download {
        final String name;
        final String url;
        Download(String name, String url) throws IOException {
            if (!url.startsWith("https://") || !name.matches("[A-Za-z0-9._+() -]+")) {
                throw new IOException("Unsafe download URL or filename");
            }
            this.name = name;
            this.url = url;
        }
    }
}

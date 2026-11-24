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
import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Shared release update policy for both About layouts. */
public final class UpdateChecker {
    private static final String API = "https://api.github.com/repos/";
    private static final String[] REPOSITORIES = {
            "Lucas04-nhr/HyperOS4-ESTKme-eSIM-LPA",
            "jabe1122/HyperOS4-ESTKme-eSIM-LPA"
    };
    private static final Pattern VERSION = Pattern.compile("^[vV]?(\\d+(?:\\.\\d+)*)(?:\\+[A-Za-z0-9.-]+)?$");
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private UpdateChecker() {}

    public static void check(Context context) {
        Context app = context.getApplicationContext();
        if (!BUSY.compareAndSet(false, true)) {
            toast(app, app.getString(R.string.update_checking));
            return;
        }
        toast(app, app.getString(R.string.update_checking));
        new Thread(() -> {
            try {
                Download update = stable(AboutInfo.load(app));
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
        Exception failure = null;
        for (String repository : REPOSITORIES) {
            try {
                JSONObject release = get(API + repository + "/releases/latest");
                if (release == null || release.optBoolean("draft") || release.optBoolean("prerelease")) continue;
                if (!isNewer(release.getString("tag_name"), installed)) continue;
                Download update = asset(release.getJSONArray("assets"));
                if (update != null) return update;
                throw new IOException("No compatible release asset in " + repository);
            } catch (Exception e) {
                // Try upstream when the fork is unavailable, but do not report an
                // incomplete check as up to date if neither source yields an update.
                if (failure == null) failure = e;
            }
        }
        if (failure != null) throw failure;
        return null;
    }

    private static boolean isNewer(String tag, AboutInfo installed) throws IOException {
        Matcher coded = Pattern.compile("^(\\d+)-[vV]?\\d+(?:\\.\\d+)*$").matcher(tag);
        if (coded.matches()) return new BigInteger(coded.group(1))
                .compareTo(BigInteger.valueOf(installed.versionCode)) > 0;
        Matcher candidate = VERSION.matcher(tag);
        Matcher current = VERSION.matcher(installed.versionName);
        if (!candidate.matches() || !current.matches()) {
            throw new IOException("Unrecognized release version: " + tag);
        }
        String[] next = candidate.group(1).split("\\.");
        String[] previous = current.group(1).split("\\.");
        for (int i = 0; i < Math.max(next.length, previous.length); i++) {
            BigInteger a = i < next.length ? new BigInteger(next[i]) : BigInteger.ZERO;
            BigInteger b = i < previous.length ? new BigInteger(previous[i]) : BigInteger.ZERO;
            int comparison = a.compareTo(b);
            if (comparison != 0) return comparison > 0;
        }
        return false;
    }

    private static Download asset(JSONArray assets) throws Exception {
        Download apk = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            String name = asset.getString("name");
            String url = asset.getString("browser_download_url");
            if (name.startsWith("XiaomiEsimLPA-HyperOS4-") && name.endsWith(".zip")) {
                return new Download(name, url);
            }
            if (name.endsWith(".apk") && !name.contains("armeabi-v7a")
                    && !name.contains("x86") && (apk == null || name.contains("universal"))) {
                apk = new Download(name, url);
            }
        }
        return apk;
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
            // GitHub returns 404 when a repository has no published stable release.
            if (status == 404) return null;
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

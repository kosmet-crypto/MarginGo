package app.margingo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hosts the MarginGo web app (bundled in assets/www) in a full-screen WebView.
 * Pages are served from https://appassets.androidplatform.net so localStorage
 * behaves like on a normal https site.
 */
public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/assets/www/index.html";
    private static final int REQ_PICK_FILE = 1;
    private static final int REQ_SAVE_FILE = 2;

    /** How long a downloaded page gets to report it started before the bundled page is used again. */
    private static final long PAGE_START_TIMEOUT = 15000;
    /** A page update downloaded in the background is applied when returning after this long. */
    private static final long RELOAD_AFTER_AWAY = 10 * 60 * 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView webView;
    private WebUpdater web;
    private ValueCallback<Uri[]> pendingPick;
    private String pendingSaveText;
    private String pendingShare;
    private boolean pageReady;
    private boolean pageUpdatePending;
    private boolean installAfterPermission;
    private long pausedAt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebUpdater(this);
        // A downloaded page (see WebUpdater) is served at the same URL as the bundled one.
        final WebViewAssetLoader.AssetsPathHandler assets = new WebViewAssetLoader.AssetsPathHandler(this);
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", path -> {
                    WebResourceResponse page = BuildConfig.SELF_UPDATE ? web.intercept(path) : null;
                    return page != null ? page : assets.handle(path);
                })
                .build();

        webView = new WebView(this);
        webView.setBackgroundColor(0xFF0F1512);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);

        webView.addJavascriptInterface(new Bridge(), "MarginGoAndroid");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (HOST.equals(url.getHost())) return false;
                // Anything outside the app (listing links) opens in the browser or the Finn/KP app.
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, url));
                } catch (ActivityNotFoundException ignored) {
                }
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (pendingPick != null) pendingPick.onReceiveValue(null);
                pendingPick = callback;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                // Backups are JSON, but file managers label them differently; the page validates.
                i.setType("*/*");
                try {
                    startActivityForResult(i, REQ_PICK_FILE);
                } catch (ActivityNotFoundException e) {
                    pendingPick = null;
                    return false;
                }
                return true;
            }
        });

        takeShare(getIntent());
        if (savedInstanceState != null) webView.restoreState(savedInstanceState);
        else webView.loadUrl(START_URL);
        watchPageStart();

        if (BuildConfig.SELF_UPDATE) {
            ApkInstaller.handleStatus(this, getIntent(), this::toast);
            if (savedInstanceState == null) checkForUpdate(false);
        }
    }

    /** If a downloaded page never reports that it started, fall back to the bundled page. */
    private void watchPageStart() {
        pageReady = false;
        handler.removeCallbacks(pageStartCheck);
        if (BuildConfig.SELF_UPDATE && web.isActive()) handler.postDelayed(pageStartCheck, PAGE_START_TIMEOUT);
    }

    private final Runnable pageStartCheck = () -> {
        if (pageReady || !web.isActive()) return;
        web.rollBack();
        webView.loadUrl(START_URL);
        pageReady = false;
    };

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (BuildConfig.SELF_UPDATE) ApkInstaller.handleStatus(this, intent, this::toast);
        takeShare(intent);
        if (pageReady) deliverShare();
    }

    /* ---------- sharing a listing into the app ---------- */

    /** Keeps text shared from another app (a Finn/KP listing) until the page is ready for it. */
    private void takeShare(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        String subject = intent.getStringExtra(Intent.EXTRA_SUBJECT);
        if (text == null && subject == null) return;
        if (subject != null && text != null && !text.contains(subject)) text = subject + "\n" + text;
        pendingShare = text != null ? text : subject;
        // Handled once; a later configuration change must not add the item again.
        intent.setAction(Intent.ACTION_MAIN);
    }

    private void deliverShare() {
        if (pendingShare == null) return;
        String js = "window.mgShared && window.mgShared(" + JSONObject.quote(pendingShare) + ")";
        pendingShare = null;
        webView.evaluateJavascript(js, null);
    }

    @Override
    protected void onPause() {
        super.onPause();
        pausedAt = System.currentTimeMillis();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (installAfterPermission && getPackageManager().canRequestPackageInstalls()) {
            installAfterPermission = false;
            startApkInstall();
        } else if (pageUpdatePending && pausedAt > 0
                && System.currentTimeMillis() - pausedAt > RELOAD_AFTER_AWAY) {
            pageUpdatePending = false;
            webView.loadUrl(START_URL);
            watchPageStart();
        }
    }

    /* ---------- update check (sideload builds only) ---------- */

    private static final long UPDATE_CHECK_INTERVAL = 12 * 60 * 60 * 1000L;

    /**
     * Two kinds of updates:
     * 1. The web page (WebUpdater): downloaded silently and used from the next start.
     * 2. The APK: the latest GitHub Release (tagged v1.0.<versionCode>); offered in a dialog
     *    and installed from inside the app (ApkInstaller).
     * On launch the page is checked every time and the APK at most every 12 hours, silently;
     * the "Check for updates" button checks both right away and reports the result.
     */
    private void checkForUpdate(final boolean manual) {
        final SharedPreferences prefs = getSharedPreferences("update", MODE_PRIVATE);
        long now = System.currentTimeMillis();
        final boolean checkApk = manual || now - prefs.getLong("lastCheck", 0) >= UPDATE_CHECK_INTERVAL;
        if (checkApk) prefs.edit().putLong("lastCheck", now).apply();
        if (manual) toast(Txt.t("Checking for updates…", "Проверавам ажурирања…", "Ser etter oppdateringer…"));

        new Thread(() -> {
            boolean newPage = false, pageChecked = false;
            try {
                newPage = web.check();
                pageChecked = true;
            } catch (Exception ignored) {
                // Offline or GitHub unreachable: keep the current page.
            }
            final boolean pageUpdated = newPage;
            if (pageUpdated && !manual) runOnUiThread(() -> pageUpdatePending = true);
            if (!checkApk) return;
            final String upToDate = Txt.t("You have the latest version", "Имаш најновију верзију", "Du har nyeste versjon");
            try {
                URL api = new URL("https://api.github.com/repos/" + BuildConfig.UPDATE_REPO + "/releases/latest");
                HttpURLConnection c = (HttpURLConnection) api.openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
                String body;
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    byte[] b = new byte[8192];
                    for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
                    body = buf.toString("UTF-8");
                }
                String tag = new JSONObject(body).optString("tag_name", "");
                final long latest = Long.parseLong(tag.substring(tag.lastIndexOf('.') + 1));
                final String name = tag.startsWith("v") ? tag.substring(1) : tag;
                // A newer release with the same Android part as this app only has web changes, which
                // WebUpdater already brings in quietly; only offer the APK when android/ changed.
                Matcher nat = Pattern.compile("native: ([0-9a-f]{12})").matcher(new JSONObject(body).optString("body", ""));
                boolean sameNative = nat.find() && nat.group(1).equals(BuildConfig.NATIVE_HASH);
                if (latest > installedVersionCode() && !sameNative) runOnUiThread(() -> showUpdateDialog(name));
                else if (manual && pageUpdated) runOnUiThread(this::showPageUpdatedDialog);
                else if (manual) toast(upToDate);
            } catch (Exception e) {
                // No network, rate limit or unexpected response: the automatic check tries again later.
                if (manual && pageUpdated) runOnUiThread(this::showPageUpdatedDialog);
                else if (manual && pageChecked) toast(upToDate);
                else if (manual) toast(Txt.t("Could not check. Are you online?",
                        "Провера није успела. Има ли интернета?", "Kunne ikke sjekke. Er du på nett?"));
            }
        }).start();
    }

    private void showPageUpdatedDialog() {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle(Txt.t("Update downloaded", "Ажурирање преузето", "Oppdatering lastet ned"))
                .setMessage(Txt.t("A new version of MarginGo is ready. Restart now? Your data stays in place.",
                        "Нова верзија MarginGo је спремна. Поново покренути сада? Подаци остају.",
                        "En ny versjon av MarginGo er klar. Starte på nytt nå? Dataene dine beholdes."))
                .setPositiveButton(Txt.t("Restart", "Покрени", "Start på nytt"), (d, w) -> {
                    pageUpdatePending = false;
                    webView.loadUrl(START_URL);
                    watchPageStart();
                })
                .setNegativeButton(Txt.t("Later", "Касније", "Senere"), (d, w) -> pageUpdatePending = true)
                .show();
    }

    private void startApkInstall() {
        if (!ApkInstaller.ensureAllowed(this)) {
            installAfterPermission = true;
            Toast.makeText(this, Txt.t("Allow MarginGo to install updates, then go back",
                    "Дозволи MarginGo да инсталира ажурирања, па се врати",
                    "Tillat MarginGo å installere oppdateringer, og gå tilbake"), Toast.LENGTH_LONG).show();
            return;
        }
        toast(Txt.t("Downloading update…", "Преузимам ажурирање…", "Laster ned oppdatering…"));
        new Thread(() -> ApkInstaller.downloadAndInstall(this, this::toast)).start();
    }

    private void toast(final String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    private long installedVersionCode() throws Exception {
        PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    private void showUpdateDialog(String version) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle(Txt.t("Update available", "Ново ажурирање", "Oppdatering tilgjengelig"))
                .setMessage(Txt.t("MarginGo " + version + " is ready. Install it now? Your data stays in place.",
                        "MarginGo " + version + " је спреман. Инсталирати сада? Подаци остају.",
                        "MarginGo " + version + " er klar. Installere nå? Dataene dine beholdes."))
                .setPositiveButton(Txt.t("Update", "Ажурирај", "Oppdater"), (d, w) -> startApkInstall())
                .setNegativeButton(Txt.t("Later", "Касније", "Senere"), null)
                .show();
    }

    /** Methods index.html can call as window.MarginGoAndroid.*. */
    private class Bridge {
        @JavascriptInterface
        public String getVersion() {
            String page = BuildConfig.SELF_UPDATE ? web.activeId() : "";
            return page.isEmpty() ? BuildConfig.VERSION_NAME : BuildConfig.VERSION_NAME + " · page " + page;
        }

        /** False in the Google Play build: the page hides "Check for updates". */
        @JavascriptInterface
        public boolean canSelfUpdate() {
            return BuildConfig.SELF_UPDATE;
        }

        /** Called by index.html once it has rendered; proves a downloaded page works. */
        @JavascriptInterface
        public void ready() {
            runOnUiThread(() -> {
                pageReady = true;
                deliverShare();
            });
        }

        @JavascriptInterface
        public void checkForUpdate() {
            if (BuildConfig.SELF_UPDATE) runOnUiThread(() -> MainActivity.this.checkForUpdate(true));
        }

        /** Saves a backup or CSV; WebView cannot download blob: URLs. */
        @JavascriptInterface
        public void saveFile(final String name, final String mime, final String text) {
            runOnUiThread(() -> {
                pendingSaveText = text;
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType(mime);
                i.putExtra(Intent.EXTRA_TITLE, name);
                try {
                    startActivityForResult(i, REQ_SAVE_FILE);
                } catch (ActivityNotFoundException e) {
                    pendingSaveText = null;
                    Toast.makeText(MainActivity.this, Txt.t("No app available to save files",
                            "Нема апликације за чување фајлова", "Ingen app for å lagre filer"), Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Uri uri = (resultCode == RESULT_OK && data != null) ? data.getData() : null;

        if (requestCode == REQ_PICK_FILE && pendingPick != null) {
            pendingPick.onReceiveValue(uri != null ? new Uri[]{uri} : null);
            pendingPick = null;
        } else if (requestCode == REQ_SAVE_FILE) {
            String text = pendingSaveText;
            pendingSaveText = null;
            if (uri == null || text == null) return;
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
                Toast.makeText(this, Txt.t("File saved", "Фајл сачуван", "Fil lagret"), Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, Txt.t("Could not save the file", "Фајл није сачуван", "Kunne ikke lagre filen"),
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onBackPressed() {
        // The page closes its open sheet first; only leave the app when nothing is open.
        webView.evaluateJavascript("window.mgBack ? window.mgBack() : false", value -> {
            if (!"true".equals(value)) {
                if (webView.canGoBack()) webView.goBack();
                else finish();
            }
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }
}

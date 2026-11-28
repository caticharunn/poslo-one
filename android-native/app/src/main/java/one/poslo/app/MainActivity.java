package one.poslo.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.browser.customtabs.CustomTabColorSchemeParams;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * POSLO ONE: vlastiti WebView koji prikazuje poslo.one bez preglednika i adresne trake.
 * - poslo.one ostaje u aplikaciji; ostali linkovi idu u preglednik ili odgovarajuću aplikaciju
 * - Google prijava ide preko preglednika (Google ne dozvoljava prijavu u WebView-u),
 *   a nazad se vraća preko one.poslo.app://auth
 */
public class MainActivity extends Activity {
    private static final String HOST = "poslo.one";
    private static final String START = "https://poslo.one/app?source=app";
    private static final String SUPABASE_HOST = "qpptpbjksrqfqjunggrq.supabase.co";
    private static final int BG = Color.parseColor("#030712");
    private static final int FILE_REQUEST = 41;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private boolean offline;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.App);
        super.onCreate(savedInstanceState);

        // Pun ekran; sadržaj se odmiče od statusne i navigacijske trake.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        web = new WebView(this);
        web.setBackgroundColor(BG);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setAllowFileAccess(false);
        s.setUserAgentString(s.getUserAgentString() + " PosloOneApp/2");
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        web.setDownloadListener((url, ua, cd, mime, len) -> openExternal(Uri.parse(url)));

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else if (!handleIntent(getIntent())) web.loadUrl(START);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    /** Povratak s Google prijave ili otvaranje poslo.one linka u aplikaciji. */
    private boolean handleIntent(Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        if (data == null) return false;
        if ("one.poslo.app".equals(data.getScheme()) && "auth".equals(data.getHost())) {
            String rest = data.getQueryParameter("u");
            if (rest == null || !(rest.startsWith("?") || rest.startsWith("#"))) rest = "";
            web.loadUrl("https://" + HOST + "/nalog" + rest);
            return true;
        }
        if ("https".equals(data.getScheme()) && HOST.equals(data.getHost())) {
            web.loadUrl(data.toString());
            return true;
        }
        return false;
    }

    private class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            String scheme = u.getScheme() == null ? "" : u.getScheme();
            String host = u.getHost() == null ? "" : u.getHost();
            if ("https".equals(scheme) && (HOST.equals(host) || host.endsWith("." + HOST))) return false;
            if ("https".equals(scheme) && SUPABASE_HOST.equals(host) && u.getPath() != null && u.getPath().startsWith("/auth/v1/authorize")) {
                openTab(withAppReturn(u));
                return true;
            }
            if ("intent".equals(scheme)) {
                try {
                    Intent i = Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME);
                    i.addCategory(Intent.CATEGORY_BROWSABLE);
                    i.setComponent(null);
                    i.setSelector(null);
                    startActivity(i);
                } catch (Exception ignored) { }
                return true;
            }
            if ("http".equals(scheme) || "https".equals(scheme)) openTab(u);
            else openExternal(u);
            return true;
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
            if (!req.isForMainFrame()) return;
            offline = true;
            view.loadDataWithBaseURL(null, OFFLINE_HTML, "text/html", "utf-8", null);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (url != null && url.startsWith("https://")) offline = false;
            CookieManager.getInstance().flush();
        }
    }

    /** OAuth adresi dodaje app=1 na povratnu adresu, da se stranica u pregledniku vrati u aplikaciju. */
    private Uri withAppReturn(Uri u) {
        String back = u.getQueryParameter("redirect_to");
        if (back == null || back.contains("app=1")) return u;
        String hash = "";
        int h = back.indexOf('#');
        if (h >= 0) { hash = back.substring(h); back = back.substring(0, h); }
        back = back + (back.contains("?") ? "&" : "?") + "app=1" + hash;
        Uri.Builder b = u.buildUpon().clearQuery();
        for (String name : u.getQueryParameterNames()) {
            for (String value : u.getQueryParameters(name)) {
                b.appendQueryParameter(name, "redirect_to".equals(name) ? back : value);
            }
        }
        return b.build();
    }

    private void openTab(Uri u) {
        try {
            CustomTabsIntent tab = new CustomTabsIntent.Builder()
                .setDefaultColorSchemeParams(new CustomTabColorSchemeParams.Builder().setToolbarColor(BG).build())
                .setShowTitle(true)
                .build();
            tab.launchUrl(this, u);
        } catch (ActivityNotFoundException e) {
            openExternal(u);
        }
    }

    private void openExternal(Uri u) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (ActivityNotFoundException ignored) { }
    }

    private class Chrome extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = callback;
            try {
                startActivityForResult(params.createIntent(), FILE_REQUEST);
            } catch (ActivityNotFoundException e) {
                fileCallback = null;
                return false;
            }
            return true;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_REQUEST && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if (offline) { retry(); return; }
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    private void retry() {
        offline = false;
        web.loadUrl(START);
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        if (offline) retry();
    }

    @Override
    protected void onPause() {
        web.onPause();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    private static final String OFFLINE_HTML =
        "<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1'>"
        + "<style>body{margin:0;height:100vh;display:flex;align-items:center;justify-content:center;background:#030712;"
        + "color:#e2e8f0;font-family:sans-serif;text-align:center}button{margin-top:20px;padding:14px 28px;border:0;"
        + "border-radius:14px;background:#2563eb;color:#fff;font-size:16px;font-weight:700}</style></head><body><div>"
        + "<h2>Nema interneta</h2><p>Provjeri vezu pa pokušaj ponovo.</p>"
        + "<button onclick=\"location.href='" + START + "'\">Pokušaj ponovo</button></div></body></html>";
}

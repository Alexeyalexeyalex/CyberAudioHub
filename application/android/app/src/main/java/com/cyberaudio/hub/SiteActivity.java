package com.cyberaudio.hub;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;

/** Расширенные функции сайта (в том числе администрирование) в той же сессии. */
public class SiteActivity extends AppCompatActivity {
    private WebView web;
    private ValueCallback<Uri[]> picker;
    private Uri origin;

    public static void open(Activity from, String path, String title) {
        from.startActivity(new Intent(from, SiteActivity.class).putExtra("path", path).putExtra("title", title));
    }

    @Override @android.annotation.SuppressLint("SetJavaScriptEnabled")
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Api api = new Api(this);
        origin = Uri.parse(api.server());
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top = Ui.row(this);
        Button back = Ui.button(this, "Назад", Ui.SECONDARY);
        back.setOnClickListener(v -> goBack()); top.addView(back);
        TextView title = Ui.label(this, getIntent().getStringExtra("title"), Ui.TEXT, 16);
        title.setPadding(Ui.dp(this, 12), 0, 0, 0); top.addView(title);
        root.addView(top);
        web = new WebView(this);
        web.setBackgroundColor(Ui.DARK);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.getSettings().setBlockNetworkImage(Settings.noImages(this));
        web.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return !sameOrigin(req.getUrl());
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> result, FileChooserParams params) {
                if (picker != null) picker.onReceiveValue(null);
                picker = result;
                try { startActivityForResult(params.createIntent(), 12); }
                catch (android.content.ActivityNotFoundException e) { picker.onReceiveValue(null); picker = null; }
                return true;
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        Ui.screen(this, root, -1);
        String path = "/admin".equals(getIntent().getStringExtra("path")) ? "/admin" : "/";
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptThirdPartyCookies(web, false);
        String value = api.cookieHeader().isEmpty() ? "session=; Max-Age=0; Path=/" : api.cookieHeader() + "; Path=/; HttpOnly";
        cookies.setCookie(api.server(), value, accepted -> web.loadUrl(api.server() + path));
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { goBack(); }
        });
    }

    private boolean sameOrigin(Uri uri) {
        return java.util.Objects.equals(origin.getScheme(), uri.getScheme())
                && java.util.Objects.equals(origin.getHost(), uri.getHost()) && origin.getPort() == uri.getPort();
    }
    private void goBack() { if (web.canGoBack()) web.goBack(); else finish(); }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 12 && picker != null) {
            picker.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data)); picker = null;
        }
    }
    @Override protected void onDestroy() {
        if (picker != null) picker.onReceiveValue(null);
        web.destroy(); super.onDestroy();
    }
}

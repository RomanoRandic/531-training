package app.f531.training;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final int FILE_CHOOSER = 1001;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.parseColor("#0a0a0c"));
        getWindow().setNavigationBarColor(Color.parseColor("#0a0a0c"));

        WebViewAssetLoader assets = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0a0a0c"));
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        web.addJavascriptInterface(new BackupBridge(), "AndroidBridge");
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), FILE_CHOOSER);
                } catch (Exception e) {
                    fileCallback = null;
                    Toast.makeText(MainActivity.this, "Could not open a file picker", Toast.LENGTH_LONG).show();
                    return false;
                }
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assets.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript(
                        "(function(){if(window.__apkSave||typeof exportBackup!=='function'||!window.AndroidBridge)return;"
                                + "window.__apkSave=true;var orig=exportBackup;"
                                + "window.exportBackup=function(){try{AndroidBridge.saveText('531-backup-'+fileDay()+'.csv','\\uFEFF'+buildCsv())}catch(e){orig()}}})();",
                        null);
            }
        });
        setContentView(web);
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html");
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != FILE_CHOOSER) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        ValueCallback<Uri[]> callback = fileCallback;
        fileCallback = null;
        if (callback == null) return;
        Uri[] uris = null;
        if (resultCode == RESULT_OK && data != null) {
            uris = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
        }
        callback.onReceiveValue(uris);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    private final class BackupBridge {
        @JavascriptInterface
        public void saveText(String name, String text) {
            runOnUiThread(() -> writeBackup(name, text == null ? "" : text));
        }
    }

    private void writeBackup(String name, String text) {
        String safe = safeName(name);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, safe);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IOException("no uri");
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new IOException("no stream");
                    out.write(text.getBytes(StandardCharsets.UTF_8));
                }
                values.clear();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(uri, values, null, null);
            } else {
                File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null) throw new IOException("no dir");
                if (!dir.exists() && !dir.mkdirs()) throw new IOException("no dir");
                try (FileOutputStream out = new FileOutputStream(new File(dir, safe))) {
                    out.write(text.getBytes(StandardCharsets.UTF_8));
                }
            }
            String where = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? "Backup saved to Downloads"
                    : "Backup saved";
            Toast.makeText(this, where, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Could not save backup", Toast.LENGTH_LONG).show();
        }
    }

    private static String safeName(String name) {
        String base = name == null ? "531-backup.csv" : name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (base.isEmpty() || ".".equals(base) || "..".equals(base)) base = "531-backup.csv";
        if (!base.toLowerCase().endsWith(".csv")) base = base + ".csv";
        if (base.length() > 80) base = base.substring(0, 76) + ".csv";
        return base;
    }
}

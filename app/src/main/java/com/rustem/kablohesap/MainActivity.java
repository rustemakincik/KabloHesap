package com.rustem.kablohesap;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.io.File;
import java.io.FileOutputStream;

public class MainActivity extends Activity {
    private static final int PDF_W = 794;   // A4 genişliği, CSS px
    private static final int PDF_H = 1123;  // A4 yüksekliği, CSS px
    private WebView web;
    private FrameLayout root;
    private boolean pdfBusy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView.enableSlowWholeDocumentDraw();
        root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setTextZoom(100);

        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("file".equals(u.getScheme())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });

        boolean dark = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        setBars(dark);

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl("file:///android_asset/www/index.html");
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.appBack ? window.appBack() : false)", value -> {
            if ("true".equals(value)) return;
            finish();
        });
    }

    private void setBars(boolean dark) {
        Window w = getWindow();
        int bg = dark ? Color.parseColor("#11151B") : Color.parseColor("#F3F5F8");
        w.setStatusBarColor(bg);
        w.setNavigationBarColor(bg);
        web.setBackgroundColor(bg);
        int flags = w.getDecorView().getSystemUiVisibility();
        if (dark) {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        } else {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        w.getDecorView().setSystemUiVisibility(flags);
    }

    /* Tek sayfalık A4 PDF: HTML ekran dışı bir WebView'da çizilir, PdfDocument'e aktarılır, paylaşılır. */
    private void makePdf(final String html, final String fileName) {
        if (pdfBusy) return;
        pdfBusy = true;
        final float d = getResources().getDisplayMetrics().density;
        final int wPx = Math.round(PDF_W * d);
        final int hPx = Math.round(PDF_H * d);
        final WebView pw = new WebView(this);
        pw.getSettings().setJavaScriptEnabled(false);
        pw.getSettings().setTextZoom(100);
        pw.setBackgroundColor(Color.WHITE);
        pw.setVerticalScrollBarEnabled(false);
        pw.setHorizontalScrollBarEnabled(false);
        root.addView(pw, 0, new FrameLayout.LayoutParams(wPx, hPx));
        pw.setWebViewClient(new WebViewClient() {
            boolean started = false;
            @Override
            public void onPageFinished(WebView v, String url) {
                if (started) return;
                started = true;
                v.postDelayed(() -> finishPdf(pw, wPx, hPx, fileName), 700);
            }
        });
        pw.loadDataWithBaseURL("file:///android_asset/www/", html, "text/html", "utf-8", null);
    }

    private void finishPdf(WebView pw, int wPx, int hPx, String fileName) {
        boolean ok = false;
        PdfDocument doc = new PdfDocument();
        try {
            PdfDocument.Page page = doc.startPage(new PdfDocument.PageInfo.Builder(595, 842, 1).create());
            Canvas c = page.getCanvas();
            float s = 595f / wPx;
            c.scale(s, s);
            c.clipRect(0, 0, wPx, hPx);
            pw.draw(c);
            doc.finishPage(page);
            File dir = new File(getCacheDir(), "pdf");
            if (!dir.exists()) dir.mkdirs();
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) f.delete();
            String safe = fileName == null ? "Kablo-Hesap.pdf" : fileName.replaceAll("[^\\p{L}\\p{N}_.-]+", "-");
            if (!safe.toLowerCase().endsWith(".pdf")) safe += ".pdf";
            File out = new File(dir, safe);
            try (FileOutputStream fo = new FileOutputStream(out)) { doc.writeTo(fo); }
            ok = true;
            Uri uri = new Uri.Builder().scheme("content").authority(getPackageName() + ".pdf").appendPath(out.getName()).build();
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("application/pdf");
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.setClipData(ClipData.newRawUri("", uri));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Raporu paylaş"));
        } catch (Exception e) {
            ok = false;
        } finally {
            doc.close();
            root.removeView(pw);
            pw.destroy();
            pdfBusy = false;
            web.evaluateJavascript("window.pdfDone && window.pdfDone(" + ok + ")", null);
        }
    }

    private class Bridge {
        @JavascriptInterface
        public void share(String text) {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(i, "Paylaş"));
            });
        }

        @JavascriptInterface
        public void sharePdf(String html, String fileName) {
            runOnUiThread(() -> makePdf(html, fileName));
        }
    }
}

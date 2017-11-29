package be.gesprokengazet.news;

import android.annotation.SuppressLint;
import android.content.res.Resources;
import android.support.annotation.NonNull;
import android.text.Html;
import android.text.Spanned;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.LinkedHashMap;
import java.util.Map;

import be.gesprokengazet.R;

/**
 * Scrapes the news website to obtain a list of articles URL's, and to download the content of
 * a specific article.
 * <p>
 * It makes use of a single WebView to access the web, therefore no concurrent access is allowed.
 * This must be guaranteed by the calling code.
 */
class WebScraper<T extends WebResource> {
    private final WebView mWebView;
    private final Resources mResources;

    WebScraper(WebView webView, Resources resources) {
        this.mWebView = webView;
        this.mResources = resources;
    }


    /**
     * Scrapes a url using the given piece of JavaScript. The JS should call
     * 'window.ContentScraper.content(string)'
     * for every piece of data it finds, and call 'window.ContentScraper.finished()' when done.
     * <p>
     * It must NOT be called concurrently.
     *
     * @param resource        The url to scrape.
     * @param callBack   The callback called by the JavaScript code.
     */
    @SuppressLint("SetJavaScriptEnabled")
    void scrape(final T resource, final String javaScript, final WebResourceCompleted<T> callBack) {
        mWebView.getSettings().setJavaScriptEnabled(true);
        mWebView.getSettings().setBlockNetworkImage(true); //reduce loaded data
        mWebView.removeJavascriptInterface("ContentScraper");
        mWebView.addJavascriptInterface(new JavascriptCallback<>(resource, callBack), "ContentScraper");
        mWebView.setWebViewClient(new WebViewClient() {
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                callBack.onError(resource, mResources.getString(R.string.error_no_connection));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // Inject JavaScript into loaded page to scrape its relevant content, the JS invokes the methods of the call back.
                mWebView.loadUrl("javascript:" + javaScript);
            }
        });

        mWebView.loadUrl(resource.getUrl());
    }

    // Called back from the Javascript side. A series of content calls are expected, followed by
    // a finished call.
    private static class JavascriptCallback<T extends WebResource> {
        private final Map<String, String> content = new LinkedHashMap<>();
        private final T resource;
        private final WebResourceCompleted<T> callBack;

        JavascriptCallback(T resource, WebResourceCompleted<T> callBack) {
            this.resource = resource;
            this.callBack = callBack;
        }

        @JavascriptInterface
        @SuppressWarnings("unused")
        public void content(String text, String url) {
            if (url == null) url = "";
            this.content.put(cleanString(text), url);
        }

        @JavascriptInterface
        @SuppressWarnings("unused")
        public void finished() {
            resource.setContent(content);
            callBack.onSuccess(resource);
        }

        @NonNull
        private String cleanString(String text) {
            // From https://stackoverflow.com/questions/6502759/how-to-strip-or-escape-html-tags-in-android
            Spanned result;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                result = Html.fromHtml(text,Html.FROM_HTML_MODE_LEGACY);
            } else {
                result = Html.fromHtml(text);
            }

            return result.toString();
        }
    }
}

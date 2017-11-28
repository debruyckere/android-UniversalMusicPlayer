package be.gesprokengazet.news;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;

import be.gesprokengazet.utils.LogHelper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages downloading the resources. It only downloads one resource at a time, but many resources can
 * be scheduled (or removed). It informs a callback when new content becomes available, or when an
 * error occurred.
 */
public class DownloadManager<T extends WebResource> {
    private static final String TAG = LogHelper.makeLogTag(DownloadManager.class);

    // Access to these is synchronized on resourcesToDownload
    private final Map<T, List<WebResourceCompleted<T>>> mResourcesToDownload = new LinkedHashMap<>();
    private T mOngoingDownload;

    private final Handler mMessageHandler;
    private final WebScraper<T> mScraper;
    private final WebView mWebView;
    private final String mScrapingJavascript;

    /**
     * Creates a new download manager.
     *
     * @param context            Android context.
     * @param scrapingJavascript The relevant scraping Javascript.
     */
    public DownloadManager(Context context,
                           String scrapingJavascript) {
        this.mMessageHandler = new Handler(Looper.getMainLooper());
        this.mWebView = new WebView(context);
        this.mScrapingJavascript = scrapingJavascript;
        this.mScraper = new WebScraper<>(mWebView, context.getResources());
    }

    public void destroy() {
        //todo: shutdown more gracefully, e.g. cancel ongoing tasks
        mWebView.destroy();
    }

    /**
     * Schedules the given resource for download. The callback given to the constructor is informed
     * when the download is available.
     *
     * @param resource The resource to download.
     * @param callback The callback that is notified when the download has finished, or failed.
     */
    public void scheduleForDownload(final T resource, final WebResourceCompleted<T> callback) {
        mMessageHandler.post(new Runnable() {
            @Override
            public void run() {
                Util.assertUIThread();

                // Content has been downloaded already
                if (!resource.getContent().isEmpty()) {
                    callback.onSuccess(resource);
                } else {
                    // No resource content is available, so go download it.
                    synchronized (mResourcesToDownload) {
                        List<WebResourceCompleted<T>> callbacks = mResourcesToDownload.get(resource);
                        if (callbacks == null) {
                            callbacks = new ArrayList<>();
                            mResourcesToDownload.put(resource, callbacks);
                        }
                        callbacks.add(callback);

                        // Kick off the downloading loop.
                        if (mOngoingDownload == null) {
                            startDownloading();
                        }
                    }
                }
            }
        });
    }

    public void removeForDownload(T resource) {
        synchronized (mResourcesToDownload) {
            mResourcesToDownload.remove(resource);
        }
    }

    /**
     * Downloads resource content, it takes the first element from the set of resources to download.
     * It is resilient against changes made to the list between scheduling and executing this method.
     * It repeatedly calls itself until the list of things to download is empty.
     */
    private void startDownloading() {
        Util.assertUIThread();

        // Grab the first resource from the work list
        final T resource;
        final List<WebResourceCompleted<T>> callbacks;
        synchronized (mResourcesToDownload) {
            if (mResourcesToDownload.isEmpty()) {
                return;
            }
            Map.Entry<T, List<WebResourceCompleted<T>>> first = mResourcesToDownload.entrySet().iterator().next();
            resource = first.getKey();
            callbacks = first.getValue();
            mOngoingDownload = resource;
        }

        mScraper.scrape(resource, mScrapingJavascript, new WebResourceCompleted<T>() {
            @Override
            public void onSuccess(final T resource) {
                postDownload(resource);
                mMessageHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        for (WebResourceCompleted<T> callback : callbacks) {
                            callback.onSuccess(resource);
                        }
                    }
                });
            }

            @Override
            public void onError(final T resource, final String message) {
                postDownload(resource);

                LogHelper.w(TAG, message);
                mMessageHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        for (WebResourceCompleted<T> callback : callbacks) {
                            callback.onError(resource, message);
                        }
                    }
                });
            }
        });
    }

    private void postDownload(T resource) {
        synchronized (mResourcesToDownload) {
            mResourcesToDownload.remove(resource);

            // Go download the other resources if needed.
            if (!mResourcesToDownload.isEmpty()) {
                mMessageHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        startDownloading();
                    }
                });
            } else {
                mOngoingDownload = null;
            }
        }
    }
}

package com.example.android.uamp.model;

import android.content.Context;
import android.support.v4.media.MediaMetadataCompat;

import com.example.android.uamp.R;
import com.example.android.uamp.news.DownloadManager;
import com.example.android.uamp.news.NewsSiteConfiguration;
import com.example.android.uamp.news.TableOfContents;
import com.example.android.uamp.news.WebResourceCompleted;
import com.example.android.uamp.utils.LogHelper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Utility class to get a list of MusicTrack's by scraping a news website.
 */
public class NewsSource implements MusicProviderSource {

    private static final String TAG = LogHelper.makeLogTag(NewsSource.class);

    private final Context mContext;
    private final NewsSiteConfiguration mConfig;
    private final TableOfContents mToc;
    private final DownloadManager<TableOfContents> mDownloadManager;

    public NewsSource(Context context, NewsSiteConfiguration config) {
        mContext = context;
        mConfig = config;
        mToc = new TableOfContents(config.getTableOfContentsURL());
        mDownloadManager = new DownloadManager<>(context, config.getTocScrapingJavascript());
    }

    public void destroy() {
        mDownloadManager.destroy();
    }

    @Override
    public Iterator<MediaMetadataCompat> iterator() {

        //Reschedule the download, in case it failed the first time. Has no effect if download succeeded last time.
        final CountDownLatch tocComplete = new CountDownLatch(1);
        final String[] error = new String[1];
        mDownloadManager.scheduleForDownload(mToc, new WebResourceCompleted<TableOfContents>() {
            @Override
            public void onSuccess(TableOfContents resource) {
                tocComplete.countDown();
            }

            @Override
            public void onError(TableOfContents resource, String message) {
                tocComplete.countDown();
                error[0] = message;
            }
        });

        try {
            boolean success = tocComplete.await(30, TimeUnit.SECONDS);
            if (!success) {
                throw new RuntimeException(mContext.getResources().getString(R.string.error_download_failed));
            }
            if (error[0] != null ){
                throw new RuntimeException(error[0]);
            }

            ArrayList<MediaMetadataCompat> tracks = new ArrayList<>();
            ArrayList<String> all = new ArrayList<>(mToc.getTitlesAndURLs().values());
            for (Map.Entry<String, String> entry : mToc.getTitlesAndURLs().entrySet()) {
                tracks.add(buildFromURL(entry.getKey(), entry.getValue(), all));
            }
            return tracks.iterator();
        } catch (InterruptedException e) {
            LogHelper.e(TAG, e, "Could not retrieve music list");
            throw new RuntimeException("Could not retrieve music list", e);
        }
    }

    private MediaMetadataCompat buildFromURL(String title, String url, List<String> allUrls) {
        return new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, url)
                .putString(MusicProviderSource.CUSTOM_METADATA_TRACK_SOURCE, url)
                .putString(MusicProviderSource.CUSTOM_METADATA_TRACK_LANGUAGE, mConfig.getLocale().getLanguage())
                .putString(MusicProviderSource.CUSTOM_METADATA_TRACK_COUNTRY, mConfig.getLocale().getCountry())
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, mConfig.getName())
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, mConfig.getName())
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, 60*60*1000) // we don't know the duration, just use a too large value
                .putString(MediaMetadataCompat.METADATA_KEY_GENRE, mConfig.getName())
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, allUrls.indexOf(url))
                .putLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS, allUrls.size())
                .build();
    }
}

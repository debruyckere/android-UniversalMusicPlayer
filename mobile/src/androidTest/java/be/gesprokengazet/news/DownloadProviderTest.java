package be.gesprokengazet.news;

import android.content.Context;
import android.support.test.InstrumentationRegistry;
import android.support.test.annotation.UiThreadTest;
import android.support.test.rule.UiThreadTestRule;
import android.support.test.runner.AndroidJUnit4;

import org.hamcrest.CoreMatchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

@RunWith(AndroidJUnit4.class)
public class DownloadProviderTest {
    @Rule
    public UiThreadTestRule uiRule = new UiThreadTestRule();

    private TableOfContents mToc;
    private DownloadManager<TableOfContents> mTocDownloader;
    private DownloadManager<Article> mArticleDownloader;


    @UiThreadTest
    @Before
    public void init() throws Throwable {
        // Despite what the docs of UiThreadTestRule claim, this method isn't executed on the UI thread.
        uiRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Context context = InstrumentationRegistry.getTargetContext();
                VRTNewsSiteConfiguration config = new VRTNewsSiteConfiguration(context);
                mToc = new TableOfContents(config.getTableOfContentsURL());
                mTocDownloader = new DownloadManager<>(context, config.getTocScrapingJavascript());
                mArticleDownloader = new DownloadManager<>(context, config.getArticleScrapingJavascript());
            }
        });
    }

    @After
    public void destroy() throws Throwable {
        uiRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mTocDownloader.destroy();
                mArticleDownloader.destroy();
            }
        });
    }

    @Test
    public void verifyTableOfContents() throws Exception {
        downloadTableOfContents();
        assertThat("Expected at least 30 articles", mToc.getTitlesAndURLs().size() > 30, is(true)); //why can't I use gt ?

        for (Map.Entry<String, String> e : mToc.getTitlesAndURLs().entrySet()) {
            assertThat("Expecting title of at least 2 chars", e.getKey().length() > 2, is(true));
            URL url = new URL(e.getValue()); // verifies format of url
            assertThat("Expecting url to be at least 10 chars", url.toExternalForm().length() > 10, is(true));
        }
    }

    // Do NOT run on the UI thread, that would deadlock as we block for the result to become available.
    private void downloadTableOfContents() throws InterruptedException {
        final CountDownLatch done = new CountDownLatch(1);
        final String[] error = new String[1];
        mTocDownloader.scheduleForDownload(mToc, new WebResourceCompleted<TableOfContents>() {
            @Override
            public void onSuccess(TableOfContents resource) {
                done.countDown();
            }

            @Override
            public void onError(TableOfContents resource, String message) {
                error[1] = message;
                done.countDown();
            }
        });

        boolean success = done.await(30, TimeUnit.SECONDS);
        assertThat("Timed out waiting for toc to download", success, is(true));
        assertThat("Error occurred", error[0], CoreMatchers.nullValue());
    }

    @Test
    public void verifyArticleContents() throws Exception {
        downloadTableOfContents();

        int articleCount = 3;
        final CountDownLatch done = new CountDownLatch(articleCount);
        final List<String> errors = new ArrayList<>();
        List<Article> articles = new ArrayList<>();
        Iterator<Map.Entry<String, String>> iterator = mToc.getTitlesAndURLs().entrySet().iterator();
        for (int i = 0; i < articleCount; i++ ) {
            Map.Entry<String, String> titleAndUrl = iterator.next();
            Article article = new Article(titleAndUrl.getValue());
            articles.add(article);
            mArticleDownloader.scheduleForDownload(article, new WebResourceCompleted<Article>() {
                @Override
                public void onSuccess(Article resource) {
                    done.countDown();
                }

                @Override
                public void onError(Article resource, String message) {
                    errors.add(message);
                    done.countDown();
                }
            });
        }
        boolean success = done.await(30, TimeUnit.SECONDS);
        assertThat("Timed out waiting for articles to download", success, is(true));
        assertThat("Error occurred: " + errors, errors.isEmpty(), is(true));

        for (Article article : articles) {
            assertThat(article.getText().isEmpty(), is(false));
        }
    }
}

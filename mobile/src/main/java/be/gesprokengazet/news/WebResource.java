package be.gesprokengazet.news;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resource that is defined by an URL and for which the content is be downloaded asynchronously.
 */
public class WebResource {
    private Map<String, String> mContent = new LinkedHashMap<>();
    private final String mUrl;

    WebResource(String url) {
        this.mUrl = url;
    }

    public String getUrl() {
        return mUrl;
    }

    @Override
    public String toString() {
        return mUrl;
    }

    public synchronized void setContent(Map<String, String> content) {
        mContent = content;
    }

    public synchronized Map<String, String> getContent() {
        return mContent;
    }
}

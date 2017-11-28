package com.example.android.uamp.news;

import java.util.Map;

/**
 * The table of contents of the news website. It is represented as a list of URL's referring to
 * the news articles. Every URL has a matching title.
 */
public class TableOfContents extends WebResource {
    public TableOfContents(String url) {
        super(url);
    }

    public Map<String, String> getTitlesAndURLs() {
        return getContent();
    }
}

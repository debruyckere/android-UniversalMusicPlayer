package com.example.android.uamp.news;

import java.util.ArrayList;
import java.util.List;

/**
 * An article of the news site. It is composed of a list of strings, where each string
 * represents a paragraph of text, a title ...
 */
public class Article extends WebResource {
    public Article(String url) {
        super(url);
    }

    public List<String> getText() {
        return new ArrayList<>(getContent().keySet());
    }
}

package be.gesprokengazet.news;

import java.util.Locale;

/**
 * Configuration for a news website.
 */
public abstract class NewsSiteConfiguration {
    /**
     * @return the locale of the website, to be sure to speak in the right language.
     */
    public abstract Locale getLocale();

    /**
     * @return the name of the news site.
     */
    public abstract String getName();

    /**
     * @return the main url that contains the overview of all articles.
     */
    public abstract String getTableOfContentsURL();

    /**
     * @return The Javascript that can be used to scrape the links to all articles from the TOC
     * page.
     */
    public abstract String getTocScrapingJavascript();

    /**
     * @return The Javascript that can be used to scrape the actual relevant content from the
     * article pages themselves.
     */
    public abstract String getArticleScrapingJavascript();
}
package com.example.android.uamp.news;

import android.content.Context;

import com.example.android.uamp.R;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Locale;

/**
 * News site configuration for vrtnws.be
 */
public class VRTNewsSiteConfiguration extends NewsSiteConfiguration {
    private final String mTocJS;
    private final String mArticleJS;

    public VRTNewsSiteConfiguration(Context context) {
        try {
            mTocJS = readRawFile(context, R.raw.vrt_toc);
            mArticleJS = readRawFile(context, R.raw.vrt_article);
        } catch (IOException e) {
            throw new RuntimeException(e); //if it happens, it is a programming error
        }
    }

    private String readRawFile(Context context, int id) throws IOException {
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(context.getResources().openRawResource(id)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line);

            }
        }
        return content.toString();
    }

    @Override
    public Locale getLocale() {
        return new Locale("nl", "BE");
    }

    @Override
    public String getName() {
        return "VRT News";
    }

    @Override
    public String getTableOfContentsURL() {
        return "https://www.vrt.be/vrtnws/nl";
    }

    @Override
    public String getTocScrapingJavascript() {
        return mTocJS;
    }

    @Override
    public String getArticleScrapingJavascript() {
        return mArticleJS;
    }
}

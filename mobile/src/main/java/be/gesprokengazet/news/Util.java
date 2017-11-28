package be.gesprokengazet.news;

import android.os.Looper;

public class Util {
    private Util() {
    }

    public static void assertUIThread() {
        if (Looper.getMainLooper().getThread() != Thread.currentThread()) {
            // Programming error, no need to translate.
            throw new RuntimeException("Expected to be on the UI thread but was called on thread: " + Thread.currentThread());
        }
    }

}

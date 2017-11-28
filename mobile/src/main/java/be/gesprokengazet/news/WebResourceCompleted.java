package be.gesprokengazet.news;

/**
 * Callback when a resource has completed downloading.
 */
public interface WebResourceCompleted<T extends WebResource> {
    void onSuccess(T resource);
    void onError(T resource, String message);
}

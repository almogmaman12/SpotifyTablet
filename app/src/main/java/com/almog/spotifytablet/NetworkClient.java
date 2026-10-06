package com.almog.spotifytablet;

import java.util.concurrent.TimeUnit;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;

/**
 * Singleton network client provider to maximize connection reuse and prevent socket leaks.
 */
public final class NetworkClient {
    private static volatile OkHttpClient defaultClient;
    private static volatile OkHttpClient authClient;

    private NetworkClient() {}

    /**
     * Shared general OkHttpClient instance.
     */
    public static OkHttpClient getInstance() {
        if (defaultClient == null) {
            synchronized (NetworkClient.class) {
                if (defaultClient == null) {
                    defaultClient = new OkHttpClient.Builder()
                            .connectionPool(new ConnectionPool(5, 5, TimeUnit.MINUTES))
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .readTimeout(20, TimeUnit.SECONDS)
                            .writeTimeout(20, TimeUnit.SECONDS)
                            .retryOnConnectionFailure(true)
                            .build();
                }
            }
        }
        return defaultClient;
    }

    /**
     * OkHttpClient instance configured with Spotify AuthInterceptor.
     */
    public static OkHttpClient getAuthInstance() {
        if (authClient == null) {
            synchronized (NetworkClient.class) {
                if (authClient == null) {
                    authClient = getInstance().newBuilder()
                            .addInterceptor(new AuthInterceptor())
                            .build();
                }
            }
        }
        return authClient;
    }
}

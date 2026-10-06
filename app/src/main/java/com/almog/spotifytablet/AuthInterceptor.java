package com.almog.spotifytablet;

import java.io.IOException;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

public class AuthInterceptor implements Interceptor {
    @Override
    public Response intercept(Chain chain) throws IOException {
        Request original = chain.request();
        String token = TokenProvider.getToken();
        String host = original.url().host();
        if (token != null && !token.isEmpty() && host.contains("spotify.com")) {
            Request modified = original.newBuilder()
                    .addHeader("Authorization", "Bearer " + token)
                    .build();
            return chain.proceed(modified);
        }
        return chain.proceed(original);
    }
}

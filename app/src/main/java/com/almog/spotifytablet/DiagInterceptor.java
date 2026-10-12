package com.almog.spotifytablet;

import android.os.SystemClock;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/** Records every HTTP call for the debug page and feeds failures to the status banner. */
public final class DiagInterceptor implements Interceptor {
    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        String host = request.url().host();
        String path = request.url().encodedPath();
        long start = SystemClock.elapsedRealtime();
        try {
            Response response = chain.proceed(request);
            DiagLog.net(host, path, response.code(), SystemClock.elapsedRealtime() - start, null);
            AppStatus.noteHttp(host, response.code(), null);
            return response;
        } catch (IOException e) {
            String msg = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            DiagLog.net(host, path, 0, SystemClock.elapsedRealtime() - start, msg);
            AppStatus.noteHttp(host, 0, msg);
            throw e;
        }
    }
}

package com.almog.spotifytablet;

public final class TokenProvider {
    private TokenProvider() {}
    private static volatile String accessToken;

    public static void setToken(String token) {
        accessToken = token;
    }

    public static String getToken() {
        return accessToken;
    }
}

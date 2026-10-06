package com.almog.spotifytablet;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;
import androidx.annotation.NonNull;
import org.json.JSONObject;
import java.io.IOException;
import okhttp3.*;

public class AuthManager {
    private static final String TAG = "SpotifyAuth";
    public static final String CLIENT_ID = Constants.SPOTIFY_CLIENT_ID;
    public static final String CLIENT_SECRET = Constants.SPOTIFY_CLIENT_SECRET;
    public static final String REDIRECT_URI = Constants.SPOTIFY_REDIRECT_URI;

    private static final String TOKEN_URI = Constants.SPOTIFY_TOKEN_URL;
    public static final String AUTH_URI = Constants.SPOTIFY_AUTH_URI;

    private final Context context;
    private final OkHttpClient http;
    public void startLogin(android.app.Activity activity) {
        android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                Uri.parse(AUTH_URI));
        activity.startActivity(intent);
    }
    public void handleAuthResponse(android.content.Intent intent, AuthCallback callback) {
        Uri data = intent.getData();
        if (data != null && data.getScheme().equals("yourapp")) {
            String code = data.getQueryParameter("code");
            if (code != null) {
                exchangeCode(code, callback);
            } else {
                String error = data.getQueryParameter("error");
                callback.onError(error != null ? error : "Unknown auth error");
            }
        }
    }


    public interface AuthCallback {
        void onTokenReceived(String token);
        void onError(String error);
    }

    public AuthManager(Context context) {
        this.context = context.getApplicationContext();
        this.http = NetworkClient.getInstance();
    }

    public void exchangeCode(String code, AuthCallback callback) {
        Log.d(TAG, "Exchanging code for token...");
        RequestBody body = new FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", REDIRECT_URI)
                .add("client_id", CLIENT_ID)
                .add("client_secret", CLIENT_SECRET)
                .build();

        Request request = new Request.Builder()
                .url(TOKEN_URI)
                .post(body)
                .build();

        http.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "exchangeCode network fail: " + e.getMessage());
                callback.onError("Network error");
            }
            @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try {
                    String responseBody = response.body().string();
                    Log.d(TAG, "Token exchange response " + response.code() + ": " + responseBody);
                    JSONObject json = new JSONObject(responseBody);
                    if (json.has("access_token")) {
                        String token = json.getString("access_token");
                        long expiry = System.currentTimeMillis() + (json.optLong("expires_in", 3600) * 1000);
                        SharedPreferences.Editor editor = getPrefs().edit()
                                .putString("access_token", token)
                                .putLong("token_expiry", expiry);
                        if (json.has("refresh_token")) {
                            editor.putString("refresh_token", json.getString("refresh_token"));
                        }
                        editor.apply();
                        callback.onTokenReceived(token);
                    } else {
                        callback.onError("No access_token: " + responseBody);
                    }
                } catch (Exception e) {
                    callback.onError("Parse error: " + e.getMessage());
                } finally {
                    response.close();
                }
            }
        });
    }

    public void getAccessToken(AuthCallback callback) {
        SharedPreferences prefs = getPrefs();
        String token = prefs.getString("access_token", null);
        long expiry = prefs.getLong("token_expiry", 0);
        String refreshToken = prefs.getString("refresh_token", null);

        boolean tokenValid = token != null && System.currentTimeMillis() < (expiry - 60_000);
        if (tokenValid) {
            Log.d(TAG, "Using cached token, expires in " + ((expiry - System.currentTimeMillis()) / 1000) + "s");
            callback.onTokenReceived(token);
        } else if (refreshToken != null) {
            Log.d(TAG, "Token expired, refreshing...");
            refreshAccessToken(refreshToken, callback);
        } else {
            callback.onError("No credentials found");
        }
    }

    private void refreshAccessToken(String refreshToken, AuthCallback callback) {
        RequestBody body = new FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("client_id", CLIENT_ID)
                .add("client_secret", CLIENT_SECRET)
                .build();

        Request request = new Request.Builder()
                .url(TOKEN_URI)
                .post(body)
                .build();

        http.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                clearTokens();
                callback.onError("Refresh network error");
            }
            @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try {
                    String responseBody = response.body().string();
                    Log.d(TAG, "Refresh response " + response.code() + ": " + responseBody);
                    JSONObject json = new JSONObject(responseBody);
                    if (json.has("access_token")) {
                        String token = json.getString("access_token");
                        long expiry = System.currentTimeMillis() + (json.optLong("expires_in", 3600) * 1000);
                        SharedPreferences.Editor editor = getPrefs().edit()
                                .putString("access_token", token)
                                .putLong("token_expiry", expiry);
                        if (json.has("refresh_token")) {
                            editor.putString("refresh_token", json.getString("refresh_token"));
                        }
                        editor.apply();
                        callback.onTokenReceived(token);
                    } else {
                        clearTokens();
                        if ("invalid_grant".equals(json.optString("error"))) {
                            callback.onError("invalid_grant");
                        } else {
                            callback.onError("Refresh failed: " + responseBody);
                        }
                    }
                } catch (Exception e) {
                    clearTokens();
                    callback.onError("Refresh parse error: " + e.getMessage());
                } finally {
                    response.close();
                }
            }
        });
    }

    public void clearTokens() {
        getPrefs().edit().remove("access_token").remove("token_expiry").remove("refresh_token").apply();
    }

    private SharedPreferences getPrefs() {
        return context.getSharedPreferences("SpotifyPrefs", Context.MODE_PRIVATE);
    }
}
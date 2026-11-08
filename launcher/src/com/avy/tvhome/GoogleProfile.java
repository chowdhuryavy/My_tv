package com.avy.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Google sign-in for TVs ("OAuth 2.0 for TV and limited-input devices"), used only to
 * read the signed-in user's profile photo and name. Scopes: openid, email, profile.
 * The user approves on their phone at google.com/device; no password is typed on the TV.
 */
final class GoogleProfile {

    static final String SCOPE = "openid email profile";

    static final class DeviceCode {
        String deviceCode, userCode, url;
        int interval = 5, expiresIn = 1800;
    }

    /** Step 1: ask Google for a code to show on the TV. */
    static DeviceCode start(String clientId) throws Exception {
        JSONObject r = post("https://oauth2.googleapis.com/device/code",
                "client_id=" + enc(clientId) + "&scope=" + enc(SCOPE));
        if (r.has("error")) throw new Exception(r.optString("error_description", r.optString("error")));
        DeviceCode d = new DeviceCode();
        d.deviceCode = r.getString("device_code");
        d.userCode = r.getString("user_code");
        d.url = r.optString("verification_url", "https://www.google.com/device");
        d.interval = r.optInt("interval", 5);
        d.expiresIn = r.optInt("expires_in", 1800);
        return d;
    }

    /** Step 2: poll until approved. Returns tokens, or null while still waiting. Throws if denied/expired. */
    static JSONObject poll(String clientId, String secret, String deviceCode) throws Exception {
        JSONObject r = post("https://oauth2.googleapis.com/token",
                "client_id=" + enc(clientId) + "&client_secret=" + enc(secret)
                + "&device_code=" + enc(deviceCode)
                + "&grant_type=" + enc("urn:ietf:params:oauth:grant-type:device_code"));
        String err = r.optString("error", "");
        if (err.isEmpty()) return r;
        if (err.equals("authorization_pending") || err.equals("slow_down")) return null;
        throw new Exception(err.equals("access_denied") ? "Sign-in was declined" : err.equals("expired_token")
                ? "The code expired, try again" : r.optString("error_description", err));
    }

    /** Fresh access token from the saved refresh token. */
    static String accessToken(String clientId, String secret, String refresh) throws Exception {
        JSONObject r = post("https://oauth2.googleapis.com/token",
                "client_id=" + enc(clientId) + "&client_secret=" + enc(secret)
                + "&refresh_token=" + enc(refresh) + "&grant_type=refresh_token");
        if (r.has("error")) throw new Exception(r.optString("error"));
        return r.getString("access_token");
    }

    /** name, given_name, email, picture */
    static JSONObject userInfo(String access) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("https://openidconnect.googleapis.com/v1/userinfo").openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Authorization", "Bearer " + access);
        try { return new JSONObject(read(c.getInputStream())); } finally { c.disconnect(); }
    }

    /** Downloads the photo at a TV-friendly size (Google photo URLs accept "=s256-c"). */
    static Bitmap photo(String pictureUrl) throws Exception {
        String url = pictureUrl.replaceAll("=s\\d+(-c)?$", "") + "=s256-c";
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        try (InputStream in = c.getInputStream()) {
            return BitmapFactory.decodeStream(in);
        } finally {
            c.disconnect();
        }
    }

    // ------------------------------------------------------------------

    private static JSONObject post(String url, String form) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try (OutputStream o = c.getOutputStream()) { o.write(form.getBytes(StandardCharsets.UTF_8)); }
        try {
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            return new JSONObject(in == null ? "{}" : read(in));
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws Exception {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    private static String enc(String s) throws Exception { return URLEncoder.encode(s, "UTF-8"); }

    private GoogleProfile() { }
}

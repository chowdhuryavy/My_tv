package com.avy.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lightweight, ad-free home screen: clock, live weather, subscription expiry
 * countdowns and the TV's apps grouped into rows.
 */
public class HomeActivity extends Activity {

    private static final String PREFS = "tvhome";
    private static final String KEY_CONFIG = "config_json";
    private static final long WEATHER_EVERY_MS = 30 * 60 * 1000L;

    private static final int BG_TOP = 0xFF0E1621;
    private static final int BG_BOTTOM = 0xFF1A2636;
    private static final int CARD = 0xFF1F2D3F;
    private static final int CARD_FOCUS = 0xFF2E4A6B;
    private static final int ACCENT = 0xFF4FC3F7;
    private static final int TEXT = 0xFFECEFF4;
    private static final int MUTED = 0xFF93A1B3;
    private static final int GREEN = 0xFF66BB6A;
    private static final int AMBER = 0xFFFFB74D;
    private static final int RED = 0xFFEF5350;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private float dp;
    private TextView clockView, dateView, weatherMain, weatherDesc, weatherExtra;
    private LinearLayout rowsBox, subsBox;
    private long lastWeatherAt = 0;
    private double lat = Double.NaN, lon = Double.NaN;
    private String place = "";

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            Date now = new Date();
            clockView.setText(new SimpleDateFormat("h:mm", Locale.getDefault()).format(now));
            dateView.setText(new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now));
            if (System.currentTimeMillis() - lastWeatherAt > WEATHER_EVERY_MS) loadWeather();
            handler.postDelayed(this, 20_000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dp = getResources().getDisplayMetrics().density;
        importConfig(getIntent());
        setContentView(buildUi());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (importConfig(intent)) {
            lastWeatherAt = 0;
            lat = Double.NaN;
            renderSubscriptions();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderApps();
        renderSubscriptions();
        handler.removeCallbacks(clockTick);
        handler.post(clockTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(clockTick);
    }

    @Override
    public void onBackPressed() {
        // Home screen: Back does nothing.
    }

    // ---------------------------------------------------------------- config

    /** Config arrives from the PC as base64 JSON: am start ... --es config_b64 <data>. */
    private boolean importConfig(Intent intent) {
        if (intent == null) return false;
        String b64 = intent.getStringExtra("config_b64");
        if (b64 == null) return false;
        try {
            String json = new String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8);
            new JSONObject(json); // validate
            prefs().edit().putString(KEY_CONFIG, json).apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }

    private JSONObject config() {
        try {
            return new JSONObject(prefs().getString(KEY_CONFIG, "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    // ---------------------------------------------------------------- layout

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{BG_TOP, BG_BOTTOM}));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(px(40), px(24), px(40), px(16));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        // Header: clock/date on the left, weather on the right.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout timeBox = new LinearLayout(this);
        timeBox.setOrientation(LinearLayout.VERTICAL);
        clockView = text("", 46, TEXT, true);
        dateView = text("", 16, MUTED, false);
        timeBox.addView(clockView);
        timeBox.addView(dateView);
        header.addView(timeBox, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout weatherBox = new LinearLayout(this);
        weatherBox.setOrientation(LinearLayout.VERTICAL);
        weatherBox.setGravity(Gravity.END);
        weatherMain = text("…", 34, TEXT, true);
        weatherMain.setGravity(Gravity.END);
        weatherDesc = text("Loading weather", 15, ACCENT, false);
        weatherDesc.setGravity(Gravity.END);
        weatherExtra = text("", 13, MUTED, false);
        weatherExtra.setGravity(Gravity.END);
        weatherBox.addView(weatherMain);
        weatherBox.addView(weatherDesc);
        weatherBox.addView(weatherExtra);
        header.addView(weatherBox);
        page.addView(header);

        // Body: app rows (left) + subscriptions card (right).
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(-1, 0, 1);
        bodyLp.topMargin = px(14);
        page.addView(body, bodyLp);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        rowsBox = new LinearLayout(this);
        rowsBox.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rowsBox);
        body.addView(scroll, new LinearLayout.LayoutParams(0, -1, 1));

        LinearLayout subsCard = new LinearLayout(this);
        subsCard.setOrientation(LinearLayout.VERTICAL);
        subsCard.setPadding(px(16), px(14), px(16), px(14));
        subsCard.setBackground(rounded(CARD, 14, 0));
        subsCard.addView(text("Subscriptions", 17, TEXT, true));
        subsBox = new LinearLayout(this);
        subsBox.setOrientation(LinearLayout.VERTICAL);
        subsCard.addView(subsBox);
        LinearLayout.LayoutParams subsLp = new LinearLayout.LayoutParams(px(250), -2);
        subsLp.leftMargin = px(20);
        subsLp.topMargin = px(26);
        body.addView(subsCard, subsLp);

        return root;
    }

    // ---------------------------------------------------------------- apps

    private static final String[][] GROUPS = {
        {"Streaming", "crunchyroll", "shahid", "starz", "osn", "sunnxt", "iqiyi", "appletv", "bstar",
            "netflix", "youtube", "amazonvideo", "disney", "hotstar", "videos", "ahaflix", "moviebox",
            "bluesun", "plex", "spotify", "mediashell"},
        {"Sports & Live", "sportzfy", "cricfy", "sport", "live", "tv.channels"},
        {"Tools", "browser", "downloader", "sendfiles", "vpn", "files", "playstore", "vending"},
    };

    private void renderApps() {
        PackageManager pm = getPackageManager();
        Map<String, ResolveInfo> apps = new LinkedHashMap<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || pkg.startsWith("com.google.android.apps.tv.launcher")) continue;
                if (!apps.containsKey(pkg)) apps.put(pkg, ri);
            }
        }

        Map<String, List<ResolveInfo>> grouped = new LinkedHashMap<>();
        for (String[] g : GROUPS) grouped.put(g[0], new ArrayList<ResolveInfo>());
        grouped.put("More apps", new ArrayList<ResolveInfo>());
        for (ResolveInfo ri : apps.values()) grouped.get(groupFor(ri.activityInfo.packageName)).add(ri);

        rowsBox.removeAllViews();
        View first = null;
        for (Map.Entry<String, List<ResolveInfo>> e : grouped.entrySet()) {
            List<ResolveInfo> list = e.getValue();
            if (list.isEmpty()) continue;
            Collections.sort(list, (a, b) -> label(a).compareToIgnoreCase(label(b)));
            LinearLayout row = addRow(e.getKey());
            for (ResolveInfo ri : list) {
                View tile = appTile(ri);
                row.addView(tile);
                if (first == null) first = tile;
            }
        }
        LinearLayout sys = addRow("System");
        sys.addView(actionTile("Settings", new Intent(Settings.ACTION_SETTINGS)));
        sys.addView(actionTile("Wi-Fi", new Intent(Settings.ACTION_WIFI_SETTINGS)));
        sys.addView(actionTile("All apps", new Intent(Settings.ACTION_APPLICATION_SETTINGS)));
        if (first != null) first.requestFocus();
    }

    private String groupFor(String pkg) {
        String p = pkg.toLowerCase(Locale.ROOT).replace("_", "");
        for (String[] g : GROUPS) {
            for (int i = 1; i < g.length; i++) if (p.contains(g[i])) return g[0];
        }
        return "More apps";
    }

    private String label(ResolveInfo ri) { return String.valueOf(ri.loadLabel(getPackageManager())); }

    private LinearLayout addRow(String title) {
        TextView t = text(title, 16, MUTED, true);
        t.setPadding(px(4), px(10), 0, px(6));
        rowsBox.addView(t);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        hs.setPadding(px(4), px(6), px(4), px(6));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(row);
        rowsBox.addView(hs);
        return row;
    }

    private View appTile(ResolveInfo ri) {
        PackageManager pm = getPackageManager();
        String pkg = ri.activityInfo.packageName;
        Drawable banner = null;
        try {
            banner = pm.getActivityBanner(new android.content.ComponentName(pkg, ri.activityInfo.name));
            if (banner == null) banner = pm.getApplicationBanner(pkg);
        } catch (Exception ignored) { }

        FrameLayout tile = baseTile();
        if (banner != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(banner);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            tile.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER);
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(ri.loadIcon(pm));
            col.addView(iv, new LinearLayout.LayoutParams(px(40), px(40)));
            TextView name = text(label(ri), 12, TEXT, false);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(1);
            name.setPadding(px(6), px(4), px(6), 0);
            col.addView(name);
            tile.addView(col, new FrameLayout.LayoutParams(-1, -1));
        }
        tile.setContentDescription(label(ri));
        tile.setOnClickListener(v -> {
            Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
            if (launch != null) safeStart(launch);
        });
        return tile;
    }

    private View actionTile(String title, Intent intent) {
        FrameLayout tile = baseTile();
        TextView t = text(title, 15, TEXT, true);
        t.setGravity(Gravity.CENTER);
        tile.addView(t, new FrameLayout.LayoutParams(-1, -1));
        tile.setOnClickListener(v -> safeStart(intent));
        return tile;
    }

    private FrameLayout baseTile() {
        FrameLayout tile = new FrameLayout(this);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setPadding(px(3), px(3), px(3), px(3));
        tile.setBackground(rounded(CARD, 10, 0));
        tile.setOnFocusChangeListener((v, has) -> {
            v.setBackground(has ? rounded(CARD_FOCUS, 10, ACCENT) : rounded(CARD, 10, 0));
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).setDuration(120).start();
            v.setElevation(has ? px(8) : 0);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(148), px(84));
        lp.rightMargin = px(14);
        tile.setLayoutParams(lp);
        return tile;
    }

    private void safeStart(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException ignored) { }
    }

    // ---------------------------------------------------------------- subscriptions

    private void renderSubscriptions() {
        subsBox.removeAllViews();
        JSONArray subs = config().optJSONArray("subscriptions");
        if (subs == null || subs.length() == 0) {
            TextView hint = text("No subscriptions yet.\nOn your PC run:\nscripts\\set-config.ps1", 13, MUTED, false);
            hint.setPadding(0, px(10), 0, 0);
            subsBox.addView(hint);
            return;
        }
        List<JSONObject> items = new ArrayList<>();
        for (int i = 0; i < subs.length(); i++) {
            JSONObject o = subs.optJSONObject(i);
            if (o != null) items.add(o);
        }
        Collections.sort(items, (a, b) -> a.optString("expires").compareTo(b.optString("expires")));

        LocalDate today = LocalDate.now();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault());
        for (JSONObject o : items) {
            String name = o.optString("name", "?");
            String when;
            long days;
            try {
                LocalDate exp = LocalDate.parse(o.optString("expires"));
                days = ChronoUnit.DAYS.between(today, exp);
                when = "Expires " + exp.format(fmt);
            } catch (Exception e) {
                days = Long.MIN_VALUE;
                when = "Invalid date (use YYYY-MM-DD)";
            }

            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setPadding(0, px(10), 0, 0);

            LinearLayout left = new LinearLayout(this);
            left.setOrientation(LinearLayout.VERTICAL);
            left.addView(text(name, 14, TEXT, true));
            left.addView(text(when, 11, MUTED, false));
            line.addView(left, new LinearLayout.LayoutParams(0, -2, 1));

            String pill;
            int color;
            if (days == Long.MIN_VALUE) { pill = "?"; color = MUTED; }
            else if (days < 0) { pill = "Expired"; color = RED; }
            else if (days == 0) { pill = "Today"; color = RED; }
            else if (days <= 3) { pill = days + "d"; color = RED; }
            else if (days <= 14) { pill = days + "d"; color = AMBER; }
            else { pill = days + "d"; color = GREEN; }
            TextView p = text(pill, 12, 0xFF10161F, true);
            p.setPadding(px(8), px(2), px(8), px(2));
            p.setBackground(rounded(color, 10, 0));
            line.addView(p);
            subsBox.addView(line);
        }
    }

    // ---------------------------------------------------------------- weather

    private void loadWeather() {
        lastWeatherAt = System.currentTimeMillis();
        final String city = config().optString("city", "").trim();
        new Thread(() -> {
            try {
                if (Double.isNaN(lat)) {
                    if (!city.isEmpty()) {
                        JSONObject g = getJson("https://geocoding-api.open-meteo.com/v1/search?count=1&name="
                                + URLEncoder.encode(city, "UTF-8"));
                        JSONObject r = g.getJSONArray("results").getJSONObject(0);
                        lat = r.getDouble("latitude");
                        lon = r.getDouble("longitude");
                        place = r.optString("name", city);
                    } else {
                        JSONObject ip = getJson("https://ipwho.is/");
                        lat = ip.getDouble("latitude");
                        lon = ip.getDouble("longitude");
                        place = ip.optString("city", "");
                    }
                }
                JSONObject w = getJson(String.format(Locale.US,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                        + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m"
                        + "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1", lat, lon));
                JSONObject cur = w.getJSONObject("current");
                JSONObject daily = w.getJSONObject("daily");
                int code = cur.optInt("weather_code");
                final String main = weatherIcon(code) + "  " + Math.round(cur.getDouble("temperature_2m")) + "°";
                final String desc = weatherText(code) + (place.isEmpty() ? "" : " · " + place);
                final String extra = String.format(Locale.getDefault(),
                        "Feels %d° · H %d° L %d° · Humidity %d%% · Wind %d km/h",
                        Math.round(cur.getDouble("apparent_temperature")),
                        Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)),
                        Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)),
                        cur.optInt("relative_humidity_2m"),
                        Math.round(cur.getDouble("wind_speed_10m")));
                runOnUiThread(() -> {
                    weatherMain.setText(main);
                    weatherDesc.setText(desc);
                    weatherExtra.setText(extra);
                });
            } catch (Exception e) {
                lastWeatherAt = System.currentTimeMillis() - WEATHER_EVERY_MS + 2 * 60 * 1000L; // retry in 2 min
                runOnUiThread(() -> weatherDesc.setText("Weather unavailable"));
            }
        }).start();
    }

    private static JSONObject getJson(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "AvyTvHome/1.0");
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return new JSONObject(sb.toString());
        } finally {
            c.disconnect();
        }
    }

    private static String weatherIcon(int c) {
        if (c == 0) return "☀";
        if (c <= 2) return "⛅";
        if (c == 3) return "☁";
        if (c == 45 || c == 48) return "🌫";
        if (c >= 95) return "⛈";
        if ((c >= 71 && c <= 77) || c == 85 || c == 86) return "❄";
        if ((c >= 51 && c <= 57) || (c >= 80 && c <= 82)) return "🌦";
        if (c >= 61 && c <= 67) return "🌧";
        return "🌡";
    }

    private static String weatherText(int c) {
        if (c == 0) return "Clear sky";
        if (c == 1) return "Mainly clear";
        if (c == 2) return "Partly cloudy";
        if (c == 3) return "Overcast";
        if (c == 45 || c == 48) return "Fog";
        if (c >= 51 && c <= 57) return "Drizzle";
        if (c >= 61 && c <= 67) return "Rain";
        if (c >= 71 && c <= 77) return "Snow";
        if (c >= 80 && c <= 82) return "Rain showers";
        if (c == 85 || c == 86) return "Snow showers";
        if (c >= 95) return "Thunderstorm";
        return "—";
    }

    // ---------------------------------------------------------------- helpers

    private int px(float v) { return Math.round(v * dp); }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private GradientDrawable rounded(int fill, float radiusDp, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radiusDp * dp);
        if (stroke != 0) d.setStroke(px(2), stroke);
        return d;
    }
}

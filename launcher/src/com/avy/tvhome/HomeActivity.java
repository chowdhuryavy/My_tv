package com.avy.tvhome;

import android.accounts.AccountManager;
import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.LayoutTransition;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.graphics.drawable.TransitionDrawable;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import java.io.File;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Base64;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Avy TV Home: ad-free glass home screen with clock, live weather, subscription
 * tracker (edited on the TV) and reorderable app rows.
 */
public class HomeActivity extends Activity {

    private static final String PREFS = "tvhome";
    private static final String KEY_CONFIG = "config_json";
    private static final String KEY_ORDER = "order_json";
    private static final String KEY_THEME = "theme";
    private static final long WEATHER_EVERY_MS = 30 * 60 * 1000L;
    private static final String[] REPEATS = {"once", "monthly", "yearly"};
    private static final String[] REPEAT_LABELS = {"One-time", "Monthly", "Yearly"};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Ui ui;
    private ImageView backdrop;
    private TextView greeting, clockView, ampmView, dateView;
    private TextView weatherIcon, weatherTemp, weatherDesc, weatherExtra, forecastView;
    private TextView hintBar, themeTile;
    private LinearLayout rowsBox, subsBox;
    private TextView subsSummary;
    private int addButtonId;

    private View moving;            // tile container being moved, or null
    private String focusPkg;        // last focused app, restored after refresh
    private final List<ResolveInfo> streamingApps = new ArrayList<>();

    // header pieces animated on entrance
    private View headerView, weatherCard, subsCard;
    // account avatar (top right)
    private static final int REQ_ACCOUNT = 42;
    private static final String KEY_ACCOUNT = "account";
    private FrameLayout avatar;
    private View gearButton;
    private TextView avatarText;
    private ImageView avatarImg;
    // arrange mode: OK picks up an app instead of opening it
    private boolean arranging;
    // running animations (stopped while another app is in front)
    private AnimatorSet drift;
    private ObjectAnimator iconFloat, wiggle;
    private final List<Animator> pulses = new ArrayList<>();

    private long lastWeatherAt = 0;
    private double lat = Double.NaN, lon = Double.NaN;
    private String place = "";

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            Date now = new Date();
            clockView.setText(new SimpleDateFormat("h:mm", Locale.getDefault()).format(now));
            ampmView.setText(new SimpleDateFormat("a", Locale.getDefault()).format(now));
            dateView.setText(new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now));
            int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            String hello = h < 5 ? "Good night" : h < 12 ? "Good morning" : h < 17 ? "Good afternoon" : h < 22 ? "Good evening" : "Good night";
            String who = displayName();
            greeting.setText(who.isEmpty() ? hello : hello + ", " + who);
            if (System.currentTimeMillis() - lastWeatherAt > WEATHER_EVERY_MS) loadWeather();
            handler.postDelayed(this, 15_000);
        }
    };

    // =================================================================== lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ui = new Ui(this);
        importConfig(getIntent());
        checkOpenExtra(getIntent());
        setContentView(buildUi());
        applyTheme();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        checkOpenExtra(intent);
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
        refreshAvatar();
        handler.removeCallbacks(clockTick);
        handler.post(clockTick);
        startAmbient();
        playEntrance();
        if (openButtonsPending) {
            openButtonsPending = false;
            handler.postDelayed(this::showRemoteButtons, 500);
        }
        if (openQuickPending) {
            openQuickPending = false;
            handler.postDelayed(this::showQuickSettings, 350);
        }
    }

    private boolean openQuickPending;

    /** Set when started from Accessibility → Configuration (ButtonsActivity). */
    private boolean openButtonsPending;

    private void checkOpenExtra(Intent i) {
        if (i == null) return;
        String open = i.getStringExtra("open");
        if ("buttons".equals(open)) openButtonsPending = true;
        if ("quick".equals(open)) openQuickPending = true; // remote Settings button
        i.removeExtra("open");
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(clockTick);
        if (moving != null) endMove();
        if (arranging) stopArrange();
        stopAmbient();
    }

    @Override
    public void onBackPressed() { /* home screen: nothing to go back to */ }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_ACCOUNT && res == RESULT_OK && data != null) {
            String name = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
            if (name != null) prefs().edit().putString(KEY_ACCOUNT, name).apply();
            refreshAvatar();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (moving == null && arranging && e.getAction() == KeyEvent.ACTION_DOWN
                && (e.getKeyCode() == KeyEvent.KEYCODE_BACK || e.getKeyCode() == KeyEvent.KEYCODE_ESCAPE)) {
            stopArrange();
            return true;
        }
        if (moving == null) return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT: moveBy(-1); break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: moveBy(1); break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_ESCAPE: endMove(); break;
                default: break;
            }
        }
        return true; // swallow everything else while moving
    }

    // =================================================================== storage

    private SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }

    private JSONObject json(String key) {
        try { return new JSONObject(prefs().getString(key, "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    private void putJson(String key, JSONObject o) { prefs().edit().putString(key, o.toString()).apply(); }

    private JSONObject config() { return json(KEY_CONFIG); }

    /** Optional settings pushed from a PC (e.g. city). Keys present replace existing ones; others are kept. */
    private boolean importConfig(Intent intent) {
        String b64 = intent == null ? null : intent.getStringExtra("config_b64");
        if (b64 == null) return false;
        try {
            JSONObject in = new JSONObject(new String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8));
            JSONObject cfg = config();
            for (Iterator<String> it = in.keys(); it.hasNext(); ) {
                String k = it.next();
                cfg.put(k, in.get(k));
            }
            putJson(KEY_CONFIG, cfg);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private JSONArray subscriptions() {
        JSONArray a = config().optJSONArray("subscriptions");
        return a == null ? new JSONArray() : a;
    }

    private void saveSubscriptions(JSONArray subs) {
        try {
            JSONObject cfg = config();
            cfg.put("subscriptions", subs);
            putJson(KEY_CONFIG, cfg);
        } catch (Exception ignored) { }
    }

    // =================================================================== layout

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.FIT_XY);
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(ui.px(44), ui.px(20), ui.px(44), ui.px(6));
        page.setClipChildren(true); // keep scrolled app rows from drawing over the header
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        // ---- slim header: [clock  greeting/date] .......... [weather chip] [⚙] [avatar]
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout timeCol = new LinearLayout(this);
        timeCol.setOrientation(LinearLayout.HORIZONTAL);
        timeCol.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout clockRow = new LinearLayout(this);
        clockRow.setOrientation(LinearLayout.HORIZONTAL);
        clockRow.setBaselineAligned(true);
        clockView = ui.text("", 36, Ui.WHITE, Ui.LIGHT);
        ampmView = ui.text("", 14, Ui.SOFT, Ui.REGULAR);
        ampmView.setPadding(ui.px(5), 0, 0, 0);
        clockRow.addView(clockView);
        clockRow.addView(ampmView);
        timeCol.addView(clockRow);
        LinearLayout gd = new LinearLayout(this);
        gd.setOrientation(LinearLayout.VERTICAL);
        gd.setPadding(ui.px(16), 0, 0, 0);
        greeting = ui.text("", 14, Ui.WHITE, Ui.MEDIUM);
        greeting.setSingleLine(true);
        greeting.setEllipsize(TextUtils.TruncateAt.END);
        dateView = ui.text("", 12, Ui.SOFT, Ui.REGULAR);
        gd.addView(greeting);
        addWithTop(gd, dateView, 3);
        timeCol.addView(gd, new LinearLayout.LayoutParams(ui.px(420), -2)); // room for 'Good afternoon, <name>'
        header.addView(timeCol, new LinearLayout.LayoutParams(0, -2, 1));

        // compact weather chip; select it for the full forecast
        LinearLayout weather = new LinearLayout(this);
        weather.setOrientation(LinearLayout.HORIZONTAL);
        weather.setGravity(Gravity.CENTER_VERTICAL);
        weather.setFocusable(true);
        weather.setClickable(true);
        weather.setBackground(ui.glassItem(22));
        weather.setPadding(ui.px(14), ui.px(6), ui.px(16), ui.px(6));
        weatherIcon = ui.text("⛅", 20, Ui.WHITE, Ui.REGULAR);
        weather.addView(weatherIcon);
        weatherTemp = ui.text("--°", 20, Ui.WHITE, Ui.LIGHT);
        weatherTemp.setPadding(ui.px(8), 0, ui.px(10), 0);
        weather.addView(weatherTemp);
        weatherDesc = ui.text("Loading weather…", 12, Ui.SOFT, Ui.REGULAR);
        weatherDesc.setSingleLine(true);
        weatherDesc.setMaxWidth(ui.px(220));
        weatherDesc.setEllipsize(TextUtils.TruncateAt.END);
        weather.addView(weatherDesc);
        // details live in the forecast dialog, not on the home screen
        weatherExtra = ui.text("", 14, Ui.SOFT, Ui.REGULAR);
        forecastView = ui.text("", 14, Ui.SOFT, Ui.REGULAR);
        weather.setOnClickListener(v -> showWeatherDetails());
        header.addView(weather);
        weatherCard = weather;

        // ---- account avatar, top right (like Google TV)
        avatar = new FrameLayout(this);
        avatar.setFocusable(true);
        avatar.setClickable(true);
        avatar.setId(View.generateViewId());
        GradientDrawable avBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF7B5CFF, 0xFF00C6FF});
        avBg.setShape(GradientDrawable.OVAL);
        avatar.setBackground(avBg);
        avatar.setClipToOutline(true);
        avatar.setForeground(ui.ovalRing());
        avatarImg = new ImageView(this);
        avatarImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.addView(avatarImg, new FrameLayout.LayoutParams(-1, -1));
        avatarText = ui.text("", 18, Ui.WHITE, Ui.MEDIUM);
        avatarText.setGravity(Gravity.CENTER);
        avatar.addView(avatarText, new FrameLayout.LayoutParams(-1, -1));
        avatar.setOnClickListener(v -> showAccountMenu());
        avatar.setOnFocusChangeListener((v, has) -> v.animate().scaleX(has ? 1.12f : 1f).scaleY(has ? 1.12f : 1f)
                .setInterpolator(new OvershootInterpolator(2f)).setDuration(220).start());
        // ---- quick settings gear (left of the avatar)
        TextView gear = ui.text("⚙", 20, Ui.WHITE, Ui.REGULAR);
        gear.setGravity(Gravity.CENTER);
        gear.setFocusable(true);
        gear.setClickable(true);
        gear.setId(View.generateViewId());
        StateListDrawable gbg = new StateListDrawable();
        GradientDrawable gOn = new GradientDrawable(); gOn.setShape(GradientDrawable.OVAL); gOn.setColor(0x4DFFFFFF); gOn.setStroke(ui.px(3), Ui.WHITE);
        GradientDrawable gOff = new GradientDrawable(); gOff.setShape(GradientDrawable.OVAL); gOff.setColor(0x26FFFFFF); gOff.setStroke(ui.px(1), 0x40FFFFFF);
        gbg.addState(new int[]{android.R.attr.state_focused}, gOn);
        gbg.addState(new int[]{}, gOff);
        gear.setBackground(gbg);
        gear.setOnClickListener(v -> showQuickSettings());
        gear.setOnFocusChangeListener((v, has) -> v.animate().rotation(has ? 60f : 0f).scaleX(has ? 1.12f : 1f).scaleY(has ? 1.12f : 1f)
                .setInterpolator(new OvershootInterpolator(2f)).setDuration(300).start());
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(ui.px(40), ui.px(40));
        glp.leftMargin = ui.px(14);
        header.addView(gear, glp);
        gearButton = gear;

        LinearLayout.LayoutParams avlp = new LinearLayout.LayoutParams(ui.px(44), ui.px(44));
        avlp.leftMargin = ui.px(14);
        header.addView(avatar, avlp);

        page.addView(header);
        headerView = timeCol;

        // ---- subscriptions strip: one thin line of chips
        LinearLayout subsStrip = new LinearLayout(this);
        subsStrip.setOrientation(LinearLayout.HORIZONTAL);
        subsStrip.setGravity(Gravity.CENTER_VERTICAL);
        subsStrip.setClipChildren(false);
        TextView subsTitle = ui.text("Subscriptions", 13, Ui.WHITE, Ui.MEDIUM);
        subsTitle.setPadding(ui.px(4), 0, ui.px(12), 0);
        subsStrip.addView(subsTitle);
        TextView add = ui.button("+ Add", true);
        add.setPadding(ui.px(12), ui.px(4), ui.px(12), ui.px(4));
        add.setTextSize(12);
        add.setOnClickListener(v -> editSubscription(-1));
        addButtonId = View.generateViewId();
        add.setId(addButtonId);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-2, -2);
        alp.rightMargin = ui.px(12);
        subsStrip.addView(add, alp);
        subsSummary = ui.text("", 12, Ui.SOFT, Ui.REGULAR); // kept for status text, not shown
        HorizontalScrollView subsScroll = new HorizontalScrollView(this);
        subsScroll.setHorizontalScrollBarEnabled(false);
        subsScroll.setClipToPadding(false);
        subsScroll.setPadding(ui.px(2), ui.px(6), ui.px(2), ui.px(6));
        subsBox = new LinearLayout(this);
        subsBox.setOrientation(LinearLayout.HORIZONTAL);
        subsBox.setGravity(Gravity.CENTER_VERTICAL);
        subsScroll.addView(subsBox);
        subsStrip.addView(subsScroll, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams sslp = new LinearLayout.LayoutParams(-1, -2);
        sslp.topMargin = ui.px(10);
        page.addView(subsStrip, sslp);
        this.subsCard = subsStrip;
        add.setNextFocusUpId(gear.getId());

        // ---- apps: full width
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(true);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, ui.px(40));
        rowsBox = new LinearLayout(this);
        rowsBox.setOrientation(LinearLayout.VERTICAL);
        rowsBox.setClipChildren(true);
        scroll.addView(rowsBox);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, 0, 1);
        blp.topMargin = ui.px(2);
        page.addView(scroll, blp);

        // ---- hint bar (move mode)
        hintBar = ui.text("", 15, Ui.WHITE, Ui.MEDIUM);
        hintBar.setBackground(ui.glassPanel(24));
        hintBar.setPadding(ui.px(24), ui.px(12), ui.px(24), ui.px(12));
        hintBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hlp.bottomMargin = ui.px(22);
        root.addView(hintBar, hlp);
        return root;
    }

    private void addWithTop(LinearLayout parent, View v, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = ui.px(topDp);
        parent.addView(v, lp);
    }

    private void applyTheme() {
        int t = prefs().getInt(KEY_THEME, 0);
        Bitmap b = Ui.backdrop(t);
        Drawable old = backdrop.getDrawable();
        if (old instanceof TransitionDrawable) old = ((TransitionDrawable) old).getDrawable(1);
        if (old == null) {
            backdrop.setImageBitmap(b);
        } else { // cross-fade between themes
            TransitionDrawable td = new TransitionDrawable(new Drawable[]{old, new BitmapDrawable(getResources(), b)});
            td.setCrossFadeEnabled(true);
            backdrop.setImageDrawable(td);
            td.startTransition(700);
        }
        updateThemeLabel();
    }

    // =================================================================== animations

    /** Slow drifting backdrop + floating weather icon. Stopped while another app is open. */
    private void startAmbient() {
        stopAmbient();
        ObjectAnimator sx = ObjectAnimator.ofFloat(backdrop, View.SCALE_X, 1.12f, 1.22f);
        ObjectAnimator sy = ObjectAnimator.ofFloat(backdrop, View.SCALE_Y, 1.12f, 1.22f);
        ObjectAnimator tx = ObjectAnimator.ofFloat(backdrop, View.TRANSLATION_X, -ui.px(40), ui.px(40));
        ObjectAnimator ty = ObjectAnimator.ofFloat(backdrop, View.TRANSLATION_Y, ui.px(16), -ui.px(16));
        for (ObjectAnimator a : new ObjectAnimator[]{sx, sy, tx, ty}) {
            a.setRepeatCount(ValueAnimator.INFINITE);
            a.setRepeatMode(ValueAnimator.REVERSE);
        }
        tx.setDuration(26_000);
        ty.setDuration(19_000);
        sx.setDuration(33_000);
        sy.setDuration(33_000);
        drift = new AnimatorSet();
        drift.playTogether(sx, sy, tx, ty);
        drift.setInterpolator(new AccelerateDecelerateInterpolator());
        drift.start();

        iconFloat = ObjectAnimator.ofFloat(weatherIcon, View.TRANSLATION_Y, -ui.px(4), ui.px(4));
        iconFloat.setDuration(2600);
        iconFloat.setRepeatCount(ValueAnimator.INFINITE);
        iconFloat.setRepeatMode(ValueAnimator.REVERSE);
        iconFloat.setInterpolator(new AccelerateDecelerateInterpolator());
        iconFloat.start();
    }

    private void stopAmbient() {
        if (drift != null) drift.cancel();
        if (iconFloat != null) iconFloat.cancel();
        for (Animator a : pulses) a.cancel();
    }

    /** Header, cards and app rows glide in, rows one after another. */
    private void playEntrance() {
        DecelerateInterpolator dec = new DecelerateInterpolator(2f);
        slideIn(headerView, -ui.px(24), 0, 0, dec);
        slideIn(weatherCard, ui.px(40), 0, 80, dec);
        slideIn(gearButton, ui.px(40), 0, 110, dec);
        slideIn(avatar, ui.px(40), 0, 140, dec);
        slideIn(subsCard, 0, -ui.px(16), 200, dec);
        int delay = 120;
        for (int i = 0; i < rowsBox.getChildCount(); i++) {
            View row = rowsBox.getChildAt(i);
            row.setAlpha(0f);
            row.setTranslationY(ui.px(28));
            row.animate().alpha(1f).translationY(0).setStartDelay(delay).setDuration(420).setInterpolator(dec).start();
            delay += 55;
        }
    }

    private void slideIn(View v, float fromX, float fromY, long delay, DecelerateInterpolator in) {
        if (v == null) return;
        v.setAlpha(0f);
        v.setTranslationX(fromX);
        v.setTranslationY(fromY);
        v.animate().alpha(1f).translationX(0).translationY(0).setStartDelay(delay).setDuration(480).setInterpolator(in).start();
    }

    /** Gentle pulse for subscriptions that are due very soon. */
    private void pulse(View v) {
        ObjectAnimator a = ObjectAnimator.ofFloat(v, View.ALPHA, 1f, 0.45f);
        a.setDuration(850);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.setRepeatMode(ValueAnimator.REVERSE);
        a.start();
        pulses.add(a);
    }

    /** Dialog panel pops in: slight zoom + fade. */
    private void popIn(View panel) {
        panel.setAlpha(0f);
        panel.setScaleX(0.9f);
        panel.setScaleY(0.9f);
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(new OvershootInterpolator(1.2f)).start();
    }

    // =================================================================== quick settings

    private void showQuickSettings() {
        LinearLayout panel = panel();
        panel.addView(ui.text("Quick settings", 24, Ui.WHITE, Ui.MEDIUM));
        final Dialog d = dialog(panel);
        Object[][] items = {
            {"📶   Wi-Fi", new Intent(Settings.ACTION_WIFI_SETTINGS)},
            {"ᛒ   Bluetooth & remotes", new Intent("com.android.tv.settings.ACCESSORIES_CONNECTED_DEVICES")},
            {"🖥   Picture & display", new Intent(Settings.ACTION_DISPLAY_SETTINGS)},
            {"🔊   Sound", new Intent(Settings.ACTION_SOUND_SETTINGS)},
            {"⎆   Inputs (HDMI)", new Intent("com.tcl.suspension.action.main").setPackage("com.tcl.suspension").putExtra("openInput", "true")},
            {"🌙   Screen saver", new Intent("com.google.android.tv.settings.ambient")},
            {"🎮   Remote buttons", (Runnable) this::showRemoteButtons},
            {"🎨   Change theme", (Runnable) () -> {
                prefs().edit().putInt(KEY_THEME, prefs().getInt(KEY_THEME, 0) + 1).apply();
                applyTheme();
            }},
            {"⚙   All settings", new Intent(Settings.ACTION_SETTINGS)},
        };
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = null;
        View first = null;
        for (int i = 0; i < items.length; i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
                rlp.topMargin = ui.px(10);
                grid.addView(row, rlp);
            }
            final Object action = items[i][1];
            TextView b = ui.button((String) items[i][0], false);
            b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            b.setPadding(ui.px(18), ui.px(14), ui.px(18), ui.px(14));
            b.setOnClickListener(v -> {
                d.dismiss();
                if (action instanceof Intent) safeStart((Intent) action); else ((Runnable) action).run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.px(230), -2);
            lp.rightMargin = ui.px(10);
            row.addView(b, lp);
            if (first == null) first = b;
        }
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-2, -2);
        glp.topMargin = ui.px(8);
        panel.addView(grid, glp);
        d.show();
        first.requestFocus();
    }

    // =================================================================== weather details

    /** Full weather (feels-like, humidity, wind, 3-day forecast) from the compact header chip. */
    private void showWeatherDetails() {
        LinearLayout panel = panel();
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(ui.text(weatherIcon.getText().toString(), 44, Ui.WHITE, Ui.REGULAR));
        TextView t = ui.text(weatherTemp.getText().toString(), 44, Ui.WHITE, Ui.LIGHT);
        t.setPadding(ui.px(16), 0, 0, 0);
        top.addView(t);
        panel.addView(top);
        addWithTop(panel, ui.text(weatherDesc.getText().toString(), 18, Ui.WHITE, Ui.MEDIUM), 8);
        addWithTop(panel, ui.text(weatherExtra.getText().toString(), 15, Ui.SOFT, Ui.REGULAR), 10);
        addWithTop(panel, ui.text(forecastView.getText().toString(), 15, Ui.SOFT, Ui.REGULAR), 10);
        final Dialog d = dialog(panel);
        TextView close = ui.button("Close", false);
        close.setOnClickListener(v -> d.dismiss());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.topMargin = ui.px(20);
        panel.addView(close, clp);
        d.show();
        close.requestFocus();
    }

    // =================================================================== remote buttons

    /** True only when the helper is actually running (enabled AND accessibility master switch on). */
    private boolean keyServiceOn() {
        android.view.accessibility.AccessibilityManager am =
                (android.view.accessibility.AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (am == null || !am.isEnabled()) return false;
        for (android.accessibilityservice.AccessibilityServiceInfo info :
                am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (info.getId() != null && info.getId().startsWith(getPackageName() + "/")) return true;
        }
        return false;
    }

    private JSONObject buttonMap() { return json(KeyService.KEY_BUTTONS); }

    private String appLabel(String pkg) {
        if (KeyService.QUICK.equals(pkg)) return "Quick settings";
        if (KeyService.HOME.equals(pkg)) return "Home screen";
        try { return String.valueOf(getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0))); }
        catch (Exception e) { return pkg; }
    }

    private void showRemoteButtons() {
        LinearLayout panel = panel();
        panel.addView(ui.text("Remote buttons", 24, Ui.WHITE, Ui.MEDIUM));
        boolean on = keyServiceOn();
        TextView status = ui.text(on ? "●  Helper running. Choose which app each remote button opens."
                : "●  Helper NOT running, so app buttons won't work.\nIn Accessibility, switch “Avy TV Home remote buttons” OFF and then ON again.",
                13, on ? Ui.GREEN : Ui.AMBER, Ui.REGULAR);
        status.setLineSpacing(ui.px(3), 1f);
        addWithTop(panel, status, 6);
        final Dialog d = dialog(panel);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ui.px(460), -2);
        llp.topMargin = ui.px(12);
        panel.addView(list, llp);

        View first;
        if (!on) {
            first = menuButton(list, "⚙   Open Accessibility settings", () -> safeStart(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)), d);
        } else {
            first = menuButton(list, "＋   Set up a button", this::learnButton, d);
        }
        // default-mapped buttons the user switched off, so they can be switched back on
        for (int i = 0; i < KeyService.DEFAULT_CODES.length; i++) {
            final String code = String.valueOf(KeyService.DEFAULT_CODES[i][0]);
            JSONObject m0 = buttonMap();
            if (m0.has(code) && m0.optString(code).isEmpty()) {
                menuButton(list, "○   " + buttonName(KeyService.DEFAULT_CODES[i][0]) + " is off      (select to turn on)", () -> {
                    JSONObject m = buttonMap();
                    m.remove(code);
                    putJson(KeyService.KEY_BUTTONS, m);
                    showRemoteButtons();
                }, d);
            }
        }
        // effective mapping = built-in defaults, overridden by the user's choices ("" = off)
        JSONObject map = buttonMap();
        Map<String, String> eff = new LinkedHashMap<>();
        for (int i = 0; i < KeyService.DEFAULT_CODES.length; i++) eff.put(String.valueOf(KeyService.DEFAULT_CODES[i][0]), KeyService.DEFAULT_PKGS[i]);
        for (Iterator<String> it = map.keys(); it.hasNext(); ) { String k = it.next(); eff.put(k, map.optString(k)); }
        for (Map.Entry<String, String> en : eff.entrySet()) {
            final String code = en.getKey();
            if (en.getValue().isEmpty()) continue;
            String name = buttonName(Integer.parseInt(code));
            // selecting a row lets you pick a different app (turning off is a separate choice there)
            menuButton(list, "🎮   " + name + "  →  " + appLabel(en.getValue()) + "      (change)",
                    () -> chooseAppFor(Integer.parseInt(code), name), d);
        }
        menuButton(list, "Close", null, d);
        d.show();
        first.requestFocus();
    }

    private static String buttonName(int code) {
        switch (code) {
            case 4062: return "Netflix button";
            case 4099: return "Prime Video button";
            case 4065: return "YouTube button";
            case KeyEvent.KEYCODE_NOTIFICATION: return "Settings button";
            case KeyEvent.KEYCODE_ALL_APPS: return "Apps button";
            case 4057: return "Web button";
            default:
                String s = KeyEvent.keyCodeToString(code);
                return s.startsWith("KEYCODE_") ? s.substring(8).replace('_', ' ') + " button" : "Button " + code;
        }
    }

    private void learnButton() {
        LinearLayout panel = panel();
        panel.addView(ui.text("Press a remote button", 24, Ui.WHITE, Ui.MEDIUM));
        final TextView sub = ui.text("e.g. the Netflix, YouTube or Prime Video button…", 14, Ui.SOFT, Ui.REGULAR);
        addWithTop(panel, sub, 8);
        final TextView cancel = ui.button("Cancel", false);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.topMargin = ui.px(20);
        panel.addView(cancel, clp);
        final Dialog d = dialog(panel);
        cancel.setOnClickListener(v -> d.dismiss());
        d.setOnDismissListener(x -> KeyService.learner = null);
        KeyService.learner = (code, name) -> {
            KeyService.learner = null;
            runOnUiThread(() -> { d.dismiss(); chooseAppFor(code, name); });
            return true;
        };
        d.show();
        cancel.requestFocus();
    }

    private void chooseAppFor(final int code, String keyName) {
        LinearLayout panel = panel();
        final String nice = buttonName(code);
        panel.addView(ui.text(nice + " opens…", 22, Ui.WHITE, Ui.MEDIUM));
        final Dialog d = dialog(panel);
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ui.px(420), ui.px(380));
        slp.topMargin = ui.px(10);
        panel.addView(sv, slp);
        PackageManager pm = getPackageManager();
        Map<String, String> apps = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName())) continue;
                apps.put(label(ri), pkg);
            }
        }
        View first = null;
        for (Map.Entry<String, String> e : apps.entrySet()) {
            final String pkg = e.getValue();
            View b = menuButton(list, e.getKey(), () -> {
                try {
                    JSONObject m = buttonMap();
                    m.put(String.valueOf(code), pkg);
                    putJson(KeyService.KEY_BUTTONS, m);
                    Toast.makeText(this, nice + " → " + appLabel(pkg), Toast.LENGTH_SHORT).show();
                } catch (Exception ignored) { }
            }, d);
            if (first == null) first = b;
        }
        menuButton(list, "⊘   Turn this button off", () -> {
            try {
                JSONObject m = buttonMap();
                m.put(String.valueOf(code), "");
                putJson(KeyService.KEY_BUTTONS, m);
                Toast.makeText(this, nice + " turned off", Toast.LENGTH_SHORT).show();
            } catch (Exception ignored) { }
        }, d);
        d.show();
        if (first != null) first.requestFocus();
    }

    // =================================================================== account avatar

    private String displayName() {
        String n = config().optString("name", "").trim();
        if (!n.isEmpty()) return n;
        String acct = prefs().getString(KEY_ACCOUNT, "");
        int at = acct.indexOf('@');
        if (at <= 0) return "";
        String local = acct.substring(0, at).replaceAll("[._\\d]+", " ").trim();
        if (local.isEmpty()) return "";
        String first = local.split(" ")[0];
        return Character.toUpperCase(first.charAt(0)) + first.substring(1);
    }

    /** Photo pushed from the PC (scripts\\set-avatar.ps1) wins; otherwise the account's initial. */
    private static final int REQ_ACCOUNTS_PERM = 43;

    /**
     * Reads the Google account signed in on the TV. Needs the "Contacts / accounts" permission
     * (asked once). The app targets SDK 25 on purpose: since Android 8, apps targeting newer
     * SDKs can't see Google accounts at all without Google's account picker, which on
     * Google TV lives inside the (disabled) stock launcher.
     */
    private void readGoogleAccount() {
        if (checkSelfPermission(android.Manifest.permission.GET_ACCOUNTS) != PackageManager.PERMISSION_GRANTED) {
            if (!prefs().getBoolean("asked_accounts", false)) {
                prefs().edit().putBoolean("asked_accounts", true).apply();
                requestPermissions(new String[]{android.Manifest.permission.GET_ACCOUNTS}, REQ_ACCOUNTS_PERM);
            }
            return;
        }
        try {
            android.accounts.Account[] list = AccountManager.get(this).getAccountsByType("com.google");
            if (list.length > 0) prefs().edit().putString(KEY_ACCOUNT, list[0].name).apply();
        } catch (Exception ignored) { }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_ACCOUNTS_PERM) {
            readGoogleAccount();
            refreshAvatar();
            handler.removeCallbacks(clockTick);
            handler.post(clockTick);
        }
    }

    private void refreshAvatar() {
        if (avatar == null) return;
        readGoogleAccount();
        File dir = getExternalFilesDir(null);
        File photo = null;
        if (dir != null) for (String n : new String[]{"avatar.jpg", "avatar.png", "avatar.webp"}) {
            File f = new File(dir, n);
            if (f.isFile()) { photo = f; break; }
        }
        Bitmap bmp = null;
        if (photo != null) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(photo.getAbsolutePath(), o);
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / sample > 320) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            bmp = BitmapFactory.decodeFile(photo.getAbsolutePath(), o);
        }
        if (bmp != null) {
            avatarImg.setImageBitmap(bmp);
            avatarImg.setVisibility(View.VISIBLE);
            avatarText.setVisibility(View.GONE);
        } else {
            avatarImg.setVisibility(View.GONE);
            avatarText.setVisibility(View.VISIBLE);
            String acct = prefs().getString(KEY_ACCOUNT, "");
            String name = displayName();
            avatarText.setText(!name.isEmpty() ? name.substring(0, 1).toUpperCase(Locale.getDefault())
                    : !acct.isEmpty() ? acct.substring(0, 1).toUpperCase(Locale.getDefault()) : "👤");
        }
    }

    private void showAccountMenu() {
        // Note: Google TV's account picker lives in the stock launcher (disabled), and
        // starting it from here makes Play Services loop. So no account picker: the user
        // sets a display name instead; the Google account itself stays signed in.
        LinearLayout panel = panel();
        String name = displayName();
        panel.addView(ui.text(name.isEmpty() ? "Your profile" : name, 22, Ui.WHITE, Ui.MEDIUM));
        String acct = prefs().getString(KEY_ACCOUNT, "");
        addWithTop(panel, ui.text(acct.isEmpty()
                ? "Google account signed in on this TV (Play Store, YouTube…)."
                : "Google account:  " + acct, 14, Ui.SOFT, Ui.REGULAR), 6);
        final Dialog d = dialog(panel);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ui.px(380), -2);
        llp.topMargin = ui.px(14);
        panel.addView(list, llp);
        View first = null;
        if (checkSelfPermission(android.Manifest.permission.GET_ACCOUNTS) != PackageManager.PERMISSION_GRANTED) {
            first = menuButton(list, "👤   Show my Google account", () ->
                    requestPermissions(new String[]{android.Manifest.permission.GET_ACCOUNTS}, REQ_ACCOUNTS_PERM), d);
        }
        View nameBtn = menuButton(list, "✎   " + (name.isEmpty() ? "Set your name" : "Change name"), this::editName, d);
        if (first == null) first = nameBtn;
        menuButton(list, "⚙   Accounts (Settings)", () -> safeStart(new Intent(Settings.ACTION_SETTINGS)), d);
        menuButton(list, "✕   Close", null, d);
        TextView tip = ui.text("Other apps can't read your Google profile photo.\nTo show it here, run scripts\\set-avatar.ps1 once on the PC.", 11, Ui.MUTED, Ui.REGULAR);
        tip.setLineSpacing(ui.px(2), 1f);
        addWithTop(panel, tip, 14);
        d.show();
        first.requestFocus();
    }

    private void editName() {
        LinearLayout panel = panel();
        panel.addView(ui.text("Your name", 22, Ui.WHITE, Ui.MEDIUM));
        final EditText field = new EditText(this);
        field.setText(displayName());
        field.setHint("e.g. Avy");
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        field.setTextColor(Ui.WHITE);
        field.setHintTextColor(0x80FFFFFF);
        field.setTextSize(18);
        field.setPadding(ui.px(16), ui.px(10), ui.px(16), ui.px(10));
        field.setBackground(ui.glassItem(14));
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(ui.px(420), -2);
        flp.topMargin = ui.px(14);
        panel.addView(field, flp);
        final Dialog d = dialog(panel);
        LinearLayout actions = new LinearLayout(this);
        TextView save = ui.button("Save", true);
        save.setOnClickListener(v -> {
            try {
                JSONObject cfg = config();
                cfg.put("name", field.getText().toString().trim());
                putJson(KEY_CONFIG, cfg);
            } catch (Exception ignored) { }
            d.dismiss();
            refreshAvatar();
            handler.removeCallbacks(clockTick);
            handler.post(clockTick);
        });
        actions.addView(save, buttonLp());
        TextView cancel = ui.button("Cancel", false);
        cancel.setOnClickListener(v -> d.dismiss());
        actions.addView(cancel, buttonLp());
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-2, -2);
        alp.topMargin = ui.px(18);
        panel.addView(actions, alp);
        d.show();
        field.requestFocus(); // keyboard opens so the name can be typed right away
    }

    private void updateThemeLabel() {
        if (themeTile != null) themeTile.setText(Ui.THEMES[Math.floorMod(prefs().getInt(KEY_THEME, 0), Ui.THEMES.length)] + " theme");
    }

    // =================================================================== apps

    private static final String[][] GROUPS = {
        {"Streaming", "netflix", "amazonvideo", "primevideo", "appletv", "disney", "hotstar", "youtube", "crunchyroll",
            "shahid", "starz", "osn", "sunnxt", "iqiyi", "bstar", "ahaflix", "moviebox", "bluesun", "plex", "spotify",
            "videos", "hbo", "max", "zee5", "sonyliv", "jiocinema"},
        {"Sports & Live", "sportzfy", "cricfy", "sport", "live", "tv.channels", "dazn", "espn"},
        {"Tools", "browser", "downloader", "sendfiles", "vpn", "files", "vending", "playstore"},
    };

    private void renderApps() {
        PackageManager pm = getPackageManager();
        Map<String, ResolveInfo> apps = new LinkedHashMap<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || pkg.startsWith("com.google.android.apps.tv.launcher")) continue;
                if (!apps.containsKey(pkg)) apps.put(pkg, ri);
            }
        }

        Map<String, List<ResolveInfo>> grouped = new LinkedHashMap<>();
        for (String[] g : GROUPS) grouped.put(g[0], new ArrayList<ResolveInfo>());
        grouped.put("More apps", new ArrayList<ResolveInfo>());
        for (ResolveInfo ri : apps.values()) grouped.get(groupFor(ri.activityInfo.packageName)).add(ri);
        streamingApps.clear();
        streamingApps.addAll(grouped.get("Streaming"));

        JSONObject orders = json(KEY_ORDER);
        rowsBox.removeAllViews();
        View first = null, restore = null;
        for (Map.Entry<String, List<ResolveInfo>> e : grouped.entrySet()) {
            List<ResolveInfo> list = e.getValue();
            if (list.isEmpty()) continue;
            final JSONArray saved = orders.optJSONArray(e.getKey());
            Collections.sort(list, (a, b) -> {
                int ia = indexIn(saved, a.activityInfo.packageName), ib = indexIn(saved, b.activityInfo.packageName);
                if (ia != ib) return Integer.compare(ia, ib);
                return label(a).compareToIgnoreCase(label(b));
            });
            LinearLayout row = addRow(e.getKey());
            boolean topRow = first == null;
            for (ResolveInfo ri : list) {
                View tile = appTile(ri);
                row.addView(tile);
                View card = ((ViewGroup) tile).getChildAt(0);
                if (topRow) card.setNextFocusUpId(addButtonId); // Up from top row -> + Add
                if (first == null) first = card;
                if (ri.activityInfo.packageName.equals(focusPkg)) restore = card;
            }
        }

        LinearLayout sys = addRow("System");
        sys.addView(actionTile("⚙", "Settings", new Intent(Settings.ACTION_SETTINGS)));
        sys.addView(actionTile("📶", "Wi-Fi", new Intent(Settings.ACTION_WIFI_SETTINGS)));
        sys.addView(actionTile("▦", "All apps", new Intent(Settings.ACTION_APPLICATION_SETTINGS)));
        View themeBox = actionTile("🎨", "Theme", null);
        themeTile = (TextView) ((ViewGroup) themeBox).getChildAt(1);
        ((ViewGroup) themeBox).getChildAt(0).setOnClickListener(v -> {
            prefs().edit().putInt(KEY_THEME, prefs().getInt(KEY_THEME, 0) + 1).apply();
            applyTheme();
        });
        sys.addView(themeBox);
        View arrangeBox = actionTile("⇄", "Arrange apps", null);
        cardOf(arrangeBox).setOnClickListener(v -> startArrange());
        sys.addView(arrangeBox, 0);
        updateThemeLabel();

        TextView tip = ui.text("Tip: hold OK on an app (or press Menu) to move it  ·  or use ⇄ Arrange apps in System", 12, Ui.MUTED, Ui.REGULAR);
        tip.setPadding(ui.px(6), ui.px(14), 0, ui.px(10));
        rowsBox.addView(tip);

        View target = restore != null ? restore : first;
        if (target != null) target.requestFocus();
    }

    private static int indexIn(JSONArray a, String pkg) {
        if (a != null) for (int i = 0; i < a.length(); i++) if (pkg.equals(a.optString(i))) return i;
        return Integer.MAX_VALUE;
    }

    private String groupFor(String pkg) {
        String p = pkg.toLowerCase(Locale.ROOT).replace("_", "");
        for (String[] g : GROUPS) for (int i = 1; i < g.length; i++) if (p.contains(g[i])) return g[0];
        return "More apps";
    }

    private String label(ResolveInfo ri) { return String.valueOf(ri.loadLabel(getPackageManager())); }

    private LinearLayout addRow(String title) {
        TextView t = ui.text(title, 15, Ui.WHITE, Ui.MEDIUM);
        t.setPadding(ui.px(4), ui.px(8), 0, 0);
        rowsBox.addView(t);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipChildren(false); // zoomed tile may use the padding; rowsBox clips at hs bounds
        hs.setClipToPadding(false);
        hs.setPadding(ui.px(10), ui.px(8), ui.px(20), ui.px(6));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setTag(title);
        hs.addView(row);
        rowsBox.addView(hs);
        return row;
    }

    /** Tile = container(round icon disc + label). The disc is the focusable part. */
    private LinearLayout tileShell(String labelText) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setClipChildren(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.px(TILE_W), -2);
        lp.rightMargin = ui.px(6);
        box.setLayoutParams(lp);

        FrameLayout card = new FrameLayout(this);
        card.setFocusable(true);
        card.setClickable(true);
        card.setBackground(ui.ovalGlass());
        card.setForeground(ui.ovalRing());
        card.setClipToOutline(true);
        box.addView(card, new LinearLayout.LayoutParams(ui.px(TILE_D), ui.px(TILE_D)));

        final TextView label = ui.text(labelText, 10, Ui.MUTED, Ui.REGULAR);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ui.px(TILE_W), -2);
        llp.topMargin = ui.px(6);
        box.addView(label, llp);

        card.setOnFocusChangeListener((v, has) -> {
            float s = has ? 1.16f : 1f;
            v.animate().scaleX(s).scaleY(s).translationY(has ? -ui.px(2) : 0)
                    .setInterpolator(has ? new OvershootInterpolator(2.2f) : new DecelerateInterpolator())
                    .setDuration(has ? 260 : 160).start();
            v.setElevation(has ? ui.px(10) : 0);
            label.setTextColor(has ? Ui.WHITE : Ui.MUTED);
            label.animate().translationY(has ? ui.px(4) : 0).setDuration(140).start();
        });
        return box;
    }

    private View appTile(ResolveInfo ri) {
        final PackageManager pm = getPackageManager();
        final String pkg = ri.activityInfo.packageName;
        final String name = label(ri);

        final LinearLayout box = tileShell(name);
        box.setTag(pkg);
        FrameLayout card = (FrameLayout) box.getChildAt(0);
        card.setTag(pkg);
        // The app's own icon (not its TV banner), filling the whole disc and clipped to the
        // circle by the card's oval outline, so every icon sits neatly inside the round.
        Drawable icon;
        try { icon = pm.getApplicationIcon(pkg); } catch (Exception e) { icon = ri.loadIcon(pm); }
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(icon);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        card.setContentDescription(name);
        card.setOnFocusChangeListener(chain(card.getOnFocusChangeListener(), (v, has) -> { if (has) focusPkg = pkg; }));
        card.setOnClickListener(v -> {
            if (arranging) { startMove(box); return; }
            Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
            if (launch != null) safeStart(launch);
        });
        card.setOnLongClickListener(v -> { showAppMenu(pkg, name, box); return true; });
        card.setOnKeyListener((v, code, ev) -> {
            if (code == KeyEvent.KEYCODE_MENU && ev.getAction() == KeyEvent.ACTION_UP) { showAppMenu(pkg, name, box); return true; }
            return false;
        });
        return box;
    }

    private static View.OnFocusChangeListener chain(View.OnFocusChangeListener a, View.OnFocusChangeListener b) {
        return (v, has) -> { if (a != null) a.onFocusChange(v, has); b.onFocusChange(v, has); };
    }

    /** Round system tile: emoji icon in the disc, title underneath (box child 1). */
    private View actionTile(String icon, String title, Intent intent) {
        LinearLayout box = tileShell(title);
        FrameLayout card = (FrameLayout) box.getChildAt(0);
        TextView ic = ui.text(icon, 20, Ui.WHITE, Ui.REGULAR);
        ic.setGravity(Gravity.CENTER);
        card.addView(ic, new FrameLayout.LayoutParams(-1, -1));
        if (intent != null) card.setOnClickListener(v -> safeStart(intent));
        return box;
    }

    private void safeStart(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Can't open that here", Toast.LENGTH_SHORT).show();
        }
    }

    // =================================================================== app menu + reordering

    private void showAppMenu(final String pkg, String name, final View box) {
        LinearLayout panel = panel();
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        try {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(getPackageManager().getApplicationIcon(pkg));
            head.addView(iv, new LinearLayout.LayoutParams(ui.px(40), ui.px(40)));
        } catch (Exception ignored) { }
        TextView title = ui.text(name, 22, Ui.WHITE, Ui.MEDIUM);
        title.setPadding(ui.px(14), 0, 0, 0);
        head.addView(title);
        panel.addView(head);

        final Dialog d = dialog(panel);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ui.px(300), -2);
        llp.topMargin = ui.px(18);
        panel.addView(list, llp);

        View open = menuButton(list, "▶   Open", () -> {
            Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (launch == null) launch = getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch != null) safeStart(launch);
        }, d);
        menuButton(list, "⇆   Move left / right", () -> startMove(box), d);
        menuButton(list, "⇤   Move to start", () -> moveToEdge(box, true), d);
        menuButton(list, "⇥   Move to end", () -> moveToEdge(box, false), d);
        menuButton(list, "ⓘ   App info", () -> safeStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg))), d);
        menuButton(list, "✕   Cancel", null, d);
        d.show();
        open.requestFocus();
    }

    private View menuButton(LinearLayout list, String text, final Runnable action, final Dialog d) {
        TextView b = ui.button(text, false);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setOnClickListener(v -> { d.dismiss(); if (action != null) action.run(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = ui.px(8);
        list.addView(b, lp);
        return b;
    }

    private FrameLayout cardOf(View box) { return (FrameLayout) ((ViewGroup) box).getChildAt(0); }

    private static final int TILE_W = 84, TILE_D = 60, TILE_ICON = 40; // small round icons: ~14 per row

    private static final String ARRANGE_HINT = "Arrange apps:  select an app, press OK to pick it up        BACK  finish";

    private void startMove(View box) {
        moving = box;
        FrameLayout card = cardOf(box);
        card.setForeground(ui.ovalMoveRing());
        LayoutTransition lt = new LayoutTransition();
        lt.disableTransitionType(LayoutTransition.APPEARING);
        lt.disableTransitionType(LayoutTransition.DISAPPEARING);
        lt.setDuration(220);
        ((ViewGroup) box.getParent()).setLayoutTransition(lt); // neighbours slide aside
        wiggle = ObjectAnimator.ofFloat(card, View.ROTATION, -1.6f, 1.6f);
        wiggle.setDuration(150);
        wiggle.setRepeatCount(ValueAnimator.INFINITE);
        wiggle.setRepeatMode(ValueAnimator.REVERSE);
        wiggle.start();
        showHint("◀  ▶   move app        OK   drop here");
        card.requestFocus();
    }

    private void showHint(String text) {
        hintBar.setText(text);
        if (hintBar.getVisibility() != View.VISIBLE) {
            hintBar.setVisibility(View.VISIBLE);
            hintBar.setAlpha(0f);
            hintBar.setTranslationY(ui.px(30));
            hintBar.animate().alpha(1f).translationY(0).setStartDelay(0).setDuration(260)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
    }

    private void hideHint() {
        hintBar.animate().alpha(0f).translationY(ui.px(30)).setStartDelay(0).setDuration(200)
                .withEndAction(() -> hintBar.setVisibility(View.GONE)).start();
    }

    private void startArrange() {
        arranging = true;
        showHint(ARRANGE_HINT);
        for (int i = 0; i < rowsBox.getChildCount(); i++) {
            View c = rowsBox.getChildAt(i);
            if (!(c instanceof HorizontalScrollView)) continue;
            ViewGroup row = (ViewGroup) ((ViewGroup) c).getChildAt(0);
            if (row.getChildCount() > 0 && row.getChildAt(0).getTag() instanceof String) {
                cardOf(row.getChildAt(0)).requestFocus();
                return;
            }
        }
    }

    private void stopArrange() {
        arranging = false;
        hideHint();
        Toast.makeText(this, "Arrangement saved", Toast.LENGTH_SHORT).show();
    }

    private void moveBy(int delta) {
        ViewGroup row = (ViewGroup) moving.getParent();
        int from = row.indexOfChild(moving), to = from + delta;
        if (to < 0 || to >= row.getChildCount()) return;
        row.removeView(moving);
        row.addView(moving, to);
        cardOf(moving).requestFocus();
        saveRowOrder(row);
    }

    private void moveToEdge(View box, boolean start) {
        ViewGroup row = (ViewGroup) box.getParent();
        row.removeView(box);
        if (start) row.addView(box, 0); else row.addView(box);
        saveRowOrder(row);
        cardOf(box).requestFocus();
    }

    private void endMove() {
        final FrameLayout card = cardOf(moving);
        if (wiggle != null) wiggle.cancel();
        card.setRotation(0f);
        card.setForeground(ui.ovalRing());
        ((ViewGroup) moving.getParent()).setLayoutTransition(null);
        // little "drop" bounce
        card.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90).withEndAction(() ->
                card.animate().scaleX(1.1f).scaleY(1.1f).setInterpolator(new OvershootInterpolator(3f)).setDuration(240).start()).start();
        moving = null;
        if (arranging) showHint(ARRANGE_HINT);
        else { hideHint(); Toast.makeText(this, "Order saved", Toast.LENGTH_SHORT).show(); }
    }

    private void saveRowOrder(ViewGroup row) {
        try {
            JSONArray a = new JSONArray();
            for (int i = 0; i < row.getChildCount(); i++) {
                Object tag = row.getChildAt(i).getTag();
                if (tag instanceof String) a.put(tag);
            }
            JSONObject orders = json(KEY_ORDER);
            orders.put(String.valueOf(row.getTag()), a);
            putJson(KEY_ORDER, orders);
        } catch (Exception ignored) { }
    }

    // =================================================================== subscriptions

    /** Monthly/yearly plans roll forward automatically once their date has passed. */
    private static LocalDate effectiveDate(JSONObject o, LocalDate today) {
        LocalDate d = LocalDate.parse(o.optString("expires"));
        String rep = o.optString("repeat", "once");
        int guard = 0;
        while (d.isBefore(today) && guard++ < 1000) {
            if ("monthly".equals(rep)) d = d.plusMonths(1);
            else if ("yearly".equals(rep)) d = d.plusYears(1);
            else break;
        }
        return d;
    }

    private void renderSubscriptions() {
        for (Animator a : pulses) a.cancel();
        pulses.clear();
        subsBox.removeAllViews();
        JSONArray subs = subscriptions();
        final LocalDate today = LocalDate.now();
        if (subs.length() == 0) {
            subsSummary.setText("Nothing tracked yet");
            TextView hint = ui.text("Track when Netflix, Prime and other plans renew  ·  select  + Add", 12, Ui.MUTED, Ui.REGULAR);
            hint.setPadding(ui.px(6), 0, 0, 0);
            subsBox.addView(hint);
            return;
        }
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < subs.length(); i++) idx.add(i);
        final JSONArray fs = subs;
        Collections.sort(idx, (a, b) -> safeDate(fs.optJSONObject(a), today).compareTo(safeDate(fs.optJSONObject(b), today)));

        int soon = 0;
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault());
        for (final int i : idx) {
            JSONObject o = subs.optJSONObject(i);
            if (o == null) continue;
            String name = o.optString("name", "?");
            String rep = o.optString("repeat", "once");
            long days;
            String when;
            try {
                LocalDate d = effectiveDate(o, today);
                days = ChronoUnit.DAYS.between(today, d);
                when = ("once".equals(rep) ? "Expires " : "Renews ") + d.format(fmt);
            } catch (Exception e) {
                days = Long.MIN_VALUE;
                when = "Set a date";
            }
            if (days != Long.MIN_VALUE && days <= 7) soon++;

            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setFocusable(true);
            line.setClickable(true);
            // compact chip:  [icon] Name  [12 d]
            line.setBackground(ui.glassItem(18));
            line.setPadding(ui.px(8), ui.px(5), ui.px(8), ui.px(5));
            line.setOnClickListener(v -> editSubscription(i));
            line.setContentDescription(name + ", " + when);

            String pkg = o.optString("pkg", "");
            if (!pkg.isEmpty()) {
                try {
                    ImageView iv = new ImageView(this);
                    iv.setImageDrawable(getPackageManager().getApplicationIcon(pkg));
                    LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(ui.px(20), ui.px(20));
                    ilp.rightMargin = ui.px(7);
                    line.addView(iv, ilp);
                } catch (Exception ignored) { }
            }
            TextView n = ui.text(name, 13, Ui.WHITE, Ui.MEDIUM);
            n.setSingleLine(true);
            n.setMaxWidth(ui.px(150));
            n.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-2, -2);
            nlp.rightMargin = ui.px(8);
            line.addView(n, nlp);

            String pill;
            int color;
            if (days == Long.MIN_VALUE) { pill = "?"; color = 0x80FFFFFF; }
            else if (days < 0) { pill = "Expired"; color = Ui.RED; }
            else if (days == 0) { pill = "Today"; color = Ui.RED; }
            else if (days <= 3) { pill = days + " d"; color = Ui.RED; }
            else if (days <= 14) { pill = days + " d"; color = Ui.AMBER; }
            else { pill = days + " d"; color = Ui.GREEN; }
            TextView p = ui.text(pill, 11, Ui.INK, Ui.MEDIUM);
            p.setShadowLayer(0, 0, 0, 0);
            p.setPadding(ui.px(7), ui.px(2), ui.px(7), ui.px(2));
            p.setBackground(ui.round(color, 10, 0, 0));
            line.addView(p);
            if (days != Long.MIN_VALUE && days <= 3) pulse(p);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = ui.px(10);
            subsBox.addView(line, lp);
        }
        subsSummary.setText(subs.length() + " tracked" + (soon > 0 ? " · " + soon + " due within a week" : " · all good"));
    }

    private static LocalDate safeDate(JSONObject o, LocalDate today) {
        try { return effectiveDate(o, today); } catch (Exception e) { return LocalDate.MAX; }
    }

    private static int repeatIndex(String r) {
        for (int i = 0; i < REPEATS.length; i++) if (REPEATS[i].equals(r)) return i;
        return 0;
    }

    /** Add (index -1) or edit a subscription entirely with the TV remote. */
    private void editSubscription(final int index) {
        final JSONArray subs = subscriptions();
        final JSONObject cur = index >= 0 ? subs.optJSONObject(index) : null;
        final String[] pkgSel = {cur != null ? cur.optString("pkg", "") : ""};
        final int[] repeat = {cur != null ? repeatIndex(cur.optString("repeat", "monthly")) : 1};
        final LocalDate[] date = new LocalDate[1];
        try { date[0] = cur != null ? LocalDate.parse(cur.optString("expires")) : LocalDate.now().plusMonths(1); }
        catch (Exception e) { date[0] = LocalDate.now().plusMonths(1); }

        LinearLayout panel = panel();
        panel.addView(ui.text(cur == null ? "Add subscription" : "Edit subscription", 24, Ui.WHITE, Ui.MEDIUM));

        // --- service picker
        panel.addView(sectionLabel("Service"));
        final EditText nameField = new EditText(this);
        HorizontalScrollView chipsScroll = new HorizontalScrollView(this);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        chipsScroll.setClipToPadding(false);
        final LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(0, ui.px(4), 0, ui.px(4));
        chipsScroll.addView(chips);
        View firstChip = null;
        for (final ResolveInfo ri : streamingApps) {
            final String pkg = ri.activityInfo.packageName;
            final String lbl = label(ri);
            final TextView chip = ui.button(lbl, false);
            chip.setTextSize(13);
            chip.setPadding(ui.px(14), ui.px(8), ui.px(14), ui.px(8));
            chip.setSelected(pkg.equals(pkgSel[0]));
            chip.setOnClickListener(v -> {
                for (int k = 0; k < chips.getChildCount(); k++) chips.getChildAt(k).setSelected(false);
                chip.setSelected(true);
                pkgSel[0] = pkg;
                nameField.setText(lbl);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = ui.px(8);
            chips.addView(chip, lp);
            if (firstChip == null) firstChip = chip;
        }
        panel.addView(chipsScroll, new LinearLayout.LayoutParams(ui.px(640), -2));

        nameField.setText(cur != null ? cur.optString("name") : "");
        nameField.setHint("…or type a name, e.g. Netflix Premium");
        nameField.setSingleLine(true);
        nameField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        nameField.setImeOptions(EditorInfo.IME_ACTION_DONE);
        // Don't pop the keyboard just by passing over the field; open it only on OK.
        nameField.setShowSoftInputOnFocus(false);
        nameField.setOnKeyListener((v, code, ev) -> {
            if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) && ev.getAction() == KeyEvent.ACTION_UP) {
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(v, android.view.inputmethod.InputMethodManager.SHOW_FORCED);
                return true;
            }
            return false;
        });
        nameField.setTextColor(Ui.WHITE);
        nameField.setHintTextColor(0x80FFFFFF);
        nameField.setTextSize(16);
        nameField.setPadding(ui.px(16), ui.px(10), ui.px(16), ui.px(10));
        nameField.setBackground(ui.glassItem(14));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(ui.px(640), -2);
        nlp.topMargin = ui.px(8);
        panel.addView(nameField, nlp);

        // --- date
        panel.addView(sectionLabel("Expires / renews on"));
        LinearLayout dateRow = new LinearLayout(this);
        dateRow.setOrientation(LinearLayout.HORIZONTAL);
        dateRow.setGravity(Gravity.CENTER_VERTICAL);
        final TextView summary = ui.text("", 14, Ui.SOFT, Ui.REGULAR);
        final TextView[] values = new TextView[3];
        final Runnable refresh = () -> {
            values[0].setText(String.valueOf(date[0].getDayOfMonth()));
            values[1].setText(date[0].getMonth().getDisplayName(TextStyle.SHORT, Locale.getDefault()));
            values[2].setText(String.valueOf(date[0].getYear()));
            long dd = ChronoUnit.DAYS.between(LocalDate.now(), date[0]);
            String dow = date[0].getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.getDefault());
            summary.setText(dow + " · " + (dd == 0 ? "today" : dd > 0 ? "in " + dd + " days" : (-dd) + " days ago"));
        };
        String[] names = {"Day", "Month", "Year"};
        // OK on a field starts editing it (gold); ▲▼ then change the value; OK again (or leaving) stops.
        final int[] editing = {-1};
        final View[] steppers = new View[3];
        for (int k = 0; k < 3; k++) {
            final int field = k;
            final LinearLayout st = new LinearLayout(this);
            steppers[k] = st;
            st.setOrientation(LinearLayout.VERTICAL);
            st.setGravity(Gravity.CENTER);
            st.setFocusable(true);
            st.setClickable(true);
            st.setBackground(ui.glassItem(14));
            st.setOnClickListener(v -> {
                editing[0] = editing[0] == field ? -1 : field;
                for (int j = 0; j < 3; j++) steppers[j].setSelected(editing[0] == j);
            });
            st.setOnFocusChangeListener((v, has) -> {
                if (!has && editing[0] == field) { editing[0] = -1; v.setSelected(false); }
            });
            st.setPadding(ui.px(8), ui.px(4), ui.px(8), ui.px(6));
            TextView up = ui.text("▲", 11, Ui.MUTED, Ui.REGULAR);
            values[k] = ui.text("", 24, Ui.WHITE, Ui.MEDIUM);
            TextView dn = ui.text("▼", 11, Ui.MUTED, Ui.REGULAR);
            TextView cap = ui.text(names[k], 10, Ui.MUTED, Ui.REGULAR);
            st.addView(cap);
            st.addView(up);
            addWithTop(st, values[k], 2);
            addWithTop(st, dn, 2);
            st.setOnKeyListener((v, code, ev) -> {
                if (editing[0] != field) return false; // not editing: arrows navigate normally
                if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) return true; // stay put while editing
                if (ev.getAction() != KeyEvent.ACTION_DOWN) return code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN;
                int step = code == KeyEvent.KEYCODE_DPAD_UP ? 1 : code == KeyEvent.KEYCODE_DPAD_DOWN ? -1 : 0;
                if (step == 0) return false;
                date[0] = field == 0 ? date[0].plusDays(step) : field == 1 ? date[0].plusMonths(step) : date[0].plusYears(step);
                refresh.run();
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.px(k == 2 ? 96 : 80), -2);
            lp.rightMargin = ui.px(10);
            dateRow.addView(st, lp);
        }
        View spacer = new View(this);
        dateRow.addView(spacer, new LinearLayout.LayoutParams(ui.px(10), 1));
        String[][] quick = {{"+1 month", "M1"}, {"+3 months", "M3"}, {"+1 year", "Y1"}};
        for (final String[] q : quick) {
            TextView b = ui.button(q[0], false);
            b.setTextSize(13);
            b.setOnClickListener(v -> {
                date[0] = q[1].equals("Y1") ? date[0].plusYears(1) : date[0].plusMonths(q[1].equals("M3") ? 3 : 1);
                refresh.run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = ui.px(8);
            dateRow.addView(b, lp);
        }
        LinearLayout.LayoutParams drlp = new LinearLayout.LayoutParams(-2, -2);
        drlp.topMargin = ui.px(4);
        panel.addView(dateRow, drlp);
        addWithTop(panel, summary, 8);
        addWithTop(panel, ui.text("Tip: press OK on Day / Month / Year, change with ▲ ▼, press OK again", 12, Ui.MUTED, Ui.REGULAR), 4);
        refresh.run();

        // --- repeat
        panel.addView(sectionLabel("Repeats"));
        final LinearLayout repRow = new LinearLayout(this);
        repRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int k = 0; k < REPEATS.length; k++) {
            final int r = k;
            final TextView b = ui.button(REPEAT_LABELS[k], false);
            b.setTextSize(13);
            b.setSelected(repeat[0] == k);
            b.setOnClickListener(v -> {
                repeat[0] = r;
                for (int j = 0; j < repRow.getChildCount(); j++) repRow.getChildAt(j).setSelected(j == r);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = ui.px(8);
            repRow.addView(b, lp);
        }
        TextView repHint = ui.text("   Monthly / Yearly roll forward by themselves", 12, Ui.MUTED, Ui.REGULAR);
        repRow.addView(repHint);
        repRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rrlp = new LinearLayout.LayoutParams(-2, -2);
        rrlp.topMargin = ui.px(4);
        panel.addView(repRow, rrlp);

        // --- actions
        final Dialog d = dialog(panel);
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        TextView save = ui.button("Save", true);
        save.setId(View.generateViewId());
        for (int j = 0; j < repRow.getChildCount(); j++) repRow.getChildAt(j).setNextFocusDownId(save.getId());
        save.setOnClickListener(v -> {
            String name = nameField.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(this, "Pick a service or type a name", Toast.LENGTH_SHORT).show();
                nameField.requestFocus();
                return;
            }
            try {
                JSONObject o = new JSONObject();
                o.put("name", name);
                o.put("expires", date[0].toString());
                o.put("repeat", REPEATS[repeat[0]]);
                if (!pkgSel[0].isEmpty()) o.put("pkg", pkgSel[0]);
                if (index >= 0) subs.put(index, o); else subs.put(o);
                saveSubscriptions(subs);
            } catch (Exception ignored) { }
            d.dismiss();
            renderSubscriptions();
        });
        actions.addView(save, buttonLp());
        if (index >= 0) {
            TextView del = ui.button("Delete", false);
            del.setOnClickListener(v -> {
                subs.remove(index);
                saveSubscriptions(subs);
                d.dismiss();
                renderSubscriptions();
            });
            actions.addView(del, buttonLp());
        }
        TextView cancel = ui.button("Cancel", false);
        cancel.setOnClickListener(v -> d.dismiss());
        actions.addView(cancel, buttonLp());
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-2, -2);
        alp.topMargin = ui.px(22);
        panel.addView(actions, alp);

        d.show();
        if (cur == null && firstChip != null) firstChip.requestFocus(); else save.requestFocus();
    }

    private LinearLayout.LayoutParams buttonLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = ui.px(10);
        return lp;
    }

    private TextView sectionLabel(String s) {
        TextView t = ui.text(s.toUpperCase(Locale.getDefault()), 11, Ui.MUTED, Ui.MEDIUM);
        t.setLetterSpacing(0.12f);
        t.setPadding(0, ui.px(18), 0, ui.px(6));
        return t;
    }

    // =================================================================== dialogs

    private LinearLayout panel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setBackground(ui.glassPanel(26));
        p.setPadding(ui.px(30), ui.px(26), ui.px(30), ui.px(26));
        return p;
    }

    private Dialog dialog(View content) {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(content);
        content.setAlpha(0f);
        d.setOnShowListener(x -> popIn(content));
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.setDimAmount(0.6f);
            w.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        return d;
    }

    // =================================================================== weather

    private void loadWeather() {
        lastWeatherAt = System.currentTimeMillis();
        final String city = config().optString("city", "").trim();
        new Thread(() -> {
            try {
                if (Double.isNaN(lat)) {
                    if (!city.isEmpty()) {
                        JSONObject r = getJson("https://geocoding-api.open-meteo.com/v1/search?count=1&name="
                                + URLEncoder.encode(city, "UTF-8")).getJSONArray("results").getJSONObject(0);
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
                        + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,is_day"
                        + "&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=4", lat, lon));
                JSONObject cur = w.getJSONObject("current");
                JSONObject daily = w.getJSONObject("daily");
                int code = cur.optInt("weather_code");
                boolean day = cur.optInt("is_day", 1) == 1;
                final String icon = weatherIcon(code, day);
                final String temp = Math.round(cur.getDouble("temperature_2m")) + "°";
                final String desc = weatherText(code) + (place.isEmpty() ? "" : "  ·  " + place);
                final String extra = String.format(Locale.getDefault(), "Feels %d°   H %d°  L %d°   💧%d%%   💨%d km/h",
                        Math.round(cur.getDouble("apparent_temperature")),
                        Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)),
                        Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)),
                        cur.optInt("relative_humidity_2m"),
                        Math.round(cur.getDouble("wind_speed_10m")));
                StringBuilder fc = new StringBuilder();
                JSONArray times = daily.getJSONArray("time");
                for (int i = 1; i < times.length(); i++) {
                    DayOfWeek dow = LocalDate.parse(times.getString(i)).getDayOfWeek();
                    if (fc.length() > 0) fc.append("     ");
                    fc.append(dow.getDisplayName(TextStyle.SHORT, Locale.getDefault())).append(' ')
                      .append(weatherIcon(daily.getJSONArray("weather_code").optInt(i), true)).append(' ')
                      .append(Math.round(daily.getJSONArray("temperature_2m_max").getDouble(i))).append("°/")
                      .append(Math.round(daily.getJSONArray("temperature_2m_min").getDouble(i))).append('°');
                }
                final String forecast = fc.toString();
                runOnUiThread(() -> {
                    weatherIcon.setText(icon);
                    weatherTemp.setText(temp);
                    weatherDesc.setText(desc);
                    weatherExtra.setText(extra);
                    forecastView.setText(forecast);
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
        c.setRequestProperty("User-Agent", "AvyTvHome/1.1");
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return new JSONObject(sb.toString());
        } finally {
            c.disconnect();
        }
    }

    private static String weatherIcon(int c, boolean day) {
        if (c == 0) return day ? "☀" : "☾";
        if (c <= 2) return day ? "⛅" : "☁";
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
}

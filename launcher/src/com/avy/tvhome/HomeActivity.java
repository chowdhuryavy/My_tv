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
    private View gearButton, searchButton;
    private TextView avatarText;
    private ImageView avatarImg;
    // arrange mode: OK picks up an app instead of opening it
    private boolean arranging;
    // running animations (stopped while another app is in front)
    private AnimatorSet drift;
    private ObjectAnimator iconFloat, wiggle;
    private final List<Animator> pulses = new ArrayList<>();
    private final List<Animator> particleAnims = new ArrayList<>();
    private FrameLayout rootLayout, particles;

    private long lastWeatherAt = 0;
    private double lat = Double.NaN, lon = Double.NaN;
    private String place = "";

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            Date now = new Date();
            final String hm = new SimpleDateFormat("h:mm", Locale.getDefault()).format(now);
            CharSequence shown = clockView.getText();
            if (shown.length() == 0) {
                clockView.setText(hm);
            } else if (!hm.contentEquals(shown)) {
                // new minute slides up into place
                clockView.animate().translationY(-ui.px(10)).alpha(0f).setDuration(160).withEndAction(() -> {
                    clockView.setText(hm);
                    clockView.setTranslationY(ui.px(12));
                    clockView.animate().translationY(0).alpha(1f).setDuration(260)
                            .setInterpolator(new OvershootInterpolator(1.6f)).start();
                }).start();
            }
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
        syncGooglePhoto(false); // keeps the Google photo current (every few hours)
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

    /**
     * App buttons (Netflix, YouTube, Prime, Web, Settings…) handled right here while the
     * home screen is showing. No accessibility helper needed, so held buttons (volume,
     * arrows) keep repeating normally everywhere.
     */
    private boolean handleAppButton(KeyEvent e) {
        int code = e.getKeyCode();
        if (KeyService.isReserved(code) || code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_MENU) return false;
        String target = KeyService.targetFor(this, code);
        if (target == null) return false;
        if (e.getAction() == KeyEvent.ACTION_UP) {
            android.util.Log.i(KeyService.TAG, "home screen: button " + code + " -> " + target);
            if (KeyService.QUICK.equals(target)) showQuickSettings();
            else if (!KeyService.HOME.equals(target)) KeyService.openApp(this, target);
        }
        return true; // swallow down + up
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (moving == null && !arranging && handleAppButton(e)) return true;
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
        rootLayout = root;
        backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.FIT_XY);
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));

        // floating light particles over the backdrop (animated in startAmbient)
        particles = new FrameLayout(this);
        java.util.Random rnd = new java.util.Random(7);
        int w = getResources().getDisplayMetrics().widthPixels;
        for (int i = 0; i < 14; i++) {
            View dot = new View(this);
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xCCFFFFFF, 0x33FFFFFF});
            g.setShape(GradientDrawable.OVAL);
            g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
            int sz = ui.px(4 + rnd.nextInt(8));
            g.setGradientRadius(sz / 2f);
            dot.setBackground(g);
            FrameLayout.LayoutParams dlp2 = new FrameLayout.LayoutParams(sz, sz);
            dlp2.leftMargin = rnd.nextInt(Math.max(1, w));
            particles.addView(dot, dlp2);
        }
        root.addView(particles, new FrameLayout.LayoutParams(-1, -1));

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
        // ---- app search (left of the gear)
        TextView search = ui.text("🔍", 18, Ui.WHITE, Ui.REGULAR);
        search.setGravity(Gravity.CENTER);
        search.setFocusable(true);
        search.setClickable(true);
        search.setContentDescription("Search apps");
        StateListDrawable sbg = new StateListDrawable();
        GradientDrawable sOn = new GradientDrawable(); sOn.setShape(GradientDrawable.OVAL); sOn.setColor(0x4DFFFFFF); sOn.setStroke(ui.px(3), Ui.WHITE);
        GradientDrawable sOff = new GradientDrawable(); sOff.setShape(GradientDrawable.OVAL); sOff.setColor(0x26FFFFFF); sOff.setStroke(ui.px(1), 0x40FFFFFF);
        sbg.addState(new int[]{android.R.attr.state_focused}, sOn);
        sbg.addState(new int[]{}, sOff);
        search.setBackground(sbg);
        search.setOnClickListener(v -> showAppSearch());
        search.setOnFocusChangeListener((v, has) -> v.animate().scaleX(has ? 1.12f : 1f).scaleY(has ? 1.12f : 1f)
                .setInterpolator(new OvershootInterpolator(2f)).setDuration(220).start());
        LinearLayout.LayoutParams sbl = new LinearLayout.LayoutParams(ui.px(40), ui.px(40));
        sbl.leftMargin = ui.px(14);
        header.addView(search, sbl);
        searchButton = search;

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
        scroll.setPadding(0, ui.px(16), 0, ui.px(40)); // headroom so a lifted (moving) icon isn't cut off
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

        // light particles rise slowly from the bottom, twinkling, each on its own rhythm
        int h = getResources().getDisplayMetrics().heightPixels;
        java.util.Random rnd = new java.util.Random();
        for (int i = 0; particles != null && i < particles.getChildCount(); i++) {
            View dot = particles.getChildAt(i);
            long dur = 16_000 + rnd.nextInt(14_000);
            ObjectAnimator rise = ObjectAnimator.ofFloat(dot, View.TRANSLATION_Y, h + ui.px(20), -ui.px(30));
            rise.setDuration(dur);
            rise.setRepeatCount(ValueAnimator.INFINITE);
            rise.setInterpolator(new android.view.animation.LinearInterpolator());
            rise.setCurrentPlayTime(rnd.nextInt((int) dur)); // start somewhere mid-flight
            rise.start();
            ObjectAnimator sway = ObjectAnimator.ofFloat(dot, View.TRANSLATION_X, -ui.px(18), ui.px(18));
            sway.setDuration(3_000 + rnd.nextInt(3_000));
            sway.setRepeatCount(ValueAnimator.INFINITE);
            sway.setRepeatMode(ValueAnimator.REVERSE);
            sway.setInterpolator(new AccelerateDecelerateInterpolator());
            sway.start();
            ObjectAnimator twinkle = ObjectAnimator.ofFloat(dot, View.ALPHA, 0.15f, 0.8f);
            twinkle.setDuration(1_500 + rnd.nextInt(2_000));
            twinkle.setRepeatCount(ValueAnimator.INFINITE);
            twinkle.setRepeatMode(ValueAnimator.REVERSE);
            twinkle.start();
            particleAnims.add(rise);
            particleAnims.add(sway);
            particleAnims.add(twinkle);
        }
    }

    private void stopAmbient() {
        if (drift != null) drift.cancel();
        if (iconFloat != null) iconFloat.cancel();
        for (Animator a : pulses) a.cancel();
        for (Animator a : particleAnims) a.cancel();
        particleAnims.clear();
    }

    /** Header, cards and app rows glide in, rows one after another. */
    private void playEntrance() {
        DecelerateInterpolator dec = new DecelerateInterpolator(2f);
        slideIn(headerView, -ui.px(24), 0, 0, dec);
        slideIn(weatherCard, ui.px(40), 0, 80, dec);
        slideIn(searchButton, ui.px(40), 0, 95, dec);
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

    // =================================================================== app search

    /** Type to find an app; results narrow as you type; OK opens it. */
    private void showAppSearch() {
        final PackageManager pm = getPackageManager();
        // every launchable app, once, sorted by name
        final List<String[]> all = new ArrayList<>(); // {label, package}
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
                all.add(new String[]{label(ri), pkg});
            }
        }
        Collections.sort(all, (a, b) -> a[0].compareToIgnoreCase(b[0]));

        LinearLayout panel = panel();
        panel.addView(ui.text("Search apps", 24, Ui.WHITE, Ui.MEDIUM));
        final EditText q = new EditText(this);
        q.setHint("Type an app name…");
        q.setSingleLine(true);
        q.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        q.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        q.setTextColor(Ui.WHITE);
        q.setHintTextColor(0x80FFFFFF);
        q.setTextSize(18);
        q.setPadding(ui.px(16), ui.px(10), ui.px(16), ui.px(10));
        q.setBackground(ui.glassItem(14));
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(ui.px(560), -2);
        qlp.topMargin = ui.px(12);
        panel.addView(q, qlp);
        final TextView count = ui.text("", 12, Ui.MUTED, Ui.REGULAR);
        addWithTop(panel, count, 8);

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipToPadding(false);
        final LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        results.setPadding(ui.px(2), ui.px(4), ui.px(2), ui.px(4));
        sv.addView(results);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ui.px(560), ui.px(300));
        slp.topMargin = ui.px(6);
        panel.addView(sv, slp);
        final Dialog d = dialog(panel);

        final Runnable refresh = () -> {
            String term = q.getText().toString().trim().toLowerCase(Locale.getDefault());
            results.removeAllViews();
            int shown = 0;
            for (final String[] app : all) {
                if (!term.isEmpty() && !app[0].toLowerCase(Locale.getDefault()).contains(term)
                        && !app[1].toLowerCase(Locale.ROOT).contains(term)) continue;
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setFocusable(true);
                row.setClickable(true);
                row.setBackground(ui.glassItem(14));
                row.setPadding(ui.px(12), ui.px(7), ui.px(12), ui.px(7));
                try {
                    ImageView iv = new ImageView(this);
                    iv.setImageDrawable(appIcon(app[1]));
                    LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(ui.px(30), ui.px(30));
                    ilp.rightMargin = ui.px(12);
                    row.addView(iv, ilp);
                } catch (Exception ignored) { }
                row.addView(ui.text(app[0], 15, Ui.WHITE, Ui.MEDIUM));
                row.setOnClickListener(v -> { d.dismiss(); KeyService.openApp(this, app[1]); });
                row.setAlpha(0f);
                row.setTranslationY(ui.px(8));
                row.animate().alpha(1f).translationY(0).setStartDelay(Math.min(shown, 8) * 25L).setDuration(180).start();
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
                rlp.topMargin = ui.px(6);
                results.addView(row, rlp);
                shown++;
            }
            count.setText(shown == 0 ? "No app matches “" + q.getText() + "”"
                    : term.isEmpty() ? all.size() + " apps" : shown + (shown == 1 ? " match" : " matches") + "  ·  press ▼ to pick");
        };
        q.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { refresh.run(); }
            @Override public void afterTextChanged(android.text.Editable s) { }
        });
        // keyboard "search"/"done": close the keyboard and jump to the first result
        q.setOnEditorActionListener((v, actionId, ev) -> {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
            if (results.getChildCount() > 0) results.getChildAt(0).requestFocus();
            return true;
        });
        refresh.run();
        d.show();
        q.requestFocus();
        handler.postDelayed(() -> {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(q, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        }, 300);
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

    /** App icon, with compact replacements for apps whose TV icon is a wide wordmark (e.g. Netflix). */
    private Drawable appIcon(String pkg) throws PackageManager.NameNotFoundException {
        Drawable o = ui.iconOverride(pkg);
        return o != null ? o : getPackageManager().getApplicationIcon(pkg);
    }

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
        TextView status = ui.text(on
                ? "●  Buttons work everywhere (helper on). Held buttons like volume won't repeat."
                : "●  Buttons work from this home screen. Holding volume / arrows works normally.",
                13, Ui.GREEN, Ui.REGULAR);
        status.setLineSpacing(ui.px(3), 1f);
        addWithTop(panel, status, 6);
        final Dialog d = dialog(panel);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ui.px(460), -2);
        llp.topMargin = ui.px(12);
        panel.addView(list, llp);

        View first = menuButton(list, "＋   Set up a button", this::learnButton, d);
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
        // Without the helper the press arrives here, at this dialog.
        d.setOnKeyListener((di, code, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN || ev.getRepeatCount() != 0) return false;
            if (KeyService.isReserved(code) || code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) return false;
            KeyService.learner = null;
            d.dismiss();
            chooseAppFor(code, KeyEvent.keyCodeToString(code));
            return true;
        });
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

    // =================================================================== Google photo sync

    private static final String KEY_G_REFRESH = "g_refresh_token";
    private static final String KEY_G_SYNCED_AT = "g_synced_at";
    private static final long G_SYNC_EVERY_MS = 6 * 60 * 60 * 1000L;

    private File googlePhotoFile() { return new File(getFilesDir(), "google_avatar.png"); }

    private String gClientId() { return config().optString("google_client_id", "").trim(); }
    private String gClientSecret() { return config().optString("google_client_secret", "").trim(); }

    /** Shows a code; the user approves on their phone at google.com/device. */
    private void startGoogleSignIn() {
        final String id = gClientId(), secret = gClientSecret();
        if (id.isEmpty() || secret.isEmpty()) {
            LinearLayout panel = panel();
            panel.addView(ui.text("One-time setup needed", 22, Ui.WHITE, Ui.MEDIUM));
            TextView t = ui.text("Google needs a sign-in ID for this home screen.\nFollow \"Google photo sync\" in the My_tv README on the PC,\nthen run scripts\\set-config.ps1.", 14, Ui.SOFT, Ui.REGULAR);
            t.setLineSpacing(ui.px(3), 1f);
            addWithTop(panel, t, 8);
            final Dialog d = dialog(panel);
            TextView ok = ui.button("OK", true);
            ok.setOnClickListener(v -> d.dismiss());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = ui.px(18);
            panel.addView(ok, lp);
            d.show();
            ok.requestFocus();
            return;
        }
        LinearLayout panel = panel();
        panel.addView(ui.text("Sign in with Google", 24, Ui.WHITE, Ui.MEDIUM));
        final TextView step = ui.text("Getting a code…", 15, Ui.SOFT, Ui.REGULAR);
        addWithTop(panel, step, 10);
        final TextView code = ui.text("", 46, Ui.WHITE, Ui.MEDIUM);
        code.setLetterSpacing(0.15f);
        addWithTop(panel, code, 12);
        final TextView status = ui.text("", 13, Ui.MUTED, Ui.REGULAR);
        addWithTop(panel, status, 12);
        final Dialog d = dialog(panel);
        final boolean[] cancelled = {false};
        TextView cancel = ui.button("Cancel", false);
        cancel.setOnClickListener(v -> d.dismiss());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = ui.px(18);
        panel.addView(cancel, lp);
        d.setOnDismissListener(x -> cancelled[0] = true);
        d.show();
        cancel.requestFocus();

        new Thread(() -> {
            try {
                GoogleProfile.DeviceCode dc = GoogleProfile.start(id);
                runOnUiThread(() -> {
                    step.setText("On your phone, open  " + dc.url.replace("https://", "") + "  and enter:");
                    code.setText(dc.userCode);
                    status.setText("Waiting for you to approve on the phone…");
                });
                long until = System.currentTimeMillis() + dc.expiresIn * 1000L;
                int wait = Math.max(dc.interval, 5);
                while (!cancelled[0] && System.currentTimeMillis() < until) {
                    Thread.sleep(wait * 1000L);
                    JSONObject tok = GoogleProfile.poll(id, secret, dc.deviceCode);
                    if (tok == null) continue;
                    String refresh = tok.optString("refresh_token", "");
                    if (!refresh.isEmpty()) prefs().edit().putString(KEY_G_REFRESH, refresh).apply();
                    runOnUiThread(() -> { status.setText("Signed in. Getting your photo…"); });
                    syncGooglePhotoBlocking(tok.optString("access_token", null));
                    runOnUiThread(() -> { if (d.isShowing()) d.dismiss(); refreshAvatar(); });
                    return;
                }
                if (!cancelled[0]) runOnUiThread(() -> status.setText("The code expired. Close and try again."));
            } catch (Exception e) {
                final String msg = e.getMessage();
                runOnUiThread(() -> status.setText("Couldn't sign in: " + msg));
            }
        }).start();
    }

    /** Background refresh of the Google photo (on resume, at most every few hours). */
    private void syncGooglePhoto(boolean force) {
        if (prefs().getString(KEY_G_REFRESH, "").isEmpty()) return;
        if (!force && System.currentTimeMillis() - prefs().getLong(KEY_G_SYNCED_AT, 0) < G_SYNC_EVERY_MS) return;
        prefs().edit().putLong(KEY_G_SYNCED_AT, System.currentTimeMillis()).apply();
        new Thread(() -> {
            try {
                syncGooglePhotoBlocking(null);
                runOnUiThread(() -> { refreshAvatar(); if (force) Toast.makeText(this, "Google photo updated", Toast.LENGTH_SHORT).show(); });
            } catch (Exception e) {
                if (force) runOnUiThread(() -> Toast.makeText(this, "Couldn't update photo: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void syncGooglePhotoBlocking(String access) throws Exception {
        if (access == null) access = GoogleProfile.accessToken(gClientId(), gClientSecret(), prefs().getString(KEY_G_REFRESH, ""));
        JSONObject me = GoogleProfile.userInfo(access);
        SharedPreferences.Editor ed = prefs().edit().putLong(KEY_G_SYNCED_AT, System.currentTimeMillis());
        if (!me.optString("email").isEmpty()) ed.putString(KEY_ACCOUNT, me.optString("email"));
        ed.apply();
        String pic = me.optString("picture", "");
        if (pic.isEmpty()) return;
        Bitmap b = GoogleProfile.photo(pic);
        if (b == null) return;
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(googlePhotoFile())) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    private void refreshAvatar() {
        if (avatar == null) return;
        readGoogleAccount();
        File dir = getExternalFilesDir(null);
        File photo = null;
        File g = googlePhotoFile();
        if (g.isFile()) photo = g; // synced from Google wins
        if (photo == null && dir != null) for (String n : new String[]{"avatar.jpg", "avatar.png", "avatar.webp"}) {
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
        // Google photo sync (sign-in on the phone at google.com/device)
        boolean signedIn = !prefs().getString(KEY_G_REFRESH, "").isEmpty();
        View gBtn;
        if (!signedIn) {
            gBtn = menuButton(list, "G   Sign in with Google (photo)", this::startGoogleSignIn, d);
        } else {
            gBtn = menuButton(list, "↻   Update Google photo now", () -> syncGooglePhoto(true), d);
            menuButton(list, "⎋   Stop Google photo sync", () -> {
                prefs().edit().remove(KEY_G_REFRESH).remove(KEY_G_SYNCED_AT).apply();
                googlePhotoFile().delete();
                refreshAvatar();
            }, d);
        }
        if (first == null) first = gBtn;
        menuButton(list, "✎   " + (name.isEmpty() ? "Set your name" : "Change name"), this::editName, d);
        menuButton(list, "⚙   Accounts (Settings)", () -> safeStart(new Intent(Settings.ACTION_SETTINGS)), d);
        menuButton(list, "✕   Close", null, d);
        TextView tip = ui.text(signedIn ? "Your Google photo updates automatically every few hours."
                : "Signing in lets this home screen read only your Google name and photo.", 11, Ui.MUTED, Ui.REGULAR);
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
        TextView t = ui.text(title, 15, Ui.SOFT, Ui.MEDIUM); // lights up when its row has focus
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
            boolean lifted = moving != null && moving == box; // app being moved stays lifted
            if (lifted) return;
            float s = has ? 1.16f : 1f;
            v.animate().scaleX(s).scaleY(s).translationY(has ? -ui.px(2) : 0)
                    .setInterpolator(has ? new OvershootInterpolator(2.2f) : new DecelerateInterpolator())
                    .setDuration(has ? 260 : 160).start();
            v.setElevation(has ? ui.px(10) : 0);
            label.setTextColor(has ? Ui.WHITE : Ui.MUTED);
            label.animate().translationY(has ? ui.px(4) : 0).setDuration(140).start();
            highlightRowTitle(box, has);
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
        try { icon = appIcon(pkg); } catch (Exception e) { icon = ri.loadIcon(pm); }
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(icon);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        card.setContentDescription(name);
        card.setOnFocusChangeListener(chain(card.getOnFocusChangeListener(), (v, has) -> { if (has) focusPkg = pkg; }));
        card.setOnClickListener(v -> {
            if (arranging) { startMove(box); return; }
            // press: quick squeeze and bounce, then open the app
            v.animate().scaleX(0.86f).scaleY(0.86f).setDuration(90).setInterpolator(new DecelerateInterpolator())
                    .withEndAction(() -> {
                        v.animate().scaleX(1.16f).scaleY(1.16f).setDuration(220)
                                .setInterpolator(new OvershootInterpolator(3f)).start();
                        Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
                        if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
                        if (launch != null) safeStart(launch);
                    }).start();
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
            iv.setImageDrawable(appIcon(pkg));
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

    /** Row title (the TextView just above the row's scroller) brightens and nudges right while its row has focus. */
    private void highlightRowTitle(View box, boolean on) {
        try {
            View hs = (View) box.getParent().getParent();
            ViewGroup rows = (ViewGroup) hs.getParent();
            View title = rows.getChildAt(rows.indexOfChild(hs) - 1);
            if (!(title instanceof TextView)) return;
            ((TextView) title).setTextColor(on ? Ui.WHITE : Ui.SOFT);
            title.animate().translationX(on ? ui.px(8) : 0).setDuration(220)
                    .setInterpolator(new DecelerateInterpolator()).start();
        } catch (Exception ignored) { } // tile temporarily detached while moving
    }

    private ValueAnimator ringPulse;

    private void startMove(View box) {
        moving = box;
        final FrameLayout card = cardOf(box);
        final ViewGroup row = (ViewGroup) box.getParent();
        card.setForeground(ui.ovalMoveRing());
        // neighbours glide aside with a soft overshoot
        LayoutTransition lt = new LayoutTransition();
        lt.disableTransitionType(LayoutTransition.APPEARING);
        lt.disableTransitionType(LayoutTransition.DISAPPEARING);
        lt.setDuration(260);
        lt.setInterpolator(LayoutTransition.CHANGE_APPEARING, new OvershootInterpolator(1.2f));
        lt.setInterpolator(LayoutTransition.CHANGE_DISAPPEARING, new OvershootInterpolator(1.2f));
        row.setLayoutTransition(lt);
        // lift: bigger, higher, above everything
        card.setElevation(ui.px(18));
        card.animate().scaleX(1.3f).scaleY(1.3f).translationY(-ui.px(10)).rotation(0)
                .setInterpolator(new OvershootInterpolator(2.5f)).setDuration(320).start();
        ((TextView) ((ViewGroup) box).getChildAt(1)).setTextColor(Ui.GOLD);
        // everything else in the row steps back
        for (int i = 0; i < row.getChildCount(); i++) {
            View other = row.getChildAt(i);
            if (other != box) other.animate().alpha(0.4f).scaleX(0.94f).scaleY(0.94f).setDuration(240).start();
        }
        // gentle wiggle + pulsing gold ring while held
        wiggle = ObjectAnimator.ofFloat(card, View.ROTATION, -1.4f, 1.4f);
        wiggle.setDuration(160);
        wiggle.setRepeatCount(ValueAnimator.INFINITE);
        wiggle.setRepeatMode(ValueAnimator.REVERSE);
        wiggle.setStartDelay(320);
        wiggle.start();
        ringPulse = ValueAnimator.ofInt(255, 110);
        ringPulse.setDuration(600);
        ringPulse.setRepeatCount(ValueAnimator.INFINITE);
        ringPulse.setRepeatMode(ValueAnimator.REVERSE);
        ringPulse.addUpdateListener(a -> {
            Drawable fg = card.getForeground();
            if (fg != null) fg.setAlpha((Integer) a.getAnimatedValue());
        });
        ringPulse.start();
        showHint("◀  ▶   move app        OK   drop here");
        card.requestFocus();
    }

    /** Expanding gold rings from a view's centre (drop "splash"). */
    private void burstAt(View v) {
        if (rootLayout == null || v.getWidth() == 0) return;
        int[] at = new int[2], base = new int[2];
        v.getLocationInWindow(at);
        rootLayout.getLocationInWindow(base);
        int size = v.getWidth();
        for (int k = 0; k < 2; k++) {
            final View ring = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setStroke(ui.px(k == 0 ? 3 : 2), Ui.GOLD);
            ring.setBackground(g);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
            lp.leftMargin = at[0] - base[0];
            lp.topMargin = at[1] - base[1];
            rootLayout.addView(ring, lp);
            ring.setScaleX(1f);
            ring.setScaleY(1f);
            ring.animate().scaleX(k == 0 ? 2.1f : 2.7f).scaleY(k == 0 ? 2.1f : 2.7f).alpha(0f)
                    .setStartDelay(k * 130L).setDuration(560).setInterpolator(new DecelerateInterpolator())
                    .withEndAction(() -> rootLayout.removeView(ring)).start();
        }
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
        final FrameLayout card = cardOf(moving);
        card.requestFocus();
        // hop into the new spot
        card.animate().cancel();
        card.animate().scaleX(1.3f).scaleY(1.3f).translationY(-ui.px(24)).setDuration(110)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> card.animate().translationY(-ui.px(10)).setDuration(200)
                        .setInterpolator(new OvershootInterpolator(3f)).start()).start();
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
        final View box = moving;
        final FrameLayout card = cardOf(box);
        final ViewGroup row = (ViewGroup) box.getParent();
        if (wiggle != null) wiggle.cancel();
        if (ringPulse != null) ringPulse.cancel();
        card.setRotation(0f);
        card.setForeground(ui.ovalRing());
        row.setLayoutTransition(null);
        ((TextView) ((ViewGroup) box).getChildAt(1)).setTextColor(Ui.WHITE);
        moving = null;
        // drop: press down, settle with a bounce, splash of gold rings
        card.animate().cancel();
        card.animate().scaleX(0.9f).scaleY(0.9f).translationY(0).setDuration(110)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    burstAt(card);
                    card.setElevation(ui.px(10));
                    card.animate().scaleX(1.16f).scaleY(1.16f).translationY(-ui.px(2))
                            .setInterpolator(new OvershootInterpolator(3f)).setDuration(300).start();
                }).start();
        // neighbours come back, rippling outwards from the drop point
        int at = row.indexOfChild(box);
        for (int i = 0; i < row.getChildCount(); i++) {
            View other = row.getChildAt(i);
            if (other == box) continue;
            other.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(60L * Math.abs(i - at))
                    .setDuration(260).setInterpolator(new OvershootInterpolator(1.5f)).start();
        }
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
                    iv.setImageDrawable(appIcon(pkg));
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
            // chips pop in one after another
            line.setAlpha(0f);
            line.setScaleX(0.7f);
            line.setScaleY(0.7f);
            line.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(250 + 80L * subsBox.indexOfChild(line))
                    .setDuration(300).setInterpolator(new OvershootInterpolator(2f)).start();
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

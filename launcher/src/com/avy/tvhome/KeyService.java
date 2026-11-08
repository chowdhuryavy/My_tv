package com.avy.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import org.json.JSONObject;

/**
 * Makes the remote's app buttons (Netflix, YouTube, Prime Video, ...) work again.
 * On Google TV those buttons are normally handled by the stock launcher, which is
 * disabled to remove its ads. This service sees every remote key, and when a key is
 * mapped to an app (Remote buttons screen, or the defaults below) it opens that app.
 */
public class KeyService extends AccessibilityService {

    static final String TAG = "AvyKeys";
    /** Built-in targets that aren't apps. */
    static final String QUICK = "avy:quick", HOME = "avy:home";
    static final String KEY_BUTTONS = "buttons_json";

    /** While the "press a button" screen is open, keys go here instead. */
    interface Learner { boolean onKey(int keyCode, String keyName); }
    static volatile Learner learner;

    /** Keys the service never touches (navigation, volume, power, media). */
    static boolean isReserved(int c) {
        switch (c) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_VOLUME_MUTE: case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_TV_POWER: case KeyEvent.KEYCODE_SLEEP: case KeyEvent.KEYCODE_WAKEUP:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: case KeyEvent.KEYCODE_MEDIA_PLAY: case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP: case KeyEvent.KEYCODE_MEDIA_NEXT: case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_CHANNEL_UP: case KeyEvent.KEYCODE_CHANNEL_DOWN:
                return true;
            default:
                return c >= KeyEvent.KEYCODE_0 && c <= KeyEvent.KEYCODE_9;
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (down && e.getRepeatCount() == 0) {
            Log.i(TAG, "key " + code + " " + KeyEvent.keyCodeToString(code) + " scan=" + e.getScanCode());
        } else if (down && e.getRepeatCount() % 5 == 0) {
            Log.i(TAG, "held " + KeyEvent.keyCodeToString(code) + " repeat=" + e.getRepeatCount());
        }
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) return handleVolume(e);
        if (isReserved(code)) return false;

        Learner l = learner;
        if (l != null) {
            if (down && e.getRepeatCount() == 0) return l.onKey(code, KeyEvent.keyCodeToString(code));
            return true;
        }

        String pkg = mapped(code);
        if (down && e.getRepeatCount() == 0) Log.i(TAG, "  -> " + (pkg == null ? "not mapped, passing through" : "opening " + pkg));
        if (pkg == null) return false;
        if (e.getAction() == KeyEvent.ACTION_UP) launch(pkg);
        return true; // swallow both down and up so nothing else reacts
    }

    // ------------------------------------------------------------------ volume

    /*
     * While key filtering is on, this TV stops repeating held buttons, so holding
     * Volume +/- only moved one step. The helper now does volume itself: one step on
     * press, then keeps stepping every 120 ms after a short pause until the button is
     * released (on-screen volume bar shown as usual).
     */
    private final android.os.Handler volHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private int volDir = 0;
    private final Runnable volRepeat = new Runnable() {
        @Override public void run() {
            if (volDir == 0) return;
            stepVolume(volDir);
            volHandler.postDelayed(this, 120);
        }
    };

    private boolean handleVolume(KeyEvent e) {
        int dir = e.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP ? 1 : -1;
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() == 0) {
                volDir = dir;
                stepVolume(dir);
                volHandler.removeCallbacks(volRepeat);
                volHandler.postDelayed(volRepeat, 400);
            }
            // hardware repeats (if any) are ignored: our own timer already repeats
        } else if (e.getAction() == KeyEvent.ACTION_UP) {
            volDir = 0;
            volHandler.removeCallbacks(volRepeat);
        }
        return true;
    }

    private void stepVolume(int dir) {
        android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return;
        am.adjustSuggestedStreamVolume(dir > 0 ? android.media.AudioManager.ADJUST_RAISE : android.media.AudioManager.ADJUST_LOWER,
                android.media.AudioManager.USE_DEFAULT_STREAM_TYPE, android.media.AudioManager.FLAG_SHOW_UI);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        volDir = 0;
        volHandler.removeCallbacks(volRepeat);
        return super.onUnbind(intent);
    }

    /**
     * Built-in mapping for this TCL remote's app buttons (key codes captured on the TV).
     * Anything set on the Remote buttons screen overrides these.
     */
    static final int[][] DEFAULT_CODES = {{4062}, {4099}, {4065}, {KeyEvent.KEYCODE_NOTIFICATION}, {KeyEvent.KEYCODE_ALL_APPS}, {4057}};
    static final String[] DEFAULT_PKGS = {
        "com.netflix.ninja",                     // 4062  Netflix button
        "com.amazon.amazonvideo.livingroom",     // 4099  Prime Video button (confirmed on the TV)
        "com.google.android.youtube.tv",         // 4065  YouTube button (confirmed on the TV)
        QUICK,                                   // 83    Settings (gear) button: Google TV sends NOTIFICATION
        HOME,                                    // 284   Apps button
        "com.tcl.browser",                       // 4057  Web button (TCL) -> BrowseHere browser
    };

    static String defaultFor(int code) {
        for (int i = 0; i < DEFAULT_CODES.length; i++) if (DEFAULT_CODES[i][0] == code) return DEFAULT_PKGS[i];
        return null;
    }

    private String mapped(int code) { return targetFor(this, code); }

    /** What a remote button opens: the user's choice, else the built-in default; null if nothing. Shared with HomeActivity. */
    static String targetFor(android.content.Context c, int code) {
        SharedPreferences p = c.getSharedPreferences("tvhome", MODE_PRIVATE);
        try {
            JSONObject m = new JSONObject(p.getString(KEY_BUTTONS, "{}"));
            String key = String.valueOf(code);
            if (m.has(key)) {
                String pkg = m.optString(key, "");
                return !pkg.isEmpty() && installed(c, pkg) ? pkg : null; // "" = user turned it off
            }
        } catch (Exception ignored) { }
        String def = defaultFor(code);
        return def != null && installed(c, def) ? def : null;
    }

    static boolean installed(android.content.Context c, String pkg) {
        if (pkg.startsWith("avy:")) return true;
        PackageManager pm = c.getPackageManager();
        if (pm.getLeanbackLaunchIntentForPackage(pkg) != null || pm.getLaunchIntentForPackage(pkg) != null) return true;
        Log.w(TAG, "  app not found: " + pkg);
        return false;
    }

    /** Opens an app by package (TV launcher entry first). */
    static void openApp(android.content.Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try { c.startActivity(i); } catch (Exception ex) { Log.w(TAG, "launch failed " + pkg, ex); }
    }

    /** One-time cleanup: v2.2/2.3 let a single click switch buttons off by accident. */
    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        // This TV sometimes re-binds the service without the key-filter flag from the XML,
        // and then no button reaches us. Request it explicitly every time we connect.
        try {
            android.accessibilityservice.AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
                setServiceInfo(info);
            }
        } catch (Exception ex) { Log.w(TAG, "could not request key events", ex); }
        resetAccidentalOffs(this);
        Log.i(TAG, "helper connected");
    }

    static void resetAccidentalOffs(android.content.Context c) {
        SharedPreferences p = c.getSharedPreferences("tvhome", MODE_PRIVATE);
        if (p.getBoolean("buttons_reset_v25", false)) return;
        try {
            JSONObject m = new JSONObject(p.getString(KEY_BUTTONS, "{}"));
            JSONObject clean = new JSONObject();
            for (java.util.Iterator<String> it = m.keys(); it.hasNext(); ) {
                String k = it.next();
                if (k.equals("4099") || k.equals("4065")) continue; // v2.5: codes were swapped, use corrected defaults
                if (!m.optString(k).isEmpty()) clean.put(k, m.optString(k));
            }
            p.edit().putString(KEY_BUTTONS, clean.toString()).putBoolean("buttons_reset_v25", true).apply();
        } catch (Exception ignored) { }
    }

    private void launch(String pkg) {
        if (pkg.startsWith("avy:")) {
            Intent home = new Intent(this, HomeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if (QUICK.equals(pkg)) home.putExtra("open", "quick");
            try { startActivity(home); } catch (Exception ex) { Log.w(TAG, "open home failed", ex); }
            return;
        }
        openApp(this, pkg);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
}

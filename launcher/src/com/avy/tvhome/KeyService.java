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
    static final String KEY_BUTTONS = "buttons_json";

    /** While the "press a button" screen is open, keys go here instead. */
    interface Learner { boolean onKey(int keyCode, String keyName); }
    static volatile Learner learner;

    /** Keys the service never touches (navigation, volume, power, media). */
    static boolean isReserved(int c) {
        switch (c) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_HOME: case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN: case KeyEvent.KEYCODE_VOLUME_MUTE: case KeyEvent.KEYCODE_POWER:
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
        }
        if (isReserved(code)) return false;

        Learner l = learner;
        if (l != null) {
            if (down && e.getRepeatCount() == 0) return l.onKey(code, KeyEvent.keyCodeToString(code));
            return true;
        }

        String pkg = mapped(code);
        if (pkg == null) return false;
        if (e.getAction() == KeyEvent.ACTION_UP) launch(pkg);
        return true; // swallow both down and up so nothing else reacts
    }

    private String mapped(int code) {
        SharedPreferences p = getSharedPreferences("tvhome", MODE_PRIVATE);
        try {
            JSONObject m = new JSONObject(p.getString(KEY_BUTTONS, "{}"));
            String pkg = m.optString(String.valueOf(code), "");
            if (!pkg.isEmpty() && installed(pkg)) return pkg;
        } catch (Exception ignored) { }
        return null;
    }

    private boolean installed(String pkg) {
        try { getPackageManager().getApplicationInfo(pkg, 0); return true; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }

    private void launch(String pkg) {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try { startActivity(i); } catch (Exception ex) { Log.w(TAG, "launch failed " + pkg, ex); }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
}

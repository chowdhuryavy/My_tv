package com.avy.tvhome;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;

/** Glass-style drawing helpers shared by the home screen and its dialogs. */
final class Ui {

    static final int WHITE = 0xFFFFFFFF;
    static final int SOFT = 0xD9FFFFFF;
    static final int MUTED = 0x99FFFFFF;
    static final int INK = 0xFF0B1026;
    static final int GOLD = 0xFFFFD166;
    static final int GREEN = 0xFF5BE49B;
    static final int AMBER = 0xFFFFB547;
    static final int RED = 0xFFFF5C7A;

    /** Theme names; the extra last slot (index THEMES.length) is "Auto" (changes with the time of day). */
    static final String[] THEMES = {"Aurora", "Ocean", "Sunset", "Forest", "Midnight", "Neon", "Rose"};
    static final int AUTO = THEMES.length;
    // {base, colour A, colour B, colour C}
    private static final int[][] PALETTES = {
        {0xFF0B1026, 0xFF7B5CFF, 0xFF00C6FF, 0xFFFF4FA3},   // Aurora
        {0xFF02121F, 0xFF0077B6, 0xFF00B4D8, 0xFF5E60CE},   // Ocean
        {0xFF170A1C, 0xFFFF7A59, 0xFFFF3D77, 0xFF7B2CBF},   // Sunset
        {0xFF06140F, 0xFF2D6A4F, 0xFF52B788, 0xFF1B4965},   // Forest
        {0xFF03060F, 0xFF1B2A6B, 0xFF3A1F6E, 0xFF0E5A7A},   // Midnight (+ stars)
        {0xFF07020F, 0xFFFF2E93, 0xFF00E5FF, 0xFF7C4DFF},   // Neon
        {0xFF1A0E14, 0xFFE8A0A8, 0xFFB76E79, 0xFF6D3B5A},   // Rose
    };
    /** Accent per theme: focus glow, row titles. */
    private static final int[] ACCENTS = {
        0xFFA996FF, 0xFF4DD0E1, 0xFFFF9E80, 0xFF8BE0A4, 0xFF8AB4FF, 0xFFFF4FD8, 0xFFFFB3C1,
    };

    /** Current accent colour (set when a theme is applied). */
    static int accent = 0xFFA996FF;

    static String themeName(int t) { return t == AUTO ? "Auto" : THEMES[Math.floorMod(t, THEMES.length)]; }

    /** Auto: Rose in the morning, Ocean by day, Sunset in the evening, Midnight at night. */
    static int resolveTheme(int t) {
        if (t != AUTO) return Math.floorMod(t, THEMES.length);
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        if (h >= 5 && h < 10) return 6;    // Rose
        if (h >= 10 && h < 17) return 1;   // Ocean
        if (h >= 17 && h < 21) return 2;   // Sunset
        return 4;                          // Midnight
    }

    static int accentFor(int resolved) { return ACCENTS[Math.floorMod(resolved, ACCENTS.length)]; }

    final Context c;
    final float dp;

    Ui(Context c) {
        this.c = c;
        this.dp = c.getResources().getDisplayMetrics().density;
    }

    int px(float v) { return Math.round(v * dp); }

    static final int REGULAR = 0, MEDIUM = 1, LIGHT = 2;

    TextView text(String s, float sp, int color, int weight) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        String family = weight == MEDIUM ? "sans-serif-medium" : weight == LIGHT ? "sans-serif-light" : "sans-serif";
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setShadowLayer(px(6), 0, px(1), 0x55000000);
        t.setIncludeFontPadding(false);
        return t;
    }

    GradientDrawable round(int fill, float radiusDp, float strokeDp, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radiusDp * dp);
        if (strokeDp > 0) d.setStroke(Math.max(1, px(strokeDp)), stroke);
        return d;
    }

    private GradientDrawable sheen(float radiusDp, int topAlpha) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{(topAlpha << 24) | 0xFFFFFF, 0x00FFFFFF});
        g.setCornerRadius(radiusDp * dp);
        return g;
    }

    /** Frosted glass card: translucent fill, fine light border, soft top sheen. */
    Drawable glass(float radiusDp) {
        return new LayerDrawable(new Drawable[]{round(0x26FFFFFF, radiusDp, 1, 0x40FFFFFF), sheen(radiusDp, 0x1C)});
    }

    /** Darker glass for dialogs so text stays readable over the dimmed screen. */
    Drawable glassPanel(float radiusDp) {
        return new LayerDrawable(new Drawable[]{round(0xEB141A2E, radiusDp, 1, 0x4DFFFFFF), sheen(radiusDp, 0x14)});
    }

    /** Focus ring drawn on top of tiles (works over banners too). */
    Drawable focusRing(float radiusDp) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, round(0x00000000, radiusDp, 3, WHITE));
        s.addState(new int[]{}, round(0x00000000, radiusDp, 1, 0x33FFFFFF));
        return s;
    }

    /**
     * Some TV apps use a wide wordmark as their icon (Netflix shows "NETFLIX"). For those we
     * draw a compact letter icon like the phone versions. Returns null when the app's own icon is fine.
     */
    Drawable iconOverride(String pkg) {
        if ("com.netflix.ninja".equals(pkg)) return letterIcon("N", 0xFF000000, 0xFFE50914);
        return null;
    }

    /** Square letter icon: bold condensed letter centred on a solid background (clipped round by the tile). */
    Drawable letterIcon(String letter, int bg, int fg) {
        int size = 192;
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        cv.drawColor(bg);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(fg);
        p.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(size * 0.78f);
        p.setTextScaleX(0.82f);       // narrower, taller letter
        android.graphics.Rect r = new android.graphics.Rect();
        p.getTextBounds(letter, 0, letter.length(), r);
        cv.drawText(letter, size / 2f, size / 2f - r.exactCenterY(), p);
        return new android.graphics.drawable.BitmapDrawable(c.getResources(), b);
    }

    Drawable moveRing(float radiusDp) { return round(0x22FFD166, radiusDp, 3.5f, GOLD); }

    private GradientDrawable oval(int fill, float strokeDp, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(fill);
        if (strokeDp > 0) d.setStroke(Math.max(1, px(strokeDp)), stroke);
        return d;
    }

    /** Round glass disc behind app icons. */
    Drawable ovalGlass() { return oval(0x2EFFFFFF, 1, 0x40FFFFFF); }

    /** Round focus ring drawn over icon discs. */
    Drawable ovalRing() {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, oval(0x00000000, 3, WHITE));
        s.addState(new int[]{}, oval(0x00000000, 1, 0x33FFFFFF));
        return s;
    }

    Drawable ovalMoveRing() { return oval(0x22FFD166, 3.5f, GOLD); }

    /** Glass row/list item that lights up when focused. */
    Drawable glassItem(float radiusDp) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_selected}, round(0x40FFD166, radiusDp, 3, GOLD));
        s.addState(new int[]{android.R.attr.state_focused}, round(0x4DFFFFFF, radiusDp, 2, WHITE));
        s.addState(new int[]{}, round(0x14FFFFFF, radiusDp, 1, 0x1FFFFFFF));
        return s;
    }

    /** Pill button: glass when idle, solid white with dark text when focused or selected. */
    TextView button(String label, boolean primary) {
        TextView b = text(label, 15, WHITE, MEDIUM);
        b.setShadowLayer(0, 0, 0, 0);
        b.setGravity(Gravity.CENTER);
        b.setPadding(px(20), px(10), px(20), px(10));
        b.setFocusable(true);
        b.setClickable(true);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, round(0xF2FFFFFF, 22, 0, 0));
        s.addState(new int[]{android.R.attr.state_selected}, round(0xFFFFD166, 22, 0, 0));
        s.addState(new int[]{}, primary ? round(0x4DFFFFFF, 22, 1, 0x80FFFFFF) : round(0x1FFFFFFF, 22, 1, 0x33FFFFFF));
        b.setBackground(s);
        b.setTextColor(new ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {android.R.attr.state_selected}, {}},
                new int[]{INK, INK, WHITE}));
        return b;
    }

    /** Soft "aurora" backdrop rendered at low resolution; scaling up keeps it smooth and cheap. */
    /**
     * Theme backdrop: a deep base gradient, soft overlapping colour "mesh" blobs, two
     * diagonal light streaks, optional starfield (Midnight), fine film grain and a vignette.
     * Rendered small (640x360) and scaled up: smooth, cheap on memory.
     */
    static Bitmap backdrop(int resolved) {
        int idx = Math.floorMod(resolved, PALETTES.length);
        int[] p = PALETTES[idx];
        int w = 640, h = 360;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);

        // 1. base: slightly lighter at the top-left, deeper at the bottom-right
        paint.setShader(new LinearGradient(0, 0, w, h, lighten(p[0], 0.10f), p[0], Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w, h, paint);

        // 2. colour mesh: large soft blobs, then smaller brighter cores
        float[][] blobs = {
            {0.10f, 0.15f, 0.60f, 0.70f}, {0.90f, 0.18f, 0.55f, 0.65f}, {0.55f, 1.05f, 0.70f, 0.70f},
            {1.05f, 0.95f, 0.45f, 0.60f}, {0.32f, 0.62f, 0.34f, 0.45f}, {0.70f, 0.45f, 0.28f, 0.35f},
        };
        for (int i = 0; i < blobs.length; i++) {
            int col = p[1 + (i % 3)];
            float x = blobs[i][0] * w, y = blobs[i][1] * h, r = blobs[i][2] * w;
            int a = (int) (blobs[i][3] * 255);
            paint.setShader(new RadialGradient(x, y, r, new int[]{(col & 0xFFFFFF) | (a << 24), (col & 0xFFFFFF) | ((a / 3) << 24), col & 0xFFFFFF},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            cv.drawCircle(x, y, r, paint);
        }

        // 3. two soft diagonal light streaks
        cv.save();
        cv.rotate(-28, w / 2f, h / 2f);
        for (float[] s : new float[][]{{0.30f, 0.10f}, {0.62f, 0.06f}}) {
            float cx = s[0] * w, half = s[1] * w;
            paint.setShader(new LinearGradient(cx - half, 0, cx + half, 0,
                    new int[]{0x00FFFFFF, 0x16FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
            cv.drawRect(cx - half, -h, cx + half, h * 2, paint);
        }
        cv.restore();
        paint.setShader(null);

        java.util.Random rnd = new java.util.Random(42 + idx);
        // 4. starfield for Midnight
        if (idx == 4) {
            for (int i = 0; i < 260; i++) {
                float sx = rnd.nextFloat() * w, sy = rnd.nextFloat() * h * 0.8f;
                float sr = rnd.nextFloat() < 0.08f ? 1.3f : 0.6f;
                paint.setColor(((int) (90 + rnd.nextInt(165)) << 24) | 0xFFFFFF);
                cv.drawCircle(sx, sy, sr, paint);
            }
        }

        // 5. fine film grain (breaks up banding on big TVs)
        float[] pts = new float[2 * 7000];
        for (int i = 0; i < pts.length; i += 2) { pts[i] = rnd.nextFloat() * w; pts[i + 1] = rnd.nextFloat() * h; }
        paint.setStrokeWidth(1f);
        paint.setColor(0x0DFFFFFF);
        cv.drawPoints(pts, 0, pts.length / 2, paint);
        for (int i = 0; i < pts.length; i += 2) { pts[i] = rnd.nextFloat() * w; pts[i + 1] = rnd.nextFloat() * h; }
        paint.setColor(0x14000000);
        cv.drawPoints(pts, 0, pts.length / 2, paint);

        // 6. vignette + darker bottom so text and icons stay readable
        paint.setShader(new RadialGradient(w / 2f, h * 0.42f, w * 0.75f,
                new int[]{0x00000000, 0x00000000, 0x80000000}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w, h, paint);
        paint.setShader(new LinearGradient(0, h * 0.55f, 0, h, 0x00000000, 0x66000000, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w, h, paint);
        return b;
    }

    private static int lighten(int c, float f) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, bl = c & 0xFF;
        r = (int) (r + (255 - r) * f); g = (int) (g + (255 - g) * f); bl = (int) (bl + (255 - bl) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}

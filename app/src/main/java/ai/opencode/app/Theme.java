package ai.opencode.app;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.TextView;

/**
 * P8 design system — P12 "graphite" edition (MONOCHROME).
 *
 * One place for the visual language: a grayscale ladder instead of the old
 * rainbow accents, hairline rims, press feedback, entrance/pulse/pop
 * animations, and a global motion switch (Settings → Interface; also
 * auto-off when the system "remove animations" accessibility setting is
 * active). Everything is framework-only — no libraries, same as the rest
 * of the app.
 */
public final class Theme {

    private Theme() {}

    // ---- palette (mirrors colors.xml; kept here for programmatic draws) --
    // P12 monochrome: luminance carries meaning, hue is gone (except ERR).
    public static final int BG        = 0xFF0A0A0A;
    public static final int SURFACE   = 0xFF151515;
    public static final int SURFACE2  = 0xFF1D1D1D;
    public static final int STROKE    = 0xFF2A2A2A;
    public static final int ACCENT    = 0xFFE8E8E8;
    public static final int ACCENT_LT = 0xFFFFFFFF;
    public static final int TXT       = 0xFFF2F2F2;
    public static final int TXT_DIM   = 0xFF8F8F8F;
    public static final int OK        = 0xFFD9D9D9;
    public static final int ERR       = 0xFFE07A7A;
    public static final int WARN      = 0xFFA6A6A6;

    /** Project-card gradient pairs — P12: graphite ladder, one shade slot
     *  per card (rotates). Depth without color; the white shine + rim keep
     *  the credit-card read. */
    public static final int[][] CARD_GRADS = {
            {0xFF3A3A3A, 0xFF161616},   // graphite
            {0xFF2E2E2E, 0xFF121212},   // slate
            {0xFF454545, 0xFF1A1A1A},   // ash
            {0xFF262626, 0xFF0F0F0F},   // charcoal
            {0xFF505050, 0xFF202020},   // smoke
            {0xFF333333, 0xFF141414},   // iron
    };

    // ---- motion ------------------------------------------------------

    /** Global motion switch: app pref AND system animator scale. */
    public static boolean motionOn(Context c) {
        if (!c.getSharedPreferences("oc", Context.MODE_PRIVATE)
                .getBoolean("motion", true)) return false;
        try {
            float s = android.provider.Settings.Global.getFloat(
                    c.getContentResolver(),
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
            if (s == 0f) return false;
        } catch (Exception ignored) {}
        return true;
    }

    public static final DecelerateInterpolator DECEL = new DecelerateInterpolator(1.6f);
    public static final OvershootInterpolator POP = new OvershootInterpolator(1.6f);
    public static final AccelerateInterpolator ACCEL = new AccelerateInterpolator(1.2f);

    /** Entrance: fade + rise. Call BEFORE the view is laid out. */
    public static void enter(View v, long delayMs) {
        if (v == null) return;
        if (!motionOn(v.getContext())) return;
        v.setAlpha(0f);
        v.setTranslationY(dp(v.getContext(), 18));
        v.animate().alpha(1f).translationY(0f)
                .setStartDelay(delayMs).setDuration(260)
                .setInterpolator(DECEL)
                .start();
    }

    /** Quick re-entrance for rows that appear in place (150 ms, no delay). */
    public static void appear(View v) {
        if (v == null) return;
        if (!motionOn(v.getContext())) return;
        v.setAlpha(0f);
        v.setTranslationY(dp(v.getContext(), 12));
        v.animate().alpha(1f).translationY(0f).setDuration(150)
                .setInterpolator(DECEL).start();
    }

    /** Pop (overshoot) — send button, chips, mode toggles. */
    public static void pop(View v) {
        if (v == null) return;
        if (!motionOn(v.getContext())) { v.setScaleX(1); v.setScaleY(1); return; }
        v.setScaleX(0.82f); v.setScaleY(0.82f);
        v.animate().scaleX(1f).scaleY(1f).setDuration(220)
                .setInterpolator(POP).start();
    }

    /** Press feedback: shrink while touched, spring back on release. */
    public static void press(final View v) {
        if (v == null) return;
        final boolean on = motionOn(v.getContext());
        v.setOnTouchListener((vv, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (on) vv.animate().scaleX(0.965f).scaleY(0.965f)
                            .setDuration(90).setInterpolator(DECEL).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (on) vv.animate().scaleX(1f).scaleY(1f)
                            .setDuration(180).setInterpolator(POP).start();
                    break;
            }
            return false; // never consume — click listeners still fire
        });
    }

    /** Repeating alpha pulse for status dots. Caller keeps the handle to cancel. */
    public static ObjectAnimator pulse(View v) {
        ObjectAnimator a = ObjectAnimator.ofFloat(v, "alpha", 1f, 0.25f);
        a.setDuration(700);
        a.setRepeatCount(ObjectAnimator.INFINITE);
        a.setRepeatMode(ObjectAnimator.REVERSE);
        a.start();
        return a;
    }

    // ---- drawables ----------------------------------------------------

    public static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density);
    }

    /** Gradient credit-card background with a diagonal shine + hairline rim. */
    public static Drawable cardBg(int accentIdx, float radiusPx) {
        int[] g = CARD_GRADS[Math.abs(accentIdx) % CARD_GRADS.length];
        GradientDrawable base = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{g[0], g[1]});
        base.setCornerRadius(radiusPx);

        GradientDrawable shine = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0x2EFFFFFF, 0x00FFFFFF, 0x00FFFFFF});
        shine.setCornerRadius(radiusPx);

        GradientDrawable rim = new GradientDrawable();
        rim.setCornerRadius(radiusPx);
        rim.setStroke(1, 0x33FFFFFF);
        return new LayerDrawable(new Drawable[]{base, shine, rim});
    }

    /** Ghost "new project" card: dashed-feel rim on translucent fill. */
    public static GradientDrawable ghostCard(Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x14FFFFFF);
        d.setCornerRadius(dp(c, 26));
        d.setStroke(dp(c, 2), 0x55FFFFFF);
        return d;
    }

    /** Rounded panel for settings rows / sheets. */
    public static GradientDrawable panel(Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(SURFACE);
        d.setCornerRadius(dp(c, 18));
        d.setStroke(1, STROKE);
        return d;
    }

    public static GradientDrawable circle(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    /** Ripple wrapper for rows/cards (framework RippleDrawable). */
    public static Drawable ripple(Context c, Drawable content) {
        try {
            return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, null);
        } catch (Exception e) {
            return content;
        }
    }

    /** Section header: uppercase, letterspaced, dim. */
    public static TextView sectionLabel(Context c, String s) {
        TextView tv = new TextView(c);
        tv.setText(s.toUpperCase());
        tv.setTextColor(TXT_DIM);
        tv.setTextSize(11);
        tv.setLetterSpacing(0.12f);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setPadding(dp(c, 4), dp(c, 18), dp(c, 4), dp(c, 8));
        return tv;
    }
}

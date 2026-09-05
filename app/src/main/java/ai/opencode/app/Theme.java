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
 * P12 design system — P27 EDITION: the single source of visual truth.
 *
 * P27 changes (the field: "some ui elements are inconsistent", "i expected
 * a dark amoled black theme and apple-like ui"):
 *
 *   • ONE SEMANTIC PALETTE, ONE ACCENT FAMILY. The old palette was
 *     graphite monochrome in Theme but indigo/violet/emerald/amber hexes
 *     lived in drawables + toolTint — ten accents fighting. Now: one calm
 *     blue accent (~#7C9CFF) for live/interactive, a subtle variant for
 *     backgrounds, danger + ok for states, and a strict text hierarchy
 *     (primary / secondary 70% / tertiary 45%).
 *
 *   • AMOLED PURE BLACK by default: base surfaces are true #000000; RAISED
 *     surfaces are distinguished by 1dp hairline borders in a very dark
 *     blue-gray (shadows are invisible on AMOLED and cost GPU time — no
 *     elevation-based depth anywhere in the flat language). Settings →
 *     "Pure black (AMOLED)" OFF switches to the softer dark surface set.
 *
 *   • TOKENS, NOT NUMBERS: spacing steps on a 4dp scale, one corner-radius
 *     set by element class, one motion set. Screens showing the same kind
 *     of thing (cards, chips, rows, headers) call the same token.
 *
 * Everything is framework-only — no libraries, same as the rest of the app.
 * The palette fields are MUTABLE and set by apply(Context) at app start and
 * after the Settings toggle (activities recreate, markdown links re-tint
 * via Markdown.setLinkColor). Colors.xml mirrors the AMOLED defaults.
 */
public final class Theme {

    private Theme() {}

    // ---- semantic palette (set by apply(); AMOLED defaults) --------------
    /** App background — true black (AMOLED). */
    public static int BG         = 0xFF000000;
    /** Raised surface (cards, sheets, composer) — near-black blue-gray. */
    public static int SURFACE    = 0xFF0B0E16;
    /** Second-level surface (chips, wells, inputs). */
    public static int SURFACE2   = 0xFF121724;
    /** The 1dp hairline that DOES the depth job shadows used to do. */
    public static int STROKE     = 0xFF1B2233;
    /** The one accent: calm blue — live/interactive only. */
    public static int ACCENT     = 0xFF7C9CFF;
    /** Brighter accent step (links, pinned states). */
    public static int ACCENT_LT  = 0xFFA5B8FF;
    /** Accent-subtle: accent-tinted background wash (bubbles, suggestions). */
    public static int ACCENT_BG  = 0xFF141A2C;
    public static int TXT        = 0xFFF4F6FB;   // primary
    public static int TXT_DIM    = 0xFFABAFBC;   // secondary ≈70%
    public static int TXT_FAINT  = 0xFF6E7280;   // tertiary ≈45%
    public static int OK         = 0xFF7FD1A7;
    public static int ERR        = 0xFFE07A7A;
    public static int WARN       = 0xFFE5C07B;
    public static int ON_ACCENT  = 0xFF0A0D18;
    // P27 tokens (no inline hex anywhere): the tool-disc family + glyph.
    public static int TINT_ACCENT = 0xFF2A3A66;   // tool disc — accent family
    public static int TINT_OK     = 0xFF27402F;   // plan/todo disc — ok family
    public static int TINT_DANGER = 0xFF4A2126;   // failed tool disc
    public static int ON_DISC     = 0xFFEDF0FA;   // glyph on a tool disc

    /** Project-card gradient pairs — very dark blue-gray steps (AMOLED:
     *  depth from hairlines, not bright fills). */
    public static final int[][] CARD_GRADS = {
            {0xFF151A28, 0xFF07080F},
            {0xFF111624, 0xFF05060C},
            {0xFF1A2030, 0xFF090B12},
            {0xFF0E1220, 0xFF04050A},
            {0xFF171C2A, 0xFF060810},
            {0xFF131826, 0xFF05060B},
    };

    // ---- tokens: spacing (4dp scale) / radii / motion ---------------------
    /** 4dp spacing scale — the ONLY horizontal/vertical rhythm in the app. */
    public static int space(Context c, int step) {   // step 0..7
        int[] dp = {0, 2, 4, 8, 12, 16, 24, 32};
        return dp(c, dp[Math.max(0, Math.min(step, dp.length - 1))]);
    }
    public static int radiusCard(Context c)   { return dp(c, 16); }
    public static int radiusSheet(Context c)  { return dp(c, 22); }
    public static int radiusRow(Context c)    { return dp(c, 12); }
    public static int radiusChip(Context c)   { return dp(c, 20); }
    public static int radiusWell(Context c)   { return dp(c, 10); }

    // ---- apply / AMOLED switch --------------------------------------------

    /** Read the theme prefs into the palette. Called from App.onCreate and
     *  after the Settings toggles (activities then recreate). */
    public static void apply(Context c) {
        boolean amoled = c.getSharedPreferences("oc", Context.MODE_PRIVATE)
                .getBoolean("amoled", true);      // default PURE BLACK
        if (amoled) {
            BG = 0xFF000000; SURFACE = 0xFF0B0E16; SURFACE2 = 0xFF121724;
            STROKE = 0xFF1B2233;
        } else {
            BG = 0xFF0A0C12; SURFACE = 0xFF141826; SURFACE2 = 0xFF1B2132;
            STROKE = 0xFF262E44;
        }
        Markdown.setLinkColor(ACCENT_LT);
        Markdown.setCodeColors(SURFACE2, TXT);
    }

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

    /** P12: springy entrance for user bubbles — overshoot scale + fade. */
    public static void springIn(View v) {
        if (v == null) return;
        if (!motionOn(v.getContext())) return;
        v.setAlpha(0f);
        v.setScaleX(0.85f);
        v.setScaleY(0.85f);
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260)
                .setInterpolator(POP).start();
    }

    /** P12: "unfold" for expand/collapse — scaleY reveal from the top
     *  edge so a tool card growing feels like unfolding paper. */
    public static void unfold(View v) {
        if (v == null) return;
        if (!motionOn(v.getContext())) return;
        v.setPivotY(0f);
        v.setScaleY(0.55f);
        v.setAlpha(0.35f);
        v.animate().scaleY(1f).alpha(1f).setDuration(210)
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

    /** THE one press treatment — shrink while touched, spring back on
     *  release. Every tappable gets this; nothing else invents feedback. */
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
        d.setColor(0x10FFFFFF);
        d.setCornerRadius(radiusCard(c));
        d.setStroke(dp(c, 2), 0x50FFFFFF);
        return d;
    }

    /** Rounded panel for settings rows / sheets — raised surface + the
     *  1dp hairline (AMOLED: hairlines do the job shadows did). */
    public static GradientDrawable panel(Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(SURFACE);
        d.setCornerRadius(radiusCard(c));
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
            return new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), content, null);
        } catch (Exception e) {
            return content;
        }
    }

    /** Inset slab for tool input/output — darker well + hairline rim. */
    public static GradientDrawable well(Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(ACCENT_BG);
        d.setCornerRadius(radiusWell(c));
        d.setStroke(1, STROKE);
        return d;
    }

    /** Small boxed badge (status pills on tool cards, chips). */
    public static GradientDrawable badge(Context c, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(SURFACE2);
        d.setCornerRadius(dp(c, 7));
        d.setStroke(1, stroke);
        return d;
    }

    /** User bubble — accent-subtle fill + hairline accent rim (the one
     *  loud element is the assistant's world, not a rainbow). */
    public static GradientDrawable userBubble(Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(ACCENT_BG);
        d.setCornerRadius(dp(c, 16));
        d.setStroke(1, 0xFF2A3552);
        return d;
    }

    /** Section header: uppercase, letterspaced, dim — WITH matching end
     *  padding (clipping audit: letterspacing adds trailing advance, so
     *  the label's last glyph used to kiss/clip the following element). */
    public static TextView sectionLabel(Context c, String s) {
        TextView tv = new TextView(c);
        tv.setText(s.toUpperCase());
        tv.setTextColor(TXT_DIM);
        tv.setTextSize(11);
        tv.setLetterSpacing(0.12f);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setPadding(dp(c, 4), dp(c, 18), dp(c, 7), dp(c, 8));
        return tv;
    }
}

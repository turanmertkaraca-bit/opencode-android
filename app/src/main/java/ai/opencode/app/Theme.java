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
import android.view.ViewGroup;
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
    // P31 tokens: the last hardcoded hues, now palette-owned.
    public static int RIM_USER   = 0xFF2A3552;   // user bubble rim
    public static int ICON_DISC  = 0x1FA5B4FF;   // settings row icon wash
    public static int DOT_IDLE   = 0x558B93A8;   // deck page dot at rest
    public static int ON_CARD    = 0xCCFFFFFF;   // text on a gradient card
    public static int RIPPLE     = 0x22FFFFFF;   // ripple mask
    public static boolean LIGHT  = false;        // paper flag (status bar icons)


    // ---------------------------------------------------- P31 palettes ----

    /** Palette ids, in menu order. "oled" is the DEFAULT (the user set
     *  it: pure black first). Fields per entry (order matters):
     *  BG SURFACE SURFACE2 STROKE ACCENT ACCENT_LT ACCENT_BG
     *  TXT TXT_DIM TXT_FAINT OK ERR WARN ON_ACCENT
     *  TINT_ACCENT TINT_OK TINT_DANGER ON_DISC RIM_USER ICON_DISC
     *  DOT_IDLE ON_CARD RIPPLE */
    public static final String[] PALETTES = {
            "oled", "midnight", "graphite", "ember", "forest", "paper"
    };

    public static String paletteName(String id) {
        if (id == null) return "";
        switch (id) {
            case "midnight":  return "Midnight blue";
            case "graphite":  return "Graphite";
            case "ember":     return "Ember";
            case "forest":    return "Forest";
            case "paper":     return "Paper (light)";
            default:          return "OLED black";
        }
    }

    // package-private: the JVM suite pins the table (P31Test)
    static final int[][] PALETTE_DATA = {
        // oled — pure black + calm blue (the P27 default, unchanged)
        {0xFF000000, 0xFF0B0E16, 0xFF121724, 0xFF1B2233, 0xFF7C9CFF, 0xFFA5B8FF,
         0xFF141A2C, 0xFFF4F6FB, 0xFFABAFBC, 0xFF6E7280, 0xFF7FD1A7, 0xFFE07A7A,
         0xFFE5C07B, 0xFF0A0D18, 0xFF2A3A66, 0xFF27402F, 0xFF4A2126, 0xFFEDF0FA,
         0xFF2A3552, 0x1FA5B4FF, 0x558B93A8, 0xCCFFFFFF, 0x22FFFFFF},
        // midnight — the old non-AMOLED surfaces + the same blue
        {0xFF0A0C12, 0xFF141826, 0xFF1B2132, 0xFF262E44, 0xFF7C9CFF, 0xFFA5B8FF,
         0xFF1B2436, 0xFFF4F6FB, 0xFFABAFBC, 0xFF6E7280, 0xFF7FD1A7, 0xFFE07A7A,
         0xFFE5C07B, 0xFF0A0D18, 0xFF2C3D66, 0xFF27402F, 0xFF4A2126, 0xFFEDF0FA,
         0xFF2E3A5A, 0x1FA5B4FF, 0x558B93A8, 0xCCFFFFFF, 0x22FFFFFF},
        // graphite — neutral gray, steel accent
        {0xFF0A0A0B, 0xFF141416, 0xFF1C1C1F, 0xFF29292E, 0xFF9FB0C3, 0xFFC4D2E2,
         0xFF1E2126, 0xFFF2F3F5, 0xFFADAFB6, 0xFF70727A, 0xFF8FCFA9, 0xFFE08282,
         0xFFE2BE7C, 0xFF101216, 0xFF2E343D, 0xFF2A3C2F, 0xFF452226, 0xFFEDF0F4,
         0xFF33363E, 0x1F9FB0B8, 0x558B93A8, 0xCCFFFFFF, 0x22FFFFFF},
        // ember — warm dark + amber
        {0xFF0D0A07, 0xFF181109, 0xFF201710, 0xFF2F2418, 0xFFE8A062, 0xFFF2BE8C,
         0xFF2A2013, 0xFFF6F1EA, 0xFFB5A998, 0xFF77695A, 0xFF9CCF9A, 0xFFE08282,
         0xFFE5C07B, 0xFF1A1208, 0xFF4A3320, 0xFF2E3E26, 0xFF4A2126, 0xFFFAF2E6,
         0xFF40301C, 0x2FA5B47C, 0x558B93A8, 0xCCFFF6EA, 0x22FFFFFF},
        // forest — deep green + green accent
        {0xFF050A07, 0xFF0C1410, 0xFF12201A, 0xFF1B2E24, 0xFF6FCF97, 0xFF94E0B4,
         0xFF13291D, 0xFFEFF7F2, 0xFFA3B3AA, 0xFF67756D, 0xFF7FD1A7, 0xFFE08282,
         0xFFE2C97C, 0xFF07130C, 0xFF1F4030, 0xFF27402F, 0xFF4A2126, 0xFFEAF6EE,
         0xFF24402F, 0x1F9FB48C, 0x558B93A8, 0xCCF0FFF5, 0x22FFFFFF},
        // paper — light: ink on warm paper (the one light option)
        {0xFFF3F1EA, 0xFFFBFAF6, 0xFFEDEBE2, 0xFFD8D4C8, 0xFF3D63D8, 0xFF2E4CB0,
         0xFFE4EAF9, 0xFF1B1D24, 0xFF565B66, 0xFF8A8F99, 0xFF2E7D53, 0xFFB3424A,
         0xFF96691F, 0xFFFFFFFF, 0xFFDCE4F8, 0xFFDDF0E2, 0xFFF6DBDD, 0xFF22242A,
         0xFFC9D2EE, 0x143D63D8, 0x33998FA0, 0xE6222630, 0x24304460},
    };

    private static final int[][][] GRAD_DATA = {
        // oled
        {{0xFF151A28, 0xFF07080F}, {0xFF111624, 0xFF05060C}, {0xFF1A2030, 0xFF090B12},
         {0xFF0E1220, 0xFF04050A}, {0xFF171C2A, 0xFF060810}, {0xFF131826, 0xFF05060B}},
        // midnight
        {{0xFF1B2136, 0xFF0B0E18}, {0xFF161B2E, 0xFF080B14}, {0xFF212845, 0xFF0C0F1C},
         {0xFF141928, 0xFF060910}, {0xFF1D2440, 0xFF090C16}, {0xFF182032, 0xFF070A12}},
        // graphite
        {{0xFF1B1B1E, 0xFF0B0B0C}, {0xFF17171A, 0xFF09090A}, {0xFF222226, 0xFF0D0D0F},
         {0xFF141416, 0xFF080809}, {0xFF1E1E22, 0xFF0B0B0D}, {0xFF19191D, 0xFF09090B}},
        // ember
        {{0xFF241A0E, 0xFF100B05}, {0xFF1E150B, 0xFF0C0804}, {0xFF2C2012, 0xFF130D06},
         {0xFF1B1209, 0xFF0A0603}, {0xFF261B10, 0xFF0F0A05}, {0xFF20160C, 0xFF0C0804}},
        // forest
        {{0xFF12241A, 0xFF060E09}, {0xFF0E1E15, 0xFF050B08}, {0xFF162C20, 0xFF081109},
         {0xFF0D1B13, 0xFF040A06}, {0xFF142819, 0xFF060F0A}, {0xFF102116, 0xFF050C08}},
        // paper
        {{0xFFFDFCF9, 0xFFEFEBE0}, {0xFFF8F5EE, 0xFFEAE6D9}, {0xFFFFFEFB, 0xFFF2EEE3},
         {0xFFF6F3EB, 0xFFE9E4D6}, {0xFFFBF9F3, 0xFFEDE9DE}, {0xFFF9F6F0, 0xFFEBE7DB}},
    };

    /** The gradient table for a palette id (index into GRAD_DATA). */
    static int[][] gradTable(String id) {
        int i = paletteIndex(id);
        return GRAD_DATA[i];
    }

    /** Index of id in PALETTES (unknown → 0 = oled). */
    public static int paletteIndex(String id) {
        if (id != null) for (int i = 0; i < PALETTES.length; i++)
            if (PALETTES[i].equals(id)) return i;
        return 0;
    }

    /** Project-card gradient pairs — palette-owned since P31 (each theme
     *  ships its own six pairs; very dark steps on dark themes, soft
     *  paper steps on light). Initialized AFTER GRAD_DATA (static order). */
    public static int[][] CARD_GRADS = gradTable("oled");

    /** The current palette id (defaults: legacy "amoled" flag → oled/midnight). */
    public static String currentId(Context c) {
        String t = c.getSharedPreferences("oc", Context.MODE_PRIVATE)
                .getString("theme", null);
        if (t == null) {
            boolean amoled = c.getSharedPreferences("oc", Context.MODE_PRIVATE)
                    .getBoolean("amoled", true);
            return amoled ? "oled" : "midnight";
        }
        return t;
    }

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
     *  after the Settings toggles (activities then recreate).
     *  P31: six palettes — the "theme" string pref is the source; the old
     *  "amoled" boolean migrates (true→oled, false→midnight) on first
     *  read and stays untouched for rollback compatibility. */
    public static void apply(Context c) {
        String id = currentId(c);
        int[] p = PALETTE_DATA[paletteIndex(id)];
        BG = p[0]; SURFACE = p[1]; SURFACE2 = p[2]; STROKE = p[3];
        ACCENT = p[4]; ACCENT_LT = p[5]; ACCENT_BG = p[6];
        TXT = p[7]; TXT_DIM = p[8]; TXT_FAINT = p[9];
        OK = p[10]; ERR = p[11]; WARN = p[12]; ON_ACCENT = p[13];
        TINT_ACCENT = p[14]; TINT_OK = p[15]; TINT_DANGER = p[16]; ON_DISC = p[17];
        RIM_USER = p[18]; ICON_DISC = p[19]; DOT_IDLE = p[20]; ON_CARD = p[21];
        RIPPLE = p[22];
        LIGHT = "paper".equals(id);
        currentIdStatic = id;
        CARD_GRADS = GRAD_DATA[paletteIndex(id)];
        Markdown.setLinkColor(ACCENT_LT);
        Markdown.setCodeColors(SURFACE2, TXT);
    }

    /** True when the active palette is the light one (status-bar icons,
     *  ripple polarity, home glow follow this). */
    public static boolean isLight() { return LIGHT; }

    // ------------------------------------------------ P31: window + skin

    /** Paint the WINDOW from the palette: decor background, status and
     *  navigation bars, light-icon flags. The XML theme can only carry
     *  the static AMOLED colors — every activity calls this once after
     *  setContentView so a palette switch is whole-screen, not
     *  "everything except the window". */
    public static void window(android.app.Activity a) {
        try {
            android.view.Window w = a.getWindow();
            w.setStatusBarColor(BG);
            w.setNavigationBarColor(BG);
            w.getDecorView().setBackgroundColor(BG);
            View d = w.getDecorView();
            int flags = d.getSystemUiVisibility();
            if (LIGHT) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            else flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            d.setSystemUiVisibility(flags);
            // P31: dialogs resolve their theme AT CREATION — applying the
            // palette's sheet style here (after setContentView is fine for
            // dialogs) skins every AlertDialog this activity will show:
            // surface, hairline, text colors, accent — no per-call-site
            // skinning needed anywhere.
            a.getTheme().applyStyle(sheetStyle(paletteIndex(currentId(a))), true);
            // and remap every static XML color in the inflated tree
            retint(w.getDecorView());
        } catch (Exception ignored) {
            // a window-level nit must never take a screen down
        }
    }

    /** R.style of the sheet presentation for a palette index. */
    static int sheetStyle(int idx) {
        switch (Math.max(0, Math.min(idx, PALETTES.length - 1))) {
            case 1: return R.style.OcSheetMidnight;
            case 2: return R.style.OcSheetGraphite;
            case 3: return R.style.OcSheetEmber;
            case 4: return R.style.OcSheetForest;
            case 5: return R.style.OcSheetPaper;
            default: return R.style.OcSheetOled;
        }
    }

    /** Skin a dialog from the palette: rounded SURFACE panel + hairline,
     *  themed nav bar. The OcSheet XML style only carries the static
     *  AMOLED colors — with six palettes, dialogs are skinned at runtime
     *  (call right after show()). */
    public static void skin(android.app.Dialog d) {
        try {
            android.view.Window w = d.getWindow();
            if (w == null) return;
            w.setBackgroundDrawable(panelDrawable(d.getContext()));
            w.setNavigationBarColor(BG);
            // custom content rows are painted at build time — give them
            // the same remap the activity tree gets
            retint(w.getDecorView());
        } catch (Exception ignored) {}
    }

    /** The rounded SURFACE panel used by skin(). */
    private static android.graphics.drawable.Drawable panelDrawable(Context c) {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setColor(SURFACE);
        g.setCornerRadius(radiusSheet(c));
        g.setStroke(dp(c, 1), STROKE);
        return g;
    }

    // ------------------------------------------------ P31: runtime retint

    /**
     * XML colors are read ONCE at build time (@color/bg, @color/surface,
     * bg_chip's solid, layout textColor attrs...) — five of the six
     * palettes cannot live inside that static file. The fix: window()
     * walks the freshly-inflated tree and REMAPS every color that equals
     * an OLED-table value to the active palette's value. Dark palettes
     * are near-identical (no-ops); Paper flips every static hue in one
     * pass. Views painted from the Java ints (cards, chips, bubbles) are
     * already correct and unaffected.
     */
    public static void retint(View root) {
        try {
            if (root == null) return;
            retintWalk(root, 0);
        } catch (Throwable ignored) {
            // a missed tint is a nit; a thrown one is a broken screen
        }
    }

    private static void retintWalk(View v, int depth) {
        if (depth > 30) return;                       // pathological trees out
        android.graphics.drawable.Drawable bg = v.getBackground();
        if (bg instanceof android.graphics.drawable.ColorDrawable) {
            android.graphics.drawable.ColorDrawable cd =
                    (android.graphics.drawable.ColorDrawable) bg;
            int mapped = remap(cd.getColor());
            if (mapped != cd.getColor()) {
                ((android.graphics.drawable.ColorDrawable) cd.mutate()).setColor(mapped);
                v.setBackground(cd);
            }
        } else if (bg instanceof android.graphics.drawable.GradientDrawable) {
            android.graphics.drawable.GradientDrawable g =
                    (android.graphics.drawable.GradientDrawable) bg;
            android.content.res.ColorStateList c = g.getColor();
            if (c != null) {
                int mapped = remap(c.getDefaultColor());
                if (mapped != c.getDefaultColor()) {
                    ((android.graphics.drawable.GradientDrawable) g.mutate()).setColor(mapped);
                    v.setBackground(g);
                }
            }
        }
        if (v instanceof android.widget.TextView) {
            android.widget.TextView tv = (android.widget.TextView) v;
            int cur = tv.getCurrentTextColor();
            int mapped = remap(cur);
            if (mapped != cur) tv.setTextColor(mapped);
            int hint = tv.getCurrentHintTextColor();
            int mappedHint = remap(hint);
            if (mappedHint != hint) tv.setHintTextColor(mappedHint);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) retintWalk(g.getChildAt(i), depth + 1);
        }
    }

    /**
     * OLED hex → active-palette hex (pure; index order = the field order
     * documented on PALETTES). Returns the input unchanged when it is not
     * an OLED table value — custom runtime colors (card gradients, washes)
     * pass through untouched.
     */
    static int remap(int color) {
        int[] oled = PALETTE_DATA[0];
        int[] cur = PALETTE_DATA[paletteIndex(currentIdStatic)];
        if (oled == cur) return color;
        for (int i = 0; i < oled.length; i++)
            if (oled[i] == color) return cur[i];
        return color;
    }

    /** The palette id retint/remap are serving (set by apply()). */
    private static volatile String currentIdStatic = "oled";

    /** A color RESOURCE read at paint time, remapped to the active
     *  palette. @color/text_primary etc. are frozen at build; views that
     *  read them through this helper (and every dialog's custom content)
     *  follow the palette instead of staying OLED-white on Paper. */
    public static int cr(Context c, int res) {
        try {
            return remap(c.getColor(res));
        } catch (Exception e) {
            return res;
        }
    }

    /** The home glow, palette-aware: the static bg_home XML is dark-tuned;
     *  the light palette gets a flat BG instead of a dark halo. */
    public static android.graphics.drawable.Drawable homeGlow(Context c) {
        if (LIGHT) {
            android.graphics.drawable.ColorDrawable cd =
                    new android.graphics.drawable.ColorDrawable(BG);
            return cd;
        }
        try {
            return c.getDrawable(R.drawable.bg_home);
        } catch (Exception e) {
            return new android.graphics.drawable.ColorDrawable(BG);
        }
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

    /** P29: THE one haptic treatment — a tick on commit-y actions (send,
     *  chip toggles, attach add/remove, compact). Deliberately independent
     *  of the motion switch: haptics are not animation, and a dead-feeling
     *  screen with animations off is worse than a tick. Contained: a
     *  device without a vibrator just returns false internally. */
    public static void haptic(View v) {
        if (v == null) return;
        try {
            v.performHapticFeedback(
                    android.view.HapticFeedbackConstants.VIRTUAL_KEY);
        } catch (Exception ignored) {}
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
            return new RippleDrawable(ColorStateList.valueOf(RIPPLE), content, null);
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
        d.setStroke(1, RIM_USER);
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

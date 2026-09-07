package ai.opencode.app;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * P34 — THE ONE PRESENTATION. Every box in the app is a Sheet now.
 *
 * The field's P34 report, verbatim: "u never stoped to look for other
 * places in the app that still use this old ui update all of tgem with
 * care and ease of use in mind … i want the ui to be just like google
 * pixel". The audit found 41 AlertDialog sites across 9 files — eleven
 * wore the P31/P33 skin, the other thirty rendered the platform's grey
 * box (the two screenshots: Credit limit, Interactive canvas). Two
 * presentation systems coexisting IS the inconsistency.
 *
 * The fix is structural, not another spot-skin: one component, owned by
 * the app, painted from the live palette tokens at build time — so a
 * sheet is palette-correct BY CONSTRUCTION on all six themes, with no
 * framework dialog theme involved anywhere:
 *
 *   • bottom-anchored, edge-to-edge panel, 28dp top corners (Pixel/M3
 *     geometry), SURFACE fill + 1dp STROKE hairline, grab handle;
 *   • large 20sp title + optional dim subtitle, content slot, then
 *     STACKED FULL-WIDTH 48dp pills — thumb-first ergonomics: the
 *     primary action is always in the same place, bottom;
 *   • slide-up in / slide-down out (window animations, 210/180 ms —
 *     inside the app's 170–230 ms motion budget; back and scrim taps
 *     animate the same way), swipe the header down to fling it away;
 *   • menu rows, inputs, note paragraphs and quick-chip rows as static
 *     builders — call sites compose, no site invents its own widgets;
 *   • every touch target ≥ 44dp, Theme.press shrink + haptic tick;
 *   • guards: dead-activity refusals, a dead flag that no-ops every
 *     method if the window could not open (the sheet can never take a
 *     screen down), single-fire dismiss, IME + nav-bar insets handled
 *     by hand (FLAG_LAYOUT_NO_LIMITS gives a full-bleed scrim; the
 *     keyboard pushes the panel up because the window itself doesn't
 *     resize under that flag).
 *
 * The migration rule for every old site: the LOGIC stays, only the
 * container changes — builders in, AlertDialog out.
 */
public final class Sheet {

    private Sheet() {}

    // ---- pill kinds -------------------------------------------------------
    /** Filled accent — the one loud button (Save, Delete forever, Explain it). */
    public static final int PRIMARY = 0;
    /** Quiet outline — secondary choices (Cancel, Keep it, Close). */
    public static final int QUIET = 1;
    /** Danger wash — destructive primaries (Delete). */
    public static final int DANGER = 2;

    private Activity act;
    private Dialog dlg;
    private LinearLayout panel;
    private LinearLayout body;
    private View scrim;
    private Runnable onDismissCb;  // caller cleanup, fires on every close path
    private boolean dead;          // window refused to open → every call no-ops
    private boolean dismissing;    // single-fire guard

    // ------------------------------------------------------------- show

    /** Create + show a sheet with a large title. Never throws: on any
     *  failure the incident is logged and this Sheet no-ops forever. */
    public static Sheet show(Activity a, String title) {
        Sheet s = new Sheet();
        s.act = a;
        try {
            if (a == null || a.isFinishing() || a.isDestroyed()) { s.dead = true; return s; }
            s.dlg = new Dialog(a);
            Window w = s.dlg.getWindow();
            if (w == null) { s.dead = true; return s; }
            s.dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setDimAmount(0f);                       // the scrim is our own view
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            // full-bleed: the scrim must dim UNDER the status bar too (the
            // platform dialog window would otherwise start below it and
            // leave a bright strip)
            w.setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED);
            boolean motion = Theme.motionOn(a);
            if (motion) w.setWindowAnimations(R.style.OcSheetWindow);

            // ---- root: scrim + panel
            FrameLayout root = new FrameLayout(a);
            s.scrim = new View(a);
            s.scrim.setBackgroundColor(Theme.surfaceScrim(0xA6));   // ~65% BG wash
            s.scrim.setOnClickListener(v -> s.dismiss());
            root.addView(s.scrim, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            s.panel = new LinearLayout(a);
            s.panel.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Theme.SURFACE);
            bg.setCornerRadii(new float[]{   // 28dp top corners — Pixel geometry
                    Theme.dp(a, 28), Theme.dp(a, 28), Theme.dp(a, 28), Theme.dp(a, 28),
                    0, 0, 0, 0});
            bg.setStroke(Theme.dp(a, 1), Theme.STROKE);
            s.panel.setBackground(bg);
            int pad = Theme.dp(a, 20);
            s.panel.setPadding(pad, Theme.dp(a, 10), pad, Theme.dp(a, 18));
            // width: edge-to-edge on phones, capped like the model sheet on
            // DeX / desktop-sized windows (the P16 rule)
            int screenW = a.getResources().getDisplayMetrics().widthPixels;
            int width = ViewGroup.LayoutParams.MATCH_PARENT;
            if (screenW >= Theme.dp(a, 720)) width = Theme.dp(a, 760);
            FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                    width, ViewGroup.LayoutParams.WRAP_CONTENT);
            plp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            root.addView(s.panel, plp);

            // grab handle
            View handle = new View(a);
            GradientDrawable hg = new GradientDrawable();
            hg.setColor(Theme.TXT_FAINT);
            hg.setCornerRadius(Theme.dp(a, 2));
            handle.setBackground(hg);
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                    Theme.dp(a, 36), Theme.dp(a, 4));
            hlp.gravity = Gravity.CENTER_HORIZONTAL;
            s.panel.addView(handle, hlp);

            // title
            TextView t = new TextView(a);
            t.setText(title);
            t.setTextSize(20);
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            t.setTextColor(Theme.TXT);
            t.setPadding(0, Theme.dp(a, 14), 0, 0);
            s.panel.addView(t, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // content column
            s.body = new LinearLayout(a);
            s.body.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.topMargin = Theme.dp(a, 10);
            s.panel.addView(s.body, blp);

            // insets: nav bar padding (and the keyboard, which does NOT
            // resize this window under FLAG_LAYOUT_NO_LIMITS — push the
            // panel up by hand)
            final int baseBottom = Theme.dp(a, 18);
            final int padSide = pad;
            root.setOnApplyWindowInsetsListener((v, ins) -> {
                try {
                    int bot = ins.getSystemWindowInsetBottom();
                    int screenH = v.getResources().getDisplayMetrics().heightPixels;
                    boolean ime = bot > screenH / 4;
                    v.setPadding(0, 0, 0, ime ? bot : 0);
                    s.panel.setPadding(padSide, Theme.dp(a, 10), padSide,
                            ime ? baseBottom : baseBottom + bot);
                } catch (Throwable ignored) {}
                return ins.consumeSystemWindowInsets();
            });
            root.setFitsSystemWindows(false);

            // swipe the header down to fling the sheet away
            s.attachSwipe(handle);

            s.dlg.setContentView(root);
            s.dlg.setCancelable(true);
            // back button: cancel() dismisses the dialog itself — route it
            // through the single-fire bookkeeping so onDismiss still fires
            s.dlg.setOnCancelListener(d -> {
                s.dismissing = true;
                try { if (s.onDismissCb != null) s.onDismissCb.run(); }
                catch (Throwable ignored) {}
            });
            s.dlg.setOnShowListener(d -> s.panel.setTranslationY(0));
            s.dlg.show();
        } catch (Throwable e) {
            // the sheet must never take a screen down (the HomeActivity
            // insurance rule, now systemic)
            s.dead = true;
            try { Trail.record(a, "sheet", e); } catch (Throwable ignored) {}
        }
        return s;
    }

    // ------------------------------------------------------------ builders

    /** Dim subtitle line under the title (inserted BEFORE the body —
     *  the body column already sits in the panel when the builder runs). */
    public Sheet sub(String s) {
        if (dead || dismissing) return this;
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextSize(13);
        t.setTextColor(Theme.TXT_DIM);
        t.setLineSpacing(Theme.dp(act, 1), 1f);
        t.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Theme.dp(act, 4);
        // panel children: [0]=handle [1]=title [2]=body — the sub joins
        // between title and body, wherever the builder is in its chain
        panel.addView(t, Math.min(2, panel.getChildCount()), lp);
        return this;
    }

    /** A paragraph in the body (the old setMessage). */
    public Sheet msg(String s) {
        return add(note(act, s));
    }

    /** Any view into the body. */
    public Sheet add(View v) {
        if (dead || dismissing || v == null) return this;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Theme.dp(act, 10);
        body.addView(v, lp);
        return this;
    }

    /** Content that scrolls, capped at a fraction of the screen (list
     *  sheets: commands, sessions, logs). AT_MOST measure: short content
     *  wraps, tall content scrolls at the cap. */
    public Sheet scroll(View v, float maxScreenRatio) {
        if (dead || dismissing) return this;
        MaxScrollView sv = new MaxScrollView(act);
        sv.setVerticalScrollBarEnabled(false);
        int screenH = act.getResources().getDisplayMetrics().heightPixels;
        sv.max = (int) (screenH * maxScreenRatio);
        sv.addView(v, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return add(sv);
    }

    /** ScrollView with a measure-time height cap (the framework one has
     *  no max-height API). */
    private static final class MaxScrollView extends ScrollView {
        int max = -1;
        MaxScrollView(android.content.Context c) { super(c); }
        @Override protected void onMeasure(int w, int h) {
            if (max > 0) super.onMeasure(w,
                    View.MeasureSpec.makeMeasureSpec(max, View.MeasureSpec.AT_MOST));
            else super.onMeasure(w, h);
        }
    }

    /** A fixed-height content well (the model sheet's 88% list). */
    public Sheet tall(View v, float screenRatio) {
        if (dead || dismissing) return this;
        int screenH = act.getResources().getDisplayMetrics().heightPixels;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (screenH * screenRatio));
        lp.topMargin = Theme.dp(act, 10);
        body.addView(v, lp);
        return this;
    }

    /** A stacked full-width pill. Returns this for chaining. */
    public Sheet pill(String label, int kind, Runnable action) {
        if (dead || dismissing) return this;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(act, 48));
        lp.topMargin = Theme.dp(act, 8);
        panel.addView(pillView(label, kind, action), lp);
        return this;
    }

    /** A pill that STAYS OPEN and hands the sheet to the action — for
     *  flows whose old dialog survived validation errors (the canvas ask
     *  with an empty topic, find-first/find-next). The action may dismiss
     *  via the Sheet handle when it succeeds. */
    public Sheet pillKeep(String label, int kind, java.util.function.Consumer<Sheet> action) {
        if (dead || dismissing) return this;
        TextView b = pillView(label, kind, null);
        b.setOnClickListener(v -> {
            Theme.haptic(b);
            if (action != null) action.accept(this);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(act, 48));
        lp.topMargin = Theme.dp(act, 8);
        panel.addView(b, lp);
        return this;
    }

    /** Fixed-height body content (a commands ListView, a sessions list). */
    public Sheet addFixed(View v, int dpHeight) {
        if (dead || dismissing || v == null) return this;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(act, dpHeight));
        lp.topMargin = Theme.dp(act, 10);
        body.addView(v, lp);
        return this;
    }

    /** Caller cleanup — fires exactly once on ANY close path (pill, scrim,
     *  back, swipe). The model sheet's in-place-refresh bookkeeping hangs
     *  here instead of a raw OnDismissListener. */
    public Sheet onDismiss(Runnable r) {
        if (dead) return this;
        this.onDismissCb = r;
        return this;
    }

    /** Two half-width pills side by side (paired choices). */
    public Sheet pillRow(String l0, int k0, Runnable r0, String l1, int k1, Runnable r1) {
        if (dead || dismissing) return this;
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp0 = new LinearLayout.LayoutParams(
                0, Theme.dp(act, 48), 1f);
        row.addView(pillView(l0, k0, r0), lp0);
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(
                0, Theme.dp(act, 48), 1f);
        lp1.leftMargin = Theme.dp(act, 10);
        row.addView(pillView(l1, k1, r1), lp1);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = Theme.dp(act, 8);
        panel.addView(row, wlp);
        return this;
    }

    /** (host == null) — the model sheet's in-place refresh needs a live
     *  handle to the pill (old sheetPill semantics). */
    public TextView pillView(String label, int kind, Runnable action) {
        TextView b = new TextView(act);
        b.setText(label);
        b.setTextSize(13);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        int hp = Theme.dp(act, 16);
        b.setPadding(hp, 0, hp, 0);
        switch (kind) {
            case PRIMARY:
                b.setTextColor(Theme.ON_ACCENT);
                b.setBackground(Theme.allowPill(act));
                break;
            case DANGER:
                b.setTextColor(Theme.ERR);
                b.setBackground(Theme.denyPill(act));
                break;
            default:
                b.setTextColor(Theme.TXT);
                b.setBackground(Theme.outlinePill(act));
        }
        Theme.press(b);
        b.setOnClickListener(v -> {
            Theme.haptic(b);
            dismiss();                      // every close path → onDismissCb
            if (action != null) action.run();
        });
        return b;
    }

    /** The vertical 12dp breathing room before an action block. */
    public Sheet gap() {
        if (dead || dismissing) return this;
        View g = new View(act);
        panel.addView(g, new LinearLayout.LayoutParams(1, Theme.dp(act, 4)));
        return this;
    }

    /** Menu rows written straight into the body (commands, pickers). */
    public Sheet row(String glyph, String label, String subTxt, int color, Runnable r) {
        if (dead || dismissing) return this;
        add(menuRow(act, glyph, label, subTxt, color, r));
        return this;
    }

    public void dismiss() {
        if (dead || dismissing) return;
        dismissing = true;
        try {
            if (dlg != null && dlg.isShowing()) dlg.dismiss();
        } catch (Throwable ignored) {}
        try {
            if (onDismissCb != null) onDismissCb.run();
        } catch (Throwable ignored) {}
    }

    public boolean showing() {
        return !dead && !dismissing && dlg != null && dlg.isShowing();
    }

    /** Raise the keyboard for the given input once the window is up. */
    public void focus(EditText in) {
        if (dead || in == null) return;
        dlg.getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        in.requestFocus();
        in.postDelayed(() -> {
            try {
                InputMethodManager im = (InputMethodManager)
                        act.getSystemService(Activity.INPUT_METHOD_SERVICE);
                if (im != null) im.showSoftInput(in, InputMethodManager.SHOW_IMPLICIT);
            } catch (Throwable ignored) {}
        }, 120);
    }

    // ------------------------------------------------------------- helpers

    /** Swipe-down-to-dismiss on the header zone. Simple drag: follow the
     *  finger 1:1 while pulling down, fling past 120dp or a fast release. */
    private void attachSwipe(View header) {
        final float[] down = new float[]{0f, 0f};
        final float[] startT = new float[]{0f};
        header.setOnTouchListener((v, ev) -> {
            if (!Theme.motionOn(act)) return false;
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    down[0] = ev.getRawY();
                    startT[0] = panel.getTranslationY();
                    return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    float dy = ev.getRawY() - down[0];
                    if (dy > 0) panel.setTranslationY(startT[0] + dy);
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    float dy2 = ev.getRawY() - down[0];
                    if (dy2 > Theme.dp(act, 120)) dismiss();
                    else panel.animate().translationY(0).setDuration(180)
                            .setInterpolator(Theme.DECEL).start();
                    return true;
            }
            return false;
        });
    }

    // ------------------------------------------------- static view builders

    /** A dim paragraph (sheet body copy). */
    public static TextView note(Activity a, String s) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(14);
        t.setTextColor(Theme.TXT);
        t.setLineSpacing(Theme.dp(a, 2), 1f);
        return t;
    }

    /** A quiet helper line (the sheetSub semantics from the deck). */
    public static TextView hint(Activity a, String s) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(11);
        t.setTextColor(Theme.TXT_DIM);
        t.setLineSpacing(Theme.dp(a, 1), 1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Theme.dp(a, 8);
        t.setLayoutParams(lp);
        return t;
    }

    /** THE input well: SURFACE fill, hairline, no platform underline —
     *  the same well every screen already trusts (Theme.codeWell). */
    public static EditText input(Activity a, String hint, String initial,
                                 boolean singleLine) {
        EditText in = new EditText(a);
        in.setHint(hint);
        in.setTextSize(15);
        in.setTextColor(Theme.TXT);
        in.setHintTextColor(Theme.TXT_FAINT);
        in.setSingleLine(singleLine);
        if (!singleLine) in.setMinLines(1);
        in.setBackground(Theme.codeWell(a));
        int p = Theme.dp(a, 13);
        in.setPadding(p, p, p, p);
        if (initial != null) {
            in.setText(initial);
            in.setSelection(initial.length());
        }
        return in;
    }

    /** One menu row: glyph column + label/sub column, 56dp target,
     *  ripple + haptic — the deck's P33 row, now the system-wide row. */
    public static View menuRow(Activity a, String glyph, String label,
                               String subTxt, int color, Runnable r) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Theme.dp(a, 56));
        row.setBackground(Theme.ripple(a, null));
        Theme.press(row);

        TextView g = new TextView(a);
        g.setText(glyph);
        g.setTypeface(Typeface.MONOSPACE);
        g.setTextSize(16);
        g.setTextColor(color);
        g.setGravity(Gravity.CENTER);
        g.setMinimumWidth(Theme.dp(a, 40));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        glp.leftMargin = Theme.dp(a, 4);
        row.addView(g, glp);

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Theme.dp(a, 10), Theme.dp(a, 10), Theme.dp(a, 12), Theme.dp(a, 10));
        TextView t1 = new TextView(a);
        t1.setText(label);
        t1.setTextSize(15);
        t1.setTextColor(color);
        t1.setTypeface(Typeface.DEFAULT_BOLD);
        t1.setSingleLine(true);
        t1.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(t1);
        if (subTxt != null) {
            TextView t2 = new TextView(a);
            t2.setText(subTxt);
            t2.setTextSize(11);
            t2.setTextColor(Theme.TXT_DIM);
            t2.setSingleLine(true);
            t2.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(t2);
        }
        row.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (r != null) row.setOnClickListener(v -> {
            Theme.haptic(row);
            r.run();
        });
        return row;
    }

    /** Quick-pick chip row (credit-limit presets). Fires with the value. */
    public interface ChipPick { void picked(double value); }

    public static View chipRow(Activity a, double[] values,
                               java.util.Set<Double> empties, ChipPick pick) {
        LinearLayout wrap = new LinearLayout(a);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = null;
        for (int i = 0; i < values.length; i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(a);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) rlp.topMargin = Theme.dp(a, 8);
                wrap.addView(row, rlp);
            }
            final double val = values[i];
            String label = empties != null && empties.contains(val)
                    ? "no limit" : CreditLimit.fmt(val);
            TextView chip = new TextView(a);
            chip.setText(label);
            chip.setTextSize(13);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
            chip.setTextColor(Theme.ACCENT_LT);
            chip.setGravity(Gravity.CENTER);
            chip.setBackground(Theme.suggestPill(a));
            int cp = Theme.dp(a, 12);
            chip.setPadding(cp, Theme.dp(a, 9), cp, Theme.dp(a, 9));
            Theme.press(chip);
            chip.setOnClickListener(v -> {
                Theme.haptic(chip);
                pick.picked(val);
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i % 3 > 0) clp.leftMargin = Theme.dp(a, 8);
            row.addView(chip, clp);
        }
        return wrap;
    }
}

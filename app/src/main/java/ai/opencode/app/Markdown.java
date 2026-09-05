package ai.opencode.app;

import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.TextView;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Zero-dependency markdown → Spannable renderer, scoped to what opencode
 * actually emits in chat: fenced code blocks, inline code, bold, italic,
 * headers, bullets. Everything else passes through as plain text.
 *
 * Deliberately NOT a full CommonMark implementation — P2 skeleton quality,
 * fast enough to re-run on every streaming update for typical bubble sizes.
 *
 * P27 phase 4 — TAPPABLE FILE MENTIONS. render(src, resolver) additionally
 * links file paths the assistant mentions: backticked paths and bare
 * relative/absolute path tokens become accent-colored underlined spans —
 * but ONLY when the resolver says the file actually exists (the app passes
 * a serving-dir existence check, so non-existent mentions stay plain
 * text). FENCED code blocks are never linked (they are code, inert);
 * INLINE code spans ARE linkable — that is how models usually name files.
 *
 * P28 — THE FIELD FIX for "the blue links do nothing": a ClickableSpan
 * only ever fires through a MOVEMENT METHOD, and the transcript rows are
 * selectable TextViews with none attached (P27 shipped the rendering —
 * accent + underline were perfect — but the tap itself fell through to
 * the row and died). enableSpanTaps() routes taps by hit-testing the
 * span array at the touch point: it needs no movement method, so text
 * selection, long-press copy and scroll drags all keep their native
 * handling, and only a genuine tap (down → up within touch slop) over a
 * span is consumed.
 */
public final class Markdown {

    private Markdown() {}

    /** The app-side mention hooks: existence check + tap-through. */
    public interface MentionResolver {
        /** Absolute path of an EXISTING file for this candidate, else null. */
        String resolve(String candidate);
        /** The user tapped a linked mention (pre-resolved absolute path). */
        void open(String absPath);
    }

    private static final Pattern INLINE = Pattern.compile(
            "(\\*\\*[^*\\n]+\\*\\*" +      // **bold**
            "|__[^_\\n]+__" +              // __bold__
            "|\\*[^*\\n]+\\*" +            // *italic*
            "|`[^`\\n]+`)"                 // `code`
    );

    // P27: fed by Theme.apply() — the inline-code well uses the same
    // accent-subtle surface the tool cards use (single source).
    private static volatile int CODE_BG = 0xFF121724;
    private static volatile int CODE_FG = 0xFFF4F6FB;

    public static CharSequence render(String src) {
        return render(src, null);
    }

    public static CharSequence render(String src, MentionResolver mr) {
        if (src == null || src.isEmpty()) return "";
        if (src.length() > 30000) return src; // fast path for huge outputs

        SpannableStringBuilder b = new SpannableStringBuilder();
        String[] lines = src.split("\n", -1);
        boolean first = true;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            // fenced code block — mentions never apply inside
            if (line.trim().startsWith("```")) {
                if (!first) b.append("\n");
                first = false;
                StringBuilder code = new StringBuilder();
                i++;
                while (i < lines.length && !lines[i].trim().startsWith("```")) {
                    code.append(lines[i]).append('\n');
                    i++;
                }
                appendCodeBlock(b, code.toString());
                continue;
            }

            // headers
            int h = headerLevel(line);
            if (h > 0) {
                if (!first) b.append("\n");
                first = false;
                String txt = line.substring(h).trim();
                int s = b.length();
                b.append(txt);
                b.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), s, b.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                b.setSpan(new RelativeSizeSpan(h == 1 ? 1.25f : 1.12f), s, b.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                b.append("\n");
                continue;
            }

            // bullets
            String t = line;
            if (t.matches("^\\s*[-*]\\s+.*")) {
                t = t.replaceFirst("^(\\s*)[-*]\\s+", "$1• ");
            }

            if (!first) b.append("\n");
            first = false;
            appendInline(b, t, mr);
        }
        return b;
    }

    private static int headerLevel(String line) {
        int n = 0;
        while (n < line.length() && n < 6 && line.charAt(n) == '#') n++;
        if (n > 0 && n < line.length() && line.charAt(n) == ' ') return n;
        return 0;
    }

    private static void appendCodeBlock(SpannableStringBuilder b, String code) {
        int s = b.length();
        b.append(code);
        int e = b.length();
        b.setSpan(new TypefaceSpan("monospace"), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.setSpan(new BackgroundColorSpan(CODE_BG), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.setSpan(new ForegroundColorSpan(CODE_FG), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static void appendInline(SpannableStringBuilder b, String line,
                                     MentionResolver mr) {
        // mention hits for THIS line, existence-resolved up front (one
        // filesystem stat per candidate — a line has a handful at most)
        List<Mentions.Hit> hits = null;
        if (mr != null && line.length() <= 2000) {
            try {
                hits = Mentions.extract(line);
                for (int i = hits.size() - 1; i >= 0; i--) {
                    Mentions.Hit h = hits.get(i);
                    String abs;
                    try { abs = mr.resolve(h.path); } catch (Exception e) { abs = null; }
                    if (abs == null) hits.remove(i);
                    else h.path = abs;   // reuse the holder for the abs path
                }
                if (hits.isEmpty()) hits = null;
            } catch (Exception e) {
                hits = null;             // a mention scan must never break a row
            }
        }

        Matcher m = INLINE.matcher(line);
        int pos = 0;
        while (m.find()) {
            // plain text before this token — mentions can live here
            if (m.start() > pos) {
                emitPlain(b, line, pos, m.start(), hits, mr);
            }
            String tok = m.group();
            int s = b.length();
            if (tok.startsWith("`")) {
                int contentFrom = m.start() + 1, contentTo = m.end() - 1;
                b.append(tok, 1, tok.length() - 1);
                b.setSpan(new TypefaceSpan("monospace"), s, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                b.setSpan(new BackgroundColorSpan(CODE_BG), s, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                // inline code spans ARE linkable (phase 4 rule): link when
                // a resolved hit covers exactly this token's content range
                if (hits != null) {
                    for (Mentions.Hit h : hits) {
                        if (h.start == contentFrom && h.end == contentTo) {
                            linkMention(b, s, b.length(), h.path, mr);
                            break;
                        }
                    }
                }
            } else if (tok.startsWith("**") || tok.startsWith("__")) {
                b.append(tok, 2, tok.length() - 2);
                b.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), s, b.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                b.append(tok, 1, tok.length() - 1);
                b.setSpan(new StyleSpan(android.graphics.Typeface.ITALIC), s, b.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            pos = m.end();
        }
        if (pos < line.length()) {
            emitPlain(b, line, pos, line.length(), hits, mr);
        }
    }

    /** Append line[from,to) to b, linking any mention hit fully inside. */
    private static void emitPlain(SpannableStringBuilder b, String line,
                                  int from, int to, List<Mentions.Hit> hits,
                                  MentionResolver mr) {
        if (hits == null || hits.isEmpty()) {
            b.append(line, from, to);
            return;
        }
        int cursor = from;
        for (Mentions.Hit h : hits) {
            if (h.start < cursor || h.end > to) continue;   // not in this run
            if (h.start > cursor) b.append(line, cursor, h.start);
            int s = b.length();
            b.append(line, h.start, h.end);
            linkMention(b, s, b.length(), h.path, mr);
            cursor = h.end;
        }
        if (cursor < to) b.append(line, cursor, to);
    }

    /**
     * P28: make ClickableSpans in {@code tv} tappable WITHOUT a movement
     * method. Why not LinkMovementMethod: it makes the view consume touches
     * and fights the selection editor on selectable rows (and the rows must
     * stay selectable — copy-a-response is a shipped feature). Instead a
     * touch listener hit-tests the span array on ACTION_UP, and only when
     * the gesture was a TAP (total travel within 2× touch slop — a scroll
     * drag is never mistaken for a tap, a swipe never fires a link):
     *
     *   • DOWN records the origin and returns false — the view/parent keep
     *     the gesture;
     *   • UP past the slop → false (a drag: scrolling proceeds untouched);
     *   • UP within slop over a ClickableSpan → span.onClick fires, event
     *     consumed;
     *   • otherwise false (selection handles, long-press copy: untouched).
     *
     * Framework-side by necessity (Layout/MotionEvent), but the pure shape
     * — which offsets a point maps to — is pinned by the Robolectric test
     * dispatching a real tap on a rendered mention.
     */
    public static void enableSpanTaps(final TextView tv) {
        final int slop = ViewConfiguration.get(tv.getContext())
                .getScaledTouchSlop();
        final float[] down = new float[2];
        tv.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = ev.getX();
                    down[1] = ev.getY();
                    return false;
                case MotionEvent.ACTION_UP: {
                    float dx = ev.getX() - down[0];
                    float dy = ev.getY() - down[1];
                    if (dx * dx + dy * dy > (float) slop * slop * 4) {
                        return false;               // a drag, not a tap
                    }
                    return fireSpanAt(tv, ev.getX(), ev.getY());
                }
                default:
                    return false;
            }
        });
    }

    /** Hit-test one point against the ClickableSpans of a laid-out
     *  TextView; fires the innermost span and reports the event consumed.
     *  Never throws — a broken layout must not break the row. */
    private static boolean fireSpanAt(TextView tv, float ex, float ey) {
        try {
            CharSequence cs = tv.getText();
            if (!(cs instanceof Spanned)) return false;
            Layout L = tv.getLayout();
            if (L == null) return false;            // not laid out yet
            int x = (int) (ex - tv.getTotalPaddingLeft() + tv.getScrollX());
            int y = (int) (ey - tv.getTotalPaddingTop() + tv.getScrollY());
            if (x < 0 || y < 0) return false;
            int line = L.getLineForVertical(y);
            if (line < 0 || line >= L.getLineCount()) return false;
            if (x < L.getLineLeft(line) - slopPad()
                    || x > L.getLineRight(line) + slopPad()) {
                return false;                       // beside the text block
            }
            int off = L.getOffsetForHorizontal(line, x);
            ClickableSpan[] spans = spanNear((Spanned) cs, L, off);
            if (spans.length == 0) return false;
            spans[spans.length - 1].onClick(tv);
            return true;
        } catch (Throwable t) {
            return false;                           // a tap must never crash
        }
    }

    /**
     * The link at/around one resolved offset. Offsets are approximate
     * under a finger: glyph-boundary rounding can land one PAST the span,
     * and equal-advance runs put several offsets on one visual point. So:
     * exact → −1 → +1 → walk the equal-x run (sameX(i) = offset i sits on
     * the same visual point as {@code off}). Pure core, JVM-pinnable.
     */
    static ClickableSpan[] spanNear(Spanned sp, int off,
                                    java.util.function.IntPredicate sameX) {
        ClickableSpan[] spans = sp.getSpans(off, off, ClickableSpan.class);
        if (spans.length == 0 && off > 0) {
            spans = sp.getSpans(off - 1, off - 1, ClickableSpan.class);
        }
        if (spans.length == 0 && off < sp.length()) {
            spans = sp.getSpans(off + 1, off + 1, ClickableSpan.class);
        }
        if (spans.length == 0 && sameX != null) {
            for (int i = off - 1; i >= 0 && spans.length == 0
                    && sameX.test(i); i--) {
                spans = sp.getSpans(i, i, ClickableSpan.class);
            }
            for (int i = off + 1; i < sp.length() && spans.length == 0
                    && sameX.test(i); i++) {
                spans = sp.getSpans(i, i, ClickableSpan.class);
            }
        }
        return spans;
    }

    /** The view-side wrapper: equal-x = same primary horizontal as off. */
    static ClickableSpan[] spanNear(Spanned sp, Layout L, int off) {
        if (L == null) return spanNear(sp, off, null);
        final float hx = L.getPrimaryHorizontal(off);
        return spanNear(sp, off, i -> L.getPrimaryHorizontal(i) == hx);
    }

    private static int slopPad() { return 24; }   // px forgiveness at line ends

    /** True when the char sequence carries at least one ClickableSpan —
     *  lets the row builder skip the touch listener on plain rows. */
    public static boolean hasLinks(CharSequence cs) {
        return cs instanceof Spanned
                && ((Spanned) cs).getSpans(0, cs.length(),
                       ClickableSpan.class).length > 0;
    }

    /** Apply the mention look + tap behavior over a builder range. */
    private static void linkMention(SpannableStringBuilder b, int s, int e,
                                    final String abs, final MentionResolver mr) {
        if (mr == null || abs == null || e <= s) return;
        b.setSpan(new ClickableSpan() {
            @Override public void onClick(View widget) {
                try { mr.open(abs); } catch (Exception ignored) {}
            }
            @Override public void updateDrawState(TextPaint ds) {
                // color comes from the app's accent (set below as a plain
                // span so this class stays palette-free); no underline here
            }
        }, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.setSpan(new ForegroundColorSpan(ACCENT_LINK), s, e,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.setSpan(new UnderlineSpan(), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** P27: the mention link color — the calm blue accent (Theme.ACCENT
     *  mirrored here to keep Markdown framework-only; single source note:
     *  Theme sets this at class-init via setLinkColor). */
    private static volatile int ACCENT_LINK = 0xFF7C9CFF;

    /** Theme calls this once per AMOLED/dark switch so mention links and
     *  inline-code colors always render in the active palette. */
    public static void setLinkColor(int color) {
        ACCENT_LINK = color;
    }

    public static void setCodeColors(int bg, int fg) {
        CODE_BG = bg;
        CODE_FG = fg;
    }
}

package ai.opencode.app;

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
import android.view.View;

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
 * Text selection outside a span is untouched (selectable TextViews fire
 * ClickableSpan taps without breaking long-press selection).
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

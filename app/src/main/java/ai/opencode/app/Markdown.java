package ai.opencode.app;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Zero-dependency markdown → Spannable renderer, scoped to what opencode
 * actually emits in chat: fenced code blocks, inline code, bold, italic,
 * headers, bullets. Everything else passes through as plain text.
 *
 * Deliberately NOT a full CommonMark implementation — P2 skeleton quality,
 * fast enough to re-run on every streaming update for typical bubble sizes.
 */
public final class Markdown {

    private Markdown() {}

    private static final Pattern INLINE = Pattern.compile(
            "(\\*\\*[^*\\n]+\\*\\*" +      // **bold**
            "|__[^_\\n]+__" +              // __bold__
            "|\\*[^*\\n]+\\*" +            // *italic*
            "|`[^`\\n]+`)"                 // `code`
    );

    private static final int CODE_BG = 0xFF2D2F31;
    private static final int CODE_FG = 0xFFE3E3E3;

    public static CharSequence render(String src) {
        if (src == null || src.isEmpty()) return "";
        if (src.length() > 30000) return src; // fast path for huge outputs

        SpannableStringBuilder b = new SpannableStringBuilder();
        String[] lines = src.split("\n", -1);
        boolean first = true;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            // fenced code block
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
            appendInline(b, t);
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

    private static void appendInline(SpannableStringBuilder b, String line) {
        Matcher m = INLINE.matcher(line);
        int pos = 0;
        while (m.find()) {
            if (m.start() > pos) b.append(line, pos, m.start());
            String tok = m.group();
            int s = b.length();
            if (tok.startsWith("`")) {
                b.append(tok, 1, tok.length() - 1);
                b.setSpan(new TypefaceSpan("monospace"), s, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                b.setSpan(new BackgroundColorSpan(CODE_BG), s, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
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
        if (pos < line.length()) b.append(line, pos, line.length());
    }
}

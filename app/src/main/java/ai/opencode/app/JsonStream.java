package ai.opencode.app;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P51 — the bounded streaming JSON reader. The crash this class exists
 * for: RunHub's replay did {@code Json.parse(entire session store)} —
 * ONE String (UTF-16, 2 bytes/char) plus ONE fully materialized parse
 * tree of EVERY message the sandbox ever stored. A session that stopped
 * compacting (the P42 context cap raised) grew past the 256 MB Java
 * heap and the app died on the main thread — and because the opencode
 * server is our child process, the sandbox died with every crash, which
 * read as "won't even start" right before the crash loop.
 *
 * THE CURE: never hold the whole document. This reader walks a
 * {@link Reader} and produces one value at a time, with HARD caps so a
 * pathological payload degrades instead of exploding:
 *
 *   - strings longer than {@link #MAX_STRING_CHARS} are truncated at
 *     the token level (the rest of the token is consumed — never
 *     buffered — and a one-line marker records the true length);
 *   - containers hold at most {@link #MAX_ITEMS} entries; extra entries
 *     are consumed and skipped so the parser stays in sync;
 *   - nesting deeper than {@link #MAX_DEPTH} is refused cleanly;
 *   - {@link #skip()} consumes exactly one value while building nothing
 *     — skipping a 40 MB string costs zero allocation.
 *
 * The decode shape is identical to {@link Json#parse}: Map / List /
 * String / Double / Boolean / null (numbers decode to Double exactly
 * like android's JsonReader, so the two paths are interchangeable), so
 * every downstream consumer keeps working unchanged. Pure Java (no
 * android.*): the JVM suite runs it and the device runs the same bytes.
 */
public final class JsonStream {

    /** Longest string token kept, in chars (128k chars ≈ 256 KB as
     *  UTF-16 — a full rendering-worthy bubble; the model's own context
     *  keeps the untruncated text server-side either way). */
    public static final int MAX_STRING_CHARS = 131_072;

    /** Entries kept per object/array; beyond this entries are consumed
     *  and skipped (the stream stays aligned for the NEXT value). */
    public static final int MAX_ITEMS = 4096;

    /** Container nesting cap — a malformed bomb must not eat the stack. */
    public static final int MAX_DEPTH = 512;

    /** Marker appended to a truncated string, with the true extra size. */
    public static String truncMark(long extraChars) {
        return "…[+" + extraChars + " chars truncated]";
    }

    // ------------------------------------------------------------------ io

    private final Reader r;
    private final char[] buf = new char[8192];
    private int blen = 0, bpos = 0;
    private int depth = 0;
    private boolean arrFirst = true;
    private boolean arrDone = true;

    /** Stream ONE JSON document from {@code r} element by element. */
    public JsonStream(Reader r) { this.r = r; }

    /** Parse ONE value with the caps applied. */
    public static Object read(Reader r) throws IOException {
        return new JsonStream(r).value();
    }

    /** Skip exactly ONE value, building nothing. */
    public static void skip(Reader r) throws IOException {
        new JsonStream(r).skipValue();
    }

    /** Instance variant — skips the next value on THIS stream, sharing
     *  its buffer state (the correct call after {@link #arrayStart()}). */
    public void skipNext() throws IOException {
        skipValue();
    }

    // ------------------------------------------------- streaming arrays

    /** Consume the '[' opening a top-level array. False when the
     *  document is not an array. */
    public boolean arrayStart() throws IOException {
        ws();
        int c = get();
        if (c != '[') { back(c); return false; }
        arrFirst = true;
        arrDone = false;
        ws();
        int n = get();
        if (n == ']') { arrDone = true; return true; }   // empty array
        back(n);
        return true;
    }

    /** True while the array opened by {@link #arrayStart()} still has an
     *  element; consumes separators as it advances. */
    public boolean arrayHasNext() throws IOException {
        if (arrDone) return false;
        if (arrFirst) { arrFirst = false; return true; }
        ws();
        int sep = get();
        if (sep == ',') return true;
        if (sep == ']') { arrDone = true; return false; }
        throw new IOException("bad array separator "
                + (sep < 0 ? "EOF" : String.valueOf((char) sep)));
    }

    // ------------------------------------------------------------ tokenizer

    private int get() throws IOException {
        if (bpos >= blen) {
            blen = r.read(buf, 0, buf.length);
            bpos = 0;
            if (blen < 0) return -1;
        }
        return buf[bpos++];
    }

    private void ws() throws IOException {
        while (true) {
            int c = get();
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') continue;
            back(c);
            return;
        }
    }

    private void back(int c) { if (c >= 0) bpos--; }

    private void expect(char c) throws IOException {
        ws();
        int got = get();
        if (got != c) throw new IOException("expected '" + c + "' got '"
                + (got < 0 ? "EOF" : (char) got) + "'");
    }

    // -------------------------------------------------------------- values

    /** Parse the next value (caps applied). The element reader for the
     *  streaming-array API. */
    public Object value() throws IOException {
        if (++depth > MAX_DEPTH) throw new IOException("json too deep");
        try {
            ws();
            int c = get();
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (c == 't') { literal("true");  return Boolean.TRUE; }
            if (c == 'f') { literal("false"); return Boolean.FALSE; }
            if (c == 'n') { literal("null");  return null; }
            if (c == '-' || (c >= '0' && c <= '9')) { back(c); return number(); }
            throw new IOException("unexpected char '"
                    + (c < 0 ? "EOF" : String.valueOf((char) c)) + "'");
        } finally {
            depth--;
        }
    }

    private void literal(String word) throws IOException {
        for (int i = 1; i < word.length(); i++) {
            int c = get();
            if (c != word.charAt(i)) throw new IOException("bad literal");
        }
    }

    private Object number() throws IOException {
        StringBuilder b = new StringBuilder(24);
        while (true) {
            int c = get();
            if ((c >= '0' && c <= '9') || c == '-' || c == '+'
                    || c == '.' || c == 'e' || c == 'E') {
                b.append((char) c);
            } else {
                back(c);
                break;
            }
        }
        String s = b.toString();
        try { return Double.parseDouble(s); }
        catch (NumberFormatException e) {
            throw new IOException("bad number '" + s + "'");
        }
    }

    /** String token with the hard cap: chars past the cap are consumed
     *  and counted, never stored. */
    private String string() throws IOException {
        StringBuilder b = new StringBuilder(64);
        long total = 0;
        while (true) {
            int c = get();
            if (c < 0) throw new IOException("EOF in string");
            if (c == '"') break;
            if (c == '\\') {
                int e = get();
                switch (e) {
                    case '"': case '\\': case '/':
                        total += append(b, (char) e); break;
                    case 'b': total += append(b, '\b'); break;
                    case 'f': total += append(b, '\f'); break;
                    case 'n': total += append(b, '\n'); break;
                    case 'r': total += append(b, '\r'); break;
                    case 't': total += append(b, '\t'); break;
                    case 'u':
                        int cp = 0;
                        for (int i = 0; i < 4; i++) {
                            int h = get();
                            int d = Character.digit((char) h, 16);
                            if (d < 0) throw new IOException("bad \\u escape");
                            cp = (cp << 4) | d;
                        }
                        total += append(b, (char) cp);
                        break;
                    default: throw new IOException("bad escape \\" + (char) e);
                }
            } else {
                total += append(b, (char) c);
            }
        }
        // the marker must tell the truth: total counts EVERY char of the
        // token (kept or not), b holds only what fit under the cap
        if (total > b.length()) b.append(truncMark(total - b.length()));
        return b.toString();
    }

    /** Append when under the cap; always consumed — returns 1 so the
     *  caller can count the token's true size either way. */
    private int append(StringBuilder b, char c) {
        if (b.length() < MAX_STRING_CHARS) b.append(c);
        return 1;
    }

    private Map<String, Object> object() throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        ws();
        int c = get();
        if (c == '}') return m;
        back(c);
        while (true) {
            ws();
            int q = get();
            if (q != '"') throw new IOException("bad object key");
            String k = string();
            expect(':');
            if (m.size() < MAX_ITEMS) m.put(k, value());
            else skipValue();                 // keep the stream in sync
            ws();
            int sep = get();
            if (sep == ',') continue;
            if (sep == '}') return m;
            throw new IOException("bad object separator");
        }
    }

    private List<Object> array() throws IOException {
        List<Object> l = new ArrayList<>();
        ws();
        int c = get();
        if (c == ']') return l;
        back(c);
        while (true) {
            if (l.size() < MAX_ITEMS) l.add(value());
            else skipValue();
            ws();
            int sep = get();
            if (sep == ',') continue;
            if (sep == ']') return l;
            throw new IOException("bad array separator");
        }
    }

    // ---------------------------------------------------------------- skip

    /** Consume one value building nothing. */
    private void skipValue() throws IOException {
        if (++depth > MAX_DEPTH) throw new IOException("json too deep (skip)");
        try {
            ws();
            int c = get();
            if (c == '{') skipContainer('}', true);
            else if (c == '[') skipContainer(']', false);
            else if (c == '"') skipString();
            else if (c == 't') literal("true");
            else if (c == 'f') literal("false");
            else if (c == 'n') literal("null");
            else if (c == '-' || (c >= '0' && c <= '9')) { back(c); number(); }
            else throw new IOException("unexpected char in skip");
        } finally {
            depth--;
        }
    }

    private void skipContainer(char close, boolean obj) throws IOException {
        ws();
        int c = get();
        if (c == close) return;
        back(c);
        while (true) {
            if (obj) {                       // key string, then the value
                ws();
                int q = get();
                if (q != '"') throw new IOException("bad object key (skip)");
                skipString();
                expect(':');
            }
            skipValue();
            ws();
            int sep = get();
            if (sep == ',') continue;
            if (sep == close) return;
            throw new IOException("bad container separator (skip)");
        }
    }

    private void skipString() throws IOException {
        while (true) {
            int c = get();
            if (c < 0) throw new IOException("EOF in string (skip)");
            if (c == '\\') { get(); continue; }   // escaped char: burn it
            if (c == '"') return;
        }
    }
}

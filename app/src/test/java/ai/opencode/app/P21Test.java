package ai.opencode.app;

import org.junit.Test;
import static org.junit.Assert.*;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P21 "the stable one" regression suite. Every constant and parser rule
 * here was verified against GROUND TRUTH, not memory:
 *  - ApplicationExitInfo reason values: javap -constants on the API-34
 *    android.jar (the field killer finally gets NAMED on-device).
 *  - The /session/{id}/message contract: captured from a REAL opencode
 *    v1.18.25 server (rig run: opencode/nemotron-3-ultra-free completed a
 *    live turn) and replayed through the exact settle/replay logic that
 *    reconcileAfterPause uses. p21-real-messages.json IS that capture.
 */
public class P21Test {

    // --------------------------------------- exit-reason names (SDK-pinned)

    @Test
    public void exitReasons_matchTheAndroidJarValues() {
        // pinned via: javap -constants -cp android.jar android.app.ApplicationExitInfo
        assertEquals("unknown", Resilience.exitReasonName(0));
        assertEquals("exited by itself", Resilience.exitReasonName(1));
        assertTrue(Resilience.exitReasonName(2).contains("signal"));
        assertTrue(Resilience.exitReasonName(3).contains("LOW MEMORY"));
        assertTrue(Resilience.exitReasonName(4).contains("Java"));
        assertTrue(Resilience.exitReasonName(5).contains("NATIVE"));
        assertTrue(Resilience.exitReasonName(6).contains("ANR"));
        assertTrue(Resilience.exitReasonName(14).contains("freezer"));
        assertEquals("other", Resilience.exitReasonName(13));
        assertTrue(Resilience.exitReasonName(99).startsWith("unknown reason"));
    }

    @Test
    public void exitLine_isGreppableAndCarriesTheSignal() {
        long ts = 1788516690608L;
        String lowMem = Resilience.formatExitLine(ts, 3, 0, null);
        assertTrue(lowMem.contains("LOW MEMORY"));
        assertFalse(lowMem.contains("signal"));   // only signal-class reasons
        String nativeKill = Resilience.formatExitLine(ts, 5, 9, "backtrace:\n pc 0000");
        assertTrue(nativeKill.contains("(signal 9)"));
        assertTrue(nativeKill.contains("NATIVE crash"));
        assertFalse(nativeKill.contains("\n"));   // detail flattened to one line
        assertEquals("time unknown · LOW MEMORY — the system killed it to free RAM",
                Resilience.formatExitLine(0, 3, 0, null));
        // long descriptions truncate (diagnostics TextView stays one-line-ish)
        String big = Resilience.formatExitLine(ts, 13, 0,
                "x".repeat(300));
        assertTrue(big.length() < 200);
        assertTrue(big.endsWith("…"));
    }

    // ------------------------------------------ settle rule on REAL payload

    /** Minimal android-free JSON → Map/List parser (test scope only — the
     *  app parses with android.util.Json; the tests must run on the JVM). */
    private static Object parseJson(String s) {
        return new Parser(s).value();
    }

    private static final class Parser {
        private final String s; private int i;
        Parser(String s) { this.s = s; }
        Object value() {
            ws();
            char c = s.charAt(i);
            switch (c) {
                case '{': return obj();
                case '[': return arr();
                case '"': return str();
                case 't': i += 4; return Boolean.TRUE;
                case 'f': i += 5; return Boolean.FALSE;
                case 'n': i += 4; return null;
                default: return num();
            }
        }
        private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        private Map<String, Object> obj() {
            Map<String, Object> m = new LinkedHashMap<>(); i++;
            ws();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws(); String k = str(); ws();
                expect(':'); Object v = value();
                m.put(k, v); ws();
                char d = s.charAt(i); i++;
                if (d == '}') return m;
            }
        }
        private List<Object> arr() {
            List<Object> l = new ArrayList<>(); i++;
            ws();
            if (s.charAt(i) == ']') { i++; return l; }
            while (true) {
                l.add(value()); ws();
                char d = s.charAt(i); i++;
                if (d == ']') return l;
            }
        }
        private String str() {
            StringBuilder b = new StringBuilder(); i++;
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n': b.append('\n'); break;
                        case 't': b.append('\t'); break;
                        case 'r': b.append('\r'); break;
                        case 'b': case 'f': break;
                        case 'u':
                            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4; break;
                        default: b.append(e);
                    }
                } else b.append(c);
            }
        }
        private Object num() {
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String t = s.substring(st, i);
            return t.contains(".") || t.contains("e") || t.contains("E")
                    ? (Object) Double.parseDouble(t) : (Object) Long.parseLong(t);
        }
        private void expect(char c) {
            ws();
            if (s.charAt(i) != c) throw new IllegalStateException("expected " + c);
            i++;
        }
    }

    private List<Object> realMessages() throws Exception {
        try (InputStream in = P21Test.class
                .getResourceAsStream("/p21-real-messages.json")) {
            assertNotNull("fixture missing from test resources", in);
            String body = read(in);
            Object o = parseJson(body);
            assertTrue("fixture must be a JSON array", o instanceof List);
            return (List<Object>) o;
        }
    }

    private static String read(InputStream in) throws Exception {
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) b.append(line).append('\n');
        }
        return b.toString();
    }

    @Test
    public void realPayload_settlesTheChat() throws Exception {
        List<Object> msgs = realMessages();
        // rig: a completed free-model turn — the LAST message is the
        // assistant's, and it carries time.completed
        assertTrue("a finished run must settle the chat on return",
                Resilience.lastAssistantDoneFrom(msgs));
    }

    @Test
    public void realPayload_everyPartHasAStableKey() throws Exception {
        List<Object> msgs = realMessages();
        assertEquals("if any renderable part lacked an id, the resume replay "
                        + "would have to skip it (possible missed edit) — the "
                        + "contract is that the real server ids every part",
                0, Resilience.partsWithoutId(msgs));
    }

    @Test
    public void realPayload_lastAssistantCarriedReasoningAndText() throws Exception {
        List<Object> msgs = realMessages();
        Map<?, ?> last = (Map<?, ?>) msgs.get(msgs.size() - 1);
        Map<?, ?> info = (Map<?, ?>) last.get("info");
        assertEquals("assistant", info.get("role"));
        List<?> parts = (List<?>) last.get("parts");
        boolean sawReasoning = false, sawText = false;
        for (Object p : parts) {
            String t = String.valueOf(((Map<?, ?>) p).get("type"));
            if ("reasoning".equals(t)) sawReasoning = true;
            if ("text".equals(t)) sawText = true;
        }
        assertTrue("the thinking ticker needs reasoning parts", sawReasoning);
        assertTrue("the reply needs a text part", sawText);
    }

    @Test
    public void settleRule_edgeCases() {
        // no messages / garbage → never settle
        assertFalse(Resilience.lastAssistantDoneFrom(null));
        assertFalse(Resilience.lastAssistantDoneFrom(new ArrayList<>()));

        // user message last (assistant still pending) → never settle
        List<Object> userLast = new ArrayList<>();
        Map<String, Object> u = new LinkedHashMap<>();
        Map<String, Object> ui = new LinkedHashMap<>();
        ui.put("role", "user");
        Map<String, Object> ut = new LinkedHashMap<>();
        ut.put("created", 1L); ut.put("completed", 2L);
        ui.put("time", ut);
        u.put("info", ui);
        userLast.add(u);
        assertFalse(Resilience.lastAssistantDoneFrom(userLast));

        // assistant last but the run is STILL going (no time at all,
        // or time without completed) → never settle
        Map<String, Object> a1 = new LinkedHashMap<>();
        Map<String, Object> ai1 = new LinkedHashMap<>();
        ai1.put("role", "assistant");
        a1.put("info", ai1);
        userLast.add(a1);
        assertFalse(Resilience.lastAssistantDoneFrom(userLast));

        Map<String, Object> a2time = new LinkedHashMap<>();
        a2time.put("created", 5L);
        Map<String, Object> ai2 = new LinkedHashMap<>();
        ai2.put("role", "assistant");
        ai2.put("time", a2time);
        Map<String, Object> a2 = new LinkedHashMap<>();
        a2.put("info", ai2);
        userLast.set(1, a2);
        assertFalse(Resilience.lastAssistantDoneFrom(userLast));

        // completed lands → settle (the exact moment the replay waits for)
        a2time.put("completed", 9L);
        assertTrue(Resilience.lastAssistantDoneFrom(userLast));

        // synthetic assistant message → never settle (matches the P20
        // loop, which skips synthetic entries before the settle check)
        Map<String, Object> a3 = new LinkedHashMap<>();
        Map<String, Object> ai3 = new LinkedHashMap<>();
        ai3.put("role", "assistant");
        ai3.put("synthetic", Boolean.TRUE);
        Map<String, Object> t3 = new LinkedHashMap<>();
        t3.put("completed", 9L);
        ai3.put("time", t3);
        a3.put("info", ai3);
        userLast.set(1, a3);
        assertFalse(Resilience.lastAssistantDoneFrom(userLast));
    }
}

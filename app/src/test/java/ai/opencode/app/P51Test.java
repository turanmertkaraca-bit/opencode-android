package ai.opencode.app;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P51 — the no-more-OOM release, pinned where each piece lives.
 *
 * FIELD REPORT DRIVING THIS RELEASE: OutOfMemoryError on the main thread
 * inside RunHub.notifySpend, right after the user raised the context
 * window cap — "the app won't even start the sandbox, then it started
 * straight up crashing". The notifySpend loop was innocent: it was the
 * allocation that found a 256 MB heap already exhausted by the replay
 * path, which parsed the ENTIRE session store
 * (GET /session/{id}/message → one String → one Json tree) on every
 * bind. With compaction effectively off (the raised cap), that store
 * grew past the heap; the process died; the sandbox — our child — died
 * with it, reading as "sandbox won't start" right before the crash loop.
 *
 * All pinned here:
 *   - JsonStream: parity with Json.parse across the shapes the server
 *     emits (escapes, unicode, numbers, booleans, null, nesting), the
 *     string cap (token consumed, honest marker, stream stays aligned),
 *     skip() consuming exactly one value, the streaming-array API,
 *   - StoreWalk: accounting sees EVERY message (in order, incl. the
 *     info-wrapper and flat shapes, parts on item or info), the render
 *     ring reproduces the old last-N window, eviction counts as hidden,
 *     abort() stops cleanly,
 *   - poison parity: the streamed Msg list decides EXACTLY like
 *     CompactionPoison.parse on the same payload,
 *   - Resilience.lastAssistantDoneFromInfo == lastAssistantDoneFrom on
 *     the final element (the settle rule survived the rewrite),
 *   - RunHub.capText: the row-text wall (head kept, honest marker),
 *   - ContextPolicy.sanitizeConfig: junk cap shapes removed, numbers
 *     clamped into [MIN, MAX], sane values untouched,
 *   - the version moved to 0.51.0-p51.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class P51Test {

    // ------------------------------------------------------------ JsonStream

    private static void assertParity(String json) throws IOException {
        Object streamed = JsonStream.read(new StringReader(json));
        Object reference = Json.parse(json);
        assertEquals("parity for: " + json, reference, streamed);
    }

    @Test public void jsonStream_parity_scalars() throws IOException {
        assertParity("null");
        assertParity("true");
        assertParity("false");
        assertParity("0");
        assertParity("-1");
        assertParity("3.14");
        assertParity("1e3");
        assertParity("9007199254740993");          // big long, kept as long
        assertParity("\"plain\"");
        assertParity("  [ ] ");
        assertParity("{}");
    }

    @Test public void jsonStream_parity_escapesAndUnicode() throws IOException {
        assertParity("\"quote\\\" back\\\\ slash\\/ newline\\n tab\\t\"");
        assertParity("\"\\u00e9\\u4e2d\\ud83d\\ude00\"");   // é 中 😀
        assertParity("[\"a\\nb\", \"\\u0000-ish \\u001f\"]");
    }

    @Test public void jsonStream_parity_serverShapes() throws IOException {
        String store = "[{\"info\":{\"id\":\"m1\",\"role\":\"user\","
                + "\"time\":{\"created\":1}},\"parts\":[{\"type\":\"text\","
                + "\"text\":\"hi\"}]},{\"id\":\"m2\",\"role\":\"assistant\","
                + "\"parts\":[{\"type\":\"reasoning\",\"text\":\"hmm\"},"
                + "{\"type\":\"tool\",\"state\":{\"status\":\"done\"}}]}]";
        assertParity(store);
    }

    @Test public void jsonStream_parity_deepNesting() throws IOException {
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 100; i++) deep.append('[');
        for (int i = 0; i < 100; i++) deep.append(']');
        assertParity(deep.toString());
    }

    @Test public void jsonStream_longStringIsCappedWithHonestMarker()
            throws IOException {
        int big = JsonStream.MAX_STRING_CHARS + 50_000;
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (int i = 0; i < big; i++) sb.append('x');
        sb.append('"');
        String out = (String) JsonStream.read(new StringReader(sb.toString()));
        assertNotNull(out);
        assertTrue("capped to the wall",
                out.length() < JsonStream.MAX_STRING_CHARS + 100);
        // the marker tells the exact truth
        String marker = out.substring(out.indexOf("…[+"));
        assertEquals("…[+" + (big - JsonStream.MAX_STRING_CHARS)
                + " chars truncated]", marker);
    }

    @Test public void jsonStream_skipConsumesExactlyOneValue()
            throws IOException {
        String rest = "[{\"a\":[1,2,{\"b\":null}]}, \"tail\", [true, false], 42]";
        StringReader r = new StringReader(rest);
        JsonStream js = new JsonStream(r);
        assertTrue(js.arrayStart());               // '[' consumed
        assertTrue(js.arrayHasNext());
        js.skipNext();                             // the big object (same stream)
        assertTrue(js.arrayHasNext());
        assertEquals("tail", js.value());          // stream stayed aligned
        assertTrue(js.arrayHasNext());
        assertEquals(Arrays.asList(true, false), js.value());
        assertTrue(js.arrayHasNext());
        assertEquals(Json.parse("42"), js.value());
        assertFalse(js.arrayHasNext());            // ']' consumed
    }

    @Test public void jsonStream_streamingArrayWalksEachElement()
            throws IOException {
        String doc = "[{\"n\":1},{\"n\":2},{\"n\":3}]";
        JsonStream js = new JsonStream(new StringReader(doc));
        assertTrue(js.arrayStart());
        long sum = 0;
        int seen = 0;
        while (js.arrayHasNext()) {
            Map<?, ?> m = (Map<?, ?>) js.value();
            sum += ((Number) m.get("n")).longValue();
            seen++;
        }
        assertEquals(3, seen);
        assertEquals(6L, sum);
        assertFalse(js.arrayHasNext());            // done stays done
    }

    @Test public void jsonStream_garbageIsRejectedCleanly() {
        try {
            JsonStream.read(new StringReader("{\"a\":}"));
            throw new IllegalStateException("must throw");
        } catch (IOException expected) { /* good */ }
        try {
            JsonStream.read(new StringReader("[1, 2"));
            throw new IllegalStateException("must throw");
        } catch (IOException expected) { /* good */ }
    }

    // -------------------------------------------------------------- StoreWalk

    private static String msgJson(String id, String role, boolean synthetic,
                                  String text, boolean wrapped,
                                  boolean partsOnInfo) {
        StringBuilder b = new StringBuilder();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("id", id);
        info.put("role", role);
        if (synthetic) info.put("synthetic", true);
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("type", "text");
        part.put("text", text);
        List<Object> parts = new ArrayList<>();
        parts.add(part);
        if (wrapped) {
            Map<String, Object> item = new LinkedHashMap<>();
            if (partsOnInfo) {
                info.put("parts", parts);
                item.put("info", info);
            } else {
                item.put("info", info);
                item.put("parts", parts);
            }
            return Json.write(item);
        }
        info.put("parts", parts);
        return Json.write(info);                   // flat shape
    }

    @Test public void storeWalk_accountingSeesEveryMessage_renderRingIsLastN()
            throws IOException {
        StringBuilder b = new StringBuilder("[");
        final int N = 40;
        for (int i = 0; i < N; i++) {
            if (i > 0) b.append(',');
            // rotate through the shapes: wrapped/parts-on-item,
            // wrapped/parts-on-info, flat
            b.append(msgJson("m" + i, i % 2 == 0 ? "user" : "assistant",
                    i == 13, "body " + i, i % 3 != 0, i % 3 == 2));
        }
        b.append(']');

        final List<String> accounting = new ArrayList<>();
        final List<String> rendered = new ArrayList<>();
        final int[] hiddenBox = {0};
        Reader in = new BufferedReader(new StringReader(b.toString()));
        StoreWalk.Stats st = StoreWalk.walk(in, 5, StoreWalk.KEEP_BYTES_DEFAULT,
                new StoreWalk.Sink() {
                    @Override public boolean abort() { return false; }
                    @Override public void message(Map<String, Object> item,
                                                  int index) {
                        Map<String, Object> info = Json.map(item, "info");
                        if (info == null) info = item;
                        accounting.add(Json.str(info, "id"));
                    }
                    @Override public void renderEnd(
                            List<Map<String, Object>> items,
                            StoreWalk.Stats stats) {
                        hiddenBox[0] = stats.hidden;
                        for (Map<String, Object> item : items) {
                            Map<String, Object> info = Json.map(item, "info");
                            if (info == null) info = item;
                            rendered.add(Json.str(info, "id"));
                        }
                    }
                });

        assertEquals(N, st.messages);
        assertEquals(N, accounting.size());
        assertEquals("m0", accounting.get(0));
        assertEquals("m" + (N - 1), accounting.get(N - 1));
        // the ring = the LAST 5 messages, oldest first — the old window
        assertEquals(Arrays.asList("m35", "m36", "m37", "m38", "m39"),
                rendered);
        assertEquals(N - 5, hiddenBox[0]);
        assertEquals(N - 5, st.hidden);
    }

    @Test public void storeWalk_byteBudgetEvictsMoreThanCount()
            throws IOException {
        StringBuilder b = new StringBuilder("[");
        // 4 messages; each body 1000 chars → count cap 10 never bites,
        // byte cap 1500 keeps only the last message
        for (int i = 0; i < 4; i++) {
            if (i > 0) b.append(',');
            StringBuilder body = new StringBuilder("x");
            for (int j = 0; j < 999; j++) body.append('y');
            b.append(msgJson("m" + i, "user", false, body.toString(), true, false));
        }
        b.append(']');
        final List<String> rendered = new ArrayList<>();
        Reader in = new BufferedReader(new StringReader(b.toString()));
        StoreWalk.walk(in, 10, 1500, new StoreWalk.Sink() {
            @Override public boolean abort() { return false; }
            @Override public void message(Map<String, Object> item, int index) { }
            @Override public void renderEnd(List<Map<String, Object>> items,
                                            StoreWalk.Stats stats) {
                for (Map<String, Object> item : items) {
                    Map<String, Object> info = Json.map(item, "info");
                    rendered.add(Json.str(info, "id"));
                }
            }
        });
        assertEquals(Arrays.asList("m3"), rendered);
    }

    @Test public void storeWalk_abortStopsTheWalk() throws IOException {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < 10; i++) {
            if (i > 0) b.append(',');
            b.append(msgJson("m" + i, "user", false, "body", true, false));
        }
        b.append(']');
        final int[] seen = {0};
        Reader in = new BufferedReader(new StringReader(b.toString()));
        StoreWalk.Stats st = StoreWalk.walk(in, 5, StoreWalk.KEEP_BYTES_DEFAULT,
                new StoreWalk.Sink() {
                    @Override public boolean abort() { return seen[0] >= 3; }
                    @Override public void message(Map<String, Object> item,
                                                  int index) { seen[0]++; }
                    @Override public void renderEnd(List<Map<String, Object>> i,
                                                    StoreWalk.Stats s) { }
                });
        assertEquals(3, st.messages);
        assertEquals(3, seen[0]);
    }

    @Test public void storeWalk_rejectsNonArray() throws IOException {
        try {
            StoreWalk.walk(new StringReader("{\"nope\":1}"), 5, 1000, null);
            throw new IllegalStateException("must throw");
        } catch (IOException expected) { /* good */ }
    }

    // ------------------------------------------------------------ poison parity

    @Test public void poison_streamedCollectionMatchesWholeParse()
            throws IOException {
        // a poisoned thread: real turns, then an EMPTY compaction root
        StringBuilder b = new StringBuilder("[");
        b.append(msgJson("u1", "user", false, "hello", true, false));
        b.append(',').append(msgJson("a1", "assistant", false, "hi there",
                true, false));
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> rinfo = new LinkedHashMap<>();
        rinfo.put("id", "root1");
        rinfo.put("role", "assistant");
        rinfo.put("agent", "compaction");
        rinfo.put("parentID", "u1");
        root.put("info", rinfo);
        root.put("parts", new ArrayList<>());       // EMPTY summary text
        b.append(',').append(Json.write(root));
        b.append(']');

        // whole-parse reference
        List<CompactionPoison.Msg> whole =
                CompactionPoison.parse(Json.arr(Json.parse(b.toString())));
        // streamed collection (the reconcile path)
        List<CompactionPoison.Msg> streamed = new ArrayList<>();
        StoreWalk.walk(new StringReader(b.toString()), 5, 1_000_000,
                new StoreWalk.Sink() {
                    @Override public boolean abort() { return false; }
                    @Override public void message(Map<String, Object> item,
                                                  int index) {
                        CompactionPoison.collectMsg(item, streamed);
                    }
                    @Override public void renderEnd(List<Map<String, Object>> i,
                                                    StoreWalk.Stats s) { }
                });

        assertEquals(whole.size(), streamed.size());
        assertTrue(CompactionPoison.poisoned(whole));
        assertTrue(CompactionPoison.poisoned(streamed));
        assertEquals(CompactionPoison.victims(whole), CompactionPoison.victims(streamed));
    }

    // ------------------------------------------------------------ settle rule

    @Test public void settle_perElementMatchesWholeArrayRule() {
        Map<String, Object> time = new LinkedHashMap<>();
        time.put("completed", 5L);
        Map<String, Object> assistant = new LinkedHashMap<>();
        assistant.put("role", "assistant");
        assistant.put("time", time);

        Map<String, Object> synthetic = new LinkedHashMap<>();
        synthetic.put("role", "assistant");
        synthetic.put("synthetic", true);
        synthetic.put("time", time);

        List<Object> arr = Arrays.asList(
                new LinkedHashMap<String, Object>(),   // junk element
                assistant, synthetic);
        assertFalse(Resilience.lastAssistantDoneFrom(arr));
        assertFalse(Resilience.lastAssistantDoneFromInfo(synthetic));

        List<Object> arr2 = Arrays.asList(assistant);
        assertTrue(Resilience.lastAssistantDoneFrom(arr2));
        assertTrue(Resilience.lastAssistantDoneFromInfo(assistant));
        assertFalse(Resilience.lastAssistantDoneFromInfo(null));
    }

    // ---------------------------------------------------------------- capText

    @Test public void rowTextWall_keepsHead_andTellsTheTruth() {
        String small = "hello";
        assertEquals(small, RunHub.capText(small));
        assertNull(RunHub.capText(null));

        StringBuilder big = new StringBuilder();
        for (int i = 0; i < RunHub.TEXT_CAP + 10; i++) big.append('z');
        String capped = RunHub.capText(big.toString());
        assertTrue(capped.length() < RunHub.TEXT_CAP + 100);
        assertTrue(capped.startsWith(new String(new char[]{'z'})));
        assertTrue(capped.contains("chars truncated"));
        assertTrue(capped.contains("still in the model's context"));
    }

    @Test public void mergeText_appliesTheWall() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < RunHub.TEXT_CAP + 500; i++) big.append('q');
        String merged = RunHub.mergeText("", big.toString());
        assertTrue(merged.length() < RunHub.TEXT_CAP + 100);
        // growth path caps too
        String grown = RunHub.mergeText("abc", big.toString());
        assertTrue(grown.length() < RunHub.TEXT_CAP + 100);
    }

    // -------------------------------------------------------- config sanitize

    @Test public void sanitizeConfig_clampsJunk_andLeavesSaneValues() {
        Map<String, Object> limit1 = new LinkedHashMap<>();
        limit1.put("context", 10L);                        // below MIN
        Map<String, Object> m1 = new LinkedHashMap<>();
        m1.put("limit", limit1);
        Map<String, Object> limit2 = new LinkedHashMap<>();
        limit2.put("context", 9_000_000_000L);             // above MAX
        Map<String, Object> m2 = new LinkedHashMap<>();
        m2.put("limit", limit2);
        Map<String, Object> limit3 = new LinkedHashMap<>();
        limit3.put("context", "huge");                     // junk type
        Map<String, Object> m3 = new LinkedHashMap<>();
        m3.put("limit", limit3);
        Map<String, Object> limit4 = new LinkedHashMap<>();
        limit4.put("context", 200_000L);                   // sane
        Map<String, Object> m4 = new LinkedHashMap<>();
        m4.put("limit", limit4);

        Map<String, Object> models = new LinkedHashMap<>();
        models.put("lo", m1);
        models.put("hi", m2);
        models.put("junk", m3);
        models.put("sane", m4);
        Map<String, Object> pdef = new LinkedHashMap<>();
        pdef.put("models", models);
        Map<String, Object> provs = new LinkedHashMap<>();
        provs.put("zen", pdef);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("provider", provs);

        assertTrue(ContextPolicy.sanitizeConfig(root));
        assertEquals(ContextPolicy.MIN,
                ((Number) limit1.get("context")).longValue());
        assertEquals(ContextPolicy.MAX,
                ((Number) limit2.get("context")).longValue());
        assertFalse("junk type removed", limit3.containsKey("context"));
        assertEquals(200_000L,
                ((Number) limit4.get("context")).longValue());
        // idempotent second pass
        assertFalse(ContextPolicy.sanitizeConfig(root));

        // no provider block → no-op, no throw
        assertFalse(ContextPolicy.sanitizeConfig(new LinkedHashMap<>()));
        assertFalse(ContextPolicy.sanitizeConfig(null));
    }

    // ---------------------------------------------------------------- version

    @Test public void version_bumped() {
        assertEquals("0.51.0-p51", SettingsActivity.VERSION_TAG);
    }
}

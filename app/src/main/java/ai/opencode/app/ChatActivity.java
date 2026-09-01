package ai.opencode.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * P2 chat screen, P5 upgrades.
 *
 * P2 baseline:
 *  - SSE via ServerService (permissions caught even when chat is closed).
 *  - Permission approval → POST /permission/{requestID}/reply.
 *  - Markdown assistant bubbles, per-session transcript cache.
 * P5 additions (endpoints verified against the shipped v1.18.25 binary):
 *  - Model picker: GET /config/providers → chip in header → messages POST
 *    "model":{"providerID","modelID"} with automatic no-model retry on
 *    400/422 (server default if the override is rejected).
 *  - Stop button: while the agent is busy the send button becomes ■ →
 *    POST /session/{id}/abort; busy state tracked via session.status.
 *  - Long-press any bubble to copy its raw text.
 *  - Animated working-dots status + empty state.
 */
public class ChatActivity extends Activity implements ServerService.Evt, ServerService.EventListener {

    private static final class Msg {
        final String key;
        final String role; // user | assistant | system
        final StringBuilder text = new StringBuilder();
        Msg(String key, String role) { this.key = key; this.role = role; }
    }

    /** Per-session transcript cache (process-lifetime, LRU capped). */
    private static final class Transcript {
        final List<Msg> msgs = new ArrayList<>();
        final Map<String, Msg> byKey = new HashMap<>();
        int sysCounter = 0;
        boolean historyLoaded = false;
    }

    private static final int CACHE_MAX = 6;
    private static final Map<String, Transcript> CACHE =
            new java.util.LinkedHashMap<String, Transcript>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Transcript> e) {
                    return size() > CACHE_MAX;
                }
            };

    private Transcript tr;
    private final Adapter adapter = new Adapter();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService ex = Executors.newSingleThreadExecutor();

    private ListView list;
    private EditText input;
    private TextView tvStatus;
    private TextView tvModel;
    private View viewEmpty;

    private volatile String sessionId;
    private volatile boolean forceNew;
    private volatile boolean busy;   // agent working on THIS session
    private int spinFrame;
    private AlertDialog permDialog;

    /** Working-dots animation for the status line while the agent runs. */
    private final Runnable spin = new Runnable() {
        @Override public void run() {
            if (!busy || tvStatus == null) return;
            String[] f = {"·", "··", "···"};
            tvStatus.setText("agent ● working " + f[spinFrame++ % 3]);
            ui.postDelayed(this, 450);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_chat);

        list = findViewById(R.id.list);
        input = findViewById(R.id.input);
        tvStatus = findViewById(R.id.tvStatus);
        tvModel = findViewById(R.id.tvModel);
        viewEmpty = findViewById(R.id.tvEmptyChat);

        // CRITICAL ORDERING: the transcript must be bound BEFORE setAdapter —
        // ListView calls getCount() synchronously, and in v0.2.0 tr was still
        // null there → NPE → crash on every chat open ("Open chat" AND
        // Sessions → "New"). This is the v0.2.1 hotfix.
        String preset = getIntent().getStringExtra("session_id");
        boolean hasPreset = preset != null && !preset.isEmpty();
        synchronized (CACHE) {
            tr = hasPreset ? CACHE.get(preset) : null;
            if (tr == null) tr = new Transcript();
        }
        sessionId = hasPreset ? preset : null;

        list.setAdapter(adapter);
        list.setStackFromBottom(true);
        list.setTranscriptMode(ListView.TRANSCRIPT_MODE_NORMAL);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnNew).setOnClickListener(v -> {
            sessionId = null;
            forceNew = true;
            tr = new Transcript(); // not cached until a session is created
            adapter.notifyDataSetChanged();
        });
        View btnSend = findViewById(R.id.btnSend);
        btnSend.setOnClickListener(v -> { if (busy) abortRun(); else send(); });

        // P5: tap the header chip → model picker
        View headerModel = findViewById(R.id.btnModel);
        if (headerModel != null) headerModel.setOnClickListener(v -> showModelPicker());

        // P5: long-press a bubble → copy raw text
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            if (pos < 0 || pos >= tr.msgs.size()) return false;
            String txt = tr.msgs.get(pos).text.toString();
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("opencode", txt));
                Toast.makeText(this, "copied", Toast.LENGTH_SHORT).show();
            }
            return true;
        });

        ServerService.subscribe(this);
        ServerService.subscribeEvents(this);
        refreshStatus();
        checkPermissionQueue();
        if (hasPreset) loadHistoryIfEmpty();
        else resolveInitialSession();
    }

    /** "Open chat" with no preset: continue the latest session (or create one). */
    private void resolveInitialSession() {
        ex.execute(() -> {
            String sid = ensureSession();
            if (sid == null) return;
            ui.post(() -> {
                if (sessionId != null && !sessionId.equals(sid)) return; // user moved on
                sessionId = sid;
                synchronized (CACHE) {
                    Transcript cached = CACHE.get(sid);
                    if (cached != null && cached != tr) tr = cached; // restore history view
                    else CACHE.put(sid, tr);
                }
                adapter.notifyDataSetChanged();
                if (!tr.historyLoaded && tr.msgs.isEmpty()) loadHistoryIfEmpty();
            });
        });
    }

    @Override
    protected void onDestroy() {
        ServerService.unsubscribe(this);
        ServerService.unsubscribeEvents(this);
        ui.removeCallbacks(spin);
        if (permDialog != null && permDialog.isShowing()) permDialog.dismiss();
        super.onDestroy();
    }

    // ------------------------------------------------- ServerService.Evt
    @Override
    public void on(int newState, String detail) {
        runOnUiThread(() -> { refreshStatus(); checkPermissionQueue(); });
    }

    // --------------------------------------- ServerService.EventListener
    @Override
    public void onEvent(Map<String, Object> ev) {
        // already on main thread (service dispatches there)
        handleEvent(ev);
        checkPermissionQueue();
    }

    private void refreshStatus() {
        int st = ServerService.getState();
        String s;
        switch (st) {
            case ServerService.ST_HEALTHY:
                s = "server ● healthy" + (ServerService.pendingPermissions() > 0
                        ? " · ⚠ " + ServerService.pendingPermissions() + " permission(s) pending" : "");
                break;
            case ServerService.ST_STARTING: s = "server ◌ starting…"; break;
            case ServerService.ST_EXITED:   s = "server ✕ exited"; break;
            default:                        s = "server ○ idle"; break;
        }
        tvStatus.setText(s);
        if (tvModel != null) tvModel.setText(currentModelLabel());
    }

    // ---------------------------------------------------------------- send

    private void send() {
        final String q = input.getText().toString().trim();
        if (q.isEmpty()) return;
        input.setText("");
        addMsg("local-" + System.nanoTime(), "user", q);
        ex.execute(() -> {
            try {
                String sid = ensureSession();
                if (sid == null) {
                    sys("cannot create session (is the server healthy?)");
                    return;
                }
                setBusy(true);
                // P5: include the picked model; if the server rejects the
                // body (400/422) retry once with the server default model.
                boolean withModel = Models.selected(this) != null;
                Api.Resp r = Api.post("/session/" + sid + "/message",
                        messageBody(q, withModel), 300_000);
                if (!r.ok() && withModel && (r.status == 400 || r.status == 422)) {
                    r = Api.post("/session/" + sid + "/message",
                            messageBody(q, false), 300_000);
                }
                if (!r.ok()) {
                    String err = Json.findErrorText(Json.parse(r.body), 0);
                    sys("send failed · HTTP " + r.status + (err == null ? "" : " · " + err)
                            + "\n(no model configured? import auth.json / opencode.json on the main screen)");
                    setBusy(false);
                    return;
                }
                reconcileFromResponse(Json.parse(r.body));
                // busy stays on: session.status idle clears it; abort (■) is
                // always available as a manual way out.
            } catch (Exception e) {
                sys("send failed: " + e.getMessage());
                setBusy(false);
            }
        });
    }

    /** Message POST body; optionally carries the picked model override. */
    private String messageBody(String q, boolean withModel) {
        StringBuilder b = new StringBuilder("{\"parts\":[{\"type\":\"text\",\"text\":")
                .append(Json.quote(q)).append("]}");
        if (withModel) {
            String[] sel = Models.selected(this);
            if (sel != null) {
                b.append(",\"model\":{\"providerID\":").append(Json.quote(sel[0]))
                        .append(",\"modelID\":").append(Json.quote(sel[1])).append("}");
            }
        }
        return b.toString();
    }

    /** Busy-state UI: send ↑ becomes stop ■, status line animates dots. */
    private void setBusy(boolean b) {
        busy = b;
        ui.post(() -> {
            TextView send = findViewById(R.id.btnSend);
            if (send != null) {
                if (b) {
                    send.setText("■");
                    send.setTextSize(16);
                    send.setBackgroundResource(R.drawable.bg_stop);
                    send.setTextColor(getResources().getColor(R.color.err));
                    ui.removeCallbacks(spin);
                    ui.postDelayed(spin, 100);
                } else {
                    send.setText("↑");
                    send.setTextSize(22);
                    send.setBackgroundResource(R.drawable.bg_send);
                    send.setTextColor(getResources().getColor(R.color.on_accent));
                    ui.removeCallbacks(spin);
                    refreshStatus();
                }
            }
        });
    }

    /** ■ tapped: abort the running agent (POST /session/{id}/abort). */
    private void abortRun() {
        final String sid = sessionId;
        if (sid == null) { setBusy(false); return; }
        sys("■ stop requested");
        ex.execute(() -> {
            try {
                Api.Resp r = Api.call("POST", "/session/" + sid + "/abort", null, 10_000);
                if (!r.ok()) sys("abort failed · HTTP " + r.status);
            } catch (Exception e) {
                sys("abort failed: " + e.getMessage());
            }
            // belt & braces: the server idle event also clears busy, but never
            // leave the user stuck on the stop button.
            setBusy(false);
        });
    }

    // ------------------------------------------------------------ model

    private String currentModelLabel() {
        String[] s = Models.selected(this);
        return s == null ? "default model ▾" : s[1] + " ▾";
    }

    private void showModelPicker() {
        if (ServerService.getState() != ServerService.ST_HEALTHY) {
            Toast.makeText(this, "server not healthy — start it on the main screen",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        tvModel.setText("loading models…");
        ex.execute(() -> {
            List<Models.Item> items = Models.fetch();
            ui.post(() -> renderModelDialog(items));
        });
    }

    private void renderModelDialog(List<Models.Item> items) {
        tvModel.setText(currentModelLabel());
        if (items.isEmpty()) {
            sys("model list empty — is auth.json imported and the server healthy?");
            return;
        }
        String[] labels = new String[items.size()];
        int cur = -1;
        String[] sel = Models.selected(this);
        for (int i = 0; i < items.size(); i++) {
            labels[i] = items.get(i).label;
            if (sel != null && items.get(i).provider.equals(sel[0])
                    && items.get(i).id.equals(sel[1])) cur = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("Model")
                .setSingleChoiceItems(labels, cur, (d, w) -> {
                    Models.Item it = items.get(w);
                    Models.save(ChatActivity.this, it.provider, it.id);
                    sys("model → " + it.id + " (" + it.provider + ")");
                    d.dismiss();
                })
                .setNeutralButton("Server default", (d, w) -> {
                    Models.clear(ChatActivity.this);
                    tvModel.setText(currentModelLabel());
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String ensureSession() {
        String sid = sessionId;
        if (sid != null) return sid;
        if (!forceNew) {
            // continue the most recent session when just opening the app
            try {
                Api.Resp g = Api.get("/session");
                List<Object> arr = Json.arr(Json.parse(g.body));
                if (arr != null && !arr.isEmpty()) {
                    Map<String, Object> first = Json.obj(arr.get(0));
                    String id = first == null ? null : Json.str(first, "id");
                    if (id != null) { sessionId = id; return id; }
                }
            } catch (Exception ignored) {}
        }
        try {
            Api.Resp p = Api.post("/session", "{}", 10_000);
            Map<String, Object> m = Json.obj(Json.parse(p.body));
            String id = Json.str(m, "id");
            if (id != null) {
                sessionId = id;
                forceNew = false;
                synchronized (CACHE) { if (!CACHE.containsKey(id)) CACHE.put(id, tr); }
                return id;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void reconcileFromResponse(Object parsed) {
        Map<String, Object> m = Json.obj(parsed);
        if (m == null) return;
        List<Object> parts = Json.list(m, "parts");
        if (parts == null) {
            List<Object> arr = Json.arr(parsed);
            if (arr != null) {
                for (Object o : arr) {
                    Map<String, Object> mm = Json.obj(o);
                    if (mm == null) continue;
                    List<Object> ps = Json.list(mm, "parts");
                    if (ps != null) parts = ps;
                }
            }
        }
        if (parts == null) return;
        for (Object o : parts) {
            Map<String, Object> part = Json.obj(o);
            if (part == null) continue;
            if ("text".equals(Json.str(part, "type"))) {
                String mid = Json.str(part, "messageID");
                if (mid != null && !mid.isEmpty()) applyText(mid, Json.str(part, "text"));
            }
        }
    }

    // ------------------------------------------------------------- history

    private void loadHistoryIfEmpty() {
        final String sid = sessionId;
        if (sid == null || tr.historyLoaded || !tr.msgs.isEmpty()) return;
        ex.execute(() -> {
            try {
                Api.Resp r = Api.get("/session/" + sid + "/message");
                if (!r.ok()) { markHistoryLoaded(); return; }
                List<Object> arr = Json.arr(Json.parse(r.body));
                if (arr == null) { markHistoryLoaded(); return; }
                int max = arr.size();
                int from = Math.max(0, max - 50); // render last 50 messages
                for (int i = from; i < max; i++) {
                    Map<String, Object> item = Json.obj(arr.get(i));
                    if (item == null) continue;
                    Map<String, Object> info = Json.map(item, "info");
                    if (info == null) info = item;
                    if (Boolean.TRUE.equals(info.get("synthetic"))) continue;
                    String role = Json.str(info, "role");
                    String mid = Json.str(info, "id");
                    if (mid == null) mid = "hist-" + i;
                    if (!"user".equals(role) && !"assistant".equals(role)) continue;
                    List<Object> parts = Json.list(item, "parts");
                    if (parts == null) parts = Json.list(info, "parts");
                    if (parts == null) continue;
                    for (Object po : parts) {
                        Map<String, Object> part = Json.obj(po);
                        if (part == null) continue;
                        String ptype = Json.str(part, "type");
                        if ("text".equals(ptype)) {
                            String txt = Json.str(part, "text");
                            if (txt != null && !txt.isEmpty()) {
                                String pid = Json.str(part, "messageID");
                                appendHistoryBubble(pid != null ? pid : mid + "#" + i,
                                        role, txt);
                            }
                        } else if ("tool".equals(ptype) && "assistant".equals(role)) {
                            String pk = Json.str(part, "id");
                            appendHistoryBubble(
                                    pk != null && !pk.isEmpty() ? pk : mid + "#t" + i,
                                    "tool", toolCardText(part));
                        }
                    }
                }
                markHistoryLoaded();
            } catch (Exception e) {
                markHistoryLoaded();
            }
        });
    }

    private void markHistoryLoaded() {
        ui.post(() -> { tr.historyLoaded = true; adapter.notifyDataSetChanged(); });
    }

    private void appendHistoryBubble(String key, String role, String text) {
        ui.post(() -> {
            if (tr.byKey.containsKey(key)) return;
            Msg m = new Msg(key, role);
            m.text.append(text);
            tr.byKey.put(key, m);
            tr.msgs.add(m);
            adapter.notifyDataSetChanged();
        });
    }

    // ------------------------------------------------------------ events

    private void handleEvent(Map<String, Object> ev) {
        String type = Json.str(ev, "type");
        Map<String, Object> props = Json.map(ev, "properties");
        if (type == null || props == null) return;

        if ("message.part.updated".equals(type)) {
            Map<String, Object> part = Json.map(props, "part");
            if (part == null) part = props;
            String ptype = Json.str(part, "type");
            String mid = Json.str(part, "messageID");
            if (mid == null || mid.isEmpty()) return;
            String sid = Json.str(part, "sessionID");
            if (sid != null && sessionId != null && !sid.equals(sessionId)) return;
            if ("text".equals(ptype)) {
                applyText(mid, Json.str(part, "text"));
            } else if ("tool".equals(ptype)) {
                applyToolCard(part);
            }
            return;
        }

        if ("message.updated".equals(type)) {
            Map<String, Object> info = Json.map(props, "info");
            if (info == null) return;
            Map<String, Object> err = Json.map(info, "error");
            if (err != null) {
                String name = nz(Json.str(err, "name"), "error");
                String msg = nz(Json.str(err, "message"), "");
                sys(name + (msg.isEmpty() ? "" : " · " + msg));
            }
            return;
        }

        if ("session.status".equals(type)) {
            // typing indicator: properties.status.type = busy|idle|retry
            String sid = Json.str(props, "sessionID");
            if (sid == null || sessionId == null || sid.equals(sessionId)) {
                Map<String, Object> st = Json.map(props, "status");
                String stype = st == null ? null : Json.str(st, "type");
                if ("busy".equals(stype) || "retry".equals(stype)) {
                    setBusy(true);
                } else if ("idle".equals(stype)) {
                    setBusy(false);
                }
            }
            return;
        }
        if ("session.error".equals(type)) {
            String sid = Json.str(props, "sessionID");
            if (sid != null && sessionId != null && !sid.equals(sessionId)) return;
            String msg = Json.findErrorText(props, 0);
            sys(nz(msg, "session error"));
            return;
        }
        // permission.asked / .updated / .replied → handled via the service queue
    }

    private String nz(String s, String def) { return s == null || s.isEmpty() ? def : s; }

    // --------------------------------------------------------- permissions

    /** Show the pending permission dialog if one exists and none is showing. */
    private void checkPermissionQueue() {
        if (permDialog != null && permDialog.isShowing()) return;
        final Map<String, Object> p = ServerService.peekPermission();
        if (p == null) { refreshStatus(); return; }

        String id = nz(Json.str(p, "id"), "?");
        // v1.18.x: {id, sessionID, permission:"bash", patterns:[...], metadata:{...}}
        String action = Json.str(p, "permission");
        if (action == null || action.isEmpty()) {
            action = nz(Json.str(p, "type"), "permission");
        }
        String title = Json.str(p, "title");
        Map<String, Object> meta = Json.map(p, "metadata");

        StringBuilder detail = new StringBuilder();
        if (title != null && !title.isEmpty()) detail.append(title).append("\n\n");
        List<Object> pats = Json.list(p, "patterns");
        if (pats != null && !pats.isEmpty()) {
            detail.append("patterns: ");
            for (Object o : pats) detail.append(String.valueOf(o)).append(' ');
            detail.append("\n");
        }
        if (meta != null && !meta.isEmpty()) {
            if (detail.length() > 0) detail.append("\n");
            for (Map.Entry<String, Object> e : meta.entrySet()) {
                String v = String.valueOf(e.getValue());
                if (v.length() > 500) v = v.substring(0, 500) + "…";
                detail.append(e.getKey()).append(": ").append(v).append("\n");
            }
        }
        if (detail.length() == 0) detail.append("(no details provided)");

        refreshStatus();
        permDialog = new AlertDialog.Builder(this)
                .setTitle("⚠ Allow " + action + "?")
                .setMessage(detail.toString())
                .setPositiveButton("Allow once", (d, w) -> answerPermission(id, "once"))
                .setNeutralButton("Always", (d, w) -> answerPermission(id, "always"))
                .setNegativeButton("Deny", (d, w) -> answerPermission(id, "reject"))
                .setOnDismissListener(d -> { if (permDialog == d) permDialog = null; })
                .create();
        permDialog.show();
    }

    private void answerPermission(String id, String response) {
        final String sid = sessionId;
        ex.execute(() -> {
            String err = null;
            try {
                // v1.18.x (verified against the shipped binary): the reply
                // body key is "reply", NOT "response" — the old body was a
                // silent 400 and the agent stalled forever.
                Api.Resp r = Api.post("/permission/" + id + "/reply",
                        "{\"reply\":" + Json.quote(response) + "}", 15_000);
                if (!r.ok() && r.status == 404 && sid != null) {
                    // legacy fallback for older servers
                    r = Api.post("/session/" + sid + "/permissions/" + id,
                            "{\"response\":" + Json.quote(response) + "}", 15_000);
                }
                if (!r.ok()) err = "HTTP " + r.status;
            } catch (Exception e) {
                err = e.getMessage();
            }
            final String failure = err;
            ServerService.noteAnswered(id);
            ui.post(() -> {
                if (failure != null) sys("permission reply failed · " + failure);
                checkPermissionQueue();
            });
        });
    }

    // ----------------------------------------------------------- tool cards

    /** Tool parts become their own monospace card bubbles keyed by part id —
     *  file reads/edits and shell commands are now visible in the stream
     *  (closes the "I can't see what files it touches" gap). */
    private void applyToolCard(Map<String, Object> part) {
        String pid = Json.str(part, "id");
        if (pid == null || pid.isEmpty()) pid = Json.str(part, "messageID") + "#tool";
        upsertBubble(pid, "tool", toolCardText(part));
    }

    /** "[bash] running · $ npm install" / "[read] completed · src/App.tsx" */
    private String toolCardText(Map<String, Object> part) {
        String tool = nz(Json.str(part, "tool"), "call");
        Map<String, Object> state = Json.map(part, "state");
        String status = state == null ? "" : nz(Json.str(state, "status"), "");
        StringBuilder sb = new StringBuilder("[").append(tool).append("]");
        if (!status.isEmpty()) sb.append(' ').append(status);
        String arg = toolKeyArg(state);
        if (arg != null) sb.append(" · ").append(arg);
        Map<String, Object> err = state == null ? null : Json.map(state, "error");
        if (err != null) {
            String em = Json.str(err, "message");
            if (em == null) em = Json.findErrorText(err, 0);
            if (em != null && !em.isEmpty()) sb.append("\n✕ ").append(shorten(em, 220));
        }
        return sb.toString();
    }

    /** First useful argument of a tool call (command / file path / query). */
    private String toolKeyArg(Map<String, Object> state) {
        if (state == null) return null;
        Map<String, Object> in = Json.map(state, "input");
        if (in == null) in = Json.map(state, "metadata");
        if (in == null) return null;
        List<Object> cmdArr = Json.list(in, "command");
        if (cmdArr != null && !cmdArr.isEmpty()) {
            StringBuilder b = new StringBuilder("$ ");
            for (Object o : cmdArr) b.append(o).append(' ');
            return shorten(b.toString(), 120);
        }
        for (String k : new String[]{"command", "filePath", "file_path", "path",
                "notebookPath", "pattern", "query", "url", "description"}) {
            String v = Json.str(in, k);
            if (v != null && !v.isEmpty()) {
                return ("command".equals(k) ? "$ " : "") + shorten(v, 120);
            }
        }
        return null;
    }

    private static String shorten(String s, int n) {
        if (s == null) return null;
        s = s.replace('\n', ' ').trim();
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    /** Create-or-replace a bubble (tool cards update in place as the call
     *  moves pending → running → completed/error). */
    private void upsertBubble(String key, String role, String text) {
        ui.post(() -> {
            Msg m = tr.byKey.get(key);
            if (m == null) {
                m = new Msg(key, role);
                tr.byKey.put(key, m);
                tr.msgs.add(m);
            }
            m.text.setLength(0);
            m.text.append(text);
            adapter.notifyDataSetChanged();
        });
    }

    // ------------------------------------------------------------- bubbles

    private void applyText(String mid, String text) {
        applyText(mid, text, false);
    }

    private void applyText(String mid, String text, boolean append) {
        if (text == null) return;
        ui.post(() -> {
            Msg m = tr.byKey.get(mid);
            if (m == null) {
                for (Msg x : tr.msgs) {
                    if ("user".equals(x.role) && x.text.toString().equals(text)) return;
                }
                m = new Msg(mid, "assistant");
                tr.byKey.put(mid, m);
                tr.msgs.add(m);
            }
            String cur = m.text.toString();
            if (append) {
                if (!cur.contains(text)) m.text.append(text);
            } else if (text.startsWith(cur)) {
                m.text.setLength(0);
                m.text.append(text);
            } else if (!cur.startsWith(text) && !cur.equals(text)) {
                m.text.append(text);
            }
            adapter.notifyDataSetChanged();
        });
    }

    private void sys(String text) {
        ui.post(() -> {
            String key = "sys-" + (tr.sysCounter++);
            Msg m = new Msg(key, "system");
            m.text.append(text);
            tr.byKey.put(key, m);
            tr.msgs.add(m);
            adapter.notifyDataSetChanged();
        });
    }

    private void addMsg(String key, String role, String text) {
        ui.post(() -> {
            Msg m = new Msg(key, role);
            m.text.append(text);
            tr.byKey.put(key, m);
            tr.msgs.add(m);
            adapter.notifyDataSetChanged();
        });
    }

    /** Sessions delete hook: drop a removed session's cached transcript. */
    static void forgetSession(String id) {
        synchronized (CACHE) { CACHE.remove(id); }
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return tr == null ? 0 : tr.msgs.size(); }
        @Override public Object getItem(int i) { return tr.msgs.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public void notifyDataSetChanged() {
            super.notifyDataSetChanged();
            if (viewEmpty != null) {
                viewEmpty.setVisibility(getCount() == 0 ? View.VISIBLE : View.GONE);
            }
        }

        @Override
        public View getView(int i, View conv, ViewGroup parent) {
            View v = conv;
            if (v == null) {
                v = LayoutInflater.from(ChatActivity.this)
                        .inflate(R.layout.item_msg, parent, false);
            }
            Msg m = tr.msgs.get(i);
            TextView tv = v.findViewById(R.id.msg);
            android.widget.LinearLayout row = (android.widget.LinearLayout) tv.getParent();
            switch (m.role) {
                case "user":
                    tv.setText(m.text);
                    tv.setBackgroundResource(R.drawable.bg_bubble_user);
                    row.setGravity(Gravity.END);
                    tv.setTextAppearance(android.R.style.TextAppearance_Material_Small);
                    tv.setTypeface(android.graphics.Typeface.DEFAULT);
                    break;
                case "system":
                    tv.setText(m.text);
                    tv.setBackgroundResource(R.drawable.bg_bubble_system);
                    row.setGravity(Gravity.START);
                    tv.setTypeface(android.graphics.Typeface.MONOSPACE);
                    tv.setTextAppearance(android.R.style.TextAppearance_Material_Small);
                    break;
                case "tool":
                    tv.setText(m.text);
                    tv.setBackgroundResource(R.drawable.bg_bubble_system);
                    row.setGravity(Gravity.START);
                    tv.setTypeface(android.graphics.Typeface.MONOSPACE);
                    tv.setTextAppearance(android.R.style.TextAppearance_Material_Small);
                    tv.setTextColor(getResources().getColor(R.color.text_secondary));
                    break;
                default:
                    tv.setText(Markdown.render(m.text.toString()));
                    tv.setBackgroundResource(R.drawable.bg_bubble_assistant);
                    row.setGravity(Gravity.START);
                    tv.setTypeface(android.graphics.Typeface.DEFAULT);
                    tv.setTextAppearance(android.R.style.TextAppearance_Material_Medium);
            }
            return v;
        }
    }
}

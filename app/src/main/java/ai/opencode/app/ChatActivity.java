package ai.opencode.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * P6 chat: full part-aware rendering, matching the opencode TUI behaviour.
 *
 * Every message is a LIST OF PARTS and each part gets the right row:
 *   text      → markdown bubble (assistant) or right-aligned bubble (user)
 *   reasoning → "✦ Thinking" card, collapsed by default, tap to expand
 *   tool      → compact card "▸ bash · $ npm install ●" — collapsed by
 *               default, tap to expand input/output; diff lines in edit
 *               output are colored +green/−red
 *   patch     → tool-style card listing the touched files
 *   system/error rows stay monospace
 *
 * Assistant bubbles carry a token/cost footer (info.tokens formula verified
 * in the binary: input+output+reasoning+cache.read+cache.write; info.cost).
 *
 * Carried from P2/P4/P5: SSE via ServerService, permission approval flow,
 * stop/abort, model picker (now grouped + searchable), copy on long-press,
 * per-session transcript cache, working-dots, empty state.
 */
public class ChatActivity extends Activity implements ServerService.Evt, ServerService.EventListener {

    // row kinds == view types
    private static final int K_USER = 0, K_ASSISTANT = 1, K_REASON = 2,
            K_TOOL = 3, K_SYSTEM = 4, K_SETUP = 5;

    private static final class Row {
        final String partId;
        final int kind;
        final StringBuilder text = new StringBuilder();   // user/assistant/reason/system
        final StringBuilder input = new StringBuilder();  // tool
        final StringBuilder output = new StringBuilder(); // tool
        String tool = "", status = "", title = "";        // tool
        String msgId = "";                                // assistant meta lookup
        Row(String partId, int kind) { this.partId = partId; this.kind = kind; }
    }

    /** Per-session transcript cache (process-lifetime, LRU capped). */
    private static final class Transcript {
        final List<Row> rows = new ArrayList<>();
        final Map<String, Row> byPartId = new HashMap<>();
        final Map<String, String> metaByMsg = new HashMap<>();
        int sysCounter = 0;
        boolean historyLoaded = false;
        boolean setupRowShown = false;
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
    private final HashSet<String> expanded = new HashSet<>();

    private ListView list;
    private EditText input;
    private TextView tvStatus;
    private TextView tvModel;
    private View viewEmpty;

    private volatile String sessionId;
    private volatile boolean forceNew;
    private volatile boolean busy;
    private int spinFrame;
    private AlertDialog permDialog;

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

        // CRITICAL ORDERING (v0.2.1 lesson): bind the transcript BEFORE
        // setAdapter — ListView calls getCount() synchronously.
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
            tr = new Transcript();
            adapter.notifyDataSetChanged();
        });
        View btnSend = findViewById(R.id.btnSend);
        btnSend.setOnClickListener(v -> { if (busy) abortRun(); else send(); });

        View headerModel = findViewById(R.id.btnModel);
        if (headerModel != null) headerModel.setOnClickListener(v -> showModelPicker());

        list.setOnItemLongClickListener((p, v, pos, id) -> {
            if (pos < 0 || pos >= tr.rows.size()) return false;
            Row r = tr.rows.get(pos);
            String txt;
            if (r.kind == K_TOOL) {
                txt = r.output.length() > 0 ? r.output.toString() : toolHeaderText(r);
            } else {
                txt = r.text.toString();
            }
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
        if (!AuthStore.hasAnyKey(this)) addSetupRow(null);
        if (hasPreset) loadHistoryIfEmpty();
        else resolveInitialSession();
    }

    /** "Open chat" with no preset: continue the latest session (or create one). */
    private void resolveInitialSession() {
        ex.execute(() -> {
            String sid = ensureSession();
            if (sid == null) return;
            ui.post(() -> {
                if (sessionId != null && !sessionId.equals(sid)) return;
                sessionId = sid;
                synchronized (CACHE) {
                    Transcript cached = CACHE.get(sid);
                    if (cached != null && cached != tr) tr = cached;
                    else CACHE.put(sid, tr);
                }
                adapter.notifyDataSetChanged();
                if (!tr.historyLoaded && tr.rows.isEmpty()) loadHistoryIfEmpty();
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
        addRow("local-" + System.nanoTime(), K_USER, q);
        ex.execute(() -> {
            try {
                String sid = ensureSession();
                if (sid == null) {
                    sys("cannot create session (is the server healthy?)");
                    return;
                }
                setBusy(true);
                // picked model override; on 400/422 retry once without it
                boolean withModel = Models.selected(this) != null;
                Api.Resp r = Api.post("/session/" + sid + "/message",
                        messageBody(q, withModel), 300_000);
                if (!r.ok() && withModel && (r.status == 400 || r.status == 422)) {
                    r = Api.post("/session/" + sid + "/message",
                            messageBody(q, false), 300_000);
                }
                if (!r.ok()) {
                    String err = Json.findErrorText(Json.parse(r.body), 0);
                    sys("send failed · HTTP " + r.status + (err == null ? "" : " · " + err));
                    // the classic cause: no provider key / no default model
                    if (r.status == 500 || r.status == 400) {
                        if (!AuthStore.hasAnyKey(this) || Models.selected(this) == null) {
                            addSetupRow(err);
                        }
                    }
                    setBusy(false);
                    return;
                }
                reconcileFromResponse(Json.parse(r.body));
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
            List<Models.Prov> provs = Models.fetch(this);
            ui.post(() -> renderModelDialog(provs));
        });
    }

    private void renderModelDialog(List<Models.Prov> provs) {
        tvModel.setText(currentModelLabel());
        int total = 0;
        for (Models.Prov p : provs) total += p.models.size();
        if (provs.isEmpty()) {
            addSetupRow("model list empty — no provider is configured yet");
            return;
        }

        View dlgView = LayoutInflater.from(this).inflate(R.layout.dialog_models, null);
        EditText search = dlgView.findViewById(R.id.etSearch);
        ListView lv = dlgView.findViewById(R.id.lvModels);
        ModelPickAdapter pa = new ModelPickAdapter(provs, "");
        lv.setAdapter(pa);
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b2, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b2, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                pa.refilter(s.toString());
            }
        });

        new AlertDialog.Builder(this)
                .setTitle("Model — " + total + " from " + provs.size() + " providers")
                .setView(dlgView)
                .setNeutralButton("Server default", (d, w) -> {
                    Models.clear(this);
                    tvModel.setText(currentModelLabel());
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Grouped + searchable model list. */
    private final class ModelPickAdapter extends BaseAdapter {
        private static final int T_HEADER = 0, T_MODEL = 1;
        private final List<Models.Prov> all;
        private final List<Object[]> rows = new ArrayList<>(); // [type, label, Prov, Mdl]
        private String query = "";

        ModelPickAdapter(List<Models.Prov> provs, String q) {
            all = provs;
            refilter(q);
        }

        void refilter(String q) {
            query = q == null ? "" : q.toLowerCase().trim();
            rows.clear();
            for (Models.Prov p : all) {
                boolean provMatch = query.isEmpty()
                        || p.id.toLowerCase().contains(query)
                        || p.name.toLowerCase().contains(query);
                List<Models.Mdl> matches = new ArrayList<>();
                for (Models.Mdl m : p.models) {
                    if (provMatch
                            || m.id.toLowerCase().contains(query)
                            || m.name.toLowerCase().contains(query)) {
                        matches.add(m);
                    }
                }
                if (matches.isEmpty()) continue;
                rows.add(new Object[]{T_HEADER,
                        p.name + (p.configured ? "  ✓" : "") + "  (" + matches.size() + ")", p, null});
                for (Models.Mdl m : matches) rows.add(new Object[]{T_MODEL, null, p, m});
            }
            ui.post(this::notifyDataSetChanged);
        }

        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int i) { return rows.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int i) { return (int) rows.get(i)[0]; }
        @Override public boolean isEnabled(int i) { return (int) rows.get(i)[0] == T_MODEL; }

        @Override
        public View getView(int i, View conv, ViewGroup parent) {
            Object[] e = rows.get(i);
            TextView t = (TextView) conv;
            if (t == null) {
                t = new TextView(ChatActivity.this);
                t.setPadding(dp(18), dp(8), dp(12), dp(8));
            }
            if ((int) e[0] == T_HEADER) {
                t.setText((String) e[1]);
                t.setTextSize(12);
                t.setTypeface(Typeface.DEFAULT_BOLD);
                t.setTextColor(getResources().getColor(R.color.text_secondary));
                t.setBackgroundResource(R.color.bg);
            } else {
                Models.Prov p = (Models.Prov) e[2];
                Models.Mdl m = (Models.Mdl) e[3];
                String[] cur = Models.selected(ChatActivity.this);
                boolean isCur = cur != null && cur[0].equals(p.id) && cur[1].equals(m.id);
                String label = m.name + (isCur ? "  ✓" : "")
                        + "\n" + p.id + "/" + m.id;
                t.setText(label);
                t.setTextSize(14);
                t.setTypeface(Typeface.DEFAULT);
                t.setTextColor(getResources().getColor(R.color.text_primary));
                t.setBackgroundResource(android.R.color.transparent);
                t.setOnClickListener(v -> {
                    Models.save(ChatActivity.this, p.id, m.id);
                    try {
                        // server-wide default too → no more 500 on fresh sessions
                        AuthStore.setDefaultModel(ChatActivity.this, p.id, m.id);
                        sys("model → " + m.id + " (" + p.id + ") · set as default");
                    } catch (Exception ex) {
                        sys("model → " + m.id + " (" + p.id + ")");
                    }
                    tvModel.setText(currentModelLabel());
                });
            }
            return t;
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
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
        for (Object o : parts) applyPart(Json.obj(o));
    }

    // ------------------------------------------------------------- history

    private void loadHistoryIfEmpty() {
        final String sid = sessionId;
        if (sid == null || tr.historyLoaded || !tr.rows.isEmpty()) return;
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
                    boolean isUser = "user".equals(role);
                    if (!isUser && !"assistant".equals(role)) continue;
                    // token/cost meta for assistant bubbles
                    String meta = metaFromInfo(info);
                    if (meta != null) tr.metaByMsg.put(mid, meta);
                    List<Object> parts = Json.list(item, "parts");
                    if (parts == null) parts = Json.list(info, "parts");
                    if (parts == null) continue;
                    int pi = 0;
                    for (Object po : parts) {
                        Map<String, Object> part = Json.obj(po);
                        if (part == null) continue;
                        String pid = partKey(part, mid + "#p" + (pi++));
                        String ptype = Json.str(part, "type");
                        if ("text".equals(ptype)) {
                            String txt = Json.str(part, "text");
                            if (txt == null || txt.isEmpty()) continue;
                            if (isUser) {
                                Row rr = new Row(pid, K_USER);
                                rr.text.append(txt);
                                putRow(rr);
                            } else {
                                Row rr = new Row(pid, K_ASSISTANT);
                                rr.text.append(txt);
                                rr.msgId = mid;
                                putRow(rr);
                            }
                        } else if ("reasoning".equals(ptype) && !isUser) {
                            String txt = Json.str(part, "text");
                            if (txt == null || txt.isEmpty()) continue;
                            Row rr = new Row(pid, K_REASON);
                            rr.text.append(txt);
                            putRow(rr);
                        } else if ("tool".equals(ptype) && !isUser) {
                            putRow(toolRow(pid, part));
                        } else if ("patch".equals(ptype) && !isUser) {
                            putRow(patchRow(pid, part));
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

    // ------------------------------------------------------------ events

    private void handleEvent(Map<String, Object> ev) {
        String type = Json.str(ev, "type");
        Map<String, Object> props = Json.map(ev, "properties");
        if (type == null || props == null) return;

        if ("message.part.updated".equals(type)) {
            Map<String, Object> part = Json.map(props, "part");
            if (part == null) part = props;
            String mid = Json.str(part, "messageID");
            String sid = Json.str(part, "sessionID");
            if (sid != null && sessionId != null && !sid.equals(sessionId)) return;
            applyPart(part);
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
            // token/cost footer for this message's assistant bubbles
            String mid = Json.str(info, "id");
            String meta = metaFromInfo(info);
            if (mid != null && meta != null && !meta.equals(tr.metaByMsg.get(mid))) {
                tr.metaByMsg.put(mid, meta);
                ui.post(adapter::notifyDataSetChanged);
            }
            return;
        }

        if ("session.status".equals(type)) {
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

    /** Route any message part to its row. */
    private void applyPart(Map<String, Object> part) {
        if (part == null) return;
        String ptype = Json.str(part, "type");
        String mid = Json.str(part, "messageID");
        if (mid == null || mid.isEmpty()) mid = "live";
        String pid = partKey(part, mid + "#live");
        if ("text".equals(ptype)) {
            applyTextLike(pid, mid, K_ASSISTANT, Json.str(part, "text"));
        } else if ("reasoning".equals(ptype)) {
            applyTextLike(pid, mid, K_REASON, Json.str(part, "text"));
        } else if ("tool".equals(ptype)) {
            upsert(toolRow(pid, part));
        } else if ("patch".equals(ptype)) {
            upsert(patchRow(pid, part));
        }
        // step-start / step-finish / agent / file → nothing visible (yet)
    }

    /** Stable key for a part row. */
    private String partKey(Map<String, Object> part, String fallback) {
        String id = Json.str(part, "id");
        return (id == null || id.isEmpty()) ? fallback : id;
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

    // ------------------------------------------------------- row builders

    /** text/reasoning create-or-update with the P5 reconcile rules. */
    private void applyTextLike(String pid, String mid, int kind, String text) {
        if (text == null) return;
        ui.post(() -> {
            Row r = tr.byPartId.get(pid);
            if (r == null && kind == K_ASSISTANT && mid != null) {
                // The POST response and the SSE stream may key the same part
                // differently (part.id vs messageID) — never render twice.
                for (Row x : tr.rows) {
                    if (x.kind == K_ASSISTANT && mid.equals(x.msgId)) { r = x; break; }
                }
            }
            if (r == null) {
                // user-echo dedupe: an assistant part repeating what we sent
                if (kind == K_ASSISTANT) {
                    for (Row x : tr.rows) {
                        if (x.kind == K_USER && text.equals(x.text.toString())) return;
                    }
                }
                r = new Row(pid, kind);
                r.msgId = mid;
                putRow(r);
            }
            reconcileText(r.text, text);
            adapter.notifyDataSetChanged();
        });
    }

    /** Cumulative replace / delta append — the P5 streaming reconcile. */
    private static void reconcileText(StringBuilder cur, String text) {
        String c = cur.toString();
        if (text.startsWith(c) && c.length() <= text.length()) {
            cur.setLength(0);
            cur.append(text);
        } else if (!c.startsWith(text) && !c.equals(text) && !text.contains(c)) {
            cur.append(text);
        } else if (text.length() > c.length()) {
            cur.setLength(0);
            cur.append(text);
        }
    }

    private Row toolRow(String pid, Map<String, Object> part) {
        Row r = new Row(pid, K_TOOL);
        r.tool = nz(Json.str(part, "tool"), "call");
        Map<String, Object> state = Json.map(part, "state");
        if (state == null) state = part;
        r.status = nz(Json.str(state, "status"), "");
        r.title = nz(Json.str(state, "title"), "");
        Map<String, Object> in = Json.map(state, "input");
        if (in != null && !in.isEmpty()) {
            StringBuilder b = new StringBuilder();
            for (Map.Entry<String, Object> e : in.entrySet()) {
                String v = String.valueOf(e.getValue());
                if (v.length() > 2000) v = v.substring(0, 2000) + "…";
                b.append(e.getKey()).append(": ").append(v).append('\n');
            }
            r.input.append(b.toString().trim());
        }
        String out = Json.str(state, "output");
        if (out != null && !out.isEmpty()) r.output.append(out);
        Map<String, Object> err = Json.map(state, "error");
        if (err != null) {
            String em = Json.str(err, "message");
            if (em == null) em = Json.findErrorText(err, 0);
            if (em != null && !em.isEmpty())
                r.output.append(r.output.length() > 0 ? "\n" : "")
                        .append("✕ ").append(em);
        }
        return r;
    }

    /** patch part (binary-verified: hash + files[]). */
    private Row patchRow(String pid, Map<String, Object> part) {
        Row r = new Row(pid, K_TOOL);
        r.tool = "patch";
        r.status = "completed";
        List<Object> files = Json.list(part, "files");
        int n = files == null ? 0 : files.size();
        r.title = n + " file" + (n == 1 ? "" : "s") + " changed";
        if (files != null) {
            for (Object f : files) r.output.append(String.valueOf(f)).append('\n');
        }
        return r;
    }

    /** Compact one-line label for a collapsed tool card. */
    private String toolHeader(Map<String, Object> state) {
        String arg = null;
        if (state != null) {
            Map<String, Object> in = Json.map(state, "input");
            if (in != null) arg = keyArgOf(in);
        }
        if (arg == null && state != null) {
            Map<String, Object> md = Json.map(state, "metadata");
            if (md != null) arg = keyArgOf(md);
        }
        return arg == null ? null : shorten(arg, 90);
    }

    private String keyArgOf(Map<String, Object> in) {
        List<Object> cmdArr = Json.list(in, "command");
        if (cmdArr != null && !cmdArr.isEmpty()) {
            StringBuilder b = new StringBuilder("$ ");
            for (Object o : cmdArr) b.append(o).append(' ');
            return b.toString().trim();
        }
        for (String k : new String[]{"command", "filePath", "file_path", "path",
                "notebookPath", "pattern", "query", "url", "description"}) {
            String v = Json.str(in, k);
            if (v != null && !v.isEmpty()) {
                return ("command".equals(k) ? "$ " : "") + v;
            }
        }
        return null;
    }

    private static String shorten(String s, int n) {
        if (s == null) return null;
        s = s.replace('\n', ' ').trim();
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    /** Create-or-replace a row (tool cards update in place as the call
     *  moves pending → running → completed/error). */
    private void upsert(Row r) {
        ui.post(() -> {
            putRow(r);
            adapter.notifyDataSetChanged();
        });
    }

    /** Put a row into the transcript (backing thread OR ui — both safe). */
    private void putRow(Row r) {
        Row old = tr.byPartId.get(r.partId);
        if (old != null) {
            int idx = tr.rows.indexOf(old);
            if (idx >= 0) tr.rows.set(idx, r);
            tr.byPartId.put(r.partId, r);
        } else {
            tr.byPartId.put(r.partId, r);
            tr.rows.add(r);
        }
    }

    private void applyText(String mid, String text) {
        // legacy shim — not used anymore, kept for safety
        applyTextLike(mid, mid, K_ASSISTANT, text);
    }

    private void sys(String text) {
        ui.post(() -> {
            String key = "sys-" + (tr.sysCounter++);
            Row r = new Row(key, K_SYSTEM);
            r.text.append(text);
            putRow(r);
            adapter.notifyDataSetChanged();
        });
    }

    private void addRow(String key, int kind, String text) {
        ui.post(() -> {
            Row r = new Row(key, kind);
            r.text.append(text);
            putRow(r);
            adapter.notifyDataSetChanged();
        });
    }

    /** Accent "connect APIs" shortcut row — shown when sends fail because
     *  nothing is configured, or the chat opens with no keys. */
    private void addSetupRow(String cause) {
        ui.post(() -> {
            if (tr.setupRowShown) {
                adapter.notifyDataSetChanged();
                return;
            }
            tr.setupRowShown = true;
            Row r = new Row("setup-" + System.nanoTime(), K_SETUP);
            r.text.append(cause == null || cause.isEmpty()
                    ? "The agent needs an API key and a model before it can answer."
                    : cause);
            putRow(r);
            adapter.notifyDataSetChanged();
        });
    }

    /** Sessions delete hook: drop a removed session's cached transcript. */
    static void forgetSession(String id) {
        synchronized (CACHE) { CACHE.remove(id); }
    }

    // ------------------------------------------------------------- meta

    /** "⇅ 12.3k tok · $0.0041" — tokens formula verified in the binary:
     *  input+output+reasoning+cache.read+cache.write; cost = info.cost. */
    private String metaFromInfo(Map<String, Object> info) {
        if (info == null) return null;
        Map<String, Object> tk = Json.map(info, "tokens");
        long total = 0;
        if (tk != null) {
            total += num(tk.get("input")) + num(tk.get("output"))
                    + num(tk.get("reasoning"));
            Map<String, Object> cache = Json.map(tk, "cache");
            if (cache != null) {
                total += num(cache.get("read")) + num(cache.get("write"));
            }
        }
        Object costO = info.get("cost");
        double cost = (costO instanceof Double) ? (Double) costO : 0;
        if (total <= 0 && cost <= 0) return null;
        StringBuilder b = new StringBuilder("⇅ ");
        b.append(total >= 1000
                ? String.format("%.1fk", total / 1000.0) : String.valueOf(total));
        b.append(" tok");
        if (cost > 0) b.append(String.format(" · $%.4f", cost));
        return b.toString();
    }

    private static long num(Object o) {
        return (o instanceof Number) ? ((Number) o).longValue() : 0;
    }

    /** Collapsed tool card label: "bash · $ npm install" / "read · src/App.tsx". */
    private String toolHeaderText(Row r) {
        String arg = r.title;
        if (arg == null || arg.isEmpty()) {
            Map<String, Object> fake = new HashMap<>();
            if (r.input.length() > 0) fake.put("input", r.input);
            // reuse the raw-input key scan
            for (String line : r.input.toString().split("\n")) {
                int c = line.indexOf(": ");
                if (c > 0) {
                    String k = line.substring(0, c), v = line.substring(c + 2);
                    if (k.equals("command")) { arg = "$ " + v; break; }
                    if (k.equals("filePath") || k.equals("file_path") || k.equals("path")
                            || k.equals("pattern") || k.equals("query") || k.equals("url")) {
                        arg = v; break;
                    }
                }
            }
        }
        if (arg == null || arg.isEmpty()) arg = "";
        else arg = " · " + shorten(arg, 90);
        return r.tool + arg;
    }

    // -------------------------------------------------------------- colors

    private static final int GREEN = 0xFF3FB950;
    private static final int RED = 0xFFF85149;
    private static final int BLUE = 0xFF58A6FF;

    /** Colorize unified-diff output: +green −red @@blue. */
    private CharSequence diffify(String out) {
        if (out.indexOf('\n') < 0 && !out.startsWith("+") && !out.startsWith("-")) {
            return out;
        }
        SpannableStringBuilder b = new SpannableStringBuilder();
        String[] lines = out.split("(?=\n)");
        int start = 0;
        for (String line : lines) {
            int color = 0;
            String t = line.trim();
            if (t.startsWith("+++") || t.startsWith("---") || t.startsWith("diff")
                    || t.startsWith("index ")) color = BLUE;
            else if (t.startsWith("+")) color = GREEN;
            else if (t.startsWith("-")) color = RED;
            else if (t.startsWith("@@")) color = BLUE;
            b.append(line);
            if (color != 0) {
                b.setSpan(new ForegroundColorSpan(color), start, b.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            start = b.length();
        }
        return b;
    }

    // ------------------------------------------------------------- adapter

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return tr == null ? 0 : tr.rows.size(); }
        @Override public Object getItem(int i) { return tr.rows.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public int getViewTypeCount() { return 6; }
        @Override public int getItemViewType(int i) { return tr.rows.get(i).kind; }

        @Override
        public void notifyDataSetChanged() {
            super.notifyDataSetChanged();
            if (viewEmpty != null) {
                viewEmpty.setVisibility(getCount() == 0 ? View.VISIBLE : View.GONE);
            }
        }

        @Override
        public View getView(int i, View conv, ViewGroup parent) {
            Row m = tr.rows.get(i);
            switch (m.kind) {
                case K_USER:       return bindUser(conv, parent, m);
                case K_ASSISTANT:  return bindAssistant(conv, parent, m);
                case K_REASON:     return bindReason(conv, parent, m);
                case K_TOOL:       return bindTool(conv, parent, m);
                case K_SETUP:      return bindSetup(conv, parent, m);
                default:           return bindSystem(conv, parent, m);
            }
        }

        private View inflate(int res, View conv, ViewGroup parent) {
            return conv != null && conv.getTag() != null
                    && ((Integer) conv.getTag()) == res
                    ? conv
                    : LayoutInflater.from(ChatActivity.this).inflate(res, parent, false);
        }

        private View bindUser(View conv, ViewGroup parent, Row m) {
            View v = inflate(R.layout.item_msg, conv, parent);
            v.setTag(R.layout.item_msg);
            TextView tv = v.findViewById(R.id.msg);
            LinearLayout row = (LinearLayout) tv.getParent();
            tv.setText(m.text);
            tv.setBackgroundResource(R.drawable.bg_bubble_user);
            row.setGravity(Gravity.END);
            tv.setTypeface(Typeface.DEFAULT);
            tv.setTextAppearance(android.R.style.TextAppearance_Material_Small);
            return v;
        }

        private View bindSystem(View conv, ViewGroup parent, Row m) {
            View v = inflate(R.layout.item_msg, conv, parent);
            v.setTag(R.layout.item_msg);
            TextView tv = v.findViewById(R.id.msg);
            LinearLayout row = (LinearLayout) tv.getParent();
            tv.setText(m.text);
            tv.setBackgroundResource(R.drawable.bg_bubble_system);
            row.setGravity(Gravity.START);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setTextAppearance(android.R.style.TextAppearance_Material_Small);
            return v;
        }

        private View bindAssistant(View conv, ViewGroup parent, Row m) {
            View v = inflate(R.layout.row_assistant, conv, parent);
            v.setTag(R.layout.row_assistant);
            TextView tv = v.findViewById(R.id.msg);
            TextView meta = v.findViewById(R.id.tvMeta);
            LinearLayout root = (LinearLayout) v;
            tv.setText(Markdown.render(m.text.toString()));
            tv.setBackgroundResource(R.drawable.bg_bubble_assistant);
            root.setGravity(Gravity.START);
            tv.setTypeface(Typeface.DEFAULT);
            tv.setTextAppearance(android.R.style.TextAppearance_Material_Medium);
            String metaS = m.msgId == null ? null : tr.metaByMsg.get(m.msgId);
            if (metaS == null) {
                meta.setVisibility(View.GONE);
            } else {
                meta.setVisibility(View.VISIBLE);
                meta.setText(metaS);
            }
            return v;
        }

        private View bindReason(View conv, ViewGroup parent, Row m) {
            View v = inflate(R.layout.row_reason, conv, parent);
            v.setTag(R.layout.row_reason);
            TextView chev = v.findViewById(R.id.tvChev);
            TextView body = v.findViewById(R.id.tvReason);
            boolean open = expanded.contains(m.partId);
            chev.setText(open ? "▾" : "▸");
            body.setVisibility(open ? View.VISIBLE : View.GONE);
            if (open) body.setText(m.text);
            v.setOnClickListener(l -> toggle(m.partId));
            return v;
        }

        private View bindTool(View conv, ViewGroup parent, Row m) {
            View v = inflate(R.layout.row_tool, conv, parent);
            v.setTag(R.layout.row_tool);
            TextView chev = v.findViewById(R.id.tvChev);
            TextView head = v.findViewById(R.id.tvTool);
            TextView dot = v.findViewById(R.id.tvDot);
            LinearLayout content = v.findViewById(R.id.toolContent);
            TextView tin = v.findViewById(R.id.tvToolInput);
            TextView tout = v.findViewById(R.id.tvToolOutput);

            boolean open = expanded.contains(m.partId);
            chev.setText(open ? "▾" : "▸");
            content.setVisibility(open ? View.VISIBLE : View.GONE);
            head.setText(toolHeaderText(m));

            int dotColor = R.color.text_secondary;
            String dotCh = "●";
            if (m.status.contains("error")) { dotColor = R.color.err; dotCh = "✕"; }
            else if (m.status.contains("complet")) { dotColor = R.color.ok; dotCh = "✓"; }
            else if (m.status.contains("run") || m.status.contains("pend")) { dotColor = R.color.warn; }
            dot.setText(dotCh);
            dot.setTextColor(getResources().getColor(dotColor));

            if (open) {
                if (m.input.length() > 0) {
                    tin.setVisibility(View.VISIBLE);
                    tin.setText(m.input);
                } else tin.setVisibility(View.GONE);
                if (m.output.length() > 0) {
                    tout.setVisibility(View.VISIBLE);
                    String out = m.output.toString();
                    if (m.tool.equals("edit") || m.tool.equals("write")
                            || m.tool.equals("patch")) {
                        tout.setText(diffify(out));
                    } else {
                        tout.setText(out);
                    }
                } else tout.setVisibility(View.GONE);
            }
            v.setOnClickListener(l -> toggle(m.partId));
            return v;
        }

        private View bindSetup(View conv, ViewGroup parent, Row m) {
            View v = inflate(R.layout.row_setup, conv, parent);
            v.setTag(R.layout.row_setup);
            TextView tv = v.findViewById(R.id.tvSetup);
            tv.setText("⚙  " + m.text + "\nTap to connect API keys & pick a model →");
            v.setOnClickListener(l -> startActivity(
                    new Intent(ChatActivity.this, ProviderSetupActivity.class)));
            return v;
        }

        private void toggle(String partId) {
            if (expanded.contains(partId)) expanded.remove(partId);
            else expanded.add(partId);
            notifyDataSetChanged();
        }
    }
}

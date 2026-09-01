package ai.opencode.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * P2 session browser, P5 upgrades: long-press → delete (DELETE /session/{id},
 * verified in the v1.18.25 binary as the session.delete feature) and relative
 * timestamps. Bug fix: opencode sends time.updated in SECONDS — v0.4.0 fed it
 * to Date() as milliseconds so every session showed "Jan 20, 1970".
 */
public class SessionsActivity extends Activity {

    private static final class Row {
        String id, title;
        double updated;
    }

    private final List<Row> rows = new ArrayList<>();
    private final Adapter adapter = new Adapter();
    private final ExecutorService ex = Executors.newSingleThreadExecutor();
    private TextView tvEmpty;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_sessions);

        ListView list = findViewById(R.id.list);
        list.setAdapter(adapter);
        tvEmpty = findViewById(R.id.tvEmpty);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnNew).setOnClickListener(v -> open(null));
        list.setOnItemClickListener((p, v, pos, id) -> {
            Row r = rows.get(pos);
            open(r.id);
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            confirmDelete(rows.get(pos));
            return true;
        });

        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void open(String sessionId) {
        Intent i = new Intent(this, ChatActivity.class);
        if (sessionId != null) i.putExtra("session_id", sessionId);
        startActivity(i);
    }

    // ------------------------------------------------------------- delete

    private void confirmDelete(Row r) {
        new AlertDialog.Builder(this)
                .setTitle("Delete session?")
                .setMessage(r.title + "\n\nits history will be removed from the server")
                .setPositiveButton("Delete", (d, w) -> delete(r))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void delete(Row r) {
        ex.execute(() -> {
            String err = null;
            try {
                Api.Resp resp = Api.call("DELETE", "/session/" + r.id, null, 15_000);
                if (!resp.ok()) err = "HTTP " + resp.status;
            } catch (Exception e) {
                err = e.getMessage();
            }
            final String failure = err;
            runOnUiThread(() -> {
                if (failure == null) {
                    ChatActivity.forgetSession(r.id);
                    Toast.makeText(this, "session deleted", Toast.LENGTH_SHORT).show();
                    refresh();
                } else {
                    Toast.makeText(this, "delete failed · " + failure,
                            Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    // ---------------------------------------------------------------- time

    /** "now", "5m", "2h", "3d", or a date — seconds→ms guard included. */
    private static String rel(double sec) {
        if (sec <= 0) return "—";
        long ms = sec < 1e12 ? (long) (sec * 1000) : (long) sec;
        long d = System.currentTimeMillis() - ms;
        if (d < 60_000L) return "now";
        if (d < 3_600_000L) return (d / 60_000L) + "m ago";
        if (d < 86_400_000L) return (d / 3_600_000L) + "h ago";
        if (d < 7 * 86_400_000L) return (d / 86_400_000L) + "d ago";
        return new SimpleDateFormat("MMM d", Locale.getDefault()).format(new Date(ms));
    }

    private void refresh() {
        ex.execute(() -> {
            List<Row> next = new ArrayList<>();
            try {
                Api.Resp r = Api.get("/session");
                List<Object> arr = Json.arr(Json.parse(r.body));
                if (arr != null) {
                    for (Object o : arr) {
                        Map<String, Object> m = Json.obj(o);
                        if (m == null) continue;
                        Row row = new Row();
                        row.id = Json.str(m, "id");
                        String t = Json.str(m, "title");
                        row.title = (t == null || t.isEmpty()) ? "(untitled)" : t;
                        Map<String, Object> time = Json.map(m, "time");
                        row.updated = time != null && time.get("updated") instanceof Double
                                ? (Double) time.get("updated") : 0;
                        if (row.id != null) next.add(row);
                    }
                }
            } catch (Exception ignored) {}
            runOnUiThread(() -> {
                rows.clear();
                rows.addAll(next);
                adapter.notifyDataSetChanged();
                tvEmpty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int i) { return rows.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View conv, ViewGroup parent) {
            View v = conv;
            if (v == null) {
                v = LayoutInflater.from(SessionsActivity.this)
                        .inflate(R.layout.item_session, parent, false);
            }
            Row r = rows.get(i);
            TextView title = v.findViewById(R.id.sTitle);
            TextView sub = v.findViewById(R.id.sSub);
            title.setText(r.title);
            String shortId = r.id.length() > 8 ? r.id.substring(0, 8) : r.id;
            sub.setText(rel(r.updated) + "  ·  " + shortId);
            return v;
        }
    }
}

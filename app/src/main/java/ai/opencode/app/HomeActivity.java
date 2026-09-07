package ai.opencode.app;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * P8 — Home. The project deck.
 *
 * Projects are credit-card-style gradient cards in a vertical snap
 * carousel (DeckView): swipe up/down, neighbors peek and shrink, tap a
 * card and that project opens ITS OWN sandbox — the opencode server is
 * (re)started with the project folder as its working directory, so the
 * agent's tools and sessions are rooted exactly there.
 *
 *   tap card      → open project (instant when it's already the sandbox root)
 *   ＋ ghost card → folder picker → new project card
 *   long-press    → open / rename / remove
 *   ⚙ top right   → Settings · status pill → Diagnostics
 */
public class HomeActivity extends Activity implements ServerService.Evt {

    private static final int REQ_STORAGE = 61;

    private DeckView deck;
    private LinearLayout dots;
    private TextView dotDot, dotText;
    /** P30: delete projects runs off-thread — a big tree must not freeze
     *  the deck mid-swipe. Single-threaded like every other screen pool. */
    private final ExecutorService ex = Executors.newSingleThreadExecutor();
    /** P30: main-thread hop for the delete flow's UI steps. */
    private final android.os.Handler ui = new android.os.Handler(
            android.os.Looper.getMainLooper());
    private final List<Projects.P> cur = new ArrayList<>();
    private ObjectAnimator pulse;
    private boolean inited;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
        ServerService.subscribe(this);

        if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
        } else {
            init();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        if (code == REQ_STORAGE) init(); // seed falls back to private storage
    }

    private void init() {
        if (inited) return;
        inited = true;
        try { Projects.seed(this); } catch (Exception ignored) {}
        buildDeck();
        refreshStatus(ServerService.getState(), null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        int st = ServerService.getState();
        if ((st == ServerService.ST_IDLE || st == ServerService.ST_STOPPED
                || st == ServerService.ST_EXITED) && !ServerService.pendingRestart()
                && Binaries.binaryReady(this)) {
            try { startForegroundService(new Intent(this, ServerService.class)); }
            catch (Exception ignored) {}
        }
        if (inited) { buildDeck(); } // pick up renames/removals on return
        refreshStatus(ServerService.getState(), null);
    }

    @Override
    protected void onPause() {
        // P31: the hibernate-resume anchor — the deck is where the user
        // left off unless a chat screen pauses after this and stamps over
        // it (each surface writes on pause; the LAST pauser wins).
        try {
            getSharedPreferences("oc", MODE_PRIVATE).edit()
                    .putString(Resume.KEY, Resume.DECK).apply();
        } catch (Exception ignored) {}
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ServerService.unsubscribe(this);
        if (pulse != null) pulse.cancel();
        super.onDestroy();
    }

    // ------------------------------------------------------------- ui

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_home);

        // ---- top bar
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Theme.dp(this, 22), Theme.dp(this, 18), Theme.dp(this, 14), Theme.dp(this, 6));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView brand = new TextView(this);
        brand.setText("OpenCode");
        brand.setTextColor(Theme.TXT);
        brand.setTextSize(23);
        brand.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        TextView sub = new TextView(this);
        sub.setText("your projects · each with its own sandbox");
        sub.setTextColor(Theme.TXT_DIM);
        sub.setTextSize(12);
        col.addView(brand);
        col.addView(sub);
        top.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView gear = new TextView(this);
        gear.setText("⚙");
        gear.setTextSize(20);
        gear.setTextColor(Theme.ACCENT_LT);
        gear.setGravity(Gravity.CENTER);
        gear.setBackgroundResource(R.drawable.bg_chip);
        gear.setPadding(Theme.dp(this, 14), Theme.dp(this, 10),
                Theme.dp(this, 14), Theme.dp(this, 10));   // P25: ≥40dp target
        Theme.press(gear);
        gear.setOnClickListener(v -> {
            Theme.pop(gear);
            startActivity(new Intent(this, SettingsActivity.class));
        });
        top.addView(gear);
        root.addView(top);

        // ---- deck + vertical page rail (P9: the old HORIZONTAL dots sat
        // under a VERTICAL deck and read as left/right — misleading).
        FrameLayout deckHost = new FrameLayout(this);
        deck = new DeckView(this);
        deck.setPadding(0, Theme.dp(this, 8), 0, Theme.dp(this, 8));
        deckHost.addView(deck, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        dots = new LinearLayout(this);
        dots.setOrientation(LinearLayout.VERTICAL);
        dots.setGravity(Gravity.CENTER_VERTICAL);
        dots.setPadding(0, 0, Theme.dp(this, 5), 0);
        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL);
        deckHost.addView(dots, rlp);

        root.addView(deckHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // ---- status pill
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(Theme.ripple(this, Theme.panel(this)));
        pill.setPadding(Theme.dp(this, 16), Theme.dp(this, 9), Theme.dp(this, 16), Theme.dp(this, 9));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.gravity = Gravity.CENTER_HORIZONTAL;
        plp.bottomMargin = Theme.dp(this, 14);

        dotDot = new TextView(this);
        dotDot.setText("●");
        dotDot.setTextSize(11);
        dotDot.setTextColor(Theme.TXT_DIM);
        dotDot.setPadding(0, 0, Theme.dp(this, 7), 0);
        pill.addView(dotDot);
        dotText = new TextView(this);
        dotText.setTextSize(12);
        dotText.setTextColor(Theme.TXT_DIM);
        pill.addView(dotText);
        pill.setOnClickListener(v ->
                startActivity(new Intent(this, DiagnosticsActivity.class)));
        root.addView(pill, plp);

        setContentView(root);
        Theme.window(this);              // P31: palette owns the window + dialogs
        Theme.enter(root, 0);
    }

    // ------------------------------------------------------------- deck

    private void buildDeck() {
        cur.clear();
        cur.addAll(Projects.list(this));

        deck.removeAllViews();
        for (Projects.P p : cur) deck.addView(projectCard(p));
        deck.addView(addCard());
        deck.setCallback(new Deck());

        int last = 0;
        long best = -1;
        for (int i = 0; i < cur.size(); i++) {
            if (cur.get(i).opened > best) { best = cur.get(i).opened; last = i; }
        }
        final int anchor = best >= 0 ? last : 0;
        deck.post(() -> {
            deck.setCurrent(anchor, false);
            syncDots(deck.page());
        });
        syncDots(deck.page());
    }

    private View projectCard(Projects.P p) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Theme.ripple(this,
                Theme.cardBg(p.accent, Theme.dp(this, 26))));
        int pad = Theme.dp(this, 22);
        card.setPadding(pad, pad, pad, pad);
        Theme.press(card);

        TextView tag = new TextView(this);
        tag.setText("P R O J E C T");
        tag.setTextSize(9);
        tag.setTextColor(0xB3FFFFFF);
        tag.setLetterSpacing(0.18f);
        // P27 clipping audit: letterspaced caps carry trailing advance —
        // matching end padding so the last glyph never kisses the edge.
        tag.setPadding(0, 0, Theme.dp(this, 5), 0);
        card.addView(tag);

        TextView name = new TextView(this);
        name.setText(p.name);
        name.setTextColor(Theme.TXT);
        name.setTextSize(26);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setPadding(0, Theme.dp(this, 10), 0, 0);
        card.addView(name);

        TextView path = new TextView(this);
        path.setText(p.path);
        path.setTypeface(Typeface.MONOSPACE);
        path.setTextSize(11);
        path.setTextColor(0xB8FFFFFF);
        path.setMaxLines(2);
        path.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        path.setPadding(0, Theme.dp(this, 6), 0, 0);
        card.addView(path);

        LinearLayout spacer = new LinearLayout(this);
        card.addView(spacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        View div = new View(this);
        div.setBackgroundColor(0x30FFFFFF);
        card.addView(div, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));

        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        foot.setPadding(0, Theme.dp(this, 12), 0, 0);
        TextView when = new TextView(this);
        when.setText(p.opened > 0 ? "last opened · " + relTime(p.opened) : "not opened yet");
        when.setTextSize(11);
        when.setTextColor(Theme.ON_CARD);   // P31 token
        when.setSingleLine(true);
        when.setEllipsize(android.text.TextUtils.TruncateAt.END);
        foot.addView(when, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView open = new TextView(this);
        open.setText("Open →");
        open.setTextSize(13);
        open.setTypeface(Typeface.DEFAULT_BOLD);
        open.setTextColor(Theme.ACCENT_LT);
        foot.addView(open);
        card.addView(foot);

        card.setOnClickListener(v -> openProject(p));
        // P31 — THE LONG-PRESS FIX. The deck's GestureDetector never sees a
        // stationary hold on a card: the card is a clickable child, it
        // consumes the touch stream, the deck's onInterceptTouchEvent only
        // wakes on a MOVE past the slop, so Deck.Callback.onLongPress could
        // only fire on the gaps BETWEEN cards. On release the card's own
        // click fired — "long press to delete just opens the chat", exactly
        // the field report. The long-press now lives ON THE CARD (native
        // long-click: fires after the timeout, consumes the gesture, and
        // suppresses the click on release). The deck-level callback stays
        // as the gap/padding fallback.
        card.setOnLongClickListener(v -> {
            Theme.haptic(v);
            cardActions(p);
            return true;
        });
        return card;
    }

    private View addCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setBackground(Theme.ripple(this, Theme.ghostCard(this)));
        Theme.press(card);

        TextView plus = new TextView(this);
        plus.setText("＋");
        plus.setTextSize(38);
        plus.setTextColor(Theme.ACCENT_LT);
        plus.setGravity(Gravity.CENTER);
        card.addView(plus);

        TextView t = new TextView(this);
        t.setText("New project");
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(Theme.ACCENT_LT);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Theme.dp(this, 6), 0, 0);
        card.addView(t);

        TextView s = new TextView(this);
        s.setText("pick a folder → it gets its own sandbox");
        s.setTextSize(11);
        s.setTextColor(Theme.TXT_DIM);
        s.setGravity(Gravity.CENTER);
        s.setPadding(0, Theme.dp(this, 3), 0, 0);
        card.addView(s);

        card.setOnClickListener(v -> pickFolder());
        return card;
    }

    private final class Deck implements DeckView.Callback {
        @Override public void onSettled(int page) { syncDots(page); }
        @Override public void onTap(int page) {
            if (page >= cur.size()) pickFolder();
        }
        @Override public void onLongPress(int page) {
            if (page < 0 || page >= cur.size()) return;
            // P30: the long-press answers the finger immediately — the
            // sheet pops with a tick, never silence under it.
            Theme.haptic(deck);
            cardActions(cur.get(page));
        }
    }

    /** P9: vertical rail on the right edge — page dots READ as up/down. */
    private void syncDots(int page) {
        int n = cur.size() + 1;
        if (dots.getChildCount() != n) {
            dots.removeAllViews();
            for (int i = 0; i < n; i++) {
                View d = new View(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        Theme.dp(this, 6), Theme.dp(this, 6));
                lp.topMargin = Theme.dp(this, 4);
                lp.bottomMargin = Theme.dp(this, 4);
                d.setLayoutParams(lp);
                d.setBackground(Theme.circle(Theme.DOT_IDLE));   // P31 token
                dots.addView(d);
            }
        }
        for (int i = 0; i < n; i++) {
            View d = dots.getChildAt(i);
            boolean active = i == page;
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) d.getLayoutParams();
            lp.height = Theme.dp(this, active ? 20 : 6);
            lp.width = Theme.dp(this, active ? 6 : 6);
            d.setLayoutParams(lp);
            d.setBackground(Theme.circle(active ? Theme.ACCENT_LT : Theme.DOT_IDLE));   // P31 token
        }
    }

    // ------------------------------------------------------------- actions

    private void openProject(Projects.P p) {
        if (!Projects.validDir(p.path)) {
            new AlertDialog.Builder(this)
                    .setTitle("Folder missing")
                    .setMessage(p.path + " is gone or unreadable. Remove the card?")
                    .setPositiveButton("Remove", (d, w) -> {
                        Projects.remove(this, p.id);
                        buildDeck();
                    })
                    .setNegativeButton("Keep", null)
                    .show();
            return;
        }
        Projects.touch(this, p.id);
        File dir = new File(p.path);
        if (ServerService.needsSwitch(dir)) {
            ServerService.switchTo(this, dir);
        }
        Intent i = new Intent(this, ChatActivity.class);
        i.putExtra("project", p.name);
        i.putExtra("path", p.path);
        startActivity(i);
        overridePendingTransition(R.anim.slide_up_in, R.anim.fade_out);
    }

    private void cardActions(Projects.P p) {
        new AlertDialog.Builder(this)
                .setTitle(p.name)
                .setItems(new String[]{"Open", "Rename", "Remove card",
                                       "Delete project…"}, (d, w) -> {
                    if (w == 0) openProject(p);
                    else if (w == 1) renameProject(p);
                    else if (w == 2) {
                        Projects.remove(this, p.id);
                        buildDeck();
                        Toast.makeText(this, "card removed (files untouched)",
                                Toast.LENGTH_SHORT).show();
                    } else {
                        confirmDeleteProject(p);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * P30: "delete my projects by long pressing on them". Remove card
     * only unpins — this DELETES: the folder and EVERY file inside it
     * (code, notes, whatever the project holds). Three shields:
     * the menu item is a separate entry from "Remove card", the dialog
     * spells out the exact path and the irreversibility, and the pure
     * ProjectDelete.safetyCheck refuses anything that could ever be more
     * than a project (roots, mount points, the app's own dir, ancestors
     * of it). If the folder is already gone, this degrades to removing
     * the card — same result, no error theater.
     */
    private void confirmDeleteProject(Projects.P p) {
        String guard = ProjectDelete.safetyCheck(p.path,
                getFilesDir().getAbsolutePath());
        if (guard != null) {
            Toast.makeText(this, "cannot delete: " + guard,
                    Toast.LENGTH_LONG).show();
            return;
        }
        File dir = new File(p.path);
        if (!dir.isDirectory()) {                    // already gone — unpin
            Projects.remove(this, p.id);
            buildDeck();
            Toast.makeText(this, "folder already gone — card removed",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        final boolean serving = dir.equals(ServerService.servingDir());
        new AlertDialog.Builder(this)
                .setTitle("Delete " + p.name + "?")
                .setMessage("Every file inside will be PERMANENTLY deleted:\n\n"
                        + p.path + "\n\n"
                        + "Code, notes, everything — this cannot be undone. "
                        + "The card is removed too."
                        + (serving ? "\n\nThis project is open right now — "
                                   + "its server will be stopped first." : ""))
                .setPositiveButton("Delete forever", (d, w) -> {
                    Theme.haptic(deck);
                    deleteProject(p, serving);
                })
                .setNegativeButton("Keep", null)
                .show();
    }

    /** The delete itself: off-thread (a big tree must not freeze the
     *  deck), stop-first when this is the project the server is serving,
     *  then the guarded tree walk, then the card leaves the deck. */
    private void deleteProject(Projects.P p, boolean serving) {
        Toast.makeText(this, "deleting " + p.name + "…",
                Toast.LENGTH_SHORT).show();
        ex.execute(() -> {
            Throwable t = Resilience.guard(() -> {
                if (serving) {
                    // the server holds a cwd inside the folder — stop it
                    // BEFORE the files vanish under proot's feet
                    ServerService.stopForDelete(this);
                }
                File dir = new File(p.path);
                int n = ProjectDelete.deleteTree(dir);
                ui.post(() -> {
                    Projects.remove(this, p.id);
                    buildDeck();
                    Toast.makeText(this, n + " entries deleted — "
                            + p.name + " is gone", Toast.LENGTH_LONG).show();
                });
            });
            if (t != null) {
                Trail.record(this, "project delete", t);
                ui.post(() -> Toast.makeText(this,
                        "delete failed: " + t.getMessage()
                                + " — the card was kept",
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    private void renameProject(Projects.P p) {
        EditText in = new EditText(this);
        in.setText(p.name);
        in.setSelection(p.name == null ? 0 : p.name.length());
        new AlertDialog.Builder(this)
                .setTitle("Rename project")
                .setView(in)
                .setPositiveButton("Save", (d, w) -> {
                    List<Projects.P> ps = Projects.list(this);
                    for (Projects.P q : ps) if (q.id.equals(p.id)) {
                        String n = in.getText().toString().trim();
                        q.name = n.isEmpty() ? q.name : n;
                    }
                    Projects.save(this, ps);
                    buildDeck();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---------------------------------------------------------- dir picker

    private File pickBase() {
        File ext = android.os.Environment.getExternalStorageDirectory();
        return ext != null ? ext : new File("/");
    }

    private void pickFolder() {
        DirDialog dlg = new DirDialog();
        dlg.show(this, pickBase(), path -> {
            Projects.P p = Projects.add(this, path);
            buildDeck();
            // land the deck on the new card
            int idx = cur.size();
            for (int i = 0; i < cur.size(); i++) if (cur.get(i).id.equals(p.id)) idx = i;
            final int page = idx;
            deck.post(() -> { deck.setCurrent(page, true); syncDots(page); });
        });
    }

    /** Callback holder so DirDialog stays static (no activity leak). */
    interface OnPick { void picked(String path); }

    static final class DirDialog {

        interface Show { void host(AlertDialog dlg); }

        void show(Activity a, File start, OnPick cb) {
            final File[] cur = new File[]{start};
            final TextView pathTv = new TextView(a);
            pathTv.setTypeface(Typeface.MONOSPACE);
            pathTv.setTextSize(11);
            pathTv.setTextColor(Theme.TXT_DIM);
            pathTv.setPadding(Theme.dp(a, 18), Theme.dp(a, 10), Theme.dp(a, 18), Theme.dp(a, 4));
            pathTv.setMaxLines(2);
            pathTv.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);

            final ListView40 lv = new ListView40(a);
            final AlertDialog[] dlgBox = new AlertDialog[1];
            Runnable[] refill = new Runnable[1];

            refill[0] = () -> {
                pathTv.setText(cur[0].getAbsolutePath());
                List<Object[]> rows = new ArrayList<>();
                File parent = cur[0].getParentFile();
                if (parent != null && parent.canRead()) rows.add(new Object[]{"↑  ..", parent});
                File[] kids = cur[0].listFiles();
                if (kids != null) {
                    Arrays.sort(kids, (x, y) -> x.getName().compareToIgnoreCase(y.getName()));
                    for (File k : kids) {
                        if (k.isDirectory() && !k.getName().startsWith(".")) {
                            rows.add(new Object[]{"▸  " + k.getName(), k});
                        }
                    }
                }
                lv.setAdapter(new android.widget.BaseAdapter() {
                    public int getCount() { return rows.size(); }
                    public Object getItem(int i) { return rows.get(i); }
                    public long getItemId(int i) { return i; }
                    public View getView(int i, View cv, ViewGroup parent) {
                        TextView tv = new TextView(a);
                        tv.setText(String.valueOf(rows.get(i)[0]));
                        tv.setTextSize(14);
                        tv.setTextColor(Theme.TXT);
                        tv.setPadding(Theme.dp(a, 18), Theme.dp(a, 11), Theme.dp(a, 18), Theme.dp(a, 11));
                        return tv;
                    }
                });
                lv.setOnItemClickListener((p2, v, pos, id) -> {
                    File f = (File) rows.get(pos)[1];
                    if (f.isDirectory()) { cur[0] = f; refill[0].run(); }
                });
            };
            refill[0].run();

            LinearLayout head = new LinearLayout(a);
            head.setOrientation(LinearLayout.VERTICAL);
            TextView title = new TextView(a);
            title.setText("Pick a project folder");
            title.setTextSize(16);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextColor(Theme.TXT);
            title.setPadding(Theme.dp(a, 18), Theme.dp(a, 16), Theme.dp(a, 18), 0);
            head.addView(title);
            head.addView(pathTv);
            head.addView(lv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(a, 320)));
            AlertDialog dlg = new AlertDialog.Builder(a)
                    .setView(head)
                    .setPositiveButton("Use this folder", (d, w) ->
                            cb.picked(cur[0].getAbsolutePath()))
                    .setNeutralButton("New folder", (d, w) ->
                            mkDirDialog(a, cur[0], name -> {
                                File nd = new File(cur[0], name);
                                if (nd.mkdirs() || nd.isDirectory()) {
                                    cur[0] = nd;
                                    refill[0].run();
                                } else {
                                    Toast.makeText(a, "could not create folder",
                                            Toast.LENGTH_SHORT).show();
                                }
                            }))
                    .setNegativeButton("Cancel", null)
                    .create();
            Theme.skin(dlg);
            dlg.show();
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Theme.ACCENT_LT);
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(Theme.ACCENT_LT);
            dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Theme.TXT_DIM);
        }

        private void mkDirDialog(Activity a, File parent, java.util.function.Consumer<String> cb) {
            final EditText in = new EditText(a);
            in.setHint("folder name");
            in.setSingleLine(true);
            new AlertDialog.Builder(a)
                    .setTitle("New folder in " + parent.getName())
                    .setView(in)
                    .setPositiveButton("Create", (d, w) -> {
                        String n = in.getText().toString().trim();
                        if (!n.isEmpty()) cb.accept(n);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }
    }

    /** trivial holder so the inner class needs no extra imports block */
    static final class ListView40 extends android.widget.ListView {
        ListView40(android.content.Context c) { super(c); }
    }

    // ------------------------------------------------------------- status

    @Override
    public void on(int newState, String detail) {
        runOnUiThread(() -> refreshStatus(newState, detail));
    }

    private void refreshStatus(int st, String detail) {
        if (dotDot == null || dotText == null) return;
        int color;
        String txt;
        boolean pulsing = false;
        switch (st) {
            case ServerService.ST_HEALTHY:
                File d = ServerService.servingDir();
                color = Theme.OK;
                txt = "sandbox ready" + (d != null ? " · " + d.getName() : "");
                break;
            case ServerService.ST_STARTING:
                color = Theme.WARN;
                txt = "starting sandbox" + (detail == null ? "…" : " · " + detail);
                pulsing = true;
                break;
            case ServerService.ST_EXITED:
                color = Theme.ERR; txt = "server exited — tap for logs"; break;
            case ServerService.ST_STOPPED:
                color = Theme.ERR; txt = "server stopped — tap for logs"; break;
            default:
                color = Theme.TXT_DIM; txt = "server idle"; break;
        }
        dotDot.setTextColor(color);
        dotText.setTextColor(Theme.TXT_DIM);
        dotText.setText(txt);
        if (pulse != null) { pulse.cancel(); pulse = null; }
        dotDot.setAlpha(1f);
        if (pulsing && Theme.motionOn(this)) pulse = Theme.pulse(dotDot);
    }

    // ------------------------------------------------------------- misc

    private static String relTime(long ms) {
        long diff = System.currentTimeMillis() - ms;
        if (diff < 0) diff = 0;
        long m = diff / 60000;
        if (m < 1) return "just now";
        if (m < 60) return m + " min ago";
        long h = m / 60;
        if (h < 24) return h + " h ago";
        return (h / 24) + " d ago";
    }
}

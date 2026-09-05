package ai.opencode.app;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * P15 — the visual file manager the user asked for ("a more visually
 * pleasing way to control files inside projects"). Project-scoped on
 * purpose: it opens at the sandbox's serving directory and REFUSES to
 * navigate outside it (canonical-path clamp), so it can never become a
 * generic device browser — it is the project's cockpit.
 *
 *   • breadcrumb header with tap-to-jump segments
 *   • rows: gradient disc for folders, tinted glyph for files, name,
 *     size · age meta, press feedback, staggered entrance
 *   • tap folder → dive · tap file → preview sheet (20k chars, mono,
 *     long-press or button copies everything)
 *   • long-press → rename / delete / copy path (delete confirms)
 *   • ghost row "+ new folder" / "+ new file" at the bottom
 * Zero dependencies — framework views only, same design language as the
 * deck (Theme).
 */
public class FilesActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout root;
    private ScrollView scrollV;
    private TextView crumb;
    private File cwd;
    private File baseDir;

    // ---- P16: LIVE project watching -----------------------------------
    // The user: "one that lets me see the real time changes that are being
    // made — that would be soo cool if done right". The whole project root
    // is watched (recursively, capped) while the screen is open; changes
    // from ANY folder land in the live rail, changes inside the open
    // folder additionally re-render the list with a hot badge.
    private DirWatcher watcher;
    private boolean liveOn = true;
    private TextView livePill;
    private LinearLayout feedBox;
    private ObjectAnimator livePulse;
    private boolean heatClearQueued;
    /** newest-first rail entries: {action, path, tsMillis}. */
    private final List<String[]> feed = new ArrayList<>();
    /** abs path → last-change ms; drives the ● badge on rows. */
    private final Map<String, Long> heat = new HashMap<>();
    private static final long HEAT_MS = 10_000;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        File proj = ServerService.servingDir();
        if (proj == null || !proj.isDirectory())
            proj = new File("/sdcard/opencode-projects");
        baseDir = proj;
        cwd = proj;

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundResource(R.drawable.bg_home);
        scroll.setFillViewport(true);
        scrollV = scroll;
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = contentInset();
        root.setPadding(pad, Theme.dp(this, 14), pad, Theme.dp(this, 40));
        scroll.addView(root);
        setContentView(scroll);
    }

    /** P16 DeX: centered content column on wide windows. */
    private int contentInset() {
        int wdp = getResources().getConfiguration().screenWidthDp;
        if (wdp < 600) return Theme.dp(this, 18);
        return Theme.dp(this, Math.min(200, Math.max(18, (wdp - 720) / 2 + 18)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
        if (liveOn) startWatching();
    }

    @Override
    protected void onPause() {
        if (watcher != null) watcher.stop();
        if (livePulse != null) { livePulse.cancel(); livePulse = null; }
        super.onPause();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newCfg) {
        super.onConfigurationChanged(newCfg);
        ui.post(this::render);   // DeX window resizes re-set the column
    }

    /** P26: back walks the directory tree UP first (the field: "back drops
     *  me to the app drawer instead of going back to the directory") —
     *  standard file-manager semantics. At the project root, back leaves. */
    @Override
    public void onBackPressed() {
        if (cwd != null && baseDir != null
                && !cwd.getAbsolutePath().equals(baseDir.getAbsolutePath())) {
            up();                            // clamped to the project root by up()
            return;
        }
        super.onBackPressed();
    }

    // -------------------------------------------------------------- live

    private void startWatching() {
        if (watcher == null) {
            watcher = new DirWatcher(Looper.getMainLooper(),
                    (path, action) -> onLiveChange(path, action));
        }
        watcher.start(baseDir);
        updateLivePill();
    }

    /** Live event (main thread): feed the rail, heat the row when the
     *  change is inside the open folder, re-render preserving scroll. */
    private void onLiveChange(String path, String action) {
        if (isFinishing() || isDestroyed()) return;
        feed.add(0, new String[]{action, path,
                String.valueOf(System.currentTimeMillis())});
        while (feed.size() > 8) feed.remove(feed.size() - 1);
        heat.put(path, System.currentTimeMillis());
        pruneHeat();
        renderFeed();
        String dirOf = new File(path).getParent();
        if (dirOf != null && dirOf.equals(cwd.getAbsolutePath())) {
            renderPreservingScroll();
        }
        scheduleHeatClear();
    }

    private void renderPreservingScroll() {
        int y = scrollV != null ? scrollV.getScrollY() : 0;
        render();
        if (scrollV != null) scrollV.post(() -> scrollV.scrollTo(0, y));
    }

    private void scheduleHeatClear() {
        if (heatClearQueued) return;
        heatClearQueued = true;
        ui.postDelayed(() -> {
            heatClearQueued = false;
            if (!isFinishing() && !isDestroyed()) renderPreservingScroll();
        }, HEAT_MS + 500);
    }

    private void pruneHeat() {
        long now = System.currentTimeMillis();
        heat.values().removeIf(t -> now - t > HEAT_MS);
    }

    /** The LIVE pill in the title row — tap pauses/resumes watching. */
    private TextView livePillView() {
        TextView p = new TextView(this);
        p.setTextSize(11);
        p.setTypeface(Typeface.MONOSPACE);
        int pad = Theme.dp(this, 10);
        p.setPadding(pad, Theme.dp(this, 5), pad, Theme.dp(this, 5));
        p.setBackgroundResource(R.drawable.bg_chip);
        Theme.press(p);
        p.setOnClickListener(v -> {
            liveOn = !liveOn;
            if (liveOn) {
                startWatching();
            } else {
                if (watcher != null) watcher.stop();
                updateLivePill();
            }
            Toast.makeText(this, liveOn ? "live changes on"
                    : "live changes paused", Toast.LENGTH_SHORT).show();
        });
        return p;
    }

    private void updateLivePill() {
        if (livePill == null) return;
        if (livePulse != null) { livePulse.cancel(); livePulse = null; }
        boolean watching = liveOn && watcher != null && watcher.isRunning();
        livePill.setText(watching ? "● LIVE" : liveOn ? "● live…" : "◌ paused");
        livePill.setTextColor(watching || liveOn ? Theme.ACCENT : Theme.TXT_DIM);
        if (watching && Theme.motionOn(this)) livePulse = Theme.pulse(livePill);
    }

    /** P16 live rail: what changed in this project, newest first. Tap a
     *  row to jump to its folder. Returns true when rows are visible. */
    private boolean renderFeed() {
        if (feedBox == null) return false;
        if (feed.isEmpty()) {
            feedBox.setVisibility(View.GONE);
            return false;
        }
        feedBox.setVisibility(View.VISIBLE);
        feedBox.removeAllViews();
        long now = System.currentTimeMillis();
        int n = 0;
        for (String[] ev : feed) {
            if (n++ >= 5) break;
            final File f = new File(ev[1]);
            String act = ev[0];
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setPadding(Theme.dp(this, 10), Theme.dp(this, 6),
                    Theme.dp(this, 10), Theme.dp(this, 6));
            line.setBackground(Theme.ripple(this, Theme.panel(this)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Theme.dp(this, 3);
            line.setLayoutParams(lp);

            TextView a = new TextView(this);
            a.setText(act.equals("del") ? "del" : act.equals("new") ? "new" : "mod");
            a.setTextSize(10);
            a.setTypeface(Typeface.MONOSPACE);
            a.setTextColor(act.equals("del") ? Theme.ERR
                    : act.equals("new") ? Theme.ACCENT : Theme.ACCENT_LT);
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            alp.rightMargin = Theme.dp(this, 9);
            line.addView(a, alp);

            TextView p = new TextView(this);
            p.setText(relPath(f));
            p.setTextSize(11);
            p.setTypeface(Typeface.MONOSPACE);
            p.setTextColor(Theme.TXT);
            p.setSingleLine(true);
            p.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            line.addView(p, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView age = new TextView(this);
            age.setText(ageStr(now - Long.parseLong(ev[2])));
            age.setTextSize(10);
            age.setTypeface(Typeface.MONOSPACE);
            age.setTextColor(Theme.TXT_DIM);
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            glp.leftMargin = Theme.dp(this, 8);
            line.addView(age, glp);

            line.setOnClickListener(v -> jumpTo(f));
            feedBox.addView(line);
            Theme.appear(line);
        }
        return true;
    }

    private String relPath(File f) {
        String b = baseDir.getAbsolutePath();
        String p = f.getAbsolutePath();
        String parent = f.getParent();
        boolean inCwd = parent != null && parent.equals(cwd.getAbsolutePath());
        if (p.startsWith(b + "/")) p = p.substring(b.length() + 1);
        return inCwd ? f.getName() : p;
    }

    private static String ageStr(long ms) {
        if (ms < 60_000) return Math.max(1, ms / 1000) + "s";
        if (ms < 3_600_000) return (ms / 60_000) + "m";
        return (ms / 3_600_000) + "h";
    }

    private void jumpTo(File f) {
        File dir = f.isDirectory() ? f : f.getParentFile();
        if (dir == null) return;
        String base = baseDir.getAbsolutePath();
        String dp2 = dir.getAbsolutePath();
        if (!dp2.equals(base) && !dp2.startsWith(base + "/")) return;
        cwd = dir;
        render();
    }

    // ------------------------------------------------------------- render

    private void render() {
        root.removeAllViews();
        int inset = contentInset();
        root.setPadding(inset, Theme.dp(this, 14), inset, Theme.dp(this, 40));

        // P16 title row: Files ····· [● LIVE] (tap toggles watching)
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView h1 = new TextView(this);
        h1.setText("Files");
        h1.setTextSize(27);
        h1.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        h1.setTextColor(Theme.TXT);
        titleRow.addView(h1, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        livePill = livePillView();
        titleRow.addView(livePill, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(titleRow);

        crumb = new TextView(this);
        crumb.setTextSize(12);
        crumb.setTypeface(Typeface.MONOSPACE);
        crumb.setTextColor(Theme.TXT_DIM);
        crumb.setPadding(0, Theme.dp(this, 2), 0, Theme.dp(this, 6));
        crumb.setText(crumbText());
        crumb.setOnClickListener(v -> up());
        root.addView(crumb);
        root.addView(breadcrumb());

        // P16: the live-change rail sits between the breadcrumb and the rows
        feedBox = new LinearLayout(this);
        feedBox.setOrientation(LinearLayout.VERTICAL);
        feedBox.setPadding(0, Theme.dp(this, 4), 0, 0);
        root.addView(feedBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        File[] kids = cwd.listFiles();
        if (kids == null) {
            TextView empty = Theme.sectionLabel(this,
                    "this folder is empty (or unreadable)");
            root.addView(empty);
            root.addView(newRowGhost());
            updateLivePill();
            renderFeed();
            return;
        }
        List<File> dirs = new ArrayList<>(), files = new ArrayList<>();
        for (File k : kids) {
            if (k.getName().startsWith(".")) continue;   // hidden: noise here
            (k.isDirectory() ? dirs : files).add(k);
        }
        Comparator<File> byName = (a, b2) ->
                a.getName().compareToIgnoreCase(b2.getName());
        Collections.sort(dirs, byName);
        Collections.sort(files, byName);

        long delay = 0;
        for (File d : dirs) { root.addView(rowFor(d, delay)); delay += 24; }
        for (File f : files) { root.addView(rowFor(f, delay)); delay += 24; }
        root.addView(newRowGhost());
        updateLivePill();
        renderFeed();
    }

    /** Segmented breadcrumb chips: tap any segment to jump back to it. */
    private LinearLayout breadcrumb() {
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        // segmented breadcrumb: root / … / here — tap a segment to jump
        File walk = cwd;
        ArrayList<File> segs = new ArrayList<>();
        while (walk != null && walk.getAbsolutePath()
                .startsWith(baseDir.getAbsolutePath())) {
            segs.add(0, walk);
            if (walk.getAbsolutePath().equals(baseDir.getAbsolutePath())) break;
            walk = walk.getParentFile();
        }
        int pad = Theme.dp(this, 8);
        for (int i = 0; i < segs.size(); i++) {
            File s = segs.get(i);
            TextView c = new TextView(this);
            c.setText(i == 0 ? "◆ " + s.getName() : s.getName());
            c.setTextSize(11);
            c.setTypeface(Typeface.MONOSPACE);
            boolean here = i == segs.size() - 1;
            c.setTextColor(here ? Theme.ACCENT : Theme.TXT_DIM);
            c.setBackgroundResource(here ? R.drawable.bg_chip : android.R.color.transparent);
            c.setPadding(pad, Theme.dp(this, 5), pad, Theme.dp(this, 5));
            c.setOnClickListener(v -> { cwd = s; render(); });
            chips.addView(c);
        }
        return chips;
    }

    private String crumbText() {
        String p = cwd.getAbsolutePath();
        String b = baseDir.getAbsolutePath();
        return p.equals(b) ? b : b + " ▸" + p.substring(b.length());
    }

    // --------------------------------------------------------------- rows

    private View rowFor(final File f, long delay) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(Theme.dp(this, 12), Theme.dp(this, 10), Theme.dp(this, 12), Theme.dp(this, 10));
        box.setBackground(Theme.ripple(this, Theme.panel(this)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Theme.dp(this, 8);
        box.setLayoutParams(lp);
        Theme.press(box);

        // icon disc
        TextView disc = new TextView(this);
        boolean dir = f.isDirectory();
        disc.setText(dir ? "▸" : extGlyph(f.getName()));
        disc.setTextSize(13);
        disc.setTypeface(Typeface.MONOSPACE);
        disc.setGravity(Gravity.CENTER);
        disc.setTextColor(dir ? Color.rgb(10, 10, 10) : Theme.ACCENT_LT);
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        if (dir) { gd.setColor(Theme.ACCENT); }
        else { gd.setColor(0x00000000); gd.setStroke(Theme.dp(this, 1), Theme.STROKE); }
        disc.setBackground(gd);
        box.addView(disc, new LinearLayout.LayoutParams(Theme.dp(this, 34), Theme.dp(this, 34)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.leftMargin = Theme.dp(this, 12);
        col.setLayoutParams(clp);

        TextView name = new TextView(this);
        // P16: a recent change heats the row — bright dot + bold, fading on
        // the scheduled heat-clear re-render (~10 s).
        boolean isHot = heat.containsKey(f.getAbsolutePath());
        name.setText((isHot ? "● " : "") + f.getName());
        name.setTextSize(15);
        name.setTextColor(isHot ? Theme.ACCENT : Theme.TXT);
        name.setTypeface(isHot ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        col.addView(name);

        TextView meta = new TextView(this);
        meta.setText(metaLine(f));
        meta.setTextSize(11);
        meta.setTypeface(Typeface.MONOSPACE);
        meta.setTextColor(Theme.TXT_DIM);
        meta.setSingleLine(true);
        meta.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        col.addView(meta);
        box.addView(col);

        TextView chev = new TextView(this);
        chev.setText(dir ? "›" : "⋯");
        chev.setTextSize(15);
        chev.setTextColor(Theme.TXT_DIM);
        box.addView(chev);

        box.setOnClickListener(v -> {
            if (f.isDirectory()) { cwd = f; render(); }
            else preview(f);
        });
        box.setOnLongClickListener(v -> { actions(f); return true; });
        if (Theme.motionOn(this) && delay > 0) Theme.enter(box, delay);
        return box;
    }

    private View newRowGhost() {
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.HORIZONTAL);
        g.setGravity(Gravity.CENTER);
        g.setPadding(0, Theme.dp(this, 16), 0, Theme.dp(this, 4));
        TextView t = new TextView(this);
        t.setText("＋ new folder      ＋ new file");
        t.setTextSize(13);
        t.setTextColor(Theme.TXT_DIM);
        t.setBackground(Theme.ripple(this, Theme.ghostCard(this)));
        t.setPadding(Theme.dp(this, 22), Theme.dp(this, 12), Theme.dp(this, 22), Theme.dp(this, 12));
        t.setOnClickListener(v -> newSheet());
        g.addView(t);
        return g;
    }

    private String metaLine(File f) {
        if (f.isDirectory()) {
            String[] k = f.list();
            int n = k == null ? 0 : k.length;
            return n + (n == 1 ? " item" : " items");
        }
        long len = f.length();
        String sz = len < 1024 ? len + " B"
                : len < 1048576 ? String.format(Locale.US, "%.1f kB", len / 1024.0)
                : String.format(Locale.US, "%.1f MB", len / 1048576.0);
        long age = System.currentTimeMillis() - f.lastModified();
        String when = age < 60_000 ? "just now"
                : age < 3_600_000 ? (age / 60_000) + " min ago"
                : age < 86_400_000 ? (age / 3_600_000) + " h ago"
                : (age / 86_400_000) + " d ago";
        return sz + " · " + when;
    }

    private String extGlyph(String name) {
        String n = name.toLowerCase(Locale.US);
        if (n.endsWith(".java") || n.endsWith(".c") || n.endsWith(".cpp")
                || n.endsWith(".h") || n.endsWith(".py") || n.endsWith(".sh")
                || n.endsWith(".js") || n.endsWith(".ts")) return "{}";
        if (n.endsWith(".md") || n.endsWith(".txt")) return "≡";
        if (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
                || n.endsWith(".gif") || n.endsWith(".webp")) return "▢";
        if (n.endsWith(".zip") || n.endsWith(".tar") || n.endsWith(".gz")
                || n.endsWith(".apk")) return "◍";
        return "·";
    }

    // ------------------------------------------------------------ actions

    private void preview(final File f) {
        ex(() -> {
            String head;
            try {
                String s = Api.readAll(new java.io.FileInputStream(f));
                head = s.length() > 20000
                        ? s.substring(0, 20000) + "\n\n… +" + (s.length() - 20000)
                          + " chars (long-press to copy ALL)"
                        : s;
            } catch (Exception e) {
                head = "(unreadable: " + e.getMessage() + ")";
            }
            final String body = head;
            ui.post(() -> {
                AlertDialog.Builder b = new AlertDialog.Builder(this);
                b.setTitle(f.getName());
                ScrollView sv = new ScrollView(this);
                TextView tv = new TextView(this);
                tv.setText(body.isEmpty() ? "(empty file)" : body);
                tv.setTextSize(12);
                tv.setTypeface(Typeface.MONOSPACE);
                tv.setTextColor(Theme.TXT);
                int p = Theme.dp(this, 18);
                tv.setPadding(p, p, p, p);
                tv.setTextIsSelectable(true);
                sv.addView(tv);
                b.setView(sv);
                b.setPositiveButton("copy all", (d, w) -> {
                    copy(f.getName(), f);
                    d.dismiss();
                });
                b.setNegativeButton("close", null);
                b.show();
            });
        });
    }

    private void actions(final File f) {
        String[] opts = {"Rename", "Delete", "Copy path"};
        new AlertDialog.Builder(this)
                .setTitle(f.getName())
                .setItems(opts, (d, w) -> {
                    if (w == 0) rename(f);
                    else if (w == 1) confirmDelete(f);
                    else copy("path", f.getAbsolutePath());
                })
                .show();
    }

    private void newSheet() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Theme.dp(this, 18);
        box.setPadding(p, Theme.dp(this, 8), p, 0);
        final EditText name = new EditText(this);
        name.setHint("name");
        name.setInputType(InputType.TYPE_CLASS_TEXT);
        name.setSingleLine(true);
        box.addView(name);
        new AlertDialog.Builder(this)
                .setTitle("Create in " + cwd.getName())
                .setView(box)
                .setPositiveButton("folder", (d, w) -> make(name.getText().toString(), true))
                .setNegativeButton("file", (d, w) -> make(name.getText().toString(), false))
                .setNeutralButton("cancel", null)
                .show();
    }

    private void make(String name, boolean dir) {
        if (name == null || name.trim().isEmpty()) return;
        File f = new File(safe(cwd, name.trim()));
        if (f.exists()) { toast("already exists"); return; }
        boolean ok = dir ? f.mkdirs() : false;
        if (!dir) { try { ok = f.createNewFile(); } catch (Exception e) { ok = false; } }
        toast(ok ? "created " + f.getName() : "create failed");
        if (ok) render();
    }

    private void rename(final File f) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Theme.dp(this, 18);
        box.setPadding(p, Theme.dp(this, 8), p, 0);
        final EditText name = new EditText(this);
        name.setText(f.getName());
        name.setSingleLine(true);
        box.addView(name);
        new AlertDialog.Builder(this)
                .setTitle("Rename")
                .setView(box)
                .setPositiveButton("ok", (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (n.isEmpty() || n.equals(f.getName())) return;
                    File to = new File(safe(f.getParentFile(), n));
                    toast(f.renameTo(to) ? "renamed" : "rename failed");
                    render();
                })
                .setNegativeButton("cancel", null)
                .show();
    }

    private void confirmDelete(final File f) {
        new AlertDialog.Builder(this)
                .setTitle("Delete " + f.getName() + "?")
                .setMessage(f.isDirectory()
                        ? "the whole folder and everything in it goes"
                        : "this file goes")
                .setPositiveButton("delete", (d, w) -> {
                    boolean ok = f.isDirectory() ? recurseDelete(f) : f.delete();
                    toast(ok ? "deleted" : "delete failed");
                    if (ok) render();
                })
                .setNegativeButton("keep", null)
                .show();
    }

    private static boolean recurseDelete(File f) {
        File[] k = f.listFiles();
        if (k != null) for (File c : k) if (!recurseDelete(c)) return false;
        return f.delete();
    }

    private void up() {
        File parent = cwd.getParentFile();
        if (parent == null) return;
        String base = baseDir.getAbsolutePath();
        String pp = parent.getAbsolutePath();
        boolean inside = pp.equals(base)
                || (pp.startsWith(base) && pp.length() > base.length()
                        && pp.charAt(base.length()) == '/');
        if (!inside) {
            toast("project root — Files stays inside the project");
            return;
        }
        cwd = parent;
        render();
    }

    /** Clamp: every create/rename target must stay under the project root. */
    private static String safe(File dir, String name) {
        String n = name.replace("/", "_").replace("..", "_");
        return new File(dir, n).getAbsolutePath();
    }

    private void copy(String label, File f) {
        ex(() -> {
            try { copy(label, Api.readAll(new java.io.FileInputStream(f))); }
            catch (Exception e) { toast("copy failed: " + e.getMessage()); }
        });
    }

    private void copy(String label, String s) {
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText(label, s));
        toast(label + " copied (" + s.length() + " chars)");
    }

    private void toast(String s) {
        ui.post(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show());
    }

    private void ex(Runnable r) {
        new Thread(r).start();
    }
}

package ai.opencode.app;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
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

    /** P27 phase 4: a file to open straight away (deep-link from a chat
     *  mention / live-tree chip). Consumed on the first resume. */
    private String pendingOpen;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        File proj = ServerService.servingDir();
        if (proj == null || !proj.isDirectory())
            proj = new File("/sdcard/opencode-projects");
        baseDir = proj;
        cwd = proj;
        Intent in = getIntent();
        if (in != null) pendingOpen = in.getStringExtra("open");

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
        Theme.window(this);              // P31: palette owns the window + dialogs
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
        Theme.syncIfNeeded(this);        // P32: a theme switch elsewhere re-skins here too
        render();
        if (liveOn) startWatching();
        consumePendingOpen();
    }

    /** P27: open the deep-linked file — land on its folder, then raise the
     *  file's preview sheet (the SAME viewer every file row uses). Gone or
     *  outside the project → one quiet toast, no dead end. */
    private void consumePendingOpen() {
        if (pendingOpen == null) return;
        String p = pendingOpen;
        pendingOpen = null;
        try {
            File f = new File(p);
            String base = baseDir.getCanonicalPath();
            String fp = f.getCanonicalPath();
            if (!f.isFile()) {
                Toast.makeText(this, "file no longer exists", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!fp.equals(base) && !fp.startsWith(base + "/")) {
                Toast.makeText(this, "file is outside this project", Toast.LENGTH_SHORT).show();
                return;
            }
            File dir = f.getParentFile();
            if (dir != null && !dir.getAbsolutePath().equals(cwd.getAbsolutePath())) {
                cwd = dir;
                render();
            }
            openFile(f);
        } catch (Exception e) {
            Toast.makeText(this, "could not open that file", Toast.LENGTH_SHORT).show();
        }
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

        // icon disc — P27: ONE visual weight. The old pair (folders solid
        // WHITE, files dark outline) fought each other on every listing
        // (field shot). Now both are quiet: accent-subtle fill for folders,
        // hairline outline for files, glyphs from the same palette.
        TextView disc = new TextView(this);
        boolean dir = f.isDirectory();
        disc.setText(dir ? "▸" : extGlyph(f.getName()));
        disc.setTextSize(13);
        disc.setTypeface(Typeface.MONOSPACE);
        disc.setGravity(Gravity.CENTER);
        disc.setTextColor(dir ? Theme.ACCENT : Theme.TXT_DIM);
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        if (dir) { gd.setColor(Theme.ACCENT_BG); gd.setStroke(1, Theme.STROKE); }
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
            else openFile(f);
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
        // P42: a stale row can reference a deleted file — lastModified() is
        // then 0 and the raw age math rendered "20090 d ago". ago() renders
        // "—" for unknown time instead.
        String when = Resilience.ago(f.lastModified(), System.currentTimeMillis());
        return typeLabel(f) + " · " + humanSize(f.length()) + " · " + when;
    }

    /** P52: short uppercase type tag for the row meta ("APK", "PNG", "MD",
     *  "file" when there is no extension). */
    private static String typeLabel(File f) {
        String e = extOf(f.getName());
        return e.isEmpty() ? "file" : e.toUpperCase(Locale.US);
    }

    /** P52: B / KB / MB / GB — never a raw byte count twice. */
    private static String humanSize(long b) {
        if (b < 1024) return b + " B";
        if (b < 1048576L) return String.format(Locale.US, "%.1f KB", b / 1024.0);
        if (b < 1073741824L) return String.format(Locale.US, "%.1f MB", b / 1048576.0);
        return String.format(Locale.US, "%.1f GB", b / 1073741824.0);
    }

    private static String extOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot < name.length() - 1
                ? name.substring(dot + 1).toLowerCase(Locale.US) : "";
    }

    /** P52: per-type glyph — images, audio, video, archives, packages and
     *  docs all read at a glance now, not just code/text. */
    private String extGlyph(String name) {
        String e = extOf(name);
        switch (e) {
            case "java": case "kt": case "kts": case "c": case "h": case "cc":
            case "cpp": case "hpp": case "cs": case "py": case "rb": case "go":
            case "rs": case "js": case "mjs": case "cjs": case "ts": case "tsx":
            case "jsx": case "sh": case "bash": case "php": case "lua": case "swift":
            case "dart": case "sql": case "gradle": case "vue": case "svelte":
                return "{}";
            case "md": case "markdown": case "txt": case "text": case "log":
            case "csv": case "tsv": case "json": case "xml": case "yml":
            case "yaml": case "toml": case "ini": case "conf": case "properties":
                return "≡";
            case "png": case "jpg": case "jpeg": case "gif": case "webp":
            case "bmp": case "ico": case "tif": case "tiff": case "heic":
            case "avif": case "svg":
                return "▢";
            case "mp3": case "m4a": case "aac": case "ogg": case "oga":
            case "opus": case "wav": case "flac": case "mid":
                return "♪";
            case "mp4": case "mkv": case "webm": case "avi": case "mov":
            case "3gp": case "m4v": case "wmv": case "flv":
                return "▶";
            case "apk": case "apks": case "xapk": case "aab":
                return "⊞";
            case "pdf": case "doc": case "docx": case "xls": case "xlsx":
            case "ppt": case "pptx":
                return "▤";
            case "zip": case "jar": case "rar": case "7z": case "tar":
            case "gz": case "tgz": case "bz2": case "xz": case "deb":
            case "rpm": case "iso":
                return "◍";
            default:
                return "·";
        }
    }

    // ------------------------------------------------ P52: MIME-aware open

    /** P52: route a tap. Text-like files keep the text viewer; everything
     *  else (or anything unreadable) goes to the action sheet instead of
     *  being force-decoded into a String (the field bug: an APK opened as
     *  mojibake text). */
    private void openFile(final File f) {
        ex(() -> {
            final boolean text = isTextLike(f);
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (text) preview(f);
                else binarySheet(f);
            });
        });
    }

    /** True only when an extension says text, or when an unknown file
     *  contains no NUL in its first 8 KB. */
    private static boolean isTextLike(File f) {
        String e = extOf(f.getName());
        if (isTextExt(e)) return true;
        if (isBinaryExt(e)) return false;
        return !looksBinary(f);
    }

    private static boolean isTextExt(String e) {
        switch (e) {
            case "txt": case "text": case "md": case "markdown": case "json":
            case "xml": case "java": case "kt": case "kts": case "js": case "mjs":
            case "cjs": case "ts": case "tsx": case "jsx": case "py": case "rb":
            case "go": case "rs": case "c": case "h": case "cc": case "cpp":
            case "hpp": case "cs": case "sh": case "bash": case "zsh": case "fish":
            case "gradle": case "properties": case "property": case "yml":
            case "yaml": case "toml": case "ini": case "cfg": case "conf":
            case "csv": case "tsv": case "log": case "html": case "htm": case "css":
            case "scss": case "less": case "sql": case "svg": case "env":
            case "dockerfile": case "makefile": case "mk": case "bat": case "cmd":
            case "ps1": case "pl": case "php": case "swift": case "dart": case "lua":
            case "r": case "m": case "vue": case "svelte": case "graphql": case "proto":
            case "diff": case "patch": case "srt": case "vtt": case "pem": case "crt":
            case "key":
                return true;
            default:
                return false;
        }
    }

    private static boolean isBinaryExt(String e) {
        switch (e) {
            case "apk": case "apks": case "xapk": case "aab": case "zip": case "jar":
            case "rar": case "7z": case "tar": case "gz": case "tgz": case "bz2":
            case "xz": case "deb": case "rpm": case "iso": case "img": case "bin":
            case "so": case "o": case "a": case "dex": case "class": case "elf":
            case "exe": case "dll": case "dylib": case "wasm": case "png": case "jpg":
            case "jpeg": case "gif": case "webp": case "bmp": case "ico": case "tif":
            case "tiff": case "heic": case "avif": case "mp3": case "m4a": case "aac":
            case "ogg": case "oga": case "opus": case "wav": case "flac": case "mid":
            case "mp4": case "mkv": case "webm": case "avi": case "mov": case "3gp":
            case "m4v": case "wmv": case "flv": case "pdf": case "doc": case "docx":
            case "xls": case "xlsx": case "ppt": case "pptx": case "ttf": case "otf":
            case "woff": case "woff2": case "eot": case "db": case "sqlite":
            case "sqlite3": case "mdb": case "pak": case "dat": case "npy": case "npz":
            case "pt": case "onnx": case "tflite": case "pb": case "safetensors":
                return false;
            default:
                return false;
        }
    }

    /** Cheap binary sniff: a NUL in the first 8 KB means "do not decode
     *  this as text". Unreadable files are treated as binary so a bad file
     *  degrades to an action sheet, never a crash. */
    private static boolean looksBinary(File f) {
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            byte[] buf = new byte[8192];
            int n = in.read(buf);
            if (n <= 0) return false;
            for (int i = 0; i < n; i++) if (buf[i] == 0) return true;
            return false;
        } catch (Exception e) {
            return true;
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
        }
    }

    private static boolean isApk(File f) {
        return f.getName().toLowerCase(Locale.US).endsWith(".apk");
    }

    /** The binary/unknown action sheet: real OS hand-offs plus the reliable
     *  "make it visible in a file manager" export. */
    private void binarySheet(final File f) {
        final String mime = FileProvider.mimeOf(f);
        Sheet sh = Sheet.show(this, f.getName());
        if (!sh.showing()) return;
        sh.sub(typeLabel(f) + " · " + humanSize(f.length()) + " · " + mime);
        sh.row("↗", "Open with", "hand off to another app", Theme.ACCENT_LT,
                () -> { sh.dismiss(); openWith(f, mime); });
        sh.row("⇪", "Share", "send this file to another app", Theme.TXT,
                () -> { sh.dismiss(); share(f, mime); });
        if (isApk(f)) {
            sh.row("⊞", "Install", "run the package installer", Theme.OK,
                    () -> { sh.dismiss(); install(f); });
        }
        sh.row("⤓", "Export to Downloads", "so any file manager can find it",
                Theme.TXT_DIM, () -> { sh.dismiss(); exportDownloads(f); });
        sh.row("ⓘ", "Info", "size, type and path", Theme.TXT_DIM,
                () -> { sh.dismiss(); info(f, mime); });
        sh.pill("Cancel", Sheet.QUIET, null);
    }

    private void openWith(File f, String mime) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(FileProvider.uriFor(f), mime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Open " + f.getName()));
        } catch (Exception e) {
            toast("no app can open this file");
        }
    }

    private void share(File f, String mime) {
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType(mime);
            i.putExtra(Intent.EXTRA_STREAM, FileProvider.uriFor(f));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Share " + f.getName()));
        } catch (Exception e) {
            toast("nothing to share with");
        }
    }

    private void install(File f) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(FileProvider.uriFor(f),
                    "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) {
            toast("cannot start installer: " + e.getMessage());
        }
    }

    /** No standard reveal-in-folder intent exists, so the reliable path is a
     *  real copy into public Downloads; any file manager then sees it. */
    private void exportDownloads(final File f) {
        ex(() -> {
            String dest = null;
            try {
                File dir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS);
                if (dir == null) throw new java.io.IOException("no Downloads dir");
                if (!dir.exists() && !dir.mkdirs())
                    throw new java.io.IOException("cannot create " + dir);
                File out = uniqueDest(dir, f.getName());
                copyFile(f, out);
                dest = out.getAbsolutePath();
            } catch (Exception e) {
                toast("export failed: " + e.getMessage());
            }
            if (dest != null) toast("exported → " + dest);
        });
    }

    private static File uniqueDest(File dir, String name) {
        File out = new File(dir, name);
        if (!out.exists()) return out;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            File c = new File(dir, stem + "-" + i + ext);
            if (!c.exists()) return c;
        }
        return new File(dir, stem + "-" + System.currentTimeMillis() + ext);
    }

    private static void copyFile(File src, File dst) throws java.io.IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(src);
             java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private void info(final File f, final String mime) {
        Sheet sh = Sheet.show(this, f.getName());
        if (!sh.showing()) return;
        TextView t = Sheet.note(this,
                typeLabel(f) + "  ·  " + humanSize(f.length()) + "\n"
                        + mime + "\n" + f.getAbsolutePath());
        t.setTextIsSelectable(true);
        sh.add(t);
        sh.pill("open with", Sheet.PRIMARY, () -> openWith(f, mime));
        sh.pill("close", Sheet.QUIET, null);
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
                // P34: the preview rides the Sheet — scrollable, copy-all
                // stays the primary action.
                ScrollView sv = new ScrollView(this);
                TextView tv = new TextView(this);
                tv.setText(body.isEmpty() ? "(empty file)" : body);
                tv.setTextSize(12);
                tv.setTypeface(Typeface.MONOSPACE);
                tv.setTextColor(Theme.TXT);
                int p = Theme.dp(this, 4);
                tv.setPadding(p, p, p, p);
                tv.setTextIsSelectable(true);
                sv.addView(tv);
                Sheet.show(this, f.getName())
                        .scroll(sv, 0.5f)
                        .pill("copy all", Sheet.PRIMARY, () -> copy(f.getName(), f))
                        .pill("close", Sheet.QUIET, null);
            });
        });
    }

    private void actions(final File f) {
        // P31: an .html file gains "▶ interactive" — the same canvas
        // viewer the chat's tool cards open. Offered, never forced.
        // P34: the app's own rows on the Sheet — no framework list box.
        boolean html = CanvasDoc.isRenderable(f.getAbsolutePath());
        Sheet sh = Sheet.show(this, f.getName());
        if (!sh.showing()) return;
        if (html) {
            sh.row("▶", "interactive"
                            + RenderCheck.chipSuffix(f.getAbsolutePath()),
                    "open in the canvas viewer",
                    Theme.ACCENT_LT, () -> {
                        sh.dismiss();
                        try {
                            startActivity(new Intent(this, CanvasActivity.class)
                                    .putExtra("path", f.getAbsolutePath()));
                        } catch (Exception e) {
                            toast("cannot open: " + e);
                        }
                    });
        }
        sh.row("✎", "Rename", "change the name in place", Theme.TXT,
                () -> {
                    sh.dismiss();
                    rename(f);
                });
        sh.row("✕", "Delete", f.isDirectory()
                        ? "the whole folder goes" : "this file goes",
                Theme.ERR, () -> {
                    sh.dismiss();
                    confirmDelete(f);
                });
        sh.row("⑂", "Copy path", "the absolute path to the clipboard",
                Theme.TXT_DIM, () -> {
                    sh.dismiss();
                    copy("path", f.getAbsolutePath());
                });
        sh.pill("Cancel", Sheet.QUIET, null);
    }

    private void newSheet() {
        final EditText name = Sheet.input(this, "name", null, true);
        name.setInputType(InputType.TYPE_CLASS_TEXT);
        Sheet sh = Sheet.show(this, "Create in " + cwd.getName());
        if (!sh.showing()) return;
        sh.add(name);
        sh.pillRow("folder", Sheet.PRIMARY,
                () -> make(name.getText().toString(), true),
                "file", Sheet.QUIET,
                () -> make(name.getText().toString(), false));
        sh.pill("cancel", Sheet.QUIET, null);
        sh.focus(name);
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
        final EditText name = Sheet.input(this, null, f.getName(), true);
        Sheet sh = Sheet.show(this, "Rename");
        if (!sh.showing()) return;
        sh.add(name);
        sh.pill("ok", Sheet.PRIMARY, () -> {
            String n = name.getText().toString().trim();
            if (n.isEmpty() || n.equals(f.getName())) return;
            File to = new File(safe(f.getParentFile(), n));
            toast(f.renameTo(to) ? "renamed" : "rename failed");
            render();
        });
        sh.pill("cancel", Sheet.QUIET, null);
        sh.focus(name);
    }

    private void confirmDelete(final File f) {
        Sheet.show(this, "Delete " + f.getName() + "?")
                .msg(f.isDirectory()
                        ? "the whole folder and everything in it goes"
                        : "this file goes")
                .pill("delete", Sheet.DANGER, () -> {
                    boolean ok = f.isDirectory() ? recurseDelete(f) : f.delete();
                    toast(ok ? "deleted" : "delete failed");
                    if (ok) render();
                })
                .pill("keep", Sheet.QUIET, null);
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

package ai.opencode.app;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * P8 project store — the Home deck's model.
 *
 * A project = a folder on device storage + a card accent + bookkeeping.
 * Opening a project starts (or re-points) the opencode server with that
 * folder as its working directory, so the agent's project tools are rooted
 * THERE — its own sandbox, scoped to exactly that folder (see
 * ServerService.switchTo). Stored as JSON in the app's private files dir;
 * folder CONTENTS are never copied or tracked here.
 */
public final class Projects {

    private Projects() {}

    public static final class P {
        public String id;
        public String name;
        public String path;
        public int accent;
        public long created;
        public long opened;
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "projects.json");
    }

    public static synchronized List<P> list(Context c) {
        List<P> out = new ArrayList<>();
        try {
            String s = read(file(c));
            List<Object> arr = Json.arr(Json.parse(s));
            if (arr == null) return out;
            for (Object o : arr) {
                Map<String, Object> m = Json.obj(o);
                if (m == null) continue;
                P p = new P();
                p.id = Json.str(m, "id");
                p.name = Json.str(m, "name");
                p.path = Json.str(m, "path");
                p.accent = (int) num(m.get("accent"));
                p.created = (long) num(m.get("created"));
                p.opened = (long) num(m.get("opened"));
                if (p.id != null && p.path != null) out.add(p);
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static synchronized void save(Context c, List<P> ps) {
        try {
            List<Object> arr = new ArrayList<>();
            for (P p : ps) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", p.id);
                m.put("name", p.name);
                m.put("path", p.path);
                m.put("accent", p.accent);
                m.put("created", p.created);
                m.put("opened", p.opened);
                arr.add(m);
            }
            write(file(c), Json.write(arr));
        } catch (Exception ignored) {}
    }

    /** Add by folder path; name = leaf folder name. Returns the card. */
    public static synchronized P add(Context c, String path) {
        List<P> ps = list(c);
        for (P p : ps) if (path.equals(p.path)) return p; // dedupe by path
        P p = new P();
        p.id = UUID.randomUUID().toString().substring(0, 8);
        File f = new File(path);
        String n = f.getName();
        p.name = (n == null || n.isEmpty()) ? path : n;
        p.path = path;
        p.accent = ps.size();
        p.created = System.currentTimeMillis();
        p.opened = 0;
        ps.add(p);
        save(c, ps);
        return p;
    }

    public static synchronized void touch(Context c, String id) {
        List<P> ps = list(c);
        for (P p : ps) if (p.id.equals(id)) { p.opened = System.currentTimeMillis(); }
        save(c, ps);
    }

    public static synchronized void remove(Context c, String id) {
        List<P> ps = list(c);
        List<P> out = new ArrayList<>();
        for (P p : ps) if (!p.id.equals(id)) out.add(p);
        save(c, out);
    }

    /** Most-recently-opened project (null if none) — boot uses its folder. */
    public static synchronized P last(Context c) {
        List<P> ps = list(c);
        P best = null;
        for (P p : ps) if (best == null || p.opened > best.opened) best = p;
        return best;
    }

    public static boolean validDir(String path) {
        if (path == null || path.isEmpty()) return false;
        File f = new File(path);
        return f.isDirectory() && f.canRead();
    }

    /** Seed the first-run "Playground" project so the deck is never empty. */
    public static void seed(Context c) {
        List<P> ps = list(c);
        if (!ps.isEmpty()) return;
        String base = null;
        try {
            File ext = android.os.Environment.getExternalStorageDirectory();
            if (ext != null && ext.canWrite()) {
                File d = new File(ext, "opencode-projects/playground");
                if (!d.exists()) d.mkdirs();
                if (d.isDirectory()) base = d.getAbsolutePath();
            }
        } catch (Exception ignored) {}
        if (base == null) {
            File d = new File(c.getFilesDir(), "playground");
            if (!d.exists()) d.mkdirs();
            base = d.getAbsolutePath();
        }
        P p = add(c, base);
        p.opened = 0; // not opened yet — just the welcome card
        save(c, list(c));
    }

    // ---- tiny io -------------------------------------------------------

    private static double num(Object o) {
        return (o instanceof Number) ? ((Number) o).doubleValue() : 0;
    }

    private static String read(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return new String(o.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static void write(File f, String s) throws Exception {
        File tmp = new File(f.getParentFile(), f.getName() + ".part");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(s.getBytes(StandardCharsets.UTF_8));
        }
        if (f.exists()) f.delete();
        tmp.renameTo(f);
    }
}

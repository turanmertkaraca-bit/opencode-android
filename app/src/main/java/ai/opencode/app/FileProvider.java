package ai.opencode.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * P52 — the read-only bridge between the project browser and the outside
 * world. targetSdk 28 forbids shipping {@code file://} URIs to other apps
 * (FileUriExposedException), so "Open with", "Share" and "Install" all hand
 * out a {@code content://ai.opencode.app.files/<absolute-path>} URI instead.
 *
 * It is deliberately tiny and READ-ONLY: every request resolves the path and
 * clamps it under an allowed root (the app's own dirs, the public Downloads
 * dir and /storage/emulated/0), then opens the file O_RDONLY. Nothing is
 * ever created, written or enumerated for a caller.
 */
public class FileProvider extends ContentProvider {

    /** Must match the &lt;provider android:authorities&gt; in the manifest. */
    public static final String AUTHORITY = "ai.opencode.app.files";

    /** Build the content URI for an absolute-path file. */
    public static Uri uriFor(File f) {
        String enc = Uri.encode(f.getAbsolutePath(), "/");
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .encodedPath(enc)
                .build();
    }

    /** Resolve a content URI back to the file it maps to. */
    private File fileFor(Uri uri) throws FileNotFoundException {
        if (uri == null) throw new FileNotFoundException("null uri");
        String raw = uri.getEncodedPath();
        if (raw == null) raw = uri.getPath();
        String path = Uri.decode(raw);
        if (path == null || path.isEmpty())
            throw new FileNotFoundException("no path in " + uri);
        File f = new File(path);
        try {
            if (!isAllowed(f))
                throw new SecurityException("outside allowed roots: " + path);
        } catch (IOException e) {
            throw new FileNotFoundException("cannot resolve " + path);
        }
        return f;
    }

    private boolean isAllowed(File f) throws IOException {
        File target = f.getCanonicalFile();
        String tp = target.getPath();
        for (File root : roots()) {
            if (root == null) continue;
            try {
                String rp = root.getCanonicalPath();
                if (tp.equals(rp) || tp.startsWith(rp + File.separator)) return true;
            } catch (IOException ignored) {}
        }
        return false;
    }

    /** Every root a caller is permitted to read through this provider. */
    private List<File> roots() {
        List<File> rs = new ArrayList<>();
        Context c = getContext();
        if (c != null) {
            rs.add(c.getFilesDir());
            rs.add(c.getCacheDir());
            rs.add(c.getExternalFilesDir(null));
            rs.add(c.getExternalCacheDir());
            File[] ext = c.getExternalFilesDirs(null);
            if (ext != null) for (File e : ext) rs.add(e);
        }
        addPublic(rs, Environment.DIRECTORY_DOWNLOADS);
        addPublic(rs, Environment.DIRECTORY_DOCUMENTS);
        rs.add(Environment.getExternalStorageDirectory());   // /storage/emulated/0
        rs.add(new File("/storage/emulated/0"));
        return rs;
    }

    private static void addPublic(List<File> rs, String kind) {
        try {
            File d = Environment.getExternalStoragePublicDirectory(kind);
            if (d != null) rs.add(d);
        } catch (Throwable ignored) {}
    }

    /** Best-effort MIME for a file; shared with FilesActivity. */
    public static String mimeOf(File f) {
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.US) : "";
        String m = null;
        try {
            m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        } catch (Throwable ignored) {}
        if (m == null) m = fallbackMime(ext);
        return m != null ? m : "application/octet-stream";
    }

    /** Extensions the platform map sometimes misses, plus the one we care
     *  about most here (apk). */
    private static String fallbackMime(String e) {
        switch (e) {
            case "apk": return "application/vnd.android.package-archive";
            case "aab": return "application/octet-stream";
            case "md": case "markdown": return "text/markdown";
            case "kt": case "kts": case "gradle": case "toml":
            case "log": case "conf": case "ini": case "env": return "text/plain";
            case "yml": case "yaml": return "text/yaml";
            case "sh": return "application/x-sh";
            case "jar": return "application/java-archive";
            case "so": case "elf": case "bin": return "application/octet-stream";
            case "svg": return "image/svg+xml";
            case "deb": return "application/vnd.debian.binary-package";
            case "sqlite": case "sqlite3": case "db": return "application/x-sqlite3";
            case "ttf": return "font/ttf";
            case "otf": return "font/otf";
            case "wasm": return "application/wasm";
            default: return null;
        }
    }

    // ----------------------------------------------------------- provider

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        File f = fileFor(uri);
        if (!f.exists() || !f.isFile())
            throw new FileNotFoundException("not a file: " + f.getAbsolutePath());
        try {
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (FileNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new FileNotFoundException("open failed: " + e);
        }
    }

    @Override
    public String getType(Uri uri) {
        try {
            return mimeOf(fileFor(uri));
        } catch (Exception e) {
            return "application/octet-stream";
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only provider");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        return 0;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }
}

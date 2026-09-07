package ai.opencode.app;

import java.io.File;

/**
 * P31 — the interactive canvas. The AI can explain things with real,
 * runnable HTML pages (sliders, buttons, visual models) the way the user
 * asked: like Gemini's interactive explainers. It is offered, never
 * forced — the only entry points are the user asking for it (⌘ palette →
 * "Interactive canvas…"), a "▶ interactive" chip on a tool card that
 * wrote an .html file, and the Files viewer's open menu.
 *
 * Pure decision + prompt rules here; the WebView host is CanvasActivity.
 */
public final class CanvasDoc {

    private CanvasDoc() {}

    /** The file the prompt tells the model to write (project root —
     *  visible in Files, one file, overwritten on each new canvas). */
    public static final String FILE_NAME = "canvas.html";

    /** Hard size cap — a WebView string load of tens of MB is an OOM
     *  invitation; a self-contained explainer is a few hundred KB. */
    public static final long MAX_BYTES = 3L * 1024 * 1024;

    /** .html / .htm, case-insensitive. Null-safe. */
    public static boolean isRenderable(String path) {
        if (path == null) return false;
        String p = path.toLowerCase(java.util.Locale.US);
        return p.endsWith(".html") || p.endsWith(".htm");
    }

    /**
     * The canned instruction the palette command sends. Self-contained on
     * purpose: inline CSS + JS, no external resources (the viewer runs
     * sandboxed and must work offline), dark surface matching the app,
     * and the exact file path so the ▶ chip can find it.
     */
    public static String prompt(String topic) {
        String t = (topic == null || topic.trim().isEmpty())
                ? "the topic we were discussing" : topic.trim();
        return "Explain " + t + " as an interactive HTML page. Write ONE "
                + "self-contained file to " + FILE_NAME + " in the project "
                + "root (overwrite it). Rules: all CSS and JavaScript inline "
                + "in the file, no external resources or network requests, no "
                + "CDN links. Make it genuinely interactive — sliders, "
                + "buttons, toggles, animations or step-through controls that "
                + "make the idea click, not a static page. Dark background "
                + "(#0B0E16), light text (#F4F6FB), accent #7C9CFF, clean "
                + "typography, responsive down to a narrow phone. After "
                + "writing the file, stop — the app renders it in a sandboxed "
                + "viewer; the user opens it with the ▶ interactive button.";
    }

    /** The one-line sys note after the canvas prompt is sent. */
    public static String sentNote() {
        return "ℹ canvas requested — when the ▶ interactive chip appears "
                + "on the tool card below, tap it to open the page";
    }

    /** Read-guard for the viewer: null = safe to load (caller reads the
     *  file itself), otherwise a human error line to show instead. */
    public static String readGuard(File f) {
        if (f == null || !f.isFile()) return "the page file is gone";
        if (f.length() > MAX_BYTES) return "the page is too large to render ("
                + (f.length() / 1024) + " kB — the viewer caps at "
                + (MAX_BYTES / 1024) + " kB)";
        if (!f.canRead()) return "the page file is not readable";
        return null;
    }
}

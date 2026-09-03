package ai.opencode.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P17 — the screenshot vision stack: "make the ai see things that it's
 * developing, like websites and stuff … uses a free vision model".
 *
 * Flow: the user taps the ◉ chip → SAF picker → downscale (≤1024 px JPEG)
 * → the app FIRST tries the opencode server's own file part (the agent
 * gets the raw pixels when the running build supports image parts); if
 * the server rejects it, a FREE vision model from the Zen gateway
 * describes the screenshot (OpenAI-compatible /chat/completions with an
 * image_url data URL — the exact ladder in CANDIDATES, all cost-0 +
 * attachment-capable models from the bundled models.dev catalog), and the
 * description is fed to the agent as message context. Either way the
 * screenshot renders in the chat as a proper image bubble.
 *
 * Auth: the Zen key (when present) rides along as a Bearer header; the
 * free tier works keyless, matching the P16 "free ones run keyless" hint.
 */
public final class Vision {

    public static final String ZEN_BASE = "https://opencode.ai/zen/v1";

    /**
     * Free vision-capable fallback ladder — bundled models-dev.json:
     * input cost 0 AND attachment=true, opencode (Zen) first, then the
     * one free vision model on opencode-go. Order is pinned by P17Test.
     */
    public static final String[][] CANDIDATES = {
            {"opencode", "kimi-k2.5-free"},
            {"opencode", "qwen3.6-plus-free"},
            {"opencode", "mimo-v2.5-free"},
            {"opencode", "muse-spark-1.3-contributor-free"},
            {"opencode", "x-preview-f-free"},
            {"opencode-go", "ox-alpha-free"},
    };

    private Vision() {}

    /** Candidate {provider, model} at index i, or null. */
    public static String[] modelAt(int i) {
        if (i < 0 || i >= CANDIDATES.length) return null;
        return new String[]{CANDIDATES[i][0], CANDIDATES[i][1]};
    }

    /** The instruction the vision model gets — tuned for a coding agent's eyes. */
    public static String prompt(String caption) {
        StringBuilder b = new StringBuilder();
        b.append("You are the eyes of a coding agent working on the user's ")
                .append("phone. Describe this screenshot precisely: what app, ")
                .append("site or UI it shows, its layout and visible text, ")
                .append("styling, and anything that looks broken or worth ")
                .append("changing next. Be concrete and compact.");
        if (caption != null && !caption.trim().isEmpty()) {
            b.append(" The user says: \"").append(caption.trim()).append('"');
        }
        return b.toString();
    }

    /** data:image/jpeg;base64,… URL for a JPEG blob. */
    public static String dataUrl(byte[] jpeg) {
        return "data:image/jpeg;base64,"
                + Base64.getEncoder().encodeToString(jpeg);
    }

    /**
     * OpenAI-compatible chat/completions request body with one text part
     * and one image_url part. Pure — the JVM suite parses it back.
     */
    public static String buildBody(String model, String prompt, String dataUrl) {
        Map<String, Object> img = new LinkedHashMap<>();
        img.put("type", "image_url");
        Map<String, Object> url = new LinkedHashMap<>();
        url.put("url", dataUrl);
        img.put("image_url", url);

        Map<String, Object> text = new LinkedHashMap<>();
        text.put("type", "text");
        text.put("text", prompt);

        List<Object> content = new ArrayList<>();
        content.add(text);
        content.add(img);

        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "user");
        msg.put("content", content);

        List<Object> messages = new ArrayList<>();
        messages.add(msg);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("model", model);
        root.put("stream", Boolean.FALSE);
        root.put("max_tokens", 700);
        root.put("messages", messages);
        return Json.write(root);
    }

    /**
     * Describe one screenshot with the given free model. Blocking — call
     * off the main thread. Throws IOException with the HTTP status on
     * failure so the caller can walk the CANDIDATES ladder.
     */
    public static String describe(String model, String prompt, byte[] jpeg,
                                  String bearer, int timeoutMs) throws IOException {
        String body = buildBody(model, prompt, dataUrl(jpeg));
        HttpURLConnection c = (HttpURLConnection)
                new URL(ZEN_BASE + "/chat/completions").openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(8_000);
            c.setReadTimeout(timeoutMs);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            if (bearer != null && !bearer.trim().isEmpty()) {
                c.setRequestProperty("Authorization", "Bearer " + bearer.trim());
            }
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(out.length);
            c.getOutputStream().write(out);
            c.getOutputStream().flush();
            int code = c.getResponseCode();
            String resp = Api.readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code < 200 || code >= 300) {
                String hint = Json.findErrorText(Json.parse(resp), 0);
                throw new IOException("HTTP " + code
                        + (hint == null ? "" : " · " + hint));
            }
            String content = parseContent(resp);
            if (content == null) throw new IOException("empty answer");
            return content;
        } finally {
            c.disconnect();
        }
    }

    /** choices[0].message.content, defensively. Pure. */
    public static String parseContent(String body) {
        try {
            Map<String, Object> root = Json.obj(Json.parse(body));
            if (root == null) return null;
            List<Object> choices = Json.arr(root.get("choices"));
            if (choices == null || choices.isEmpty()) return null;
            Map<String, Object> c0 = Json.obj(choices.get(0));
            if (c0 == null) return null;
            Map<String, Object> msg = Json.map(c0, "message");
            if (msg == null) return null;
            String t = Json.str(msg, "content");
            return (t == null || t.trim().isEmpty()) ? null : t.trim();
        } catch (Exception e) {
            return null;
        }
    }

    /** Stored OpenCode Zen key (null when keyless — free tier still works). */
    public static String zenKey(Context c) {
        try {
            Map<String, Object> e = Json.map(AuthStore.readAuth(c), "opencode");
            return e == null ? null : Json.str(e, "key");
        } catch (Exception ex) {
            return null;
        }
    }

    // --------------------------------------------------------- imaging

    /**
     * SAF Uri → cache-file JPEG, long edge ≤1024 px, quality 82. Downscale
     * FIRST (inSampleSize), then a exact scale — a 12 MP photo must never
     * become a 4 MB base64 blob.
     */
    public static File downscale(Context c, Uri uri) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        InputStream in = c.getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("cannot open image");
        BitmapFactory.decodeStream(in, null, bounds);
        try { in.close(); } catch (Exception ignored) {}
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0)
            throw new IOException("not a decodable image");

        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= 1024
                || bounds.outHeight / (sample * 2) >= 1024) sample *= 2;

        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap bmp;
        InputStream in2 = c.getContentResolver().openInputStream(uri);
        if (in2 == null) throw new IOException("cannot reopen image");
        try {
            bmp = BitmapFactory.decodeStream(in2, null, o);
        } finally {
            try { in2.close(); } catch (Exception ignored) {}
        }
        if (bmp == null) throw new IOException("decode failed");

        int w = bmp.getWidth(), h = bmp.getHeight();
        float scale = Math.min(1f, 1024f / Math.max(w, h));
        if (scale < 1f) {
            Bitmap scaled = Bitmap.createScaledBitmap(bmp,
                    Math.max(1, Math.round(w * scale)),
                    Math.max(1, Math.round(h * scale)), true);
            if (scaled != bmp) bmp.recycle();
            bmp = scaled;
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 82, bos);
        bmp.recycle();

        File dir = new File(c.getCacheDir(), "vision");
        if (!dir.isDirectory()) dir.mkdirs();
        File out = new File(dir, "shot_" + System.currentTimeMillis() + ".jpg");
        try (FileOutputStream fo = new FileOutputStream(out)) {
            fo.write(bos.toByteArray());
        }
        return out;
    }

    /** Decode a file to a display bitmap, long edge ≤1024 (chat bubbles). */
    public static Bitmap decodeBounded(String path, int maxEdge) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= maxEdge
                    || bounds.outHeight / (sample * 2) >= maxEdge) sample *= 2;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeFile(path, o);
            if (bmp == null) return null;
            int w = bmp.getWidth(), h = bmp.getHeight();
            float scale = Math.min(1f, maxEdge / (float) Math.max(w, h));
            if (scale < 1f) {
                Bitmap scaled = Bitmap.createScaledBitmap(bmp,
                        Math.max(1, Math.round(w * scale)),
                        Math.max(1, Math.round(h * scale)), true);
                if (scaled != bmp) bmp.recycle();
                bmp = scaled;
            }
            return bmp;
        } catch (Throwable t) {
            return null;
        }
    }
}

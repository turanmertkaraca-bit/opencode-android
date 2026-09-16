package ai.opencode.app;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * P42 — TLS truth for the agent shell.
 *
 * THE FIELD FAILURE (v0.41.0, a 3-hour session): every https call from
 * the agent's shell died with
 *   "curl: error setting certificate file:
 *    /etc/ssl/certs/ca-certificates.crt"
 * because NOTHING in the app ever exported a CA bundle. Android's trust
 * store lives at /system/etc/security/cacerts (one PEM-ish file per
 * root) — not at the /etc/ssl paths CLI tools compiled for Linux expect.
 * The agent, seeing dead TLS everywhere, burned whole sessions probing
 * and re-probing ("apt-get install ca-certificates", /etc/hosts hacks,
 * nslookup hunts) instead of doing the task.
 *
 * THE SOURCE FIX: build ONE merged PEM bundle from the real system
 * store (plus the Alpine rootfs bundle that ships in assets, as a
 * union — dedup makes overlap harmless), write it under files/home (a
 * path the Debian guest bind-mounts at its real host location), and
 * export the standard variables every tool family honors:
 *
 *   SSL_CERT_FILE / SSL_CERT_DIR ......... OpenSSL builds (curl, git's libcurl)
 *   CURL_CA_BUNDLE ....................... curl
 *   GIT_SSL_CAINFO ....................... git
 *   REQUESTS_CA_BUNDLE ................... python requests/urllib3
 *   PIP_CERT ............................. pip
 *   NODE_EXTRA_CA_CERTS .................. node/bun (adds to its own store)
 *   NPM_CONFIG_CAFILE .................... npm
 *
 * This class is deliberately boring: idempotent, never throws (a
 * missing store just means no bundle and callers skip the exports),
 * and the merging itself is pure so the suite can pin it.
 */
public final class CaBundle {

    private CaBundle() {}

    /** System trust store on every Android build to date. */
    private static final String SYSTEM_CA_DIR = "/system/etc/security/cacerts";

    /**
     * Build or refresh the merged bundle. Returns the bundle's absolute
     * path, or null when no source was readable. Safe to call on every
     * spawn: a count marker makes the common case a two-stat short
     * circuit.
     */
    public static String ensure(Context c) {
        try {
            File out = bundleFile(c);
            File ssl = out.getParentFile();
            if (ssl == null) return null;
            List<String> pems = new ArrayList<>();
            int srcCount = 0;

            File sys = new File(SYSTEM_CA_DIR);
            File[] certs = sys.listFiles();
            if (certs != null) {
                java.util.Arrays.sort(certs);                       // stable order
                for (File f : certs) {
                    if (!f.isFile()) continue;
                    String t = readText(f);
                    if (t == null) continue;
                    srcCount++;
                    pems.add(t);
                }
            }
            // Union the shipped Alpine bundle — it is a normal Mozilla
            // CA set (ISRG/X1 et al) and covers stores missing from
            // exotic vendor ROMs. Dedup makes overlap harmless.
            File alpine = new File(Sandbox.alpineDir(c),
                    "etc/ssl/certs/ca-certificates.crt");
            if (alpine.isFile()) {
                String t = readText(alpine);
                if (t != null) { srcCount++; pems.add(t); }
            }

            if (srcCount == 0) return null;

            String merged = merge(pems);
            if (merged.isEmpty()) return null;

            // short-circuit: unchanged source count + non-empty bundle
            File marker = new File(ssl, ".bundle-n");
            String cur = out.isFile() && out.length() > 0 ? readText(marker) : null;
            if (cur != null && cur.trim().equals(String.valueOf(srcCount))
                    && out.isFile() && out.length() > 0) {
                return out.getAbsolutePath();
            }

            ssl.mkdirs();
            try (FileOutputStream o = new FileOutputStream(out)) {
                o.write(merged.getBytes(StandardCharsets.US_ASCII));
            }
            writeText(marker, String.valueOf(srcCount));
            return out.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    /** The one bundle path everything points at. */
    public static File bundleFile(Context c) {
        return new File(Binaries.homeDir(c), "etc/ssl/cert.pem");
    }

    // ------------------------------------------------------------- pure

    /**
     * Merge PEM texts into one canonical bundle: every
     * BEGIN/END CERTIFICATE block extracted, deduped by the
     * whitespace-normalized base64 body (same root → same body), order
     * preserved, emitted with 64-character base64 lines. Non-cert text
     * (comments, metadata, trust headers) is dropped — consumers want
     * certs, nothing else.
     */
    public static String merge(List<String> pemTexts) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        StringBuilder out = new StringBuilder();
        if (pemTexts == null) return "";
        for (String t : pemTexts) {
            if (t == null) continue;
            for (String body : certBodies(t)) {
                if (seen.add(body)) {
                    out.append("-----BEGIN CERTIFICATE-----\n")
                       .append(reflow(body)).append('\n')
                       .append("-----END CERTIFICATE-----\n");
                }
            }
        }
        return out.toString();
    }

    /** Extract normalized base64 bodies of cert blocks; garbage skipped. */
    static List<String> certBodies(String pem) {
        List<String> res = new ArrayList<>();
        if (pem == null) return res;
        String up = pem;
        int i = 0;
        while (true) {
            int b = up.indexOf("-----BEGIN CERTIFICATE-----", i);
            if (b < 0) break;
            int e = up.indexOf("-----END CERTIFICATE-----", b);
            if (e < 0) break;
            String body = up.substring(b + "-----BEGIN CERTIFICATE-----".length(), e);
            StringBuilder sb = new StringBuilder(body.length());
            for (int k = 0; k < body.length(); k++) {
                char ch = body.charAt(k);
                if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')
                        || (ch >= '0' && ch <= '9') || ch == '+' || ch == '/' || ch == '=') {
                    sb.append(ch);
                }
            }
            String norm = sb.toString();
            // a real body is non-empty base64 (decodable length % 4 == 0)
            if (!norm.isEmpty() && norm.length() % 4 == 0) res.add(norm);
            i = e + "-----END CERTIFICATE-----".length();
        }
        return res;
    }

    /** Wrap a base64 body at 64 chars (PEM wire format). */
    private static String reflow(String body) {
        StringBuilder sb = new StringBuilder(body.length() + body.length() / 64 + 2);
        for (int i = 0; i < body.length(); i += 64) {
            if (i > 0) sb.append('\n');
            sb.append(body, i, Math.min(body.length(), i + 64));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------- io

    private static String readText(File f) {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.US_ASCII);
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeText(File f, String s) {
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(s.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception ignored) {}
    }
}

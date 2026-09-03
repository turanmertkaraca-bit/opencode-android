package ai.opencode.app;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * P14 regression tests — the exact field bugs from the user's device.
 *
 * The P13 bash shim generator emitted:
 *     if [ -x "…/debian/launch" ]
 *         && [ -f "…/debian/.probe" ]; then
 * mksh ends the command at the newline, so a line starting with `&&` is a
 * SYNTAX ERROR — the shim died before exec and every bash tool call failed.
 * These tests pin the invariant: no generated shim may ever contain a line
 * starting with a continuation operator, and the Debian branch must be a
 * single-line test.
 */
@RunWith(RobolectricTestRunner.class)
public class ShimTest {

    private static String read(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n < 0) break;
                off += n;
            }
            return new String(b, StandardCharsets.UTF_8);
        }
    }

    private static void assertMkshSafe(String s) {
        for (String line : s.split("\n")) {
            String t = line.trim();
            assertFalse("line starts with && (mksh syntax error): " + line,
                    t.startsWith("&&"));
            assertFalse("line starts with || (mksh syntax error): " + line,
                    t.startsWith("||"));
        }
    }

    @Test
    public void bashShim_isSingleLineSafe_andKeepsDebianBranch() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        Shims.ensure(c);
        File bash = new File(Shims.shimsDir(c), "bash");
        assertTrue("bash shim was written", bash.isFile());
        String s = read(bash);
        assertMkshSafe(s);
        // the Debian branch survived the rewrite — as a ONE-LINE condition
        assertTrue("single-line condition: [ -x … ] && [ -f … ]",
                s.contains("] && [ -f \""));
        assertTrue("debian launcher still referenced",
                s.contains("launch"));
        assertTrue(".probe gate still referenced",
                s.contains(".probe"));
        assertTrue("final fallback intact", s.contains("exec /system/bin/sh"));
    }

    @Test
    public void gitShim_isSingleLineSafe() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        Shims.ensure(c);
        File git = new File(Shims.shimsDir(c), "git");
        assertTrue("git shim was written", git.isFile());
        assertMkshSafe(read(git));
    }

    @Test
    public void debianLauncher_isSingleLineSafe() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        Debian.writeLauncher(c);
        File launch = new File(Debian.dir(c), "launch");
        assertTrue("debian launcher was written", launch.isFile());
        String s = read(launch);
        assertMkshSafe(s);
        assertTrue("no token exported when prefs empty", !s.contains("GH_TOKEN"));
        assertTrue("rootfs bound", s.contains("--rootfs="));
        assertTrue("project bind slot present", s.contains("--bind="));
    }

    @Test
    public void jsonNum_parsesNumbersAndStrings() {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put("a", 0);
        m.put("b", 2.5);
        m.put("c", "3.25");
        m.put("d", "nope");
        assertEquals(0d, Json.num(m, "a"), 1e-9);
        assertEquals(2.5d, Json.num(m, "b"), 1e-9);
        assertEquals(3.25d, Json.num(m, "c"), 1e-9);
        assertEquals(0d, Json.num(m, "d"), 1e-9);
        assertEquals(0d, Json.num(m, "missing"), 1e-9);
        assertEquals(0d, Json.num(null, "x"), 1e-9);
    }
}

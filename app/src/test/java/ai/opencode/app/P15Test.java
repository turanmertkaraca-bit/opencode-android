package ai.opencode.app;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * P15 regression tests — the "model picker still doesn't work" round.
 *
 * The user's verdict was final: the WORKING picker is the one in the first
 * P12 (v0.12.0 / P12a). P14's rework dropped Mdl.live, so every
 * discovery-catalog row saved silently and the server answered
 * "Model not found" — the picker looked broken a third time. These tests
 * pin the restored P12a semantics in Models.java and the Debian directory
 * initialization the agent's own field report demanded.
 */
@RunWith(RobolectricTestRunner.class)
public class P15Test {

    // ------------------------------------------------------- picker (P12a)

    /** available() must require m.live — a catalog-only pick must NOT pass
     *  the send-path gate, so validateSelectedModel() self-heals it. */
    @Test
    public void available_requiresLive_notMereCatalogPresence() {
        Models.Prov p = new Models.Prov();
        p.id = "opencode";
        p.name = "OpenCode";
        Models.Mdl live = new Models.Mdl();
        live.id = "grok-code"; live.name = "Grok Code"; live.live = true;
        Models.Mdl dead = new Models.Mdl();
        dead.id = "gpt-5"; dead.name = "GPT-5"; dead.live = false;
        p.models.add(live);
        p.models.add(dead);
        List<Models.Prov> provs = new ArrayList<>();
        provs.add(p);

        assertTrue("server-listed model must be available",
                Models.available(provs, "opencode", "grok-code"));
        assertFalse("catalog-only model must NOT be available (P12a rule)",
                Models.available(provs, "opencode", "gpt-5"));
        assertFalse("unknown provider never available",
                Models.available(provs, "anthropic", "grok-code"));
    }

    /** Providers flagged usable (listed by the RUNNING server) sort first —
     *  the P12a ordering that made the picker feel right. */
    @Test
    public void order_putsUsableProvidersFirst() {
        Models.Prov catalog = new Models.Prov();
        catalog.id = "anthropic"; catalog.name = "Anthropic";
        Models.Prov server = new Models.Prov();
        server.id = "opencode"; server.name = "OpenCode"; server.usable = true;
        Map<String, Models.Prov> byId = new LinkedHashMap<>();
        byId.put("anthropic", catalog);
        byId.put("opencode", server);

        List<Models.Prov> out = Models.orderForTest(byId);
        assertTrue("server-usable provider must come first",
                out.get(0).id.equals("opencode"));
    }

    // ---------------------------------------------------------- debian dirs

    /** The agent's field report, pinned forever: PROOT_TMP_DIR's target
     *  files/debian/tmp must EXIST before proot runs — proot mkdtemps
     *  inside it and dies on a fresh install otherwise. files/home must
     *  exist for the opencode bind. */
    @Test
    public void ensureDirs_createsTmpAndHome_beforeAnyProotRun() {
        Context c = RuntimeEnvironment.getApplication();
        Debian.ensureDirs(c);
        File deb = Debian.dir(c);
        assertTrue("files/debian must exist",
                deb.isDirectory());
        assertTrue("files/debian/tmp must exist (PROOT_TMP_DIR target)",
                new File(deb, "tmp").isDirectory());
        assertTrue("files/home must exist (opencode bind)",
                Binaries.homeDir(c).isDirectory());
    }

    /** envReport must always return a usable summary — never throw — and
     *  must write env.txt next to the debian dir for the agent to read. */
    @Test
    public void envReport_neverThrows_andWritesEnvTxt() {
        Context c = RuntimeEnvironment.getApplication();
        String rep = Debian.envReport(c);
        assertNotNull(rep);
        assertFalse(rep.isEmpty());
        assertTrue(rep.contains("sandbox") || rep.contains("host"));
        assertTrue("env.txt should be written for the agent",
                new File(Debian.dir(c), "env.txt").isFile());
    }
}

package ai.opencode.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P36 — the launcher icon follows the theme.
 *
 * One activity-alias per palette (plus the classic P17 icon), and
 * exactly ONE alias may be enabled at any moment — the launcher shows
 * the enabled alias's icon as the app icon. sync() runs on every
 * explicit theme pick in Settings; reconcile() runs at process start
 * and self-heals an interrupted switch (the enable landed, the disable
 * did not) so the app can never vanish from the launcher.
 *
 * Ordering rule, pinned by P36Test: enable the desired alias FIRST,
 * only then disable the others — the launcher must never see a
 * no-icon moment.
 *
 * Policy: the icon follows an EXPLICIT pick. A device that never chose
 * a theme keeps the classic icon — the Graphite default is a rendering
 * default, not a choice (same philosophy as the P33 defaultId carve-out).
 *
 * Containment (the house rule): any PackageManager failure is one
 * incident-log line — never a crash, never a broken theme switch (the
 * palette change that called us already succeeded; the icon is
 * cosmetics). Some launchers repaint the icon lazily (often only after
 * a launcher restart) — that is launcher behavior, not app state.
 */
public final class LauncherIcon {

    /** Alias simple names, index-aligned with Theme.PALETTES. */
    static final String[] THEME_ALIASES = {
            "LauncherOled", "LauncherMidnight", "LauncherGraphite",
            "LauncherEmber", "LauncherForest", "LauncherPaper"
    };
    /** The pre-P36 icon: fresh installs and explicit-no-choice. */
    static final String CLASSIC = "LauncherClassic";

    private LauncherIcon() {}

    /**
     * theme id → alias simple name. null or unknown id → classic.
     * (pure, pinned) — validated against the PALETTES table directly,
     * NOT paletteIndex (that clamps garbage to 0 = oled).
     */
    static String aliasFor(String themeId) {
        if (themeId == null) return CLASSIC;
        for (int i = 0; i < Theme.PALETTES.length && i < THEME_ALIASES.length; i++)
            if (Theme.PALETTES[i].equals(themeId)) return THEME_ALIASES[i];
        return CLASSIC;
    }

    /**
     * Minimal ordered ops to reach exactly-one-enabled: enable the
     * desired alias (when it isn't already), then disable every OTHER
     * enabled one. Disable order is deterministic (classic first, then
     * table order) so tests and logs read the same every time.
     * (pure, pinned)
     *
     * @param states  alias simple name → currently effectively enabled
     * @param desired alias simple name to keep enabled
     */
    static String[][] planOps(Map<String, Boolean> states, String desired) {
        List<String[]> ops = new ArrayList<>();
        Boolean on = states.get(desired);
        if (on == null || !on) ops.add(new String[]{desired, "enable"});
        List<String> order = new ArrayList<>();
        order.add(CLASSIC);
        order.addAll(Arrays.asList(THEME_ALIASES));
        for (String alias : order) {
            if (alias.equals(desired)) continue;
            if (Boolean.TRUE.equals(states.get(alias)))
                ops.add(new String[]{alias, "disable"});
        }
        return ops.toArray(new String[0][]);
    }

    /** Effective enabled flag: explicit setting wins, else manifest default. */
    private static boolean effective(PackageManager pm, String pkg,
                                     String alias, boolean manifestDefault) {
        int s = pm.getComponentEnabledSetting(
                new ComponentName(pkg, pkg + "." + alias));
        if (s == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return true;
        if (s == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) return false;
        return manifestDefault;
    }

    /** Core: make desired the one enabled alias. Idempotent — a no-op
     *  costs 7 cheap PackageManager queries and fires nothing. */
    private static void apply(Context c, String desired) {
        String pkg = c.getPackageName();
        PackageManager pm = c.getPackageManager();
        Map<String, Boolean> states = new LinkedHashMap<>();
        states.put(CLASSIC, effective(pm, pkg, CLASSIC, true));
        for (String a : THEME_ALIASES) states.put(a, effective(pm, pkg, a, false));
        for (String[] op : planOps(states, desired)) {
            int state = "enable".equals(op[1])
                    ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            pm.setComponentEnabledSetting(
                    new ComponentName(pkg, pkg + "." + op[0]), state,
                    PackageManager.DONT_KILL_APP);
        }
    }

    /** Theme picker path — call right after the theme pref is saved. */
    public static void sync(Context c, String themeId) {
        try {
            apply(c, aliasFor(themeId));
        } catch (Exception e) {
            ServerService.appendDiagStatic(c, "icon-sync", Resilience.traceLine(e));
        }
    }

    /** Process start — self-heal an interrupted switch. Reads the RAW
     *  theme pref: a device that never picked keeps the classic icon. */
    public static void reconcile(Context c) {
        try {
            String theme = c.getSharedPreferences("oc", Context.MODE_PRIVATE)
                    .getString("theme", null);
            apply(c, aliasFor(theme));
        } catch (Exception e) {
            ServerService.appendDiagStatic(c, "icon-reconcile", Resilience.traceLine(e));
        }
    }
}

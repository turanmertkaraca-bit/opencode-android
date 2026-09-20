package ai.opencode.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * P50 — the keep-alive watchdog.
 *
 * The field report: "the app cannot work in background, it keeps closing
 * after a little while". Two independent killers were found and cured:
 *
 *   1. AUTO-HIBERNATE (in-app, P31, default ON) — the sandbox stopped
 *      ITSELF after 10 quiet background minutes. The user flipped the
 *      "Cool idle" switch (a different feature — the wake lock) and the
 *      sandbox kept dying. Cured at the default: hibernate is now opt-in
 *      (default OFF); background survival is the factory posture.
 *
 *   2. OEM/OS PROCESS KILLS (out of app) — even a foreground service can
 *      be reaped on aggressive devices. START_STICKY asks Android to
 *      restart the service, but under Doze the restart can be postponed
 *      indefinitely. This receiver closes that gap: an allow-while-idle
 *      alarm chain (≈4 min cadence) that boots the service again the
 *      moment the system hands the process back, whenever the sandbox is
 *      still WANTED (svc_want) and keep-alive is enabled (default ON).
 *
 * The chain is self-perpetuating: every fire re-arms the next tick while
 * the keep-alive pref holds; flipping keep-alive off starves the chain
 * and the app returns to stock Android lifecycle rules.
 */
public final class WatchdogReceiver extends BroadcastReceiver {

    /** Re-arm cadence. Small enough to rescue a run between kills, rare
     *  enough to cost nothing (one broadcast ≈ 4 min). */
    public static long INTERVAL_MS = 4 * 60_000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        boolean keep = context.getSharedPreferences("oc", Context.MODE_PRIVATE)
                .getBoolean("keepalive", DEFAULT_KEEPALIVE);
        if (shouldBoot(keep, ServerService.svcWant(context))) {
            try {
                context.startForegroundService(
                        new Intent(context, ServerService.class));
            } catch (Exception ignored) {
                // app in background restrictions — the next tick retries
            }
        }
        ServerService.scheduleWatchdog(context);   // keep the chain alive
    }

    /** Pure decision — JVM-pinned. Boot only when the user wants both
     *  keep-alive AND the sandbox is expected to be running. */
    public static boolean shouldBoot(boolean keepalive, boolean svcWant) {
        return keepalive && svcWant;
    }

    /** Factory posture: keep-alive ON (the background-working fix). */
    public static final boolean DEFAULT_KEEPALIVE = true;
}

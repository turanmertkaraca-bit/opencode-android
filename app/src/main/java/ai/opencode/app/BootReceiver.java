package ai.opencode.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * P12 — keep-alive part 2: relaunch the sandbox server after a reboot
 * when the user opted in (Settings → keep alive → Start on boot).
 * Termux-pattern: a silent startService on BOOT_COMPLETED; the foreground
 * notification is the visible anchor. opt-in only, nothing runs unless
 * the user flipped the switch.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String a = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(a)) return;
        if (!context.getSharedPreferences("oc", Context.MODE_PRIVATE)
                .getBoolean("boot_start", false)) return;
        if (!Binaries.binaryReady(context)) return;
        try {
            context.startForegroundService(new Intent(context, ServerService.class));
        } catch (Exception ignored) {}
    }
}

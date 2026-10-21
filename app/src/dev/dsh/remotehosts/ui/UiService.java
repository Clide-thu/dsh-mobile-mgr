package dev.dsh.remotehosts.ui;

import android.content.Context;
import android.content.Intent;

import dev.dsh.remotehosts.core.ServiceProtocol;

/**
 * Small helpers around starting {@code TunnelService}.
 *
 * OPEN / TRUST_HOSTKEY may raise the "foreground service must call
 * startForeground()" requirement, so they use {@code startForegroundService}
 * (the activity is visible when the user taps, so the API 31+ background start
 * restriction cannot trigger). CLOSE / STOP_ALL are fire-and-forget cleanups and
 * use plain {@code startService}, which is safe even when the service is not
 * running (the service simply stops itself).
 *
 * Every call returns whether the intent was accepted; callers show a dialog on
 * failure instead of crashing.
 */
final class UiService {

    private UiService() {
    }

    static boolean open(Context context, String alias) {
        return startForeground(context, ServiceProtocol.openIntent(context, alias));
    }

    static boolean close(Context context, String alias) {
        return startSafe(context, ServiceProtocol.closeIntent(context, alias));
    }

    static boolean stopAll(Context context) {
        return startSafe(context, ServiceProtocol.stopAllIntent(context));
    }

    static boolean trustHostKey(
            Context context,
            String alias,
            String hostPort,
            String keyType,
            String keyBase64,
            String fingerprint,
            int remotePort) {
        return startForeground(context, ServiceProtocol.trustHostKeyIntent(
                context, alias, hostPort, keyType, keyBase64, fingerprint, remotePort));
    }

    private static boolean startForeground(Context context, Intent intent) {
        try {
            context.startForegroundService(intent);
            return true;
        } catch (RuntimeException error) {
            return false;
        }
    }

    private static boolean startSafe(Context context, Intent intent) {
        try {
            context.startService(intent);
            return true;
        } catch (RuntimeException error) {
            return false;
        }
    }
}

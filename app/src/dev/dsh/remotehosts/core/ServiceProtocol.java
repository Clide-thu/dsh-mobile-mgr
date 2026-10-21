package dev.dsh.remotehosts.core;

/**
 * The Intent / broadcast contract between the UI and {@link TunnelService}.
 *
 * The service runs in the same process, but a foreground service is started by
 * Intent and reports progress by broadcast, so both sides only depend on these
 * constants instead of on each other.
 */
public final class ServiceProtocol {

    private ServiceProtocol() {
    }

    /** Start (or reuse) the tunnel for one host. Extra: {@link #EXTRA_ALIAS}. */
    public static final String ACTION_OPEN = "dev.dsh.remotehosts.action.OPEN";
    /** Close the tunnel for one host. Extra: {@link #EXTRA_ALIAS}. */
    public static final String ACTION_CLOSE = "dev.dsh.remotehosts.action.CLOSE";
    /** Close every tunnel and leave the foreground. */
    public static final String ACTION_STOP_ALL = "dev.dsh.remotehosts.action.STOP_ALL";
    /**
     * Trust one host key and retry the pending connection.
     * Extras: {@link #EXTRA_ALIAS}, {@link #EXTRA_HOST_PORT}, {@link #EXTRA_KEY_TYPE},
     * {@link #EXTRA_KEY_B64}, {@link #EXTRA_FINGERPRINT}, {@link #EXTRA_REMOTE_PORT}.
     */
    public static final String ACTION_TRUST_HOSTKEY = "dev.dsh.remotehosts.action.TRUST_HOSTKEY";

    public static final String EXTRA_ALIAS = "alias";
    public static final String EXTRA_LOCAL_PORT = "localPort";
    public static final String EXTRA_ERROR_KIND = "errorKind";
    public static final String EXTRA_ERROR_MESSAGE = "errorMessage";
    public static final String EXTRA_HOST_PORT = "hostPort";
    public static final String EXTRA_KEY_TYPE = "keyType";
    public static final String EXTRA_KEY_B64 = "keyB64";
    public static final String EXTRA_FINGERPRINT = "fingerprint";
    public static final String EXTRA_REMOTE_PORT = "remotePort";

    /** Broadcast action carrying every tunnel state change. */
    public static final String EVENT_TUNNEL = "dev.dsh.remotehosts.event.TUNNEL";
    /** One of the {@code STATE_*} values. */
    public static final String EXTRA_STATE = "state";

    public static final String STATE_STARTING = "STARTING";
    public static final String STATE_OPEN = "OPEN";
    public static final String STATE_FAILED = "FAILED";
    public static final String STATE_CLOSED = "CLOSED";
    /** The host key was never seen before (or has changed) and needs the user's decision. */
    public static final String STATE_NEEDS_HOSTKEY = "NEEDS_HOSTKEY";

    /** Notification channel id used by the foreground service. */
    public static final String NOTIFICATION_CHANNEL_ID = "tunnel";
    /** Every foreground notification uses this id. */
    public static final int NOTIFICATION_ID = 0x4453; // "DS"

    /** Build the Intent that asks the service to open one host. */
    public static android.content.Intent openIntent(android.content.Context context, String alias) {
        return new android.content.Intent(context, TunnelService.class)
                .setAction(ACTION_OPEN)
                .putExtra(EXTRA_ALIAS, alias);
    }

    /** Build the Intent that asks the service to close one host. */
    public static android.content.Intent closeIntent(android.content.Context context, String alias) {
        return new android.content.Intent(context, TunnelService.class)
                .setAction(ACTION_CLOSE)
                .putExtra(EXTRA_ALIAS, alias);
    }

    /** Build the Intent that asks the service to close everything. */
    public static android.content.Intent stopAllIntent(android.content.Context context) {
        return new android.content.Intent(context, TunnelService.class).setAction(ACTION_STOP_ALL);
    }

    /** Build the Intent that records a trusted host key and retries. */
    public static android.content.Intent trustHostKeyIntent(
            android.content.Context context,
            String alias,
            String hostPort,
            String keyType,
            String keyBase64,
            String fingerprint,
            int remotePort) {
        return new android.content.Intent(context, TunnelService.class)
                .setAction(ACTION_TRUST_HOSTKEY)
                .putExtra(EXTRA_ALIAS, alias)
                .putExtra(EXTRA_HOST_PORT, hostPort)
                .putExtra(EXTRA_KEY_TYPE, keyType)
                .putExtra(EXTRA_KEY_B64, keyBase64)
                .putExtra(EXTRA_FINGERPRINT, fingerprint)
                .putExtra(EXTRA_REMOTE_PORT, remotePort);
    }

    /** Fill a state event broadcast with the common extras. */
    public static android.content.Intent stateEvent(String state, String alias) {
        return new android.content.Intent(EVENT_TUNNEL)
                .putExtra(EXTRA_STATE, state)
                .putExtra(EXTRA_ALIAS, alias);
    }
}

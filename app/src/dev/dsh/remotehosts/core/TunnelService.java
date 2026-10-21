package dev.dsh.remotehosts.core;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import dev.dsh.remotehosts.R;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Foreground service that owns every SSH tunnel so they survive the activity.
 *
 * Contract (docs/app-spec.md §6):
 *  - started with {@code startForegroundService} for OPEN / TRUST_HOSTKEY, and
 *    with plain {@code startService} for CLOSE / STOP_ALL (so a stopped service
 *    must handle those actions without ever calling startForeground);
 *  - every state change is broadcast as {@link ServiceProtocol#EVENT_TUNNEL}
 *    (package-scoped, receiver registered NOT_EXPORTED by the UI);
 *  - the persistent notification shows the number of open tunnels and offers
 *    "全部断开"; a missing POST_NOTIFICATIONS permission on Android 13+ must not
 *    prevent tunnels from working;
 *  - after the last tunnel closes the service leaves the foreground and stops.
 *
 * SECURITY: the token/password/private key/passphrase never reach a notification,
 * a broadcast extra, a log line or an exception message. Error messages are
 * re-scrubbed with {@link LogSanitizer} before leaving this class.
 */
public final class TunnelService extends Service implements SshTunnelEngine.Listener {

    private static final String TAG = "TunnelService";
    /** Must stay below the UI's 25 s screen timeout while allowing slow networks. */
    private static final int OPEN_TIMEOUT_MS = 20000;
    /** v0.2.1: budget for the in-tunnel DSH token pre-check (docs §5 item 7). */
    private static final int TOKEN_PROBE_TIMEOUT_MS = 5000;
    /** Shown when the remote dsh web rejects the stored token (401/403). */
    private static final String TOKEN_REJECTED_MESSAGE =
            "远端拒绝了 token（可能已失效：dsh web 重启会换新 token）。"
                    + "请在远端重新复制 dsh web 打印的 URL 更新凭证。";
    /** Shown when the token pre-check itself could not reach the remote service. */
    private static final String TOKEN_PROBE_UNREACHABLE_MESSAGE =
            "隧道已建立，但无法读取远端 dsh web 的响应（连接异常/超时）。"
                    + "请确认远端 dsh web 仍在运行后重试。";

    private static volatile TunnelManager managerRef;
    private static volatile boolean running;

    private final AtomicInteger inFlightOpens = new AtomicInteger();
    private ExecutorService executor;
    private HostStore store;
    private TunnelManager manager;
    private volatile boolean foregroundStarted;

    // --------------------------------------------------------------- lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        store = HostStore.get(this);
        manager = newManager();
        managerRef = manager;
        executor = Executors.newCachedThreadPool(new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();

            @Override
            public Thread newThread(Runnable task) {
                Thread thread = new Thread(task, "dsh-tunnel-" + counter.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        });
        createNotificationChannel();
        running = true;
        Log.i(TAG, "服务已创建");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            // Restarted without a request (process death): no tunnel can survive, stop.
            refreshAfterAttempt();
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        Log.i(TAG, "收到 action=" + action);
        if (ServiceProtocol.ACTION_OPEN.equals(action)) {
            handleOpen(intent.getStringExtra(ServiceProtocol.EXTRA_ALIAS));
        } else if (ServiceProtocol.ACTION_CLOSE.equals(action)) {
            handleClose(intent.getStringExtra(ServiceProtocol.EXTRA_ALIAS));
        } else if (ServiceProtocol.ACTION_STOP_ALL.equals(action)) {
            handleStopAll();
        } else if (ServiceProtocol.ACTION_TRUST_HOSTKEY.equals(action)) {
            handleTrustHostKey(intent);
        } else {
            Log.w(TAG, "未知 action，忽略");
            refreshAfterAttempt();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        managerRef = null;
        try {
            if (manager != null) manager.closeAll();
        } catch (RuntimeException error) {
            Log.w(TAG, "关闭隧道失败：" + LogSanitizer.sanitizeThrowable(error));
        }
        if (executor != null) executor.shutdownNow();
        foregroundStarted = false;
        Log.i(TAG, "服务已销毁");
        super.onDestroy();
    }

    // ------------------------------------------------------------------ actions

    private void handleOpen(String rawAlias) {
        String alias = rawAlias == null ? "" : rawAlias.trim();
        if (alias.isEmpty()) {
            broadcastFailed(null, AppError.badRequest("缺少主机 alias"));
            refreshAfterAttempt();
            return;
        }
        ensureManagerUsable();
        startForegroundSafely();
        broadcastState(alias, ServiceProtocol.STATE_STARTING);
        inFlightOpens.incrementAndGet();
        submitOpen(alias, null);
    }

    /** Trust a host key (user confirmed the fingerprint), then retry the connection. */
    private void handleTrustHostKey(Intent intent) {
        String alias = trim(intent.getStringExtra(ServiceProtocol.EXTRA_ALIAS));
        String hostPortKey = trim(intent.getStringExtra(ServiceProtocol.EXTRA_HOST_PORT));
        String keyType = trim(intent.getStringExtra(ServiceProtocol.EXTRA_KEY_TYPE));
        String keyBase64 = intent.getStringExtra(ServiceProtocol.EXTRA_KEY_B64);
        String fingerprint = trim(intent.getStringExtra(ServiceProtocol.EXTRA_FINGERPRINT));
        if (alias.isEmpty() || hostPortKey.isEmpty() || keyBase64 == null || keyBase64.isEmpty()) {
            broadcastFailed(alias.isEmpty() ? null : alias,
                    AppError.badRequest("信任主机密钥的参数不完整"));
            refreshAfterAttempt();
            return;
        }
        ensureManagerUsable();
        startForegroundSafely();
        broadcastState(alias, ServiceProtocol.STATE_STARTING);
        inFlightOpens.incrementAndGet();
        submitOpen(alias, new PendingTrust(hostPortKey, keyType, keyBase64, fingerprint));
    }

    private void handleClose(String rawAlias) {
        String alias = trim(rawAlias);
        if (alias.isEmpty()) {
            refreshAfterAttempt();
            return;
        }
        boolean closed = manager != null && manager.close(alias);
        if (!closed) {
            // No tunnel was open (e.g. the service was restarted); still tell the UI
            // so its row can reset to "configured".
            broadcastState(alias, ServiceProtocol.STATE_CLOSED);
        }
        refreshAfterAttempt();
    }

    private void handleStopAll() {
        TunnelManager current = manager;
        if (current != null) {
            current.closeAll();
        } else {
            managerRef = null;
        }
        stopForegroundAndStop();
    }

    // ------------------------------------------------------------------- opens

    /** New tunnel manager wired to the host store (also used after a stopAll). */
    private TunnelManager newManager() {
        return new TunnelManager(this,
                new SshTunnelEngine.HostResolver() {
                    @Override
                    public HostConfig find(String alias) {
                        return store == null ? null : store.find(alias);
                    }
                },
                // v0.2.0: hosts reference app-managed key pairs by keyId.
                new SshTunnelEngine.KeyResolver() {
                    @Override
                    public SshKeyRecord find(String keyId) {
                        return store == null ? null : store.findSshKey(keyId);
                    }
                });
    }

    /**
     * A previous ACTION_STOP_ALL leaves its manager "stopped"; a later OPEN on the
     * same service instance must get a fresh one rather than fail.
     */
    private TunnelManager ensureManagerUsable() {
        TunnelManager current = manager;
        if (current == null || current.isStopped()) {
            current = newManager();
            manager = current;
            managerRef = current;
        }
        return current;
    }

    /** One pending trust decision carried from ACTION_TRUST_HOSTKEY into the worker. */
    private static final class PendingTrust {
        final String hostPortKey;
        final String keyType;
        final String keyBase64;
        final String fingerprint;

        PendingTrust(String hostPortKey, String keyType, String keyBase64, String fingerprint) {
            this.hostPortKey = hostPortKey;
            this.keyType = keyType;
            this.keyBase64 = keyBase64;
            this.fingerprint = fingerprint;
        }
    }

    private void submitOpen(final String alias, final PendingTrust trust) {
        ExecutorService pool = executor;
        if (pool == null) {
            inFlightOpens.decrementAndGet();
            refreshAfterAttempt();
            return;
        }
        try {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    openTask(alias, trust);
                }
            });
        } catch (RuntimeException error) {
            Log.w(TAG, "无法调度连接任务：" + LogSanitizer.sanitizeThrowable(error));
            inFlightOpens.decrementAndGet();
            broadcastFailed(alias, AppError.internal("内部错误：无法调度连接任务"));
            refreshAfterAttempt();
        }
    }

    /** Worker thread: trust (optional), resolve the host, open the tunnel, broadcast. */
    private void openTask(String alias, PendingTrust trust) {
        try {
            if (trust != null) {
                try {
                    store.trustHostKey(trust.hostPortKey, trust.keyType, trust.keyBase64, trust.fingerprint);
                } catch (IOException saveError) {
                    Log.w(TAG, "保存主机密钥信任失败：" + LogSanitizer.sanitizeThrowable(saveError));
                    broadcastFailed(alias, AppError.internal("无法保存主机密钥信任，请重试"));
                    return;
                }
            }
            HostConfig host = store == null ? null : store.find(alias);
            if (host == null) {
                broadcastFailed(alias, AppError.unknownHost("主机不存在或已删除：" + alias));
                return;
            }
            if (host.accessMode == HostConfig.AccessMode.DIRECT_URL) {
                broadcastFailed(alias, AppError.badRequest("该主机是「直接网址」模式，不需要 SSH 隧道"));
                return;
            }
            int localPort = manager.ensureOpen(host, hostKeyPolicy(), OPEN_TIMEOUT_MS);

            // v0.2.1 (docs/app-spec.md §5 item 7; v0.2.5 follows the GLOBAL
            // browser/WebView setting): verify the DSH token THROUGH the freshly
            // opened tunnel only when the external browser will receive the token
            // URL. A stale token (dsh web restarted) would otherwise only show the
            // remote "authentication required" page. In built-in WebView mode the
            // viewer performs its own token exchange and reports a clear error,
            // so this probe is skipped by design (same as v0.2.4's appendToken=false).
            // The probe never logs the token.
            if (SettingsStore.isBrowserMode(this) && host.token != null && !host.token.isEmpty()) {
                TokenProbe.Result probe = TokenProbe.check(localPort, host.token, TOKEN_PROBE_TIMEOUT_MS);
                if (probe == TokenProbe.Result.REJECTED) {
                    closeAfterProbeFailure(alias);
                    broadcastFailed(alias, AppError.needsCredentials(TOKEN_REJECTED_MESSAGE));
                    Log.w(TAG, "远端拒绝了 DSH token，已关闭隧道：" + alias);
                    return;
                }
                if (probe == TokenProbe.Result.UNREACHABLE) {
                    closeAfterProbeFailure(alias);
                    broadcastFailed(alias, AppError.unreachable(TOKEN_PROBE_UNREACHABLE_MESSAGE));
                    Log.w(TAG, "隧道已建立但 DSH token 预校验失败：" + alias);
                    return;
                }
            }

            broadcastOpen(alias, localPort);
            Log.i(TAG, "隧道已打开 " + alias + " -> 127.0.0.1:" + localPort);
        } catch (SshTunnelEngine.HostKeyUnknownException unknown) {
            if (unknown.changed) {
                broadcastFailed(alias, AppError.hostKeyChanged(
                        "主机密钥已变更，可能被中间人攻击：" + unknown.hostPortKey));
            } else {
                broadcastNeedsHostKey(alias, unknown);
            }
        } catch (AppError error) {
            broadcastFailed(alias, sanitizeForHost(alias, error));
        } catch (RuntimeException unexpected) {
            Log.w(TAG, "连接任务异常：" + LogSanitizer.sanitizeThrowable(unexpected));
            broadcastFailed(alias, AppError.internal("内部错误：" + LogSanitizer.sanitizeThrowable(unexpected)));
        } finally {
            inFlightOpens.decrementAndGet();
            refreshAfterAttempt();
        }
    }

    /** TOFU policy: trust exactly what is stored, ask on unknown, fail on change. */
    private SshTunnelEngine.HostKeyPolicy hostKeyPolicy() {
        return new SshTunnelEngine.HostKeyPolicy() {
            @Override
            public Decision check(String hostPortKey, String keyType, String keyBase64, String fingerprint) {
                if (store == null) return Decision.REJECT_ONCE;
                String trusted = store.trustedHostKey(hostPortKey);
                if (trusted == null || trusted.isEmpty()) return Decision.REJECT_ONCE;
                return trusted.equals(keyBase64) ? Decision.TRUST : Decision.REJECT_AND_FAIL;
            }
        };
    }

    private AppError sanitizeForHost(String alias, AppError error) {
        if (error == null) return AppError.internal("未知错误");
        HostConfig host = store == null ? null : store.find(alias);
        return LogSanitizer.sanitizeError(error, LogSanitizer.secretsOf(host));
    }

    /**
     * Tear down a tunnel whose post-open token check failed. The engine performs
     * the actual disconnect and emits CLOSED (the UI resets its row); the caller
     * then broadcasts FAILED so the reason is shown as a dialog instead of a
     * broken browser page. Closing also keeps retries clean: the next OPEN builds
     * a fresh tunnel (and re-probes the token).
     */
    private void closeAfterProbeFailure(String alias) {
        TunnelManager current = manager;
        if (current != null) {
            try {
                current.close(alias);
            } catch (RuntimeException error) {
                Log.w(TAG, "关闭隧道失败：" + LogSanitizer.sanitizeThrowable(error));
            }
        }
    }

    // ------------------------------------------------------- engine callbacks

    @Override
    public void onState(String alias, String state) {
        Log.i(TAG, "状态 " + state + "：" + alias);
        if (SshTunnelEngine.STATE_CLOSED.equals(state)) {
            broadcastState(alias, ServiceProtocol.STATE_CLOSED);
        }
        refreshAfterAttempt();
    }

    @Override
    public void onHostKey(String alias, String hostPortKey, String keyType,
                          String keyBase64, String fingerprint) {
        // The decision is reported through HostKeyUnknownException, which carries
        // every field the UI needs for its confirmation dialog. This callback is
        // intentionally diagnostics-only; public key material is safe to log.
        Log.i(TAG, "主机密钥待确认：" + hostPortKey + " " + keyType + " " + fingerprint);
    }

    @Override
    public void onLog(String message) {
        if (message != null && !message.isEmpty()) {
            Log.d(TAG, message);
        }
    }

    // ----------------------------------------------------------- notifications

    private void createNotificationChannel() {
        try {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) return;
            if (manager.getNotificationChannel(ServiceProtocol.NOTIFICATION_CHANNEL_ID) != null) return;
            NotificationChannel channel = new NotificationChannel(
                    ServiceProtocol.NOTIFICATION_CHANNEL_ID,
                    getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.notif_channel_desc));
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        } catch (RuntimeException error) {
            // Notification setup must never prevent tunnels from working.
            Log.w(TAG, "通知渠道创建失败：" + LogSanitizer.sanitizeThrowable(error));
        }
    }

    private Notification buildNotification(int openCount) {
        Intent stopIntent = ServiceProtocol.stopAllIntent(this);
        PendingIntent stopPending = PendingIntent.getService(
                this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = new Notification.Builder(this, ServiceProtocol.NOTIFICATION_CHANNEL_ID);
        builder.setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(notificationText(openCount))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .addAction(new Notification.Action.Builder(
                        R.drawable.ic_notif, getString(R.string.notif_action_stop), stopPending).build());

        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launchIntent != null) {
            PendingIntent contentPending = PendingIntent.getActivity(
                    this, 0, launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.setContentIntent(contentPending);
        }
        return builder.build();
    }

    private CharSequence notificationText(int openCount) {
        if (openCount <= 0) {
            // Setting up / connecting: no tunnel count yet.
            return getString(R.string.notif_channel_name);
        }
        if (openCount == 1) {
            return getString(R.string.notif_text_one);
        }
        return getString(R.string.notif_text_many, openCount);
    }

    /** Enter the foreground (or refresh the notification). Never throws. */
    private void startForegroundSafely() {
        try {
            int openCount = manager == null ? 0 : manager.openCount();
            Notification notification = buildNotification(openCount);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(ServiceProtocol.NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(ServiceProtocol.NOTIFICATION_ID, notification);
            }
            foregroundStarted = true;
        } catch (RuntimeException error) {
            // Android 12+ may refuse a foreground start from the background (the UI
            // is expected to be visible, but never crash if it is not); a missing
            // Android 13+ notification permission does not throw.
            Log.w(TAG, "进入前台失败：" + LogSanitizer.sanitizeThrowable(error));
        }
    }

    private void updateNotification(int openCount) {
        if (!foregroundStarted) return;
        try {
            NotificationManager notificationManager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager != null) {
                notificationManager.notify(ServiceProtocol.NOTIFICATION_ID, buildNotification(openCount));
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "更新通知失败：" + LogSanitizer.sanitizeThrowable(error));
        }
    }

    private void stopForegroundAndStop() {
        try {
            if (foregroundStarted) stopForeground(true);
        } catch (RuntimeException error) {
            Log.w(TAG, "退出前台失败：" + LogSanitizer.sanitizeThrowable(error));
        }
        foregroundStarted = false;
        stopSelf();
    }

    /** Refresh the tunnel count and leave the foreground when nothing is open. */
    private void refreshAfterAttempt() {
        TunnelManager current = manager;
        if (current == null) {
            stopForegroundAndStop();
            return;
        }
        int openCount = current.openCount();
        if (openCount == 0 && inFlightOpens.get() == 0) {
            stopForegroundAndStop();
        } else {
            updateNotification(openCount);
        }
    }

    // -------------------------------------------------------------- broadcasts

    private void broadcast(Intent intent) {
        try {
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
        } catch (RuntimeException error) {
            Log.w(TAG, "广播失败：" + LogSanitizer.sanitizeThrowable(error));
        }
    }

    private void broadcastState(String alias, String state) {
        broadcast(ServiceProtocol.stateEvent(state, alias));
    }

    private void broadcastOpen(String alias, int localPort) {
        broadcast(ServiceProtocol.stateEvent(ServiceProtocol.STATE_OPEN, alias)
                .putExtra(ServiceProtocol.EXTRA_LOCAL_PORT, localPort));
    }

    private void broadcastFailed(String alias, AppError error) {
        AppError safe = error == null ? AppError.internal("未知错误") : LogSanitizer.sanitizeError(error);
        Intent intent = ServiceProtocol.stateEvent(ServiceProtocol.STATE_FAILED, alias)
                .putExtra(ServiceProtocol.EXTRA_ERROR_KIND, safe.kind.id)
                .putExtra(ServiceProtocol.EXTRA_ERROR_MESSAGE, safe.getMessage());
        broadcast(intent);
    }

    private void broadcastNeedsHostKey(String alias, SshTunnelEngine.HostKeyUnknownException unknown) {
        String hostPortKey = unknown.hostPortKey == null ? "" : unknown.hostPortKey;
        String keyType = unknown.keyType == null ? "" : unknown.keyType;
        String keyBase64 = unknown.keyBase64 == null ? "" : unknown.keyBase64;
        String fingerprint = unknown.fingerprint == null ? "" : unknown.fingerprint;
        if (hostPortKey.isEmpty() || keyBase64.isEmpty() || fingerprint.isEmpty()) {
            // Without key material the UI cannot offer "信任"; report a clear failure.
            broadcastFailed(alias, AppError.internal("主机密钥信息不完整，无法确认"));
            return;
        }
        Intent intent = ServiceProtocol.stateEvent(ServiceProtocol.STATE_NEEDS_HOSTKEY, alias)
                .putExtra(ServiceProtocol.EXTRA_HOST_PORT, hostPortKey)
                .putExtra(ServiceProtocol.EXTRA_KEY_TYPE, keyType)
                .putExtra(ServiceProtocol.EXTRA_KEY_B64, keyBase64)
                .putExtra(ServiceProtocol.EXTRA_FINGERPRINT, fingerprint);
        broadcast(intent);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    // ------------------------------------------------------------- static API

    /**
     * Immutable alias -> localPort snapshot of the tunnels that are currently
     * OPEN. The UI uses it to repaint rows after onResume without waiting for a
     * broadcast. Never null; empty when the service is not running.
     */
    public static Map<String, Integer> openTunnelsSnapshot() {
        TunnelManager current = managerRef;
        if (current == null) return Collections.emptyMap();
        try {
            return current.openTunnels();
        } catch (RuntimeException error) {
            return Collections.emptyMap();
        }
    }

    /** Whether the foreground service is currently created/running. */
    public static boolean isRunning() {
        return running;
    }
}

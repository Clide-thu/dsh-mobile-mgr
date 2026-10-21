package dev.dsh.remotehosts.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Owns the per-alias tunnel table above {@link SshTunnelEngine}.
 *
 * Responsibilities:
 *  - concurrent {@code ensureOpen} calls for the same alias share ONE startup
 *    (the first caller connects, the others await the same result);
 *  - keep an alias -> localPort snapshot of the tunnels that reached OPEN, so the
 *    foreground service can answer {@code openTunnelsSnapshot()} at any time;
 *  - translate engine CLOSED callbacks into table updates before forwarding them
 *    to the service listener.
 *
 * Pure Java so the sharing behaviour is unit/integration testable on a desktop
 * JVM. Android imports live only in {@link TunnelService}.
 */
public final class TunnelManager {

    private final SshTunnelEngine engine;
    private final SshTunnelEngine.Listener listener;
    private final Object monitor = new Object();
    /** alias -> in-flight startup result. Guarded by {@link #monitor}. */
    private final Map<String, CompletableFuture<Integer>> inFlight = new HashMap<>();
    /** alias -> localPort for tunnels in OPEN state. Guarded by {@link #monitor}. */
    private final Map<String, Integer> openPorts = new LinkedHashMap<>();
    /** Set once the owning service shuts down, so a late callback cannot reopen. */
    private volatile boolean stopped;

    public TunnelManager(SshTunnelEngine.Listener listener) {
        this(listener, null);
    }

    public TunnelManager(SshTunnelEngine.Listener listener, SshTunnelEngine.HostResolver resolver) {
        this(listener, resolver, null);
    }

    /**
     * v0.2.0: full wiring, including the app-managed SSH key store used to
     * resolve {@code host.keyId} (docs/app-spec.md §8.4).
     */
    public TunnelManager(SshTunnelEngine.Listener listener, SshTunnelEngine.HostResolver resolver,
                         SshTunnelEngine.KeyResolver keyResolver) {
        this.listener = listener;
        this.engine = new SshTunnelEngine(new SshTunnelEngine.Listener() {
            @Override public void onState(String alias, String state) {
                if (SshTunnelEngine.STATE_CLOSED.equals(state)) {
                    synchronized (monitor) {
                        openPorts.remove(alias);
                    }
                }
                forwardState(alias, state);
            }

            @Override public void onHostKey(String alias, String hostPortKey, String keyType,
                                            String keyBase64, String fingerprint) {
                forwardHostKey(alias, hostPortKey, keyType, keyBase64, fingerprint);
            }

            @Override public void onLog(String message) {
                forwardLog(message);
            }
        }, resolver, keyResolver);
    }

    /**
     * Ensure a tunnel for {@code host}; concurrent calls for the same alias share
     * one startup attempt and see the same result or error.
     */
    public int ensureOpen(HostConfig host, SshTunnelEngine.HostKeyPolicy policy, int timeoutMs) throws AppError {
        return ensureOpen(host, policy, timeoutMs, true);
    }

    /**
     * Same as {@link #ensureOpen(HostConfig, SshTunnelEngine.HostKeyPolicy, int)}
     * with control over the HTTP readiness probe; {@code requireHttpReady=false}
     * exists for the desktop integration test and diagnostics only.
     */
    public int ensureOpen(HostConfig host, SshTunnelEngine.HostKeyPolicy policy,
                          int timeoutMs, boolean requireHttpReady) throws AppError {
        if (stopped) throw AppError.badRequest("隧道服务已停止，请重新打开主机");
        String alias = host == null || host.alias == null ? "" : host.alias.trim();
        if (alias.isEmpty()) {
            // Let the engine produce its precise BAD_REQUEST message for anything
            // else, but we need the alias to key the in-flight map.
            return engine.ensureOpen(host, policy, timeoutMs, requireHttpReady);
        }

        CompletableFuture<Integer> future;
        boolean leader = false;
        synchronized (monitor) {
            Integer knownPort = openPorts.get(alias);
            if (knownPort != null && engine.isOpen(alias)) return knownPort;
            openPorts.remove(alias);
            future = inFlight.get(alias);
            if (future == null) {
                future = new CompletableFuture<>();
                inFlight.put(alias, future);
                leader = true;
            }
        }

        if (!leader) return await(future);

        try {
            int localPort = engine.ensureOpen(host, policy, timeoutMs, requireHttpReady);
            synchronized (monitor) {
                openPorts.put(alias, localPort);
                inFlight.remove(alias);
            }
            if (stopped) {
                // closeAll() raced with a connect that had already started: do not
                // leave an orphan session behind.
                engine.close(alias);
                throw AppError.badRequest("隧道服务已停止，请重新打开主机");
            }
            future.complete(localPort);
            return localPort;
        } catch (AppError error) {
            synchronized (monitor) {
                inFlight.remove(alias);
                openPorts.remove(alias);
            }
            future.completeExceptionally(error);
            throw error;
        } catch (RuntimeException unexpected) {
            AppError error = AppError.internal("启动隧道失败："
                    + LogSanitizer.sanitizeThrowable(unexpected));
            synchronized (monitor) {
                inFlight.remove(alias);
                openPorts.remove(alias);
            }
            future.completeExceptionally(error);
            throw error;
        }
    }

    /** Close one alias' tunnel; returns whether one was open/known. */
    public boolean close(String alias) {
        boolean closed = engine.close(alias);
        synchronized (monitor) {
            openPorts.remove(alias);
        }
        return closed;
    }

    /** Close every tunnel and refuse further opens (the service is stopping). */
    public void closeAll() {
        stopped = true;
        engine.closeAll();
        synchronized (monitor) {
            openPorts.clear();
        }
    }

    public boolean isOpen(String alias) {
        return engine.isOpen(alias);
    }

    public int localPort(String alias) {
        return engine.localPort(alias);
    }

    /** Immutable alias -> localPort snapshot of tunnels that are currently OPEN. */
    public Map<String, Integer> openTunnels() {
        synchronized (monitor) {
            Map<String, Integer> snapshot = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> entry : openPorts.entrySet()) {
                if (engine.isOpen(entry.getKey())) snapshot.put(entry.getKey(), entry.getValue());
            }
            return Collections.unmodifiableMap(snapshot);
        }
    }

    public int openCount() {
        return openTunnels().size();
    }

    private static int await(CompletableFuture<Integer> future) throws AppError {
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw AppError.internal("等待隧道启动时被中断");
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof AppError) throw (AppError) cause;
            throw AppError.internal("启动隧道失败：" + LogSanitizer.sanitizeThrowable(cause));
        }
    }

    // ---------------------------------------------------- listener forwarding

    private void forwardState(String alias, String state) {
        if (listener == null) return;
        try {
            listener.onState(alias, state);
        } catch (RuntimeException ignored) {
            // Listener failures must not corrupt tunnel bookkeeping.
        }
    }

    private void forwardHostKey(String alias, String hostPortKey, String keyType,
                                String keyBase64, String fingerprint) {
        if (listener == null) return;
        try {
            listener.onHostKey(alias, hostPortKey, keyType, keyBase64, fingerprint);
        } catch (RuntimeException ignored) {
            // Best effort.
        }
    }

    private void forwardLog(String message) {
        if (listener == null) return;
        try {
            listener.onLog(message);
        } catch (RuntimeException ignored) {
            // Best effort.
        }
    }

    /** Whether {@link #closeAll()} has run (the owning service is stopping). */
    public boolean isStopped() {
        return stopped;
    }

    /** Snapshot of aliases currently being started (diagnostics/tests). */
    public java.util.List<String> startingAliases() {
        synchronized (monitor) {
            return Collections.unmodifiableList(new ArrayList<>(inFlight.keySet()));
        }
    }
}

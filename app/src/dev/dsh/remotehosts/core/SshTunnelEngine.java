package dev.dsh.remotehosts.core;

import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchChangedHostKeyException;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.JSchUnknownHostKeyException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SocketFactory;
import com.jcraft.jsch.UserInfo;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * SSH tunnel engine: one {@code Session} pair (jump + target when ProxyJump is
 * configured) and one loopback local-forward per host, built on JSch.
 *
 * Pure Java (JSch only) so the same code runs on Android and on a desktop JVM
 * for integration tests; nothing here touches Android APIs or persistent state.
 * Trusted host keys are supplied by the caller through {@link HostKeyPolicy};
 * this class never trusts a host key on its own.
 *
 * Lifecycle per alias: at most one live tunnel. {@link #ensureOpen} is
 * idempotent and per-alias serialised; {@link #close}/{@link #closeAll} tear
 * down the sessions and their forwards. A daemon watcher thread turns an
 * unexpected disconnect into an {@link Listener#onState(String, String)} call so
 * the foreground service can broadcast CLOSED.
 *
 * Security contract:
 *  - the token/password/private key/passphrase of a host are never logged or put
 *    into an exception message; every message leaving this class goes through
 *    {@link LogSanitizer} with the host's secret material;
 *  - host keys are TOFU: unknown or changed keys are reported through a
 *    {@link HostKeyUnknownException} for the UI to confirm, never auto-accepted.
 */
public final class SshTunnelEngine {

    /** Engine-level state value; matches {@code ServiceProtocol.STATE_CLOSED}. */
    public static final String STATE_CLOSED = "CLOSED";

    /** Documented local-port window (docs/app-spec.md §5.4). */
    public static final int MIN_LOCAL_PORT = 20000;
    public static final int MAX_LOCAL_PORT = 60000;
    /** Reserved by another DSH component; never handed out as a tunnel port. */
    public static final int RESERVED_LOCAL_PORT = 19387;

    private static final int DEFAULT_TIMEOUT_MS = 20000;
    private static final int SERVER_ALIVE_INTERVAL_MS = 15000;
    private static final int SERVER_ALIVE_COUNT_MAX = 3;
    private static final int FORWARD_ATTEMPTS = 3;
    private static final int READY_RETRY_DELAY_MS = 150;
    private static final int READY_HTTP_TIMEOUT_MS = 2000;
    private static final int WATCH_INTERVAL_MS = 1000;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static final UserInfo SILENT_USER_INFO = new UserInfo() {
        @Override public String getPassphrase() { return null; }
        @Override public String getPassword() { return null; }
        @Override public boolean promptPassword(String message) { return false; }
        @Override public boolean promptPassphrase(String message) { return false; }
        @Override public boolean promptYesNo(String message) { return false; }
        @Override public void showMessage(String message) { }
    };

    /** Receives lifecycle events. Every method is optional and null-safe. */
    public interface Listener {
        /** A tunnel state change originated by the engine itself (currently CLOSED). */
        void onState(String alias, String state);

        /**
         * A host key needs a trust decision (called before the policy is asked).
         * All fields are public key material / a fingerprint, never a secret.
         */
        void onHostKey(String alias, String hostPortKey, String keyType, String keyBase64, String fingerprint);

        /** Diagnostics; already sanitised by the engine. */
        void onLog(String message);
    }

    /** Trust decision for one SSH host key. */
    public interface HostKeyPolicy {
        enum Decision {
            /** The caller recognises this key: proceed. */
            TRUST,
            /** Never seen before: abort and let the UI ask the user (NEEDS_HOSTKEY). */
            REJECT_ONCE,
            /** Known-different key: abort and report a changed key (possible MITM). */
            REJECT_AND_FAIL
        }

        Decision check(String hostPortKey, String keyType, String keyBase64, String fingerprint);
    }

    /** Resolves a ProxyJump value written as another host's alias, or null. */
    public interface HostResolver {
        HostConfig find(String alias);
    }

    /**
     * v0.2.0: resolves a host's {@link HostConfig#keyId} to its app-managed key
     * pair. {@code null} means "no such key" (the engine reports
     * NEEDS_CREDENTIALS so the UI can ask the user to pick another key).
     */
    public interface KeyResolver {
        SshKeyRecord find(String keyId);
    }

    /**
     * A host key the user has not accepted. Carries the public key data needed to
     * show a confirmation dialog and to remember the decision; never a secret.
     */
    public static final class HostKeyUnknownException extends AppError {
        /** True when a previously trusted key changed (possible man-in-the-middle). */
        public final boolean changed;
        public final String hostPortKey;
        public final String keyType;
        public final String keyBase64;
        public final String fingerprint;

        HostKeyUnknownException(
                boolean changed, String hostPortKey, String keyType, String keyBase64, String fingerprint) {
            super(changed ? ErrorKind.HOST_KEY_CHANGED : ErrorKind.INTERNAL,
                    (changed ? "SSH 主机密钥已变更，连接被拒绝：" : "SSH 主机密钥尚未信任：")
                            + hostPortKey + "（" + fingerprint + "）");
            this.changed = changed;
            this.hostPortKey = hostPortKey == null ? "" : hostPortKey;
            this.keyType = keyType == null ? "" : keyType;
            this.keyBase64 = keyBase64 == null ? "" : keyBase64;
            this.fingerprint = fingerprint == null ? "" : fingerprint;
        }
    }

    private final Listener listener;
    private final HostResolver resolver;
    private final KeyResolver keyResolver;
    private final Random random = new Random();

    /** alias -> live tunnel; guarded by {@code this}. */
    private final Map<String, Tunnel> tunnels = new HashMap<>();
    /** alias -> monitor used to serialise ensureOpen/close; guarded by {@code this}. */
    private final Map<String, Object> aliasLocks = new HashMap<>();

    public SshTunnelEngine(Listener listener) {
        this(listener, null, null);
    }

    /**
     * @deprecated v0.1.0-style constructor kept for source compatibility; use
     *             {@link #SshTunnelEngine(Listener, HostResolver, KeyResolver)}
     *             when app-managed SSH keys are available.
     */
    @Deprecated
    public SshTunnelEngine(Listener listener, HostResolver resolver) {
        this(listener, resolver, null);
    }

    /** v0.2.0: resolves {@code host.keyId} against the app key store. */
    public SshTunnelEngine(Listener listener, KeyResolver keyResolver) {
        this(listener, null, keyResolver);
    }

    public SshTunnelEngine(Listener listener, HostResolver resolver, KeyResolver keyResolver) {
        this.listener = listener;
        this.resolver = resolver;
        this.keyResolver = keyResolver;
    }

    // ------------------------------------------------------------------ public

    /**
     * Ensure a usable tunnel for {@code host} and return its local loopback port.
     *
     * Blocks until the forward answers HTTP or {@code timeoutMs} elapses; call it
     * off the main thread.
     *
     * @throws AppError with {@code NEEDS_CREDENTIALS}, {@code UNREACHABLE},
     *                  {@code BAD_REQUEST} or {@code INTERNAL}
     * @throws HostKeyUnknownException when the user must decide about a host key
     */
    public int ensureOpen(HostConfig host, HostKeyPolicy policy, int timeoutMs) throws AppError {
        return ensureOpen(host, policy, timeoutMs, true);
    }

    /**
     * Same as {@link #ensureOpen(HostConfig, HostKeyPolicy, int)} with control
     * over the readiness probe.
     *
     * {@code requireHttpReady=false} skips the "an HTTP request through the tunnel
     * answers" check. That mode exists for the desktop integration test and
     * diagnostics against non-HTTP ports (e.g. forwarding to sshd on 22); the app
     * itself always uses the HTTP check.
     */
    public int ensureOpen(HostConfig host, HostKeyPolicy policy, int timeoutMs, boolean requireHttpReady)
            throws AppError {
        validate(host, policy);
        String alias = host.alias.trim();
        Object lock = lockFor(alias);
        synchronized (lock) {
            Tunnel existing = tunnelFor(alias);
            if (existing != null) {
                if (existing.isAlive()) return existing.localPort;
                retire(existing);
            }
            Tunnel tunnel = connect(host, policy, normalizeTimeout(timeoutMs), requireHttpReady);
            synchronized (this) {
                tunnels.put(alias, tunnel);
            }
            startWatcher(tunnel);
            return tunnel.localPort;
        }
    }

    /** Whether a live tunnel exists for the alias. */
    public boolean isOpen(String alias) {
        Tunnel tunnel = tunnelFor(alias);
        return tunnel != null && tunnel.isAlive();
    }

    /** Local loopback port of a live tunnel, or -1. */
    public int localPort(String alias) {
        Tunnel tunnel = tunnelFor(alias);
        return tunnel != null && tunnel.isAlive() ? tunnel.localPort : -1;
    }

    /**
     * Close the tunnel for one alias.
     *
     * @return true when a live/known tunnel was closed; false when nothing was open
     */
    public boolean close(String alias) {
        if (alias == null) return false;
        Tunnel removed;
        Object lock = lockFor(alias);
        synchronized (lock) {
            synchronized (this) {
                removed = tunnels.remove(alias);
            }
            if (removed == null) return false;
            shutdown(removed);
        }
        emitState(alias, STATE_CLOSED);
        return true;
    }

    /** Close every tunnel. */
    public void closeAll() {
        List<String> aliases;
        synchronized (this) {
            aliases = new ArrayList<>(tunnels.keySet());
        }
        for (String alias : aliases) {
            close(alias);
        }
    }

    /** Canonical trusted-host-key id shared with {@link HostStore}. */
    public static String hostPortKey(String host, int port) {
        String resolved = host == null ? "" : host.trim();
        int effectivePort = port >= 1 && port <= 65535 ? port : 22;
        return resolved + ":" + effectivePort;
    }

    // ------------------------------------------------------------- connection

    /** A ProxyJump destination with credentials resolved (v1: target host's auth). */
    private static final class JumpTarget {
        final String user;
        final String host;
        final int port;

        JumpTarget(String user, String host, int port) {
            this.user = user;
            this.host = host;
            this.port = port;
        }

        String canonicalKey() {
            return hostPortKey(host, port);
        }
    }

    /** Open one session through {@code jsch}, honouring the host-key policy. */
    private Session openSession(
            JSch jsch,
            String canonicalKey,
            String user,
            String host,
            int port,
            HostConfig auth,
            int connectTimeoutMs,
            HostKeyPolicy policy,
            String alias,
            String[] secrets,
            SocketFactory socketFactory) throws AppError {
        if (connectTimeoutMs < 1000) {
            throw AppError.unreachable("连接超时：没有足够的剩余时间连接 " + host + ":" + port);
        }
        PolicyRepository repository = new PolicyRepository(canonicalKey, policy, listener, alias);
        jsch.setHostKeyRepository(repository);
        for (int attempt = 0; attempt < 2; attempt += 1) {
            Session session = null;
            try {
                session = jsch.getSession(user, host, port);
                configureSession(session, auth, canonicalKey, socketFactory);
                emitLog("连接 " + user + "@" + host + ":" + port + "（" + (socketFactory == null ? "直连" : "经跳板") + "）", secrets);
                session.connect(connectTimeoutMs);
                return session;
            } catch (JSchUnknownHostKeyException unknown) {
                if (session != null) session.disconnect();
                // The repository captured the presented key; ask the caller.
                repository.decide(false);
                // TRUST: loop once more with the same repository (key now accepted).
            } catch (JSchChangedHostKeyException changedKey) {
                if (session != null) session.disconnect();
                repository.decide(true);
            } catch (JSchException error) {
                if (session != null) session.disconnect();
                throw classify(error, host, port, secrets);
            } catch (RuntimeException error) {
                if (session != null) session.disconnect();
                throw classify(error, host, port, secrets);
            }
        }
        throw AppError.internal("主机密钥已确认，但重新连接仍然失败");
    }

    private void configureSession(
            Session session, HostConfig auth, String canonicalKey, SocketFactory socketFactory) throws JSchException {
        session.setUserInfo(SILENT_USER_INFO);
        // Use our own TOFU repository instead of a known_hosts file. "yes" makes
        // an unknown key abort with JSchUnknownHostKeyException, which we turn into
        // a HostKeyUnknownException; JSch's UserInfo prompt is never consulted.
        session.setConfig("StrictHostKeyChecking", "yes");
        session.setConfig("HashKnownHosts", "no");
        session.setConfig("PreferredAuthentications", preferredAuthentications(auth));
        session.setHostKeyAlias(canonicalKey);
        session.setServerAliveInterval(SERVER_ALIVE_INTERVAL_MS);
        session.setServerAliveCountMax(SERVER_ALIVE_COUNT_MAX);
        if (auth.authType == HostConfig.AuthType.PASSWORD
                && auth.password != null && !auth.password.isEmpty()) {
            session.setPassword(auth.password);
        }
        if (socketFactory != null) {
            session.setSocketFactory(socketFactory);
        }
    }

    private static String preferredAuthentications(HostConfig auth) {
        if (auth.authType == HostConfig.AuthType.PRIVATE_KEY) {
            return "publickey,password,keyboard-interactive";
        }
        return "password,keyboard-interactive,publickey";
    }

    /**
     * Private-key material resolved for one connection attempt (v0.2.0: either
     * from the app key store through {@link KeyResolver} or from the legacy
     * inline fields of an un-migrated host).
     */
    private static final class KeyMaterial {
        final String privateKey;
        final String passphrase;

        KeyMaterial(String privateKey, String passphrase) {
            this.privateKey = privateKey == null ? "" : privateKey;
            this.passphrase = passphrase == null ? "" : passphrase;
        }
    }

    /**
     * Resolve the private-key material for {@code host}.
     *
     * Order (docs/app-spec.md §5): keyId -> resolver; keyId missing but inline
     * privateKey present -> legacy fallback; otherwise NEEDS_CREDENTIALS.
     */
    private KeyMaterial resolveKeyMaterial(HostConfig host) throws AppError {
        if (host.authType != HostConfig.AuthType.PRIVATE_KEY) return null;
        String keyId = host.keyId == null ? "" : host.keyId.trim();
        if (!keyId.isEmpty()) {
            SshKeyRecord record = keyResolver == null ? null : keyResolver.find(keyId);
            if (record == null) {
                throw AppError.needsCredentials("所选密钥已不存在（" + keyId + "），请重新选择");
            }
            if (record.privateKey == null || record.privateKey.trim().isEmpty()) {
                throw AppError.needsCredentials("所选密钥缺少私钥内容，请重新导入该密钥");
            }
            return new KeyMaterial(record.privateKey, record.passphrase);
        }
        String inline = host.privateKey == null ? "" : host.privateKey;
        if (!inline.trim().isEmpty()) {
            // Defensive fallback: HostStore migrates these on load, so this only
            // happens when the migration could not run (corrupt/partial store).
            return new KeyMaterial(inline, host.keyPassphrase);
        }
        throw AppError.needsCredentials("缺少私钥：请在主机编辑页选择一个 SSH 密钥或改用密码认证");
    }

    /** Add private-key material (if any) to a JSch instance. */
    private void addIdentity(JSch jsch, HostConfig host, KeyMaterial material,
                             String tag, String[] secrets) throws AppError {
        if (host.authType != HostConfig.AuthType.PRIVATE_KEY) return;
        if (material == null) {
            throw AppError.needsCredentials("缺少私钥：请选择一个 SSH 密钥或改用密码认证");
        }
        byte[] passphrase = material.passphrase.isEmpty() ? null : material.passphrase.getBytes(UTF8);
        try {
            jsch.addIdentity(tag, material.privateKey.getBytes(UTF8), null, passphrase);
        } catch (JSchException error) {
            String safe = LogSanitizer.sanitize(
                    error.getMessage() == null ? "私钥无法解析" : error.getMessage(), secrets);
            throw AppError.needsCredentials("私钥无法解析或口令错误：" + safe);
        }
    }

    /** Host secrets plus the resolved key-store material (all must be redacted). */
    private static String[] secretsWith(HostConfig host, KeyMaterial material) {
        String[] hostSecrets = LogSanitizer.secretsOf(host);
        if (material == null) return hostSecrets;
        List<String> merged = new ArrayList<>(hostSecrets.length + 2);
        for (String secret : hostSecrets) {
            if (secret != null && !secret.isEmpty()) merged.add(secret);
        }
        if (!material.privateKey.isEmpty()) merged.add(material.privateKey);
        if (!material.passphrase.isEmpty()) merged.add(material.passphrase);
        return merged.toArray(new String[0]);
    }

    private Tunnel connect(HostConfig host, HostKeyPolicy policy, int timeoutMs, boolean requireHttpReady)
            throws AppError {
        long deadline = System.currentTimeMillis() + timeoutMs;
        KeyMaterial material = resolveKeyMaterial(host);
        String[] secrets = secretsWith(host, material);
        String alias = host.alias.trim();
        Session jumpSession = null;
        Session targetSession = null;
        int jumpLocalPort = 0;
        try {
            JumpTarget jump = resolveJump(host);
            String targetHost = host.resolvedHostName();
            int targetPort = effectivePort(host.port);

            // --- 1. optional jump host: connect, then expose TARGET:targetPort on
            // the jump session's loopback. The second session is carried through
            // that forward, so it really talks to the target sshd (ProxyJump
            // semantics), never to the jump host's own sshd.
            if (jump != null) {
                JSch jumpJsch = new JSch();
                addIdentity(jumpJsch, host, material, "dsh-jump-" + tag(alias), secrets);
                jumpSession = openSession(jumpJsch, jump.canonicalKey(), jump.user, jump.host, jump.port,
                        host, remaining(deadline), policy, alias, secrets, null);
                jumpLocalPort = openForward(jumpSession, targetHost, targetPort, secrets, "跳板机转发");
                emitLog("跳板机 " + jump.user + "@" + jump.host + ":" + jump.port
                        + " 已就绪，本地端口 " + jumpLocalPort + " -> " + targetHost + ":" + targetPort, secrets);
            }

            // --- 2. target session (through the jump forward when present)
            JSch targetJsch = new JSch();
            addIdentity(targetJsch, host, material, "dsh-target-" + tag(alias), secrets);
            SocketFactory socketFactory = jump == null
                    ? null : socketFactoryTo(jumpLocalPort, remaining(deadline));
            targetSession = openSession(targetJsch, hostPortKey(targetHost, targetPort),
                    host.user.trim(), targetHost, targetPort, host, remaining(deadline), policy,
                    alias, secrets, socketFactory);
            emitLog("SSH 会话已建立：" + host.user.trim() + "@" + targetHost + ":" + targetPort, secrets);

            // --- 3. local forward to the remote dsh web port
            int remotePort = host.remotePort;
            if (remotePort < 1 || remotePort > 65535) {
                throw AppError.badRequest("远端 dsh web 端口无效：" + remotePort);
            }
            int localPort = openForward(targetSession, "127.0.0.1", remotePort, secrets, "本地转发");
            emitLog("本地端口 " + localPort + " -> 127.0.0.1:" + remotePort, secrets);

            // --- 4. readiness probe
            if (requireHttpReady && !waitForHttpReady(localPort, deadline)) {
                throw AppError.unreachable("SSH 隧道已建立，但远端服务没有响应：请确认远端 dsh web 仍在运行（端口 "
                        + remotePort + "）");
            }

            Tunnel tunnel = new Tunnel(alias, localPort, targetSession, jumpSession);
            targetSession = null;
            jumpSession = null;
            return tunnel;
        } finally {
            if (targetSession != null) targetSession.disconnect();
            if (jumpSession != null) jumpSession.disconnect();
        }
    }

    /** A connected tunnel: the target session owns the local forward, jump is optional. */
    private static final class Tunnel {
        final String alias;
        final int localPort;
        final Session target;
        final Session jump;
        volatile boolean closed;

        Tunnel(String alias, int localPort, Session target, Session jump) {
            this.alias = alias;
            this.localPort = localPort;
            this.target = target;
            this.jump = jump;
        }

        boolean isAlive() {
            if (closed || target == null) return false;
            if (!target.isConnected()) return false;
            return jump == null || jump.isConnected();
        }
    }

    private Tunnel tunnelFor(String alias) {
        if (alias == null) return null;
        synchronized (this) {
            return tunnels.get(alias);
        }
    }

    private Object lockFor(String alias) {
        synchronized (this) {
            Object lock = aliasLocks.get(alias);
            if (lock == null) {
                lock = new Object();
                aliasLocks.put(alias, lock);
            }
            return lock;
        }
    }

    /** Drop a dead tunnel without emitting CLOSED (ensureOpen is about to replace it). */
    private void retire(Tunnel tunnel) {
        synchronized (this) {
            if (tunnels.get(tunnel.alias) == tunnel) tunnels.remove(tunnel.alias);
        }
        shutdown(tunnel);
    }

    private void shutdown(Tunnel tunnel) {
        tunnel.closed = true;
        try {
            if (tunnel.target != null) tunnel.target.disconnect();
        } catch (RuntimeException ignored) {
            // Best effort: the session is already unusable by definition here.
        }
        try {
            if (tunnel.jump != null) tunnel.jump.disconnect();
        } catch (RuntimeException ignored) {
            // Same as above.
        }
    }

    private void startWatcher(final Tunnel tunnel) {
        Thread watcher = new Thread(new Runnable() {
            @Override public void run() {
                while (!tunnel.closed) {
                    try {
                        Thread.sleep(WATCH_INTERVAL_MS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (tunnel.closed) return;
                    if (tunnel.isAlive()) continue;
                    boolean removed;
                    synchronized (SshTunnelEngine.this) {
                        removed = tunnels.get(tunnel.alias) == tunnel;
                        if (removed) tunnels.remove(tunnel.alias);
                    }
                    if (!removed) return;
                    shutdown(tunnel);
                    emitState(tunnel.alias, STATE_CLOSED);
                    return;
                }
            }
        }, "dsh-tunnel-watch-" + tag(tunnel.alias));
        watcher.setDaemon(true);
        watcher.start();
    }

    // ---------------------------------------------------------------- forwarding

    private int openForward(Session session, String remoteHost, int remotePort,
                            String[] secrets, String label) throws AppError {
        JSchException last = null;
        for (int attempt = 0; attempt < FORWARD_ATTEMPTS; attempt += 1) {
            int localPort;
            try {
                localPort = findFreeLocalPort();
            } catch (IOException noPort) {
                throw AppError.unreachable("手机本地没有可用的转发端口：" + noPort.getMessage());
            }
            try {
                session.setPortForwardingL("127.0.0.1", localPort, remoteHost, remotePort);
                return localPort;
            } catch (JSchException error) {
                last = error;
            }
        }
        String detail = last == null ? "" : LogSanitizer.sanitize(last.getMessage(), secrets);
        throw AppError.unreachable(label + "失败（已重试 " + FORWARD_ATTEMPTS + " 次）"
                + (detail.isEmpty() ? "" : "：" + detail));
    }

    /**
     * Probe a loopback port and return it. A {@code ServerSocket(0)} probe is used
     * first (the OS-vetted way to find a free port); the documented 20000–60000
     * window is enforced, falling back to random candidates inside it.
     */
    private int findFreeLocalPort() throws IOException {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        for (int attempt = 0; attempt < 8; attempt += 1) {
            ServerSocket probe = new ServerSocket(0, 1, loopback);
            try {
                int port = probe.getLocalPort();
                if (isUsableLocalPort(port)) return port;
            } finally {
                probe.close();
            }
        }
        for (int attempt = 0; attempt < 40; attempt += 1) {
            int port = MIN_LOCAL_PORT + random.nextInt(MAX_LOCAL_PORT - MIN_LOCAL_PORT + 1);
            if (!isUsableLocalPort(port)) continue;
            try {
                ServerSocket probe = new ServerSocket(port, 1, loopback);
                try {
                    return port;
                } finally {
                    probe.close();
                }
            } catch (IOException taken) {
                // Port raced into use; try the next candidate.
            }
        }
        throw new IOException("在 " + MIN_LOCAL_PORT + "–" + MAX_LOCAL_PORT + " 内找不到空闲端口");
    }

    private static boolean isUsableLocalPort(int port) {
        return port >= MIN_LOCAL_PORT && port <= MAX_LOCAL_PORT && port != RESERVED_LOCAL_PORT;
    }

    // ----------------------------------------------------------------- readiness

    private boolean waitForHttpReady(int localPort, long deadline) {
        while (System.currentTimeMillis() < deadline) {
            if (httpProbe(localPort)) return true;
            try {
                Thread.sleep(READY_RETRY_DELAY_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        // One last probe even when the budget was consumed by a slow handshake:
        // the forward is already active, so a healthy loopback answer wins.
        return httpProbe(localPort);
    }

    /** Any HTTP status code counts as ready: an unauthenticated 401 is the healthy answer. */
    private static boolean httpProbe(int localPort) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL("http://127.0.0.1:" + localPort + "/");
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(READY_HTTP_TIMEOUT_MS);
            connection.setReadTimeout(READY_HTTP_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Connection", "close");
            connection.setUseCaches(false);
            int status = connection.getResponseCode();
            return status > 0;
        } catch (Exception notReady) {
            return false;
        } finally {
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (RuntimeException ignored) {
                    // Nothing useful to do; the probe result is already decided.
                }
            }
        }
    }

    // ---------------------------------------------------------------- ProxyJump

    private JumpTarget resolveJump(HostConfig host) throws AppError {
        String raw = host.proxyJump == null ? "" : host.proxyJump.trim();
        if (raw.isEmpty() || "none".equalsIgnoreCase(raw)) return null;
        if (raw.indexOf(',') >= 0) {
            throw AppError.badRequest("不支持多级 ProxyJump（逗号分隔），请填写单个跳板机");
        }
        String targetUser = host.user == null ? "" : host.user.trim();

        // Form 1: another host in the local list, referenced by alias.
        if (resolver != null) {
            HostConfig aliased = null;
            try {
                aliased = resolver.find(raw);
            } catch (RuntimeException ignored) {
                // A failing resolver must not break the literal form below.
            }
            if (aliased != null) {
                String jumpHost = aliased.resolvedHostName();
                if (jumpHost == null || jumpHost.trim().isEmpty()) {
                    throw AppError.badRequest("ProxyJump 别名 " + raw + " 缺少主机名");
                }
                String jumpUser = aliased.user == null || aliased.user.trim().isEmpty()
                        ? targetUser : aliased.user.trim();
                if (jumpUser.isEmpty()) throw AppError.badRequest("ProxyJump 缺少用户名");
                return new JumpTarget(jumpUser, jumpHost.trim(), effectivePort(aliased.port));
            }
        }

        // Form 2: literal [user@]host[:port].
        String value = raw;
        String jumpUser = targetUser;
        int at = value.indexOf('@');
        if (at >= 0) {
            jumpUser = value.substring(0, at).trim();
            value = value.substring(at + 1).trim();
        }
        if (jumpUser.isEmpty()) {
            throw AppError.badRequest("ProxyJump 缺少用户名，请填写 user@host:port");
        }
        String jumpHost;
        int jumpPort = 22;
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            if (close < 0) throw AppError.badRequest("ProxyJump 的 IPv6 地址缺少右方括号");
            jumpHost = value.substring(1, close).trim();
            String rest = value.substring(close + 1).trim();
            if (!rest.isEmpty()) {
                if (!rest.startsWith(":")) throw AppError.badRequest("ProxyJump 格式错误：" + raw);
                jumpPort = parsePort(rest.substring(1), "ProxyJump");
            }
        } else {
            int firstColon = value.indexOf(':');
            int lastColon = value.lastIndexOf(':');
            if (firstColon >= 0 && firstColon == lastColon) {
                jumpHost = value.substring(0, firstColon).trim();
                jumpPort = parsePort(value.substring(firstColon + 1), "ProxyJump");
            } else {
                // Bare host, or an unbracketed IPv6 literal: use it as the host.
                jumpHost = value.trim();
            }
        }
        if (jumpHost.isEmpty()) throw AppError.badRequest("ProxyJump 主机为空");
        return new JumpTarget(jumpUser, jumpHost, jumpPort);
    }

    private static int parsePort(String text, String label) throws AppError {
        try {
            int port = Integer.parseInt(text.trim());
            if (port >= 1 && port <= 65535) return port;
        } catch (NumberFormatException malformed) {
            // Fall through to the shared error below.
        }
        throw AppError.badRequest(label + " 端口不合法：" + text);
    }

    private static int effectivePort(int port) {
        return port >= 1 && port <= 65535 ? port : 22;
    }

    private static SocketFactory socketFactoryTo(final int jumpLocalPort, final int connectTimeoutMs) {
        return new SocketFactory() {
            @Override public Socket createSocket(String target, int targetPort) throws IOException {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress("127.0.0.1", jumpLocalPort),
                        Math.max(1000, connectTimeoutMs));
                return socket;
            }

            @Override public InputStream getInputStream(Socket socket) throws IOException {
                return socket.getInputStream();
            }

            @Override public OutputStream getOutputStream(Socket socket) throws IOException {
                return socket.getOutputStream();
            }
        };
    }

    // ------------------------------------------------------------- host key TOFU

    /** Public host-key data captured from the SSH wire format (no secret). */
    private static final class HostKeyData {
        final String hostPortKey;
        final String keyType;
        final String keyBase64;
        final String fingerprint;

        HostKeyData(String hostPortKey, String keyType, String keyBase64, String fingerprint) {
            this.hostPortKey = hostPortKey;
            this.keyType = keyType;
            this.keyBase64 = keyBase64;
            this.fingerprint = fingerprint;
        }

        static HostKeyData of(String hostPortKey, byte[] blob) {
            byte[] key = blob == null ? new byte[0] : blob;
            String type = "unknown";
            if (key.length >= 4) {
                int length = ((key[0] & 0xFF) << 24) | ((key[1] & 0xFF) << 16)
                        | ((key[2] & 0xFF) << 8) | (key[3] & 0xFF);
                if (length > 0 && length <= key.length - 4) {
                    type = new String(key, 4, length, UTF8);
                }
            }
            String base64 = Base64.getEncoder().encodeToString(key);
            return new HostKeyData(hostPortKey, type, base64, fingerprintOf(key));
        }

        private static String fingerprintOf(byte[] key) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(key);
                return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(hash);
            } catch (NoSuchAlgorithmException impossible) {
                return "SHA256:unknown";
            }
        }
    }

    /**
     * JSch host-key repository backed by the caller's policy: OK for keys accepted
     * during this connection attempt, NOT_INCLUDED otherwise. With
     * {@code StrictHostKeyChecking=yes} the latter surfaces as
     * {@link JSchUnknownHostKeyException} and {@link #decide(boolean)} asks the
     * policy whether to trust the captured key.
     */
    private static final class PolicyRepository implements HostKeyRepository {
        private final String hostPortKey;
        private final HostKeyPolicy policy;
        private final Listener listener;
        private final String alias;
        private final Set<String> accepted = new HashSet<>();
        private volatile HostKeyData pending;

        PolicyRepository(String hostPortKey, HostKeyPolicy policy, Listener listener, String alias) {
            this.hostPortKey = hostPortKey;
            this.policy = policy;
            this.listener = listener;
            this.alias = alias;
        }

        @Override public int check(String host, byte[] key) {
            HostKeyData data = HostKeyData.of(hostPortKey, key);
            pending = data;
            if (accepted.contains(data.keyBase64)) return OK;
            return NOT_INCLUDED;
        }

        /** Ask the policy about the captured key; returns only on TRUST. */
        void decide(boolean changedHint) throws HostKeyUnknownException {
            HostKeyData data = pending;
            if (data == null) {
                throw new HostKeyUnknownException(changedHint, hostPortKey, "", "", "");
            }
            notifyHostKey(listener, alias, data);
            HostKeyPolicy.Decision decision = policy.check(
                    data.hostPortKey, data.keyType, data.keyBase64, data.fingerprint);
            if (decision == HostKeyPolicy.Decision.TRUST) {
                accepted.add(data.keyBase64);
                return;
            }
            boolean changed = changedHint || decision == HostKeyPolicy.Decision.REJECT_AND_FAIL;
            throw new HostKeyUnknownException(changed, data.hostPortKey, data.keyType,
                    data.keyBase64, data.fingerprint);
        }

        @Override public void add(HostKey hostkey, UserInfo ui) {
            // Trust is written by HostStore, not by JSch. Never auto-add.
        }

        @Override public void remove(String host, String type) {
            // The app's trust table is managed by HostStore.
        }

        @Override public void remove(String host, String type, byte[] key) {
            // The app's trust table is managed by HostStore.
        }

        @Override public String getKnownHostsRepositoryID() {
            return "dsh-mobile-trust";
        }

        @Override public HostKey[] getHostKey() {
            return new HostKey[0];
        }

        @Override public HostKey[] getHostKey(String host, String type) {
            return new HostKey[0];
        }
    }

    // ------------------------------------------------------------------ errors

    private AppError classify(Throwable error, String host, int port, String[] secrets) {
        if (error instanceof HostKeyUnknownException) {
            return (HostKeyUnknownException) error;
        }
        String raw = error == null ? "" : error.getMessage();
        if (raw == null || raw.trim().isEmpty()) {
            raw = error == null ? "未知错误" : error.getClass().getSimpleName();
        }
        String safe = LogSanitizer.sanitize(raw, secrets);
        String lower = raw.toLowerCase(Locale.ROOT);
        String endpoint = host + ":" + port;

        if (error instanceof JSchChangedHostKeyException
                || lower.contains("hostkey has been changed")) {
            return AppError.hostKeyChanged("SSH 主机密钥已变更，已拒绝连接：" + endpoint);
        }
        if (isAuthFailure(lower)) {
            return AppError.needsCredentials("SSH 认证失败（" + endpoint + "）：" + safe);
        }
        if (error instanceof java.net.UnknownHostException
                || error instanceof java.net.ConnectException
                || error instanceof java.net.SocketTimeoutException
                || error instanceof java.net.NoRouteToHostException
                || isUnreachable(lower)) {
            return AppError.unreachable("连不上 " + endpoint + "：" + safe);
        }
        return AppError.internal("SSH 连接失败（" + endpoint + "）：" + safe);
    }

    private static boolean isAuthFailure(String lowerMessage) {
        String[] markers = {
                "auth fail", "auth cancel", "userauth", "permission denied",
                "invalid privatekey", "invalid private key", "privatekey is invalid",
                "passphrase is wrong", "wrong passphrase", "key is encrypted",
                "password is not available", "no such identity", "authentication failed",
                "auth method", "cannot prompt", "publickey"
        };
        for (String marker : markers) {
            if (lowerMessage.contains(marker)) return true;
        }
        return false;
    }

    private static boolean isUnreachable(String lowerMessage) {
        String[] markers = {
                "connection refused", "connect timed out", "connection timed out",
                "connect timeout", "timeout", "timed out",
                "no route to host", "network is unreachable", "unknown host",
                "name or service not known", "temporary failure in name resolution",
                "connection reset", "broken pipe", "session is down", "socket is closed",
                "channel open failure", "administratively prohibited",
                "port forwarding", "forwarding failed", "connection closed",
                "connection is closed", "closed by foreign host",
                "unexpected end of file", "eof"
        };
        for (String marker : markers) {
            if (lowerMessage.contains(marker)) return true;
        }
        return false;
    }

    // ----------------------------------------------------------------- helpers

    private void validate(HostConfig host, HostKeyPolicy policy) throws AppError {
        if (host == null) throw AppError.badRequest("主机配置为空");
        if (host.alias == null || host.alias.trim().isEmpty()) {
            throw AppError.badRequest("缺少主机别名 alias");
        }
        if (host.resolvedHostName().isEmpty()) {
            throw AppError.badRequest("缺少 SSH 主机名（HostName）");
        }
        if (host.user == null || host.user.trim().isEmpty()) {
            throw AppError.badRequest("缺少 SSH 用户名");
        }
        if (host.authType == null) {
            throw AppError.badRequest("缺少 SSH 认证方式");
        }
        if (host.authType == HostConfig.AuthType.PRIVATE_KEY) {
            boolean hasKeyId = host.keyId != null && !host.keyId.trim().isEmpty();
            boolean hasInlineKey = host.privateKey != null && !host.privateKey.trim().isEmpty();
            if (!hasKeyId && !hasInlineKey) {
                throw AppError.needsCredentials(
                        "缺少私钥：请在主机编辑页选择一个 SSH 密钥或改用密码认证");
            }
        } else if (host.password == null || host.password.isEmpty()) {
            throw AppError.needsCredentials("缺少 SSH 密码：请填写密码或改用私钥认证");
        }
        if (policy == null) {
            throw AppError.badRequest("缺少主机密钥确认策略");
        }
        int remotePort = host.remotePort;
        if (remotePort < 1 || remotePort > 65535) {
            throw AppError.badRequest("缺少远端 dsh web 端口（凭证 URL 解析失败？）");
        }
    }

    private static int normalizeTimeout(int timeoutMs) {
        if (timeoutMs < 1000) return DEFAULT_TIMEOUT_MS;
        return timeoutMs;
    }

    private static int remaining(long deadline) {
        long left = deadline - System.currentTimeMillis();
        return (int) Math.max(1000, Math.min(Integer.MAX_VALUE, left));
    }

    private static String tag(String alias) {
        String safe = alias == null ? "host" : alias.replaceAll("[^A-Za-z0-9_.-]", "_");
        return safe.isEmpty() ? "host" : safe;
    }

    private void emitState(String alias, String state) {
        emitLog("隧道状态 " + state + "：" + alias, null);
        if (listener == null) return;
        try {
            listener.onState(alias, state);
        } catch (RuntimeException ignored) {
            // A listener failure must never break tunnel bookkeeping.
        }
    }

    private void emitLog(String message, String[] secrets) {
        if (listener == null) return;
        try {
            listener.onLog(LogSanitizer.sanitize(message, secrets));
        } catch (RuntimeException ignored) {
            // Logging is best effort.
        }
    }

    private static void notifyHostKey(Listener listener, String alias, HostKeyData data) {
        if (listener == null) return;
        try {
            listener.onHostKey(alias, data.hostPortKey, data.keyType, data.keyBase64, data.fingerprint);
        } catch (RuntimeException ignored) {
            // A listener failure must never break the decision flow.
        }
    }
}

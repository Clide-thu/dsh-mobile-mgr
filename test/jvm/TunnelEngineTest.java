import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SocketFactory;
import com.jcraft.jsch.UserInfo;

import dev.dsh.remotehosts.core.AppError;
import dev.dsh.remotehosts.core.ErrorKind;
import dev.dsh.remotehosts.core.HostConfig;
import dev.dsh.remotehosts.core.SshKeyManager;
import dev.dsh.remotehosts.core.SshKeyRecord;
import dev.dsh.remotehosts.core.SshTunnelEngine;
import dev.dsh.remotehosts.core.TunnelManager;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Desktop integration test for {@link SshTunnelEngine} and {@link TunnelManager}
 * against a real SSH host: direct connection, ProxyJump, TOFU host-key handling,
 * local port forwarding and concurrent shared startup.
 *
 * Real endpoints are supplied ONLY through the environment; this file contains no
 * host, user, address or key material (same contract as SshSmokeTest):
 *
 *   SSH_TEST_KEY                 private key path       (default: ~/.ssh/id_rsa)
 *   SSH_TEST_PASSPHRASE          private key passphrase (optional)
 *   SSH_TEST_DIRECT_HOST         direct case host       (direct cases skipped if unset)
 *   SSH_TEST_DIRECT_USER         direct case user
 *   SSH_TEST_DIRECT_PORT         direct case SSH port   (default 22; may be a NAT port)
 *   SSH_TEST_DIRECT_REMOTE_PORT  forwarded remote port  (default 22 = remote sshd)
 *   SSH_TEST_JUMP_HOST           jump host              (proxyjump cases need all six)
 *   SSH_TEST_JUMP_USER           jump user
 *   SSH_TEST_JUMP_PORT           jump SSH port          (default 22)
 *   SSH_TEST_TARGET_HOST         target host (reached through the jump)
 *   SSH_TEST_TARGET_USER         target user
 *   SSH_TEST_TARGET_PORT         target SSH port        (default 22)
 *   SSH_TEST_TARGET_REMOTE_PORT  forwarded remote port  (default 22 = remote sshd)
 *
 * The ProxyJump assertions are anchored to an INDEPENDENT reference baseline built
 * with plain JSch (jump connect -> raw -L forward to the target -> target connect):
 * the target's real host key and real banner are captured that way and compared
 * against what the engine under test reports and serves. A tunnel that silently
 * stops at the jump host (the historical bug) therefore fails this test instead of
 * passing on a banner prefix that both hosts satisfy.
 *
 * With nothing configured the test prints SKIP and exits 0, so it can live in the
 * default test run; `-Dssh.require=true` turns a skip into a failure for the
 * release acceptance run. The key file is only read, never printed or copied.
 *
 * Run via tools/test-jvm.ps1 (or compile with jschJar on the classpath).
 */
public final class TunnelEngineTest {

    private static int failures = 0;

    /** Exactly one remote endpoint, taken from the environment. */
    private static final class Endpoint {
        final String user;
        final String host;
        final int port;

        Endpoint(String user, String host, int port) {
            this.user = user;
            this.host = host;
            this.port = port;
        }

        String display() {
            return user + "@" + host + ":" + port;
        }

        static Endpoint fromEnv(String prefix) {
            String host = setting(prefix + "_HOST");
            if (host == null || host.trim().isEmpty()) return null;
            String user = setting(prefix + "_USER");
            if (user == null || user.trim().isEmpty()) {
                throw new IllegalArgumentException(prefix + "_USER must be set together with " + prefix + "_HOST");
            }
            return new Endpoint(user.trim(), host.trim(), intSetting(prefix + "_PORT", 22));
        }
    }

    private static String setting(String name) {
        String property = System.getProperty(name.toLowerCase().replace('_', '.'));
        if (property != null && !property.isEmpty()) return property;
        return System.getenv(name);
    }

    private static int intSetting(String name, int fallback) {
        String raw = setting(name);
        if (raw == null || raw.trim().isEmpty()) return fallback;
        return Integer.parseInt(raw.trim());
    }

    private static boolean required() {
        return "true".equalsIgnoreCase(System.getProperty("ssh.require", "false"));
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            System.out.println("PASS " + name);
        } else {
            failures++;
            System.out.println("FAIL " + name + (detail == null || detail.isEmpty() ? "" : " (" + detail + ")"));
        }
    }

    private static void fail(String name, String detail) {
        failures++;
        System.out.println("FAIL " + name + (detail == null || detail.isEmpty() ? "" : " (" + detail + ")"));
    }

    // --------------------------------------------------------------- fixtures

    private static String keyText = "";

    private static HostConfig makeHost(String alias, Endpoint endpoint, int remotePort) {
        HostConfig host = new HostConfig();
        host.alias = alias;
        host.hostName = endpoint.host;
        host.user = endpoint.user;
        host.port = endpoint.port;
        host.authType = HostConfig.AuthType.PRIVATE_KEY;
        host.privateKey = keyText;
        host.keyPassphrase = setting("SSH_TEST_PASSPHRASE") == null ? "" : setting("SSH_TEST_PASSPHRASE");
        host.accessMode = HostConfig.AccessMode.TUNNEL;
        host.remotePort = remotePort;
        return host;
    }

    private static SshTunnelEngine.Listener listener() {
        return new SshTunnelEngine.Listener() {
            @Override public void onState(String alias, String state) {
                // State transitions are asserted through isOpen()/localPort().
            }

            @Override public void onHostKey(String alias, String hostPortKey, String keyType,
                                            String keyBase64, String fingerprint) {
                // Decisions are asserted through HostKeyUnknownException below.
            }

            @Override public void onLog(String message) {
                // Engine diagnostics are already sanitised; nothing to assert here.
            }
        };
    }

    private static final SshTunnelEngine.HostKeyPolicy TRUST_ALL = new SshTunnelEngine.HostKeyPolicy() {
        @Override public Decision check(String hostPortKey, String keyType,
                                       String keyBase64, String fingerprint) {
            return Decision.TRUST;
        }
    };

    private static SshTunnelEngine.HostKeyPolicy rejectAll() {
        return new SshTunnelEngine.HostKeyPolicy() {
            @Override public Decision check(String hostPortKey, String keyType,
                                           String keyBase64, String fingerprint) {
                return Decision.REJECT_ONCE;
            }
        };
    }

    private static SshTunnelEngine.HostKeyPolicy trustOnly(
            final String expectedHostPortKey, final String expectedKeyBase64) {
        return new SshTunnelEngine.HostKeyPolicy() {
            @Override public Decision check(String hostPortKey, String keyType,
                                           String keyBase64, String fingerprint) {
                if (expectedHostPortKey.equals(hostPortKey) && expectedKeyBase64.equals(keyBase64)) {
                    return Decision.TRUST;
                }
                return Decision.REJECT_ONCE;
            }
        };
    }

    // ------------------------------------------- independent reference baseline

    /**
     * Evidence captured with plain JSch/Sockets, never through the engine under
     * test: the real host key of the endpoint at the end of the tunnel and the
     * real SSH banner served through a reference port forward.
     */
    private static final class Reference {
        String hostKeyBase64 = "";
        String banner = "";
    }

    private static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return probe.getLocalPort();
        }
    }

    /** Accept-everything repository used only by the independent reference probes. */
    private static final class AcceptAll implements HostKeyRepository {
        @Override public int check(String host, byte[] key) { return OK; }
        @Override public void add(HostKey hostkey, UserInfo ui) { }
        @Override public void remove(String host, String type) { }
        @Override public void remove(String host, String type, byte[] key) { }
        @Override public String getKnownHostsRepositoryID() { return "tunnel-engine-test-reference"; }
        @Override public HostKey[] getHostKey() { return new HostKey[0]; }
        @Override public HostKey[] getHostKey(String host, String type) { return new HostKey[0]; }
    }

    private static final UserInfo NO_PROMPT = new UserInfo() {
        @Override public String getPassphrase() { return null; }
        @Override public String getPassword() { return null; }
        @Override public boolean promptPassword(String message) { return false; }
        @Override public boolean promptPassphrase(String message) { return false; }
        @Override public boolean promptYesNo(String message) { return false; }
        @Override public void showMessage(String message) { }
    };

    private static byte[] passphraseBytes() {
        String passphrase = setting("SSH_TEST_PASSPHRASE");
        return passphrase == null || passphrase.isEmpty()
                ? null : passphrase.getBytes(StandardCharsets.UTF_8);
    }

    private static JSch referenceJsch() throws Exception {
        JSch jsch = new JSch();
        jsch.setHostKeyRepository(new AcceptAll());
        jsch.addIdentity("reference", keyText.getBytes(StandardCharsets.UTF_8), null, passphraseBytes());
        return jsch;
    }

    private static Session referenceConnect(JSch jsch, Endpoint endpoint, int jumpLocalPort) throws Exception {
        Session session = jsch.getSession(endpoint.user, endpoint.host, endpoint.port);
        session.setUserInfo(NO_PROMPT);
        session.setConfig("StrictHostKeyChecking", "no");
        session.setConfig("PreferredAuthentications", "publickey,password,keyboard-interactive");
        session.setServerAliveInterval(15000);
        session.setServerAliveCountMax(3);
        if (jumpLocalPort > 0) {
            session.setSocketFactory(new SocketFactory() {
                @Override public Socket createSocket(String target, int targetPort) throws IOException {
                    return new Socket("127.0.0.1", jumpLocalPort);
                }

                @Override public InputStream getInputStream(Socket socket) throws IOException {
                    return socket.getInputStream();
                }

                @Override public java.io.OutputStream getOutputStream(Socket socket) throws IOException {
                    return socket.getOutputStream();
                }
            });
        }
        session.connect(20000);
        return session;
    }

    /**
     * Reference for a DIRECT connection: the connected host's real key plus the
     * banner served through a raw local forward to {@code remotePort}.
     */
    private static Reference referenceDirect(Endpoint endpoint, int remotePort) throws Exception {
        Reference reference = new Reference();
        Session session = null;
        try {
            session = referenceConnect(referenceJsch(), endpoint, 0);
            reference.hostKeyBase64 = session.getHostKey() == null ? "" : session.getHostKey().getKey();
            int forwardPort = freePort();
            session.setPortForwardingL("127.0.0.1", forwardPort, "127.0.0.1", remotePort);
            reference.banner = readBanner(forwardPort);
        } finally {
            if (session != null) session.disconnect();
        }
        return reference;
    }

    /**
     * Reference for a ProxyJump connection: raw JSch jump session, a raw
     * {@code -L forwardPort -> target.host:target.port} forward, then a second raw
     * session through that forward. Captures the real target host key (the key of
     * the machine actually at the end of the chain) and the real target banner.
     */
    private static Reference referenceProxyJump(Endpoint jump, Endpoint target, int targetRemotePort)
            throws Exception {
        Reference reference = new Reference();
        Session jumpSession = null;
        Session targetSession = null;
        try {
            jumpSession = referenceConnect(referenceJsch(), jump, 0);
            int forwardPort = freePort();
            jumpSession.setPortForwardingL("127.0.0.1", forwardPort, target.host, target.port);
            reference.banner = readBanner(forwardPort);
            targetSession = referenceConnect(referenceJsch(), target, forwardPort);
            reference.hostKeyBase64 = targetSession.getHostKey() == null
                    ? "" : targetSession.getHostKey().getKey();
        } finally {
            if (targetSession != null) targetSession.disconnect();
            if (jumpSession != null) jumpSession.disconnect();
        }
        return reference;
    }

    /** Reference host key of an endpoint's own sshd (no forward involved). */
    private static String referenceHostKey(Endpoint endpoint) throws Exception {
        Session session = null;
        try {
            session = referenceConnect(referenceJsch(), endpoint, 0);
            return session.getHostKey() == null ? "" : session.getHostKey().getKey();
        } finally {
            if (session != null) session.disconnect();
        }
    }

    /** Read the SSH banner through a forwarded loopback port: proof the tunnel works. */
    private static String readBanner(int localPort) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", localPort)) {
            socket.setSoTimeout(15000);
            InputStream in = socket.getInputStream();
            byte[] buffer = new byte[256];
            int read = in.read(buffer);
            return read <= 0 ? "" : new String(buffer, 0, read, StandardCharsets.US_ASCII).trim();
        }
    }

    private static void checkBanner(String name, int localPort, String expectedBanner) {
        try {
            String banner = readBanner(localPort);
            check(name + " serves an SSH banner", banner.startsWith("SSH-2.0-"), banner);
            check(name + " banner matches independent reference",
                    expectedBanner.isEmpty() || expectedBanner.equals(banner),
                    "engine=" + banner + " reference=" + expectedBanner);
        } catch (IOException error) {
            fail(name + " banner through local port " + localPort, error.toString());
        }
    }

    private static String shortKey(String base64) {
        if (base64 == null || base64.length() <= 12) return String.valueOf(base64);
        return base64.substring(0, 8) + "…" + base64.substring(base64.length() - 4);
    }

    // ------------------------------------------------------------------- cases

    /** Validation must fail fast, without touching the network. */
    private static void testValidation() {
        SshTunnelEngine engine = new SshTunnelEngine(listener());
        try {
            HostConfig noAlias = new HostConfig();
            noAlias.hostName = "example.invalid";
            noAlias.user = "someone";
            noAlias.password = "x";
            noAlias.remotePort = 1;
            expectKind("missing alias -> BAD_REQUEST",
                    () -> engine.ensureOpen(noAlias, TRUST_ALL, 1000, false), ErrorKind.BAD_REQUEST);

            HostConfig noPassword = new HostConfig();
            noPassword.alias = "no-password";
            noPassword.hostName = "example.invalid";
            noPassword.user = "someone";
            noPassword.remotePort = 1;
            expectKind("missing password -> NEEDS_CREDENTIALS",
                    () -> engine.ensureOpen(noPassword, TRUST_ALL, 1000, false), ErrorKind.NEEDS_CREDENTIALS);

            HostConfig noRemotePort = new HostConfig();
            noRemotePort.alias = "no-remote-port";
            noRemotePort.hostName = "example.invalid";
            noRemotePort.user = "someone";
            noRemotePort.password = "x";
            expectKind("missing remote port -> BAD_REQUEST",
                    () -> engine.ensureOpen(noRemotePort, TRUST_ALL, 1000, false), ErrorKind.BAD_REQUEST);

            HostConfig nullHost = null;
            expectKind("null host -> BAD_REQUEST",
                    () -> engine.ensureOpen(nullHost, TRUST_ALL, 1000, false), ErrorKind.BAD_REQUEST);
            check("isOpen on unknown alias is false", !engine.isOpen("nope"), "");
        } finally {
            engine.closeAll();
        }
    }

    private interface ThrowingCall {
        int call() throws AppError;
    }

    private static void expectKind(String name, ThrowingCall call, ErrorKind expected) {
        try {
            call.call();
            fail(name, "no exception thrown");
        } catch (AppError error) {
            check(name + " (got " + error.kind.id + ")", error.kind == expected,
                    String.valueOf(error.getMessage()));
        }
    }

    /**
     * TOFU against the DIRECT endpoint: the reported key must be the real host
     * key, an unknown key is never auto-trusted, a changed key is flagged.
     */
    private static void testHostKeyPolicy(Endpoint endpoint, int remotePort, String keyAlias) {
        Reference reference;
        try {
            reference = referenceDirect(endpoint, remotePort);
        } catch (Exception error) {
            fail("build direct reference baseline", error.toString());
            return;
        }
        SshTunnelEngine engine = new SshTunnelEngine(listener());
        HostConfig host = makeHost(keyAlias, endpoint, remotePort);
        String expectedKey = SshTunnelEngine.hostPortKey(endpoint.host, endpoint.port);
        try {
            String keyBase64;
            try {
                engine.ensureOpen(host, rejectAll(), 20000, false);
                fail("unknown host key is rejected", "engine auto-trusted a key");
                return;
            } catch (SshTunnelEngine.HostKeyUnknownException unknown) {
                keyBase64 = unknown.keyBase64;
                check("unknown host key rejected with hostPortKey", expectedKey.equals(unknown.hostPortKey),
                        unknown.hostPortKey);
                check("unknown host key is not 'changed'", !unknown.changed, "");
                check("unknown host key exposes type", unknown.keyType != null && !unknown.keyType.isEmpty(),
                        unknown.keyType);
                check("unknown host key exposes base64", keyBase64 != null && !keyBase64.isEmpty(), "");
                check("unknown host key exposes SHA256 fingerprint",
                        unknown.fingerprint != null && unknown.fingerprint.startsWith("SHA256:"),
                        unknown.fingerprint);
                check("reported host key equals the real host key",
                        !reference.hostKeyBase64.isEmpty() && reference.hostKeyBase64.equals(keyBase64),
                        "engine=" + shortKey(keyBase64) + " reference=" + shortKey(reference.hostKeyBase64));
            }

            // Trusting exactly that key lets the connection proceed.
            int localPort = engine.ensureOpen(host, trustOnly(expectedKey, keyBase64), 25000, false);
            check("trusted host key opens tunnel", localPort > 0 && engine.isOpen(keyAlias),
                    "port=" + localPort);
            check("engine localPort matches ensureOpen result", engine.localPort(keyAlias) == localPort, "");
            checkBanner("direct after trust", localPort, reference.banner);

            // Same key presented again must be accepted without a new decision.
            int again = engine.ensureOpen(host, rejectAll(), 5000, false);
            check("repeat ensureOpen reuses tunnel", again == localPort, again + " vs " + localPort);

            // close(alias) tears the sessions down.
            check("close returns true for open tunnel", engine.close(keyAlias), "");
            check("close makes isOpen false", !engine.isOpen(keyAlias), "");
            check("second close returns false", !engine.close(keyAlias), "");

            // A policy that reports a known-different key must surface 'changed'.
            SshTunnelEngine.HostKeyPolicy rejectChanged = new SshTunnelEngine.HostKeyPolicy() {
                @Override public Decision check(String hostPortKey, String keyType,
                                               String keyBase64, String fingerprint) {
                    return Decision.REJECT_AND_FAIL;
                }
            };
            try {
                engine.ensureOpen(host, rejectChanged, 20000, false);
                fail("changed host key reported", "engine accepted a rejected key");
            } catch (SshTunnelEngine.HostKeyUnknownException changed) {
                check("changed host key flagged 'changed'", changed.changed, "");
                check("changed host key keeps ErrorKind HOST_KEY_CHANGED",
                        changed.kind == ErrorKind.HOST_KEY_CHANGED, String.valueOf(changed.kind));
            }
        } catch (AppError error) {
            fail("host key policy flow", error.kind + " " + error.getMessage());
        } finally {
            engine.closeAll();
        }
    }

    /** Direct connection: engine opens a forward and the banner proves it is live. */
    private static void testDirect(Endpoint endpoint, int remotePort) {
        Reference reference;
        try {
            reference = referenceDirect(endpoint, remotePort);
        } catch (Exception error) {
            fail("build direct reference baseline", error.toString());
            return;
        }
        SshTunnelEngine engine = new SshTunnelEngine(listener());
        HostConfig host = makeHost("direct-case", endpoint, remotePort);
        try {
            int localPort = engine.ensureOpen(host, TRUST_ALL, 25000, false);
            check("direct ensureOpen returns a local port", localPort > 0, "port=" + localPort);
            check("direct tunnel is open", engine.isOpen("direct-case"), "");
            checkBanner("direct " + endpoint.display(), localPort, reference.banner);
        } catch (AppError error) {
            fail("direct tunnel " + endpoint.display(), error.kind + " " + error.getMessage());
        } finally {
            engine.closeAll();
        }
    }

    /** ProxyJump literal form user@host:port, verified against a raw reference chain. */
    private static void testProxyJump(Endpoint jump, Endpoint target, int targetRemotePort) {
        Reference reference;
        String referenceJumpKey;
        try {
            reference = referenceProxyJump(jump, target, targetRemotePort);
            referenceJumpKey = referenceHostKey(jump);
        } catch (Exception error) {
            fail("build proxyjump reference baseline", error.toString());
            return;
        }
        if (reference.hostKeyBase64.isEmpty() || referenceJumpKey.isEmpty()) {
            fail("proxyjump reference baseline", "missing host key");
            return;
        }
        check("reference jump and target host keys differ",
                !reference.hostKeyBase64.equals(referenceJumpKey),
                "jump=" + shortKey(referenceJumpKey) + " target=" + shortKey(reference.hostKeyBase64));

        SshTunnelEngine engine = new SshTunnelEngine(listener());
        HostConfig host = makeHost("jump-case", target, targetRemotePort);
        host.proxyJump = jump.user + "@" + jump.host + ":" + jump.port;
        String jumpKey = SshTunnelEngine.hostPortKey(jump.host, jump.port);
        String targetKey = SshTunnelEngine.hostPortKey(target.host, target.port);
        try {
            // 1st decision: the jump host's real key, under the jump host's id.
            String jumpBase64;
            try {
                engine.ensureOpen(host, rejectAll(), 25000, false);
                fail("proxyjump asks about the jump host key first", "no exception");
                return;
            } catch (SshTunnelEngine.HostKeyUnknownException unknown) {
                jumpBase64 = unknown.keyBase64;
                check("proxyjump first decision is for the jump host", jumpKey.equals(unknown.hostPortKey),
                        unknown.hostPortKey);
                check("proxyjump first decision carries the real jump host key",
                        referenceJumpKey.equals(jumpBase64),
                        "engine=" + shortKey(jumpBase64) + " reference=" + shortKey(referenceJumpKey));
            }

            // 2nd decision: the TARGET's real key (not the jump host's key again).
            String targetBase64;
            try {
                engine.ensureOpen(host, trustOnly(jumpKey, jumpBase64), 25000, false);
                fail("proxyjump asks about the target host key second", "no exception");
                return;
            } catch (SshTunnelEngine.HostKeyUnknownException unknown) {
                targetBase64 = unknown.keyBase64;
                check("proxyjump second decision is for the target host",
                        targetKey.equals(unknown.hostPortKey), unknown.hostPortKey);
                check("proxyjump target decision carries the real target host key",
                        reference.hostKeyBase64.equals(targetBase64),
                        "engine=" + shortKey(targetBase64) + " reference=" + shortKey(reference.hostKeyBase64));
                check("proxyjump target key is not the jump host key",
                        !jumpBase64.equals(targetBase64),
                        "jump=" + shortKey(jumpBase64) + " target=" + shortKey(targetBase64));
            }

            // With both keys trusted the tunnel carries the TARGET sshd banner.
            int localPort = engine.ensureOpen(host, TRUST_ALL, 30000, false);
            check("proxyjump tunnel is open", localPort > 0 && engine.isOpen("jump-case"),
                    "port=" + localPort);
            checkBanner("proxyjump " + jump.display() + " -> " + target.display(), localPort, reference.banner);
        } catch (AppError error) {
            fail("proxyjump tunnel " + jump.display() + " -> " + target.display(),
                    error.kind + " " + error.getMessage());
        } finally {
            engine.closeAll();
        }
    }

    /** ProxyJump alias form: the jump host is another entry in the local list. */
    private static void testProxyJumpAlias(Endpoint jump, Endpoint target, int targetRemotePort) {
        Reference reference;
        try {
            reference = referenceProxyJump(jump, target, targetRemotePort);
        } catch (Exception error) {
            fail("build proxyjump alias reference baseline", error.toString());
            return;
        }
        final HostConfig jumpHost = makeHost("jump-alias", jump, 22);
        SshTunnelEngine.HostResolver resolver = new SshTunnelEngine.HostResolver() {
            @Override public HostConfig find(String alias) {
                return "jump-alias".equals(alias) ? jumpHost : null;
            }
        };
        SshTunnelEngine engine = new SshTunnelEngine(listener(), resolver);
        HostConfig host = makeHost("jump-alias-case", target, targetRemotePort);
        host.proxyJump = "jump-alias";
        try {
            int localPort = engine.ensureOpen(host, TRUST_ALL, 30000, false);
            check("proxyjump alias tunnel is open", localPort > 0 && engine.isOpen("jump-alias-case"),
                    "port=" + localPort);
            checkBanner("proxyjump alias jump", localPort, reference.banner);
        } catch (AppError error) {
            fail("proxyjump alias tunnel", error.kind + " " + error.getMessage());
        } finally {
            engine.closeAll();
        }
    }

    /** TunnelManager: concurrent ensureOpen for one alias connects exactly once. */
    private static void testManagerSharing(Endpoint endpoint, int remotePort) {
        final List<String> managerLogs = Collections.synchronizedList(new ArrayList<String>());
        SshTunnelEngine.Listener listener = new SshTunnelEngine.Listener() {
            @Override public void onState(String alias, String state) { }

            @Override public void onHostKey(String alias, String hostPortKey, String keyType,
                                            String keyBase64, String fingerprint) { }

            @Override public void onLog(String message) {
                managerLogs.add(message);
            }
        };
        TunnelManager manager = new TunnelManager(listener);
        HostConfig host = makeHost("shared-case", endpoint, remotePort);
        final SshTunnelEngine.HostKeyPolicy trust = TRUST_ALL;
        final int[] ports = new int[2];
        final boolean[] ok = new boolean[2];
        final CountDownLatch start = new CountDownLatch(1);
        Thread first = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    start.await();
                    ports[0] = manager.ensureOpen(host, trust, 25000, false);
                    ok[0] = true;
                } catch (Exception error) {
                    System.out.println("  [shared] first ensureOpen: " + error);
                }
            }
        });
        Thread second = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    start.await();
                    ports[1] = manager.ensureOpen(host, trust, 25000, false);
                    ok[1] = true;
                } catch (Exception error) {
                    System.out.println("  [shared] second ensureOpen: " + error);
                }
            }
        });
        try {
            first.start();
            second.start();
            start.countDown();
            first.join(60000);
            second.join(60000);
            check("concurrent ensureOpen both succeed", ok[0] && ok[1], ok[0] + "/" + ok[1]);
            check("concurrent ensureOpen share one port", ok[0] && ok[1] && ports[0] == ports[1],
                    ports[0] + " vs " + ports[1]);
            check("manager reports one open tunnel", manager.openCount() == 1,
                    String.valueOf(manager.openCount()));
            Map<String, Integer> snapshot = manager.openTunnels();
            check("manager snapshot contains alias->port",
                    snapshot.containsKey("shared-case") && snapshot.get("shared-case") == ports[0],
                    snapshot.toString());
            int connections = 0;
            synchronized (managerLogs) {
                for (String line : managerLogs) {
                    if (line.contains("SSH 会话已建立")) connections++;
                }
            }
            check("concurrent ensureOpen connects once", connections == 1, "connections=" + connections);
            check("manager.close returns true", manager.close("shared-case"), "");
            check("manager.close clears snapshot", manager.openTunnels().isEmpty(),
                    manager.openTunnels().toString());
            check("manager.close second time false", !manager.close("shared-case"), "");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            fail("concurrent ensureOpen", "interrupted");
        } finally {
            manager.closeAll();
        }
        try {
            HostConfig late = makeHost("late-case", endpoint, remotePort);
            manager.ensureOpen(late, trust, 5000, false);
            fail("manager refuses opens after closeAll", "no exception");
        } catch (AppError error) {
            check("manager refuses opens after closeAll", error.kind == ErrorKind.BAD_REQUEST, error.kind.id);
        }
    }

    // -------------------------------------------------------------------- main

    /**
     * v0.2.0 KeyResolver path: the env private key is imported into an
     * {@link SshKeyRecord}, the host references it only by {@code keyId}, and a
     * real connection must succeed WITHOUT any inline private key on the host.
     * Also verifies the defensive legacy-inline fallback and the
     * missing-key -> NEEDS_CREDENTIALS contract.
     */
    private static void testKeyResolverPath(Endpoint direct, Endpoint jump, Endpoint target,
                                            int directRemotePort, int targetRemotePort) {
        String envPassphrase = setting("SSH_TEST_PASSPHRASE");
        String passphrase = envPassphrase == null ? "" : envPassphrase;
        SshKeyRecord imported;
        try {
            imported = SshKeyManager.importKeyPair("env-key", keyText, passphrase, "");
        } catch (AppError error) {
            fail("KeyResolver: import env private key", error.kind + " " + error.getMessage());
            return;
        }
        final SshKeyRecord keyRecord = imported;
        check("KeyResolver: imported key id format", keyRecord.id.matches("key-[0-9a-f]{12}"), keyRecord.id);
        check("KeyResolver: imported key fingerprint",
                keyRecord.fingerprint != null && keyRecord.fingerprint.startsWith("SHA256:"),
                keyRecord.fingerprint);
        check("KeyResolver: imported key type", keyRecord.keyType != null && !keyRecord.keyType.isEmpty(),
                keyRecord.keyType);

        Endpoint connect;
        int remotePort;
        String proxyJump = "";
        Reference reference;
        try {
            if (direct != null) {
                connect = direct;
                remotePort = directRemotePort;
                reference = referenceDirect(direct, directRemotePort);
            } else if (jump != null && target != null) {
                connect = target;
                remotePort = targetRemotePort;
                proxyJump = jump.user + "@" + jump.host + ":" + jump.port;
                reference = referenceProxyJump(jump, target, targetRemotePort);
            } else {
                return;
            }
        } catch (Exception error) {
            fail("KeyResolver: build reference baseline", error.toString());
            return;
        }

        SshTunnelEngine.KeyResolver resolver = new SshTunnelEngine.KeyResolver() {
            @Override
            public SshKeyRecord find(String keyId) {
                return keyRecord.id.equals(keyId) ? keyRecord : null;
            }
        };
        SshTunnelEngine engine = new SshTunnelEngine(listener(), resolver);

        HostConfig host = makeHost("key-store-case", connect, remotePort);
        host.keyId = keyRecord.id;
        // v0.2.0 hosts carry NO inline private key: everything comes from keyId.
        host.privateKey = "";
        host.keyPassphrase = "";
        host.proxyJump = proxyJump;
        try {
            int localPort = engine.ensureOpen(host, TRUST_ALL, 30000, false);
            check("KeyResolver: tunnel opens from key-store material",
                    localPort > 0 && engine.isOpen("key-store-case"), "port=" + localPort);
            checkBanner("KeyResolver key-store", localPort, reference.banner);
        } catch (AppError error) {
            fail("KeyResolver tunnel with key-store material",
                    error.kind + " " + error.getMessage());
        } finally {
            engine.closeAll();
        }

        // A keyId that the store no longer knows must be a clear credential error.
        HostConfig missing = makeHost("key-missing-case", connect, remotePort);
        missing.keyId = "key-000000000000";
        missing.privateKey = "";
        missing.proxyJump = proxyJump;
        try {
            engine.ensureOpen(missing, TRUST_ALL, 10000, false);
            fail("KeyResolver: missing key rejected", "no exception thrown");
        } catch (AppError error) {
            check("KeyResolver: missing key -> NEEDS_CREDENTIALS",
                    error.kind == ErrorKind.NEEDS_CREDENTIALS, error.kind.id);
            String message = error.getMessage() == null ? "" : error.getMessage();
            check("KeyResolver: missing-key message asks to re-select",
                    message.contains("已不存在"), message);
            check("KeyResolver: missing-key message has no key material",
                    !message.contains("PRIVATE KEY"), message);
        } finally {
            engine.closeAll();
        }

        // TunnelManager wiring: the service path must forward the KeyResolver too.
        HostConfig managerHost = makeHost("key-store-manager-case", connect, remotePort);
        managerHost.keyId = keyRecord.id;
        managerHost.privateKey = "";
        managerHost.proxyJump = proxyJump;
        TunnelManager keyedManager = new TunnelManager(listener(), null, resolver);
        try {
            int managerPort = keyedManager.ensureOpen(managerHost, TRUST_ALL, 30000, false);
            check("KeyResolver: TunnelManager opens tunnel from key store",
                    managerPort > 0 && keyedManager.openTunnels().containsKey("key-store-manager-case"),
                    "port=" + managerPort);
            checkBanner("KeyResolver via TunnelManager", managerPort, reference.banner);
        } catch (AppError error) {
            fail("KeyResolver via TunnelManager", error.kind + " " + error.getMessage());
        } finally {
            keyedManager.closeAll();
        }

        // Defensive legacy fallback: un-migrated host with an inline key still works.
        HostConfig legacy = makeHost("legacy-inline-case", connect, remotePort);
        legacy.keyId = "";
        legacy.privateKey = keyText;
        legacy.keyPassphrase = passphrase;
        legacy.proxyJump = proxyJump;
        try {
            int legacyPort = engine.ensureOpen(legacy, TRUST_ALL, 30000, false);
            check("KeyResolver: legacy inline key fallback opens tunnel",
                    legacyPort > 0 && engine.isOpen("legacy-inline-case"), "port=" + legacyPort);
            checkBanner("KeyResolver legacy inline", legacyPort, reference.banner);
        } catch (AppError error) {
            fail("KeyResolver legacy inline fallback", error.kind + " " + error.getMessage());
        } finally {
            engine.closeAll();
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("TunnelEngineTest: SSH engine integration test");
        testValidation();

        Endpoint direct = null;
        Endpoint jump = null;
        Endpoint target = null;
        try {
            direct = Endpoint.fromEnv("SSH_TEST_DIRECT");
            jump = Endpoint.fromEnv("SSH_TEST_JUMP");
            target = Endpoint.fromEnv("SSH_TEST_TARGET");
        } catch (IllegalArgumentException badConfig) {
            fail("environment configuration", badConfig.getMessage());
        }

        boolean directConfigured = direct != null;
        boolean jumpConfigured = jump != null && target != null;
        if (!directConfigured && !jumpConfigured) {
            String message = "SKIP TunnelEngineTest: set SSH_TEST_DIRECT_HOST (+USER) "
                    + "or SSH_TEST_JUMP_*/SSH_TEST_TARGET_* to run the real-SSH cases";
            if (required()) {
                System.out.println("FAIL " + message + " (ssh.require=true)");
                System.exit(1);
            }
            System.out.println(message);
            if (failures != 0) {
                System.out.println("TUNNEL ENGINE TEST FAILED (" + failures + " check(s))");
                System.exit(1);
            }
            System.out.println("TUNNEL ENGINE TEST PASSED (network cases skipped)");
            return;
        }

        String keyPath = setting("SSH_TEST_KEY");
        if (keyPath == null || keyPath.isEmpty()) {
            keyPath = System.getProperty("user.home") + "/.ssh/id_rsa";
        }
        Path keyFile = Paths.get(keyPath.replace("~", System.getProperty("user.home")));
        if (!Files.isReadable(keyFile)) {
            fail("private key readable at configured path", keyFile.toString());
            System.out.println("TUNNEL ENGINE TEST FAILED (" + failures + ")");
            System.exit(1);
        }
        // Read once; the engine receives the text and nothing is printed.
        keyText = new String(Files.readAllBytes(keyFile), StandardCharsets.UTF_8);

        int directRemotePort = intSetting("SSH_TEST_DIRECT_REMOTE_PORT", 22);
        int targetRemotePort = intSetting("SSH_TEST_TARGET_REMOTE_PORT", 22);

        if (directConfigured) {
            testDirect(direct, directRemotePort);
            testHostKeyPolicy(direct, directRemotePort, "tofu-case");
            testManagerSharing(direct, directRemotePort);
        } else {
            System.out.println("SKIP direct cases: SSH_TEST_DIRECT_HOST not set");
        }

        if (jumpConfigured) {
            testProxyJump(jump, target, targetRemotePort);
            testProxyJumpAlias(jump, target, targetRemotePort);
        } else {
            System.out.println("SKIP proxyjump cases: SSH_TEST_JUMP_* / SSH_TEST_TARGET_* not fully set");
        }

        // v0.2.0: real connection using an imported key-store entry (host.keyId).
        if (directConfigured || jumpConfigured) {
            testKeyResolverPath(direct, directConfigured ? null : jump,
                    directConfigured ? null : target, directRemotePort, targetRemotePort);
        } else {
            System.out.println("SKIP key-resolver case: no real endpoint configured");
        }

        if (failures > 0) {
            System.out.println("TUNNEL ENGINE TEST FAILED (" + failures + " check(s))");
            System.exit(1);
        }
        System.out.println("TUNNEL ENGINE TEST PASSED");
    }
}

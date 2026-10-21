import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SocketFactory;
import com.jcraft.jsch.UserInfo;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Desktop smoke test for the SSH engine design used by the Android app. It
 * proves the two connection shapes the app supports: a direct connection and a
 * single jump host (ProxyJump), each with a live local port forward (verified by
 * reading the target's SSH banner through the forwarded port).
 *
 * This test talks to a REAL host and is therefore configured entirely from the
 * environment; no host, user or key material is checked in:
 *
 *   SSH_TEST_KEY                   private key path      (default: ~/.ssh/id_rsa)
 *   SSH_TEST_PASSPHRASE            private key passphrase (optional)
 *   SSH_TEST_DIRECT_HOST           direct case host      (skip direct case if unset)
 *   SSH_TEST_DIRECT_USER           direct case user
 *   SSH_TEST_DIRECT_PORT           direct case SSH port   (default 22; may be a NAT port)
 *   SSH_TEST_DIRECT_REMOTE_PORT    forwarded remote port  (default 22 = remote sshd)
 *   SSH_TEST_JUMP_HOST             jump host             (proxyjump case, all three required)
 *   SSH_TEST_JUMP_USER             jump user
 *   SSH_TEST_JUMP_PORT             jump port             (default 22)
 *   SSH_TEST_TARGET_HOST           target host (reached through the jump)
 *   SSH_TEST_TARGET_USER           target user
 *   SSH_TEST_TARGET_PORT           target SSH port       (default 22)
 *   SSH_TEST_TARGET_REMOTE_PORT    forwarded remote port (default 22 = remote sshd)
 *
 * The *_REMOTE_PORT variables exist because the locally forwarded port must
 * target the sshd bound on the remote loopback (usually 22), which can differ
 * from the externally reachable connect port (for example a NAT gateway
 * forwarding :20000 to :22). Defaults match TunnelEngineTest.
 *
 * With nothing configured the test SKIPS and exits 0 so it can live in the
 * default test run. `-Dssh.require=true` turns a skip into a failure, which is
 * what the release acceptance run uses.
 *
 * Run via tools/test-jvm.ps1; see also test/jvm/TunnelEngineTest.java, which
 * exercises the app's SshTunnelEngine with the same environment variables.
 */
public final class SshSmokeTest {
    private static final UserInfo NO_PROMPT = new UserInfo() {
        @Override public String getPassphrase() { return null; }
        @Override public String getPassword() { return null; }
        @Override public boolean promptPassword(String message) { return false; }
        @Override public boolean promptPassphrase(String message) { return false; }
        @Override public boolean promptYesNo(String message) { return false; }
        @Override public void showMessage(String message) { System.out.println("[ssh] " + message); }
    };

    /** Accept every host key: this smoke test is about transport, not trust. */
    private static final class AcceptAll implements HostKeyRepository {
        @Override public int check(String host, byte[] key) { return HostKeyRepository.OK; }
        @Override public void add(HostKey hostkey, UserInfo ui) { }
        @Override public void remove(String host, String type) { }
        @Override public void remove(String host, String type, byte[] key) { }
        @Override public String getKnownHostsRepositoryID() { return "smoke-test"; }
        @Override public HostKey[] getHostKey() { return new HostKey[0]; }
        @Override public HostKey[] getHostKey(String host, String type) { return new HostKey[0]; }
    }

    /** One SSH endpoint taken from the environment. */
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
        // Properties win (handy for IDE runs); env is the documented interface.
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

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    private static JSch newJsch(String keyPath, String passphrase) throws Exception {
        JSch jsch = new JSch();
        jsch.setHostKeyRepository(new AcceptAll());
        jsch.addIdentity(keyPath, passphrase == null ? "" : passphrase);
        return jsch;
    }

    private static Session connect(JSch jsch, Endpoint endpoint, int jumpLocalPort) throws Exception {
        Session session = jsch.getSession(endpoint.user, endpoint.host, endpoint.port);
        session.setUserInfo(NO_PROMPT);
        session.setConfig("StrictHostKeyChecking", "no");
        session.setConfig("PreferredAuthentications", "publickey");
        session.setServerAliveInterval(15000);
        session.setServerAliveCountMax(3);
        if (jumpLocalPort > 0) {
            session.setSocketFactory(new SocketFactory() {
                @Override public Socket createSocket(String target, int targetPort) throws java.io.IOException {
                    return new Socket("127.0.0.1", jumpLocalPort);
                }
                @Override public InputStream getInputStream(Socket socket) throws java.io.IOException {
                    return socket.getInputStream();
                }
                @Override public java.io.OutputStream getOutputStream(Socket socket) throws java.io.IOException {
                    return socket.getOutputStream();
                }
            });
        }
        session.connect(20000);
        return session;
    }

    private static void forward(Session session, int localPort, int remotePort) throws Exception {
        session.setPortForwardingL("127.0.0.1", localPort, "127.0.0.1", remotePort);
    }

    /** Forwarding to the target's own sshd port yields an SSH banner: proof the tunnel is live. */
    private static String readBannerThroughTunnel(int localPort) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", localPort)) {
            socket.setSoTimeout(15000);
            InputStream in = socket.getInputStream();
            byte[] buffer = new byte[64];
            int read = in.read(buffer);
            return read <= 0 ? "" : new String(buffer, 0, read, java.nio.charset.StandardCharsets.US_ASCII).trim();
        }
    }

    private static void check(String name, String banner) {
        if (!banner.startsWith("SSH-2.0-")) {
            throw new AssertionError(name + ": expected SSH banner, got \"" + banner + "\"");
        }
        System.out.println("PASS " + name + " (banner=" + banner + ")");
    }

    public static void main(String[] args) throws Exception {
        String keyPath = setting("SSH_TEST_KEY");
        if (keyPath == null || keyPath.isEmpty()) {
            keyPath = System.getProperty("user.home") + "/.ssh/id_rsa";
        }
        String passphrase = setting("SSH_TEST_PASSPHRASE");
        Endpoint direct = Endpoint.fromEnv("SSH_TEST_DIRECT");
        Endpoint jump = Endpoint.fromEnv("SSH_TEST_JUMP");
        Endpoint target = Endpoint.fromEnv("SSH_TEST_TARGET");
        // Port the local forward points at on the remote loopback. The sshd is
        // normally on 22 regardless of the externally reachable connect port
        // (NAT gateways forward e.g. :20000 -> :22), so 22 is the default here,
        // matching TunnelEngineTest; override via the *_REMOTE_PORT variables.
        int directRemotePort = direct == null ? 0 : intSetting("SSH_TEST_DIRECT_REMOTE_PORT", 22);
        int targetRemotePort = target == null ? 0 : intSetting("SSH_TEST_TARGET_REMOTE_PORT", 22);

        if (direct == null && (jump == null || target == null)) {
            String message = "SKIP SshSmokeTest: set SSH_TEST_DIRECT_HOST (+USER) or SSH_TEST_JUMP_*/SSH_TEST_TARGET_* to run";
            if (required()) {
                System.out.println("FAIL " + message + " (ssh.require=true)");
                System.exit(1);
            }
            System.out.println(message);
            return;
        }

        int failures = 0;
        Session directSession = null;
        Session jumpSession = null;
        Session targetSession = null;
        try {
            if (direct != null) {
                try {
                    JSch jsch = newJsch(keyPath, passphrase);
                    directSession = connect(jsch, direct, 0);
                    int local = freePort();
                    forward(directSession, local, directRemotePort);
                    check("direct " + direct.display() + " tunnel (forwarding to :" + directRemotePort + ")",
                            readBannerThroughTunnel(local));
                } catch (Exception error) {
                    failures++;
                    System.out.println("FAIL direct case: " + error);
                }
            } else {
                System.out.println("SKIP direct case: SSH_TEST_DIRECT_HOST not set");
            }

            if (jump != null && target != null) {
                try {
                    JSch jumpJsch = newJsch(keyPath, passphrase);
                    jumpSession = connect(jumpJsch, jump, 0);
                    int jumpTunnelPort = freePort();
                    forward(jumpSession, jumpTunnelPort, target.port);

                    JSch targetJsch = newJsch(keyPath, passphrase);
                    targetSession = connect(targetJsch, target, jumpTunnelPort);
                    int local = freePort();
                    forward(targetSession, local, targetRemotePort);
                    check("proxyjump " + jump.display() + " -> " + target.display()
                                    + " tunnel (forwarding to :" + targetRemotePort + ")",
                            readBannerThroughTunnel(local));
                } catch (Exception error) {
                    failures++;
                    System.out.println("FAIL proxyjump case: " + error);
                }
            } else {
                System.out.println("SKIP proxyjump case: SSH_TEST_JUMP_* / SSH_TEST_TARGET_* not fully set");
            }
        } finally {
            if (targetSession != null) targetSession.disconnect();
            if (jumpSession != null) jumpSession.disconnect();
            if (directSession != null) directSession.disconnect();
        }

        if (failures > 0) {
            System.out.println("SMOKE TEST FAILED (" + failures + " case(s))");
            System.exit(1);
        }
        System.out.println("SMOKE TEST PASSED");
    }
}

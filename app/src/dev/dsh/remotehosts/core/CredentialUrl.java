package dev.dsh.remotehosts.core;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;

/**
 * Parsing and construction of the one credential shape a remote host needs.
 *
 * A user copies the URL that {@code dsh web} printed on the remote machine:
 *
 * <pre>http://127.0.0.1:3080/?token=9f4c…</pre>
 *
 * Only the port and the token are retained; the host part is irrelevant because
 * the app reaches that port through its own SSH tunnel.
 *
 * SECURITY: {@link #browserUrlFor(int, String)} is the ONLY function in the app
 * allowed to put a token into a URL. The token must never reach a log line, a
 * notification, a preferences file or an error message.
 */
public final class CredentialUrl {

    private CredentialUrl() {
    }

    /** Result of parsing user input, with a user-facing reason on failure. */
    public static final class Parsed {
        public final boolean ok;
        public final int remotePort;
        public final String token;
        public final String reason;

        private Parsed(boolean ok, int remotePort, String token, String reason) {
            this.ok = ok;
            this.remotePort = remotePort;
            this.token = token;
            this.reason = reason;
        }

        static Parsed failure(String reason) {
            return new Parsed(false, 0, "", reason);
        }

        static Parsed success(int remotePort, String token) {
            return new Parsed(true, remotePort, token, "");
        }
    }

    /**
     * Parse the credential string printed by {@code dsh web}.
     *
     * Accepts the loopback form and the LAN form (only port + token matter).
     * The reason string never contains the token.
     */
    public static Parsed parse(String input) {
        if (input == null || input.trim().isEmpty()) {
            return Parsed.failure("凭证为空");
        }
        String trimmed = input.trim();
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException error) {
            return Parsed.failure("不是合法的网址，请粘贴 dsh web 打印的完整地址");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return Parsed.failure("网址协议必须是 http 或 https");
        }
        String rawQuery = uri.getRawQuery();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return Parsed.failure("网址缺少 ?token=…，请复制 dsh web 打印的完整地址");
        }
        String token = null;
        for (String pair : rawQuery.split("&")) {
            int split = pair.indexOf('=');
            String key = split < 0 ? pair : pair.substring(0, split);
            if (!"token".equals(key)) continue;
            String value = split < 0 ? "" : pair.substring(split + 1);
            token = decode(value);
            break;
        }
        if (token == null || token.isEmpty()) {
            return Parsed.failure("网址缺少 ?token=…，请复制 dsh web 打印的完整地址");
        }
        int port = uri.getPort();
        if (port == -1) {
            port = "https".equals(scheme) ? 443 : 80;
        }
        if (port < 1 || port > 65535) {
            return Parsed.failure("端口不合法");
        }
        return Parsed.success(port, token);
    }

    /**
     * Build the URL handed to the default browser for one tunnel.
     *
     * This mirrors the desktop plugin's {@code browserUrlFor()}: The remote UI
     * answers {@code GET /?token=…} by setting its auth cookie and redirecting to
     * a clean {@code ./}, so the token does not linger in the address bar. If the
     * hand-off ever moves to a cookie-injection reverse proxy, only this method
     * changes.
     *
     * @param localPort local end of the SSH tunnel (loopback)
     * @param token     remote launch token
     * @return {@code http://127.0.0.1:<localPort>/?token=<percent-encoded-token>}
     */
    public static String browserUrlFor(int localPort, String token) {
        if (localPort < 1 || localPort > 65535) {
            throw new IllegalArgumentException("localPort out of range");
        }
        return "http://127.0.0.1:" + localPort + "/?token=" + percentEncode(token == null ? "" : token);
    }

    /**
     * Build the <em>token-less</em> loopback URL used by the built-in
     * {@code WebViewActivity} (the global "通过外部浏览器连接" setting is off;
     * v0.2.2 introduced it for the per-host appendToken=false mode, removed in
     * v0.2.5). See docs/app-spec.md §2.
     *
     * This method deliberately takes no token argument: there is no way for a
     * caller to leak a token into this URL. It is never an alternative token
     * outlet; {@link #browserUrlFor(int, String)} remains the only one.
     *
     * @param localPort local end of the SSH tunnel (loopback)
     * @return {@code http://127.0.0.1:<localPort>/}
     */
    public static String plainUrlFor(int localPort) {
        if (localPort < 1 || localPort > 65535) {
            throw new IllegalArgumentException("localPort out of range");
        }
        return "http://127.0.0.1:" + localPort + "/";
    }

    /** Mask a token for display: keep a 3-char prefix/suffix so credentials stay distinguishable. */
    public static String maskToken(String token) {
        if (token == null || token.isEmpty()) return "";
        if (token.length() <= 6) return "******";
        String masked = token.substring(0, 3) + "…" + token.substring(token.length() - 3);
        return masked.equals(token) ? "******" : masked;
    }

    /** Whether a direct (no tunnel) URL is plausible. Does not validate connectivity. */
    public static boolean isValidDirectUrl(String url) {
        if (url == null || url.trim().isEmpty()) return false;
        try {
            URI uri = new URI(url.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            if (!"http".equals(scheme) && !"https".equals(scheme)) return false;
            return uri.getHost() != null && !uri.getHost().isEmpty();
        } catch (URISyntaxException error) {
            return false;
        }
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException error) {
            return value;
        }
    }

    /** RFC 3986 percent-encoding of a single query value (encodeURIComponent semantics). */
    static String percentEncode(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index += 1) {
            char ch = value.charAt(index);
            boolean unreserved =
                    (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')
                            || (ch >= '0' && ch <= '9')
                            || ch == '-' || ch == '_' || ch == '.' || ch == '~';
            if (unreserved) {
                out.append(ch);
                continue;
            }
            byte[] bytes;
            try {
                bytes = String.valueOf(ch).getBytes("UTF-8");
            } catch (UnsupportedEncodingException impossible) {
                bytes = new byte[]{(byte) ch};
            }
            for (byte valueByte : bytes) {
                out.append('%');
                int high = (valueByte >> 4) & 0xF;
                int low = valueByte & 0xF;
                out.append(Character.toUpperCase(Character.forDigit(high, 16)));
                out.append(Character.toUpperCase(Character.forDigit(low, 16)));
            }
        }
        return out.toString();
    }
}

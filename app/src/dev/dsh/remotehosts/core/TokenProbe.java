package dev.dsh.remotehosts.core;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * v0.2.1: pre-flight check of a remote DSH launch token, performed through the
 * just-opened SSH tunnel before the app hands the user to the browser
 * (docs/app-spec.md §5 item 7).
 *
 * Why this exists: the remote {@code dsh web} answers {@code GET /?token=…} with
 * a 303 that sets its session cookie; without a valid token the next navigation
 * renders {@code "dsh web authentication required; reopen the URL printed by dsh
 * web."} inside the browser. The app cannot otherwise tell a stale token from a
 * working one, so a rejected token would look like "the app opened a broken
 * page". One probe turns that into an explicit, actionable app error.
 *
 * Semantics:
 *  - 2xx / 3xx → {@link Result#OK} (the token exchange works and sets a cookie);
 *  - 401 / 403 → {@link Result#REJECTED} (stale/wrong token);
 *  - IO error / timeout / invalid input → {@link Result#UNREACHABLE};
 *  - every other status code (404/5xx/…) → {@link Result#OK}: the probe must not
 *    block a user whose remote service answers oddly but may still work.
 *
 * SECURITY: the token only ever appears inside the request URL, which is built
 * by {@link CredentialUrl#browserUrlFor} (the single approved token-in-URL
 * outlet). This class never logs, never puts the URL/token in an exception and
 * never returns either of them; the result enum is the entire output.
 *
 * Pure Java (no Android imports) so it is unit-tested on a desktop JVM.
 */
public final class TokenProbe {

    /** What the token pre-check concluded. */
    public enum Result {
        /** The token exchange answered 2xx/3xx: the browser hand-off can proceed. */
        OK,
        /** 401/403: the remote rejected the token (stale/wrong). */
        REJECTED,
        /** Connection error or timeout: readiness could not be verified. */
        UNREACHABLE
    }

    /** Suggested timeout for the app path. */
    public static final int DEFAULT_TIMEOUT_MS = 5000;

    private TokenProbe() {
    }

    /**
     * Probe {@code http://127.0.0.1:<localPort>/?token=<token>} once, without
     * following the remote 303 (the cookie only has to be minted; the browser
     * performs the real navigation later).
     *
     * @param localPort the tunnel's loopback port
     * @param token     the stored launch token (never logged/returned)
     * @param timeoutMs connect+read timeout; non-positive falls back to
     *                  {@link #DEFAULT_TIMEOUT_MS}
     * @return OK / REJECTED / UNREACHABLE; never null
     */
    public static Result check(int localPort, String token, int timeoutMs) {
        if (localPort < 1 || localPort > 65535) return Result.UNREACHABLE;
        if (token == null || token.isEmpty()) return Result.UNREACHABLE;
        int effectiveTimeout = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
        // The ONLY place the token enters a URL; the local variable is never
        // logged or attached to an exception.
        String probeUrl = CredentialUrl.browserUrlFor(localPort, token);
        HttpURLConnection connection = null;
        try {
            URL url = new URL(probeUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(effectiveTimeout);
            connection.setReadTimeout(effectiveTimeout);
            connection.setRequestMethod("GET");
            connection.setUseCaches(false);
            // Do NOT set restricted headers such as "Connection: close": Android's
            // HttpURLConnection may reject them and the resulting RuntimeException
            // would turn every probe into a false UNREACHABLE. A short-lived
            // connection is guaranteed by disconnect() in the finally block instead.
            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_UNAUTHORIZED
                    || status == HttpURLConnection.HTTP_FORBIDDEN) {
                return Result.REJECTED;
            }
            // 2xx/3xx are the healthy answers; other 4xx/5xx are deliberately not
            // treated as a token rejection (the browser gets its chance).
            return Result.OK;
        } catch (IOException networkError) {
            return Result.UNREACHABLE;
        } catch (RuntimeException unexpected) {
            // Malformed URL / protocol misuse: nothing was verified, so do not
            // block the user with a rejection.
            return Result.UNREACHABLE;
        } finally {
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (RuntimeException ignored) {
                    // The result was already decided; disconnect is best effort.
                }
            }
        }
    }
}

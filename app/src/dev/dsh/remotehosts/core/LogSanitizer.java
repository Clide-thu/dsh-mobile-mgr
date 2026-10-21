package dev.dsh.remotehosts.core;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Scrubs secrets out of anything that may leave the process boundary.
 *
 * SECURITY CONTRACT (docs/app-spec.md §2/§5.9): a token, a password, a private
 * key and a key passphrase must never appear in a log line, a notification, an
 * exception message, a broadcast extra or a UI dialog. The rest of the core
 * layer is written so those values are never put into such a string in the
 * first place; this class is the belt-and-braces net for the places where an
 * exception from a library or an OS message could carry one back.
 *
 * Two mechanisms are applied:
 *  1. Every known secret is replaced literally, plus its percent-encoded form
 *     (the token also travels inside a URL query in error text produced by
 *     libraries, so the encoded spelling must be redacted too).
 *  2. Structural patterns catch leftovers even when the caller forgot to pass
 *     the secret: {@code token=…}, {@code password=…}, {@code passphrase=…},
 *     {@code Authorization: Bearer …} and complete PEM private-key blocks.
 *
 * Pure Java, no Android imports: the engine and the unit tests both use it on a
 * desktop JVM.
 */
public final class LogSanitizer {

    /** Replacement text; deliberately not the secret's length. */
    public static final String REDACTED = "[redacted]";

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** {@code token=…} / {@code password=…} query-style leftovers. */
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)([?&]|\\b)(token|password|passwd|passphrase|pwd)=([^\\s&\"'<>]*)");

    /** A full PEM private key block (RSA/EC/OpenSSH/PKCS#8), including line breaks. */
    private static final Pattern PEM_PRIVATE_KEY = Pattern.compile(
            "-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z0-9 ]*PRIVATE KEY-----");

    /** {@code Authorization: Bearer …}. */
    private static final Pattern BEARER = Pattern.compile(
            "(?i)(bearer\\s+)[A-Za-z0-9._~+\\/=\\-]{4,}");

    /** PGP-ish/raw private key blocks some tools quote in messages. */
    private static final Pattern OPENSSH_KEY_BODY = Pattern.compile(
            "(?i)(-----BEGIN OPENSSH PRIVATE KEY-----)[\\s\\S]*?(-----END OPENSSH PRIVATE KEY-----)");

    private LogSanitizer() {
    }

    /**
     * Replace every occurrence of every secret in {@code text} with
     * {@link #REDACTED}, then apply the structural patterns above.
     *
     * Null-tolerant: a null text becomes an empty string, null/empty secrets are
     * skipped, and the result never throws for ordinary input.
     */
    public static String sanitize(String text, String... secrets) {
        String result = text == null ? "" : text;
        for (String secret : expandSecrets(secrets)) {
            result = replaceLiteral(result, secret);
            result = replaceLiteral(result, percentEncode(secret));
            result = replaceLiteral(result, formEncode(secret));
        }
        result = SECRET_ASSIGNMENT.matcher(result).replaceAll("$1$2=" + REDACTED);
        result = BEARER.matcher(result).replaceAll("$1" + REDACTED);
        result = PEM_PRIVATE_KEY.matcher(result).replaceAll(REDACTED);
        result = OPENSSH_KEY_BODY.matcher(result).replaceAll(REDACTED);
        return result;
    }

    /** Sanitise a throwable's message (class name when the message is absent). */
    public static String sanitizeThrowable(Throwable error, String... secrets) {
        if (error == null) return "";
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = error.getClass().getSimpleName();
        }
        return sanitize(message, secrets);
    }

    /**
     * Outlet filter for {@link AppError}: returns a fresh error with the same
     * {@link ErrorKind} and a scrubbed message.
     *
     * The cause is intentionally dropped: a library cause may carry the original
     * (unscrubbed) text and stack traces are not needed at the UI boundary. Pass
     * {@code null} through unchanged.
     */
    public static AppError sanitizeError(AppError error, String... secrets) {
        if (error == null) return null;
        return new AppError(error.kind, sanitize(error.getMessage(), secrets));
    }

    /**
     * The secret material a {@link HostConfig} owns, non-empty entries only.
     * Convenience so call sites cannot forget one of the four fields.
     */
    public static String[] secretsOf(HostConfig host) {
        if (host == null) return new String[0];
        List<String> secrets = new ArrayList<>(4);
        addIfPresent(secrets, host.token);
        addIfPresent(secrets, host.password);
        addIfPresent(secrets, host.privateKey);
        addIfPresent(secrets, host.keyPassphrase);
        return secrets.toArray(new String[0]);
    }

    private static void addIfPresent(List<String> out, String value) {
        if (value != null && !value.isEmpty()) out.add(value);
    }

    /**
     * Deduplicate secrets and drop anything that would make replacement unsafe
     * (null, empty) or useless. Secrets are kept exactly as given; callers pass
     * the raw values.
     */
    private static List<String> expandSecrets(String... secrets) {
        if (secrets == null || secrets.length == 0) return new ArrayList<>(0);
        Set<String> unique = new LinkedHashSet<>();
        for (String secret : secrets) {
            if (secret == null || secret.isEmpty()) continue;
            unique.add(secret);
        }
        return new ArrayList<>(unique);
    }

    private static String replaceLiteral(String text, String needle) {
        if (needle == null || needle.isEmpty() || text.isEmpty()) return text;
        return text.replace(needle, REDACTED);
    }

    /** RFC 3986 percent-encoding, matching what {@link CredentialUrl#browserUrlFor} emits. */
    private static String percentEncode(String value) {
        try {
            return CredentialUrl.percentEncode(value);
        } catch (RuntimeException impossible) {
            return value;
        }
    }

    /** application/x-www-form-urlencoded spelling ({@code +} for spaces), seen in forms/logs. */
    private static String formEncode(String value) {
        try {
            return URLEncoder.encode(value, UTF8.name());
        } catch (UnsupportedEncodingException | RuntimeException impossible) {
            return value;
        }
    }
}

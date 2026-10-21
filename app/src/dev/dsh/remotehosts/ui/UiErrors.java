package dev.dsh.remotehosts.ui;

import android.content.Context;

import java.util.List;

import dev.dsh.remotehosts.R;
import dev.dsh.remotehosts.core.ErrorKind;
import dev.dsh.remotehosts.core.HostConfig;
import dev.dsh.remotehosts.core.HostStore;

/**
 * Maps {@link ErrorKind} values to the Chinese hints from docs/app-spec.md §8
 * and gives every message a last line of defence against leaked secrets.
 *
 * Core already sanitises its messages; this scrub is defence in depth, because
 * a stray exception message must still never put a token/password on screen.
 * Nothing here is ever logged.
 */
final class UiErrors {

    private UiErrors() {
    }

    /** Full user-facing text for a failed action. */
    static String describe(Context context, ErrorKind kind, String rawMessage) {
        String detail = scrub(context, rawMessage);
        int baseRes;
        if (kind == null) {
            baseRes = R.string.error_internal;
        } else {
            switch (kind) {
                case BAD_REQUEST:
                    baseRes = R.string.error_bad_request;
                    break;
                case UNKNOWN_HOST:
                    baseRes = R.string.error_unknown_host;
                    break;
                case NEEDS_CREDENTIALS:
                    baseRes = R.string.error_needs_credentials;
                    break;
                case UNREACHABLE:
                    baseRes = R.string.error_unreachable;
                    break;
                case HOST_KEY_CHANGED:
                    baseRes = R.string.error_host_key_changed;
                    break;
                case INTERNAL:
                default:
                    baseRes = R.string.error_internal;
                    break;
            }
        }
        String base = context.getString(baseRes);
        if (detail == null || detail.isEmpty()) return base;
        return base + context.getString(R.string.error_detail, detail);
    }

    /** Convenience for arbitrary failure text (I/O errors, service start errors). */
    static String describeRaw(Context context, String rawMessage) {
        return scrub(context, rawMessage);
    }

    /**
     * Removes every known host secret from an arbitrary string, plus anything
     * that looks like a {@code token=...} query value.
     */
    static String scrub(Context context, String message) {
        if (message == null) return "";
        String out = message;
        try {
            List<HostConfig> hosts = HostStore.get(context).list();
            for (HostConfig host : hosts) {
                out = replaceSecret(out, host.token);
                out = replaceSecret(out, host.password);
                out = replaceSecret(out, host.keyPassphrase);
                out = replaceSecret(out, host.privateKey);
            }
        } catch (RuntimeException ignored) {
            // The store is best-effort here; never let scrubbing break the UI.
        }
        out = out.replaceAll("(?i)(token=)[^&\\s\"']+", "$1[redacted]");
        return out;
    }

    private static String replaceSecret(String text, String secret) {
        if (secret == null || secret.length() < 4) return text;
        return text.replace(secret, "[redacted]");
    }

    /**
     * Scrub known store secrets plus explicitly supplied ones (for example a
     * private key typed into an import dialog but not persisted yet).
     */
    static String scrubExtra(Context context, String message, String... extraSecrets) {
        String out = scrub(context, message);
        if (extraSecrets == null) return out;
        for (String secret : extraSecrets) {
            out = replaceSecret(out, secret);
        }
        return out;
    }
}

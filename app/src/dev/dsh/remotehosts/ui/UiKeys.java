package dev.dsh.remotehosts.ui;

import android.content.Context;

import dev.dsh.remotehosts.R;

/** Formatting helpers for SSH key records; never touches secret material. */
final class UiKeys {

    private UiKeys() {
    }

    /** Short display form of a SHA256 fingerprint, e.g. {@code SHA256:abcd1234…wxyz}. */
    static String shortFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.isEmpty()) return "?";
        if (fingerprint.length() <= 20) return fingerprint;
        return fingerprint.substring(0, 14) + "…" + fingerprint.substring(fingerprint.length() - 4);
    }

    /** ISO-8601 -> compact display time (best effort; keeps the raw value otherwise). */
    static String formatCreatedAt(String iso) {
        if (iso == null || iso.isEmpty()) return "?";
        String value = iso.trim();
        if (value.length() >= 16) {
            value = value.substring(0, 16).replace('T', ' ');
        }
        return value;
    }

    static String sourceLabel(Context context, String source) {
        if ("generated".equals(source)) return context.getString(R.string.ssh_key_source_generated);
        if ("imported".equals(source)) return context.getString(R.string.ssh_key_source_imported);
        if ("migrated".equals(source)) return context.getString(R.string.ssh_key_source_migrated);
        return source == null || source.isEmpty() ? "?" : source;
    }
}

package dev.dsh.remotehosts.core;

/**
 * Error taxonomy, aligned with the desktop plugin's contract
 * ({@code bad-request | unknown-host | needs-credentials | unreachable | internal})
 * plus one mobile-specific kind for host-key verification.
 */
public enum ErrorKind {
    BAD_REQUEST("bad-request"),
    UNKNOWN_HOST("unknown-host"),
    NEEDS_CREDENTIALS("needs-credentials"),
    UNREACHABLE("unreachable"),
    HOST_KEY_CHANGED("host-key-changed"),
    INTERNAL("internal");

    /** Stable wire/display id (also used in broadcasts). */
    public final String id;

    ErrorKind(String id) {
        this.id = id;
    }

    /** Parse a broadcast extra back to an enum, falling back to INTERNAL. */
    public static ErrorKind fromId(String id) {
        if (id != null) {
            for (ErrorKind kind : values()) {
                if (kind.id.equals(id)) return kind;
            }
        }
        return INTERNAL;
    }
}

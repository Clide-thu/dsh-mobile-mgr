package dev.dsh.remotehosts.core;

/**
 * One SSH key pair managed by the app (v0.2.0 key manager).
 *
 * The app keeps its own key pairs instead of asking the user to paste a private
 * key into every host: hosts only store a {@code keyId} reference, and this
 * record holds the material. Everything here is persisted inside the encrypted
 * host store (`hosts.enc`); the private key and passphrase never leave the app's
 * Keystore-protected storage except through an explicit, user-confirmed action.
 *
 * Plain mutable POJO with no Android imports so the key manager and the JSON
 * codec can be unit-tested on a desktop JVM.
 */
public final class SshKeyRecord {

    /** Unique, stable id, e.g. {@code key-1a2b3c4d5e6f}. */
    public String id = "";
    /** User-visible label, e.g. {@code app-key-1} or {@code gpu-cluster}. */
    public String label = "";
    /** OpenSSH key type: {@code ssh-rsa}, {@code ecdsa-sha2-nistp256}, {@code ssh-ed25519}, … */
    public String keyType = "";
    /** Full OpenSSH public key line: {@code <type> <base64> <comment>}. Public, safe to share. */
    public String publicKey = "";
    /** Private key text (PEM or OpenSSH format). Secret. */
    public String privateKey = "";
    /** Optional passphrase protecting {@link #privateKey}. Secret. */
    public String passphrase = "";
    /** {@code SHA256:<standard-base64(sha256(public key blob))>}, no padding; same as ssh-keygen -lf. */
    public String fingerprint = "";
    /** ISO-8601 creation time. */
    public String createdAt = "";
    /** {@code generated} / {@code imported} / {@code migrated}. */
    public String source = "";

    public SshKeyRecord() {
    }

    /** Deep copy; the store hands out snapshots only. */
    public SshKeyRecord copy() {
        SshKeyRecord copy = new SshKeyRecord();
        copy.id = id;
        copy.label = label;
        copy.keyType = keyType;
        copy.publicKey = publicKey;
        copy.privateKey = privateKey;
        copy.passphrase = passphrase;
        copy.fingerprint = fingerprint;
        copy.createdAt = createdAt;
        copy.source = source;
        return copy;
    }

    /** Short one-line summary for lists; never includes secret material. */
    public String summary() {
        String type = keyType == null || keyType.isEmpty() ? "?" : keyType;
        String fp = fingerprint == null ? "" : fingerprint;
        if (fp.length() > 24) fp = fp.substring(0, 21) + "…";
        return label + " · " + type + " · " + fp;
    }

    @Override
    public String toString() {
        // Deliberately excludes privateKey and passphrase.
        return "SshKeyRecord{" + id + "," + label + "," + keyType + "," + fingerprint + "}";
    }
}

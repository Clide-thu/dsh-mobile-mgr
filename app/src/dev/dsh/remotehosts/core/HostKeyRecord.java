package dev.dsh.remotehosts.core;

/**
 * A trusted SSH host key (TOFU decision), stored in the encrypted host store.
 *
 * {@code fingerprint} is the OpenSSH-style SHA256 fingerprint used only for
 * display ("SHA256:…"); {@code keyBase64} is the raw public key blob JSch
 * reports, which is what actually gets compared on later connections.
 */
public final class HostKeyRecord {

    public String keyType = "";
    public String keyBase64 = "";
    public String fingerprint = "";
    public String addedAt = "";

    public HostKeyRecord() {
    }

    public HostKeyRecord(String keyType, String keyBase64, String fingerprint, String addedAt) {
        this.keyType = keyType;
        this.keyBase64 = keyBase64;
        this.fingerprint = fingerprint;
        this.addedAt = addedAt;
    }

    public HostKeyRecord copy() {
        return new HostKeyRecord(keyType, keyBase64, fingerprint, addedAt);
    }
}

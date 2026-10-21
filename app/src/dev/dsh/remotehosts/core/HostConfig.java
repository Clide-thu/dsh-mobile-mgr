package dev.dsh.remotehosts.core;

import java.util.ArrayList;
import java.util.List;

/**
 * One remote host: how to reach it over SSH, how to authenticate, and where its
 * DSH Web UI credential comes from.
 *
 * This is the wire model between the UI, the encrypted store and the tunnel
 * service. It is intentionally a plain mutable POJO (no Android imports) so the
 * parser, codec and tests can run on a desktop JVM.
 *
 * v0.2.5 removed the old per-host {@code appendToken} switch: whether "打开"
 * hands off to the external browser or uses the built-in WebView is a global
 * preference now ({@code SettingsStore#isBrowserMode}); hosts only keep the
 * credential ({@code remotePort} + {@code token}). Old store documents that
 * still contain an {@code appendToken} field decode normally (the unknown JSON
 * field is ignored).
 */
public final class HostConfig {

    /** How the SSH session authenticates. */
    public enum AuthType {
        PASSWORD,
        PRIVATE_KEY
    }

    /** What "open" means for this host. */
    public enum AccessMode {
        /** Start an SSH tunnel to the remote dsh web port, open its loopback URL. */
        TUNNEL,
        /** Open a ready-made URL (reverse proxy / tailnet / LAN) without a tunnel. */
        DIRECT_URL
    }

    /** SSH config alias, also the unique key for this host. Never empty. */
    public String alias = "";
    /** Resolved HostName (falls back to alias when empty). */
    public String hostName = "";
    /** SSH user. */
    public String user = "";
    /** SSH port, 22 by default. */
    public int port = 22;

    /** SSH authentication selection. */
    public AuthType authType = AuthType.PASSWORD;
    /** Password for {@link AuthType#PASSWORD}. */
    public String password = "";
    /**
     * v0.2.0: id of the key pair in the app key store ({@link SshKeyRecord}) used
     * for {@link AuthType#PRIVATE_KEY}. Preferred over the legacy inline fields.
     */
    public String keyId = "";
    /** Legacy inline private key text; migrated into the key store on first load. */
    public String privateKey = "";
    /** Legacy passphrase for {@link #privateKey}; cleared by the migration. */
    public String keyPassphrase = "";
    /** Optional ProxyJump target: {@code user@host:port} or another host's alias. */
    public String proxyJump = "";

    /** How "open" is carried out. */
    public AccessMode accessMode = AccessMode.TUNNEL;
    /** Remote loopback port the remote {@code dsh web} listens on (TUNNEL mode). */
    public int remotePort = 0;
    /** Remote launch token printed by {@code dsh web} (TUNNEL mode). */
    public String token = "";
    /** Complete URL to open (DIRECT_URL mode). */
    public String directUrl = "";

    /** IdentityFile entries seen in the imported SSH config; informational only. */
    public List<String> identityFiles = new ArrayList<>();
    /** Free-form provenance note, e.g. "imported from ssh config". */
    public String importedFrom = "";

    /** Whether this host has enough credential material for its access mode. */
    public boolean hasCredential() {
        if (accessMode == AccessMode.DIRECT_URL) {
            return CredentialUrl.isValidDirectUrl(directUrl);
        }
        return remotePort > 0 && token != null && !token.isEmpty();
    }

    /** Whether the SSH connection fields are complete. */
    public boolean hasSshIdentity() {
        if (alias == null || alias.trim().isEmpty()) return false;
        if (user == null || user.trim().isEmpty()) return false;
        if (authType == AuthType.PRIVATE_KEY) {
            if (keyId != null && !keyId.trim().isEmpty()) return true;
            // Legacy inline key: only before the store migration has run.
            return privateKey != null && !privateKey.trim().isEmpty();
        }
        return password != null && !password.isEmpty();
    }

    /** Complete enough to attempt "open". */
    public boolean isComplete() {
        return hasSshIdentity() && hasCredential();
    }

    /** Display form of the SSH endpoint, e.g. {@code user@host:22}. */
    public String sshEndpoint() {
        String host = hostName == null || hostName.isEmpty() ? alias : hostName;
        String who = user == null || user.isEmpty() ? "" : user + "@";
        return who + host + ":" + port;
    }

    /** Resolved host name to connect to. */
    public String resolvedHostName() {
        return hostName == null || hostName.trim().isEmpty() ? alias : hostName.trim();
    }

    /** Deep copy; used to hand a snapshot to the service without aliasing UI state. */
    public HostConfig copy() {
        HostConfig copy = new HostConfig();
        copy.alias = alias;
        copy.hostName = hostName;
        copy.user = user;
        copy.port = port;
        copy.authType = authType;
        copy.password = password;
        copy.keyId = keyId;
        copy.privateKey = privateKey;
        copy.keyPassphrase = keyPassphrase;
        copy.proxyJump = proxyJump;
        copy.accessMode = accessMode;
        copy.remotePort = remotePort;
        copy.token = token;
        copy.directUrl = directUrl;
        copy.identityFiles = new ArrayList<>(identityFiles);
        copy.importedFrom = importedFrom;
        return copy;
    }

    @Override
    public String toString() {
        // Deliberately excludes every secret.
        return "HostConfig{" + alias + "," + sshEndpoint() + "," + accessMode + "}";
    }
}

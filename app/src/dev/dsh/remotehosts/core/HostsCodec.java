package dev.dsh.remotehosts.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON wire format for the encrypted host store (docs/app-spec.md §4).
 *
 * <pre>
 * { "version": 2,
 *   "hosts": [ { alias, hostName, user, port, authType, password, keyId,
 *                privateKey, keyPassphrase, proxyJump, accessMode, remotePort,
 *                token, directUrl, identityFiles, importedFrom } ],
 *   "sshKeys": [ { id, label, keyType, publicKey, privateKey, passphrase,
 *                  fingerprint, createdAt, source } ],
 *   "trustedHostKeys": { "host:port": { keyType, keyBase64, fingerprint, addedAt } } }
 * </pre>
 *
 * v0.2.5 removes the per-host {@code appendToken} field (the browser/WebView
 * choice is a global setting now). Old documents that still contain it decode
 * exactly as before: {@link JSONObject} simply ignores the unknown field, so no
 * store version bump or migration is needed.
 *
 * v0.2.0 adds the {@code sshKeys} array; hosts reference a key through
 * {@code keyId}. A v0.1.0 document without {@code sshKeys} (or without
 * {@code keyId}) simply decodes to an empty key list / empty keyId, which is what
 * {@link HostsMigration} then turns into key records on load.
 *
 * This class is pure Java ({@code org.json} is part of the Android platform and
 * is supplied as a jar on the desktop test classpath) so the round trip is
 * covered by JVM unit tests without Android.
 *
 * SECURITY: the encoded text still contains secrets (host tokens/passwords and
 * private keys) in plaintext; it is meant to be handed straight to
 * {@link SecretBox} and never written, logged or shown. Decoding is deliberately
 * total: malformed JSON, wrong types or a missing field must yield an empty /
 * partially filled store instead of crashing first run.
 */
public final class HostsCodec {

    /** Current store schema version (2 = app-managed SSH keys). */
    public static final int VERSION = 2;

    private HostsCodec() {
    }

    /** In-memory projection of the store document. Mutable lists/maps by design. */
    public static final class Decoded {
        public final List<HostConfig> hosts;
        public final List<SshKeyRecord> sshKeys;
        public final Map<String, HostKeyRecord> trustedHostKeys;

        public Decoded(List<HostConfig> hosts, List<SshKeyRecord> sshKeys,
                       Map<String, HostKeyRecord> trustedHostKeys) {
            this.hosts = hosts == null ? new ArrayList<HostConfig>() : hosts;
            this.sshKeys = sshKeys == null ? new ArrayList<SshKeyRecord>() : sshKeys;
            this.trustedHostKeys = trustedHostKeys == null
                    ? new LinkedHashMap<String, HostKeyRecord>() : trustedHostKeys;
        }

        /** Backward-compatible constructor for v0.1.0 call sites (no key store). */
        public Decoded(List<HostConfig> hosts, Map<String, HostKeyRecord> trustedHostKeys) {
            this(hosts, null, trustedHostKeys);
        }

        public static Decoded empty() {
            return new Decoded(new ArrayList<HostConfig>(), new ArrayList<SshKeyRecord>(),
                    new LinkedHashMap<String, HostKeyRecord>());
        }
    }

    /** Encode the whole store (v0.2.0: hosts + app-managed SSH keys + trust table). */
    public static String encode(List<HostConfig> hosts, List<SshKeyRecord> sshKeys,
                                Map<String, HostKeyRecord> trustedHostKeys) {
        JSONObject root = new JSONObject();
        try {
            root.put("version", VERSION);
            root.put("hosts", encodeHosts(hosts));
            root.put("sshKeys", encodeSshKeys(sshKeys));
            root.put("trustedHostKeys", encodeTrusted(trustedHostKeys));
        } catch (JSONException error) {
            // Unreachable for plain string/int/boolean data, but keep the contract:
            // no partial JSON escapes and the message carries no host data.
            throw new IllegalStateException("无法序列化主机数据");
        }
        return root.toString();
    }

    /** Backward-compatible v0.1.0 overload: no app-managed SSH keys. */
    public static String encode(List<HostConfig> hosts, Map<String, HostKeyRecord> trustedHostKeys) {
        return encode(hosts, null, trustedHostKeys);
    }

    /** Encode an already decoded store. */
    public static String encode(Decoded store) {
        if (store == null) return encode(null, null, null);
        return encode(store.hosts, store.sshKeys, store.trustedHostKeys);
    }

    /** Decode the store document; never throws, never returns null. */
    public static Decoded decode(String json) {
        if (json == null || json.trim().isEmpty()) return Decoded.empty();
        JSONObject root;
        try {
            root = new JSONObject(json);
        } catch (Throwable malformed) {
            // org.json throws JSONException for malformed text, but a
            // StackOverflowError on hostile deep nesting must not escape either.
            return Decoded.empty();
        }
        List<HostConfig> hosts = decodeHosts(root.optJSONArray("hosts"));
        List<SshKeyRecord> sshKeys = decodeSshKeys(root.optJSONArray("sshKeys"));
        Map<String, HostKeyRecord> trusted = decodeTrusted(root.optJSONObject("trustedHostKeys"));
        return new Decoded(hosts, sshKeys, trusted);
    }

    // ---------------------------------------------------------------- encode

    private static JSONArray encodeHosts(List<HostConfig> hosts) throws JSONException {
        JSONArray array = new JSONArray();
        if (hosts == null) return array;
        for (HostConfig host : hosts) {
            if (host == null || isBlank(host.alias)) continue;
            JSONObject item = new JSONObject();
            item.put("alias", safe(host.alias));
            item.put("hostName", safe(host.hostName));
            item.put("user", safe(host.user));
            item.put("port", host.port);
            item.put("authType", host.authType == null
                    ? HostConfig.AuthType.PASSWORD.name() : host.authType.name());
            item.put("password", safe(host.password));
            item.put("keyId", safe(host.keyId));
            // Legacy inline key fields are still serialised (hosts that failed to
            // migrate keep working); the migration clears them once a key owns them.
            item.put("privateKey", safe(host.privateKey));
            item.put("keyPassphrase", safe(host.keyPassphrase));
            item.put("proxyJump", safe(host.proxyJump));
            item.put("accessMode", host.accessMode == null
                    ? HostConfig.AccessMode.TUNNEL.name() : host.accessMode.name());
            item.put("remotePort", host.remotePort);
            item.put("token", safe(host.token));
            item.put("directUrl", safe(host.directUrl));
            JSONArray identityFiles = new JSONArray();
            if (host.identityFiles != null) {
                for (String file : host.identityFiles) {
                    if (file != null && !file.isEmpty()) identityFiles.put(file);
                }
            }
            item.put("identityFiles", identityFiles);
            item.put("importedFrom", safe(host.importedFrom));
            array.put(item);
        }
        return array;
    }

    private static JSONArray encodeSshKeys(List<SshKeyRecord> keys) throws JSONException {
        JSONArray array = new JSONArray();
        if (keys == null) return array;
        for (SshKeyRecord key : keys) {
            if (key == null || isBlank(key.id)) continue;
            JSONObject item = new JSONObject();
            item.put("id", safe(key.id));
            item.put("label", safe(key.label));
            item.put("keyType", safe(key.keyType));
            item.put("publicKey", safe(key.publicKey));
            item.put("privateKey", safe(key.privateKey));
            item.put("passphrase", safe(key.passphrase));
            item.put("fingerprint", safe(key.fingerprint));
            item.put("createdAt", safe(key.createdAt));
            item.put("source", safe(key.source));
            array.put(item);
        }
        return array;
    }

    private static JSONObject encodeTrusted(Map<String, HostKeyRecord> trusted) throws JSONException {
        JSONObject object = new JSONObject();
        if (trusted == null) return object;
        for (Map.Entry<String, HostKeyRecord> entry : trusted.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isEmpty() || entry.getValue() == null) continue;
            HostKeyRecord record = entry.getValue();
            JSONObject item = new JSONObject();
            item.put("keyType", safe(record.keyType));
            item.put("keyBase64", safe(record.keyBase64));
            item.put("fingerprint", safe(record.fingerprint));
            item.put("addedAt", safe(record.addedAt));
            object.put(entry.getKey(), item);
        }
        return object;
    }

    // ---------------------------------------------------------------- decode

    private static List<HostConfig> decodeHosts(JSONArray array) {
        List<HostConfig> hosts = new ArrayList<>();
        if (array == null) return hosts;
        for (int index = 0; index < array.length(); index += 1) {
            JSONObject item;
            try {
                item = array.optJSONObject(index);
            } catch (RuntimeException malformed) {
                continue;
            }
            if (item == null) continue;
            String alias = string(item, "alias");
            if (alias.isEmpty()) continue;
            HostConfig host = new HostConfig();
            host.alias = alias;
            host.hostName = string(item, "hostName");
            host.user = string(item, "user");
            int port = item.optInt("port", 22);
            host.port = port >= 1 && port <= 65535 ? port : 22;
            host.authType = parseAuthType(item.optString("authType", ""));
            host.password = string(item, "password");
            host.keyId = string(item, "keyId");
            host.privateKey = string(item, "privateKey");
            host.keyPassphrase = string(item, "keyPassphrase");
            host.proxyJump = string(item, "proxyJump");
            host.accessMode = parseAccessMode(item.optString("accessMode", ""));
            int remotePort = item.optInt("remotePort", 0);
            host.remotePort = remotePort >= 1 && remotePort <= 65535 ? remotePort : 0;
            host.token = string(item, "token");
            host.directUrl = string(item, "directUrl");
            host.identityFiles = decodeIdentityFiles(item.optJSONArray("identityFiles"));
            host.importedFrom = string(item, "importedFrom");
            hosts.add(host);
        }
        return hosts;
    }

    private static List<SshKeyRecord> decodeSshKeys(JSONArray array) {
        List<SshKeyRecord> keys = new ArrayList<>();
        if (array == null) return keys;
        for (int index = 0; index < array.length(); index += 1) {
            JSONObject item;
            try {
                item = array.optJSONObject(index);
            } catch (RuntimeException malformed) {
                continue;
            }
            if (item == null) continue;
            String id = string(item, "id");
            if (id.isEmpty()) continue;
            SshKeyRecord key = new SshKeyRecord();
            key.id = id;
            key.label = string(item, "label");
            key.keyType = string(item, "keyType");
            key.publicKey = string(item, "publicKey");
            key.privateKey = string(item, "privateKey");
            key.passphrase = string(item, "passphrase");
            key.fingerprint = string(item, "fingerprint");
            key.createdAt = string(item, "createdAt");
            key.source = string(item, "source");
            keys.add(key);
        }
        return keys;
    }

    private static List<String> decodeIdentityFiles(JSONArray array) {
        List<String> files = new ArrayList<>();
        if (array == null) return files;
        for (int index = 0; index < array.length(); index += 1) {
            Object value;
            try {
                value = array.opt(index);
            } catch (RuntimeException malformed) {
                continue;
            }
            if (value instanceof String && !((String) value).isEmpty()) {
                files.add((String) value);
            }
        }
        return files;
    }

    private static Map<String, HostKeyRecord> decodeTrusted(JSONObject object) {
        Map<String, HostKeyRecord> trusted = new LinkedHashMap<>();
        if (object == null) return trusted;
        JSONArray names;
        try {
            names = object.names();
        } catch (RuntimeException malformed) {
            return trusted;
        }
        if (names == null) return trusted;
        for (int index = 0; index < names.length(); index += 1) {
            Object rawKey;
            try {
                rawKey = names.opt(index);
            } catch (RuntimeException malformed) {
                continue;
            }
            if (!(rawKey instanceof String) || ((String) rawKey).isEmpty()) continue;
            JSONObject item;
            try {
                item = object.optJSONObject((String) rawKey);
            } catch (RuntimeException malformed) {
                continue;
            }
            if (item == null) continue;
            trusted.put((String) rawKey, new HostKeyRecord(
                    string(item, "keyType"),
                    string(item, "keyBase64"),
                    string(item, "fingerprint"),
                    string(item, "addedAt")));
        }
        return trusted;
    }

    private static HostConfig.AuthType parseAuthType(String value) {
        for (HostConfig.AuthType type : HostConfig.AuthType.values()) {
            if (type.name().equalsIgnoreCase(value)) return type;
        }
        return HostConfig.AuthType.PASSWORD;
    }

    private static HostConfig.AccessMode parseAccessMode(String value) {
        for (HostConfig.AccessMode mode : HostConfig.AccessMode.values()) {
            if (mode.name().equalsIgnoreCase(value)) return mode;
        }
        return HostConfig.AccessMode.TUNNEL;
    }

    private static String string(JSONObject object, String key) {
        if (!object.has(key) || object.isNull(key)) return "";
        String value = object.optString(key, "");
        return value == null ? "" : value;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}

package dev.dsh.remotehosts.core;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Process-wide, encrypted persistence for hosts and trusted host keys.
 *
 * File layout (see docs/app-spec.md §4): {@code filesDir/hosts.enc}, an
 * AES-256-GCM envelope ({@link SecretBox}) whose key lives in the Android
 * Keystore and can never be exported. The activity and the foreground service
 * run in the same process, so a singleton with an in-memory cache is sufficient;
 * there is no cross-process access.
 *
 * Guarantees:
 *  - first run: the file does not exist, the store is empty and nothing is
 *    written until the first mutation;
 *  - a corrupt file, a truncated file or an invalidated Keystore key yields an
 *    EMPTY store instead of a crash (the next save rewrites it);
 *  - writes are atomic ({@link AtomicFile}: temp file + rename + fsync);
 *  - a failure to persist raises a readable {@link IOException} that contains no
 *    secret data;
 *  - secrets are never logged from this class.
 */
public final class HostStore {

    private static final String TAG = "HostStore";
    private static final String FILE_NAME = "hosts.enc";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static HostStore instance;

    private final Context context;
    private final Map<String, HostConfig> hosts = new LinkedHashMap<>();
    private final Map<String, SshKeyRecord> sshKeys = new LinkedHashMap<>();
    private final Map<String, HostKeyRecord> trustedHostKeys = new LinkedHashMap<>();
    private SecretBox secretBox;
    private boolean loaded;
    /** Set when the existing ciphertext could not be decrypted (corrupt or key invalidated). */
    private boolean keyNeedsReset;

    private HostStore(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Get (and lazily create) the process-wide store. */
    public static synchronized HostStore get(Context context) {
        if (instance == null) {
            instance = new HostStore(context);
        }
        return instance;
    }

    /** Load from disk once; safe to call from any thread. */
    public synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        File file = storeFileObject();
        if (!file.isFile()) return;
        byte[] envelope;
        try {
            envelope = readAll(file);
        } catch (IOException readError) {
            Log.w(TAG, "主机存储文件读取失败，按空存储启动（" + readError.getClass().getSimpleName() + "）");
            return;
        }
        byte[] plaintext;
        try {
            plaintext = secretBox().decrypt(envelope);
        } catch (Exception decryptError) {
            // A corrupt file or a Keystore key that no longer exists (e.g. after a
            // device reset) must not crash the app on first run. The old content is
            // unrecoverable; a later save regenerates the key and overwrites.
            keyNeedsReset = true;
            Log.w(TAG, "主机存储无法解密，按空存储启动（" + decryptError.getClass().getSimpleName() + "）");
            return;
        }
        HostsCodec.Decoded decoded = HostsCodec.decode(new String(plaintext, UTF8));
        hosts.clear();
        sshKeys.clear();
        trustedHostKeys.clear();
        for (HostConfig host : decoded.hosts) {
            if (host == null || host.alias == null || host.alias.trim().isEmpty()) continue;
            hosts.put(host.alias, host);
        }
        for (SshKeyRecord key : decoded.sshKeys) {
            if (key == null || key.id == null || key.id.trim().isEmpty()) continue;
            sshKeys.put(key.id, key);
        }
        trustedHostKeys.putAll(decoded.trustedHostKeys);

        // v0.1.0 -> v0.2.0 data migration: inline per-host private keys become
        // app-managed key records. A failed migration must never make the store
        // unreadable, so every failure path keeps the inline key and only logs.
        boolean migrated = false;
        try {
            List<HostConfig> hostList = new ArrayList<>(hosts.values());
            List<SshKeyRecord> keyList = new ArrayList<>(sshKeys.values());
            migrated = HostsMigration.migrate(hostList, keyList);
            if (migrated) {
                sshKeys.clear();
                for (SshKeyRecord key : keyList) {
                    if (key == null || key.id == null || key.id.trim().isEmpty()) continue;
                    sshKeys.put(key.id, key);
                }
            }
        } catch (RuntimeException migrationError) {
            Log.w(TAG, "主机密钥迁移失败，保留旧内联私钥（" + migrationError.getClass().getSimpleName() + "）");
        }
        if (migrated) {
            try {
                // Persist immediately so the plaintext inline keys do not linger in
                // the old encrypted document; on failure the next save retries and
                // the in-memory state stays consistent either way.
                save();
            } catch (IOException saveError) {
                Log.w(TAG, "迁移结果持久化失败，将在下次保存时重试（"
                        + saveError.getClass().getSimpleName() + "）");
            }
        }
    }

    /** Hosts in insertion order. Returned snapshots are copies. */
    public synchronized List<HostConfig> list() {
        ensureLoaded();
        List<HostConfig> out = new ArrayList<>(hosts.size());
        for (HostConfig host : hosts.values()) out.add(host.copy());
        return out;
    }

    /** Find one host by alias (snapshot), or null. */
    public synchronized HostConfig find(String alias) {
        ensureLoaded();
        HostConfig host = alias == null ? null : hosts.get(alias);
        return host == null ? null : host.copy();
    }

    /** Insert or replace one host (order preserved), then persist. */
    public synchronized void upsert(HostConfig host) throws IOException {
        ensureLoaded();
        if (host == null || host.alias == null || host.alias.trim().isEmpty()) {
            throw new IOException("主机别名为空，无法保存");
        }
        HostConfig stored = host.copy();
        stored.alias = host.alias.trim();
        // LinkedHashMap.put keeps the original position for an existing key, which
        // is exactly the "replace in place, keep insertion order" contract.
        HostConfig previous = hosts.put(stored.alias, stored);
        try {
            save();
        } catch (IOException saveError) {
            // Keep memory and disk consistent: roll back the in-memory change.
            if (previous == null) hosts.remove(stored.alias);
            else hosts.put(stored.alias, previous);
            throw saveError;
        }
    }

    /** Remove a host; returns whether it existed. Also drops its trusted host key. */
    public synchronized boolean remove(String alias) throws IOException {
        ensureLoaded();
        if (alias == null) return false;
        HostConfig removed = hosts.remove(alias);
        if (removed == null) return false;
        String hostPortKey = hostPortKeyFor(removed);
        if (hostPortKey != null && !hostPortKey.isEmpty()) {
            trustedHostKeys.remove(hostPortKey);
        }
        try {
            save();
        } catch (IOException saveError) {
            hosts.put(removed.alias, removed);
            throw saveError;
        }
        return true;
    }

    /** Raw key blob for {@code host:port}, or null when this host is not trusted yet. */
    public synchronized String trustedHostKey(String hostPortKey) {
        ensureLoaded();
        HostKeyRecord record = trustedHostKeys.get(hostPortKey);
        return record == null ? null : record.keyBase64;
    }

    /** Full record for display (fingerprint), or null. */
    public synchronized HostKeyRecord trustedHostKeyRecord(String hostPortKey) {
        ensureLoaded();
        HostKeyRecord record = trustedHostKeys.get(hostPortKey);
        return record == null ? null : record.copy();
    }

    /** All trusted records in insertion order; snapshot, safe to iterate from the UI. */
    public synchronized Map<String, HostKeyRecord> trustedHostKeyEntries() {
        ensureLoaded();
        Map<String, HostKeyRecord> copy = new LinkedHashMap<>();
        for (Map.Entry<String, HostKeyRecord> entry : trustedHostKeys.entrySet()) {
            if (entry.getValue() != null) copy.put(entry.getKey(), entry.getValue().copy());
        }
        return copy;
    }

    /** Trust one host key (TOFU decision). */
    public synchronized void trustHostKey(
            String hostPortKey, String keyType, String keyBase64, String fingerprint) throws IOException {
        ensureLoaded();
        if (hostPortKey == null || hostPortKey.trim().isEmpty()) {
            throw new IOException("主机密钥标识为空，无法保存信任");
        }
        if (keyBase64 == null || keyBase64.isEmpty()) {
            throw new IOException("主机公钥为空，无法保存信任");
        }
        String key = hostPortKey.trim();
        HostKeyRecord record = new HostKeyRecord(
                keyType == null ? "" : keyType,
                keyBase64,
                fingerprint == null ? "" : fingerprint,
                Instant.now().toString());
        HostKeyRecord previous = trustedHostKeys.put(key, record);
        try {
            save();
        } catch (IOException saveError) {
            if (previous == null) trustedHostKeys.remove(key);
            else trustedHostKeys.put(key, previous);
            throw saveError;
        }
    }

    /** Forget one trusted host key; returns whether it existed. */
    public synchronized boolean clearTrustedHostKey(String hostPortKey) throws IOException {
        ensureLoaded();
        if (hostPortKey == null) return false;
        HostKeyRecord removed = trustedHostKeys.remove(hostPortKey);
        if (removed == null) return false;
        try {
            save();
        } catch (IOException saveError) {
            trustedHostKeys.put(hostPortKey, removed);
            throw saveError;
        }
        return true;
    }

    /** Forget the trusted host key belonging to one host; returns whether it existed. */
    public synchronized boolean clearTrustedHostKeyFor(HostConfig host) throws IOException {
        String key = hostPortKeyFor(host);
        return key != null && !key.isEmpty() && clearTrustedHostKey(key);
    }

    /** Forget every trusted host key; returns how many were removed. */
    public synchronized int clearTrustedHostKeys() throws IOException {
        ensureLoaded();
        if (trustedHostKeys.isEmpty()) return 0;
        Map<String, HostKeyRecord> previous = new LinkedHashMap<>(trustedHostKeys);
        int count = previous.size();
        trustedHostKeys.clear();
        try {
            save();
        } catch (IOException saveError) {
            trustedHostKeys.putAll(previous);
            throw saveError;
        }
        return count;
    }

    /** Persist the current in-memory state. */
    public synchronized void save() throws IOException {
        ensureLoaded();
        List<HostConfig> hostList = new ArrayList<>(hosts.values());
        List<SshKeyRecord> keyList = new ArrayList<>(sshKeys.values());
        // v0.2.0: the key list is part of the same encrypted document so hosts and
        // their referenced keys can never disagree across a crash.
        String json = HostsCodec.encode(hostList, keyList, trustedHostKeys);
        byte[] plaintext = json.getBytes(UTF8);
        if (keyNeedsReset) {
            try {
                secretBox().resetKey();
            } catch (Exception resetError) {
                throw new IOException("无法重置本机加密密钥：" + resetError.getClass().getSimpleName());
            }
            keyNeedsReset = false;
        }
        byte[] envelope;
        try {
            envelope = secretBox().encrypt(plaintext);
        } catch (Exception encryptError) {
            throw new IOException("无法加密主机存储：" + encryptError.getClass().getSimpleName());
        }
        writeAtomically(envelope);
    }

    /** Force a reload from disk (e.g. after a restore); mainly for tests. */
    public synchronized void reload() {
        hosts.clear();
        sshKeys.clear();
        trustedHostKeys.clear();
        loaded = false;
        keyNeedsReset = false;
        ensureLoaded();
    }

    // ------------------------------------------------------ SSH key store (v0.2.0)
    // See docs/app-spec.md §8. The key material lives in the same encrypted
    // envelope as the hosts; every mutation persists atomically.

    /** All managed SSH key pairs, insertion order, deep copies. */
    public synchronized List<SshKeyRecord> listSshKeys() {
        ensureLoaded();
        List<SshKeyRecord> out = new ArrayList<>(sshKeys.size());
        for (SshKeyRecord key : sshKeys.values()) out.add(key.copy());
        return out;
    }

    /** One key pair by id (snapshot), or null. */
    public synchronized SshKeyRecord findSshKey(String id) {
        ensureLoaded();
        SshKeyRecord key = id == null ? null : sshKeys.get(id);
        return key == null ? null : key.copy();
    }

    /** Insert or replace one key pair, then persist atomically. */
    public synchronized void upsertSshKey(SshKeyRecord key) throws IOException {
        ensureLoaded();
        if (key == null || key.id == null || key.id.trim().isEmpty()) {
            throw new IOException("密钥 id 为空，无法保存");
        }
        SshKeyRecord stored = key.copy();
        stored.id = stored.id.trim();
        SshKeyRecord previous = sshKeys.put(stored.id, stored);
        try {
            save();
        } catch (IOException saveError) {
            if (previous == null) sshKeys.remove(stored.id);
            else sshKeys.put(stored.id, previous);
            throw saveError;
        }
    }

    /** Remove one key pair; returns whether it existed. */
    public synchronized boolean removeSshKey(String id) throws IOException {
        ensureLoaded();
        if (id == null) return false;
        SshKeyRecord removed = sshKeys.remove(id);
        if (removed == null) return false;
        try {
            save();
        } catch (IOException saveError) {
            sshKeys.put(id, removed);
            throw saveError;
        }
        return true;
    }

    /** How many hosts reference this key id; used by the delete confirmation. */
    public synchronized int countHostsUsingKey(String id) {
        ensureLoaded();
        int count = 0;
        for (HostConfig host : hosts.values()) {
            if (host.keyId != null && host.keyId.equals(id)) count += 1;
        }
        return count;
    }

    /** File that holds the encrypted store; exposed for diagnostics only. */
    public String storeFile() {
        return storeFileObject().getAbsolutePath();
    }

    /**
     * Canonical trusted-host-key id for a host. MUST stay identical to the value
     * {@link SshTunnelEngine} uses (it sets it as JSch's host-key alias), so the
     * UI can look up / clear a host's TOFU trust without guessing the format.
     *
     * @return {@code resolvedHostName:port}, or null for a null host
     */
    public static String hostPortKeyFor(HostConfig host) {
        if (host == null) return null;
        String hostName = host.resolvedHostName();
        if (hostName == null || hostName.trim().isEmpty()) return null;
        return SshTunnelEngine.hostPortKey(hostName, host.port);
    }

    // ------------------------------------------------------------- file access

    private File storeFileObject() {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    private SecretBox secretBox() {
        if (secretBox == null) secretBox = new SecretBox();
        return secretBox;
    }

    private static byte[] readAll(File file) throws IOException {
        long length = file.length();
        if (length <= 0 || length > 8L * 1024 * 1024) {
            throw new IOException("主机存储文件大小异常");
        }
        byte[] data = new byte[(int) length];
        try (FileInputStream in = new FileInputStream(file)) {
            int offset = 0;
            while (offset < data.length) {
                int read = in.read(data, offset, data.length - offset);
                if (read < 0) throw new IOException("主机存储文件被截断");
                offset += read;
            }
            return data;
        } catch (FileNotFoundException notFound) {
            // Raced with another process/cleanup; treat as empty.
            throw notFound;
        }
    }

    /** Atomic write via {@link AtomicFile} (temp file + rename), with fsync. */
    private void writeAtomically(byte[] data) throws IOException {
        File file = storeFileObject();
        AtomicFile atomicFile = new AtomicFile(file);
        FileOutputStream out = null;
        try {
            out = atomicFile.startWrite();
            out.write(data);
            out.flush();
            out.getFD().sync();
            atomicFile.finishWrite(out);
        } catch (IOException writeError) {
            if (out != null) atomicFile.failWrite(out);
            throw new IOException("写入主机存储失败：" + writeError.getClass().getSimpleName());
        }
    }
}

package dev.dsh.remotehosts.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * v0.1.0 → v0.2.0 data migration: move inline per-host private keys into the
 * app-managed SSH key store (docs/app-spec.md §8.3).
 *
 * Pure Java (no Android imports) so it is unit-tested on a desktop JVM. The
 * function mutates its arguments in place and is idempotent:
 *
 *  - a host with a non-empty {@code privateKey} and an empty {@code keyId} gets a
 *    key record (or reuses one with the same private-key text) and then references
 *    it by {@code keyId}; {@code privateKey}/{@code keyPassphrase} are cleared;
 *  - hosts without an inline key, and hosts that already reference a key, are
 *    left untouched;
 *  - when a key cannot be parsed (wrong passphrase / unsupported format) the host
 *    is left completely unchanged — the legacy inline copy is never lost;
 *  - only the key reference fields change; every other host field is preserved.
 *
 * @return true when anything changed (a key was added or a host was rewritten)
 */
public final class HostsMigration {

    private HostsMigration() {
    }

    /**
     * @param hosts host list to migrate in place (order preserved, never null)
     * @param keys  key list to add migrated records to (order preserved)
     * @return whether anything changed; the caller persists only in that case
     */
    public static boolean migrate(List<HostConfig> hosts, List<SshKeyRecord> keys) {
        if (hosts == null || hosts.isEmpty() || keys == null) return false;

        // Dedupe by private-key text: a key already in the store (e.g. from a
        // partially finished migration) is reused instead of creating a duplicate.
        Map<String, String> keyIdByPrivateKey = new HashMap<>();
        for (SshKeyRecord key : keys) {
            if (key == null) continue;
            if (isBlank(key.id) || isBlank(key.privateKey)) continue;
            String normalized = normalizePrivateKey(key.privateKey);
            if (!keyIdByPrivateKey.containsKey(normalized)) {
                keyIdByPrivateKey.put(normalized, key.id);
            }
        }

        boolean changed = false;
        for (HostConfig host : hosts) {
            if (host == null) continue;
            if (!isBlank(host.keyId)) continue; // already migrated / manually chosen
            if (isBlank(host.privateKey)) continue; // nothing to migrate

            String normalized = normalizePrivateKey(host.privateKey);
            String existingId = keyIdByPrivateKey.get(normalized);
            if (existingId != null) {
                host.keyId = existingId;
                host.privateKey = "";
                host.keyPassphrase = "";
                changed = true;
                continue;
            }

            SshKeyRecord derived;
            try {
                // Reuse the import path so the record gets keyType/publicKey and a
                // verifiable fingerprint; needs no network and no Android APIs.
                derived = SshKeyManager.importKeyPair(host.alias, normalized, host.keyPassphrase, "");
            } catch (AppError cannotMigrate) {
                // Leave the host untouched (inline key stays usable); try the next.
                continue;
            }
            derived.id = SshKeyManager.newId();
            derived.label = isBlank(host.alias) ? "migrated-key" : host.alias;
            derived.createdAt = Instant.now().toString();
            derived.source = "migrated";

            keys.add(derived);
            keyIdByPrivateKey.put(normalized, derived.id);
            host.keyId = derived.id;
            host.privateKey = "";
            host.keyPassphrase = "";
            changed = true;
        }
        return changed;
    }

    /** Copy of the list, handy for tests/callers that must not mutate the input. */
    public static List<SshKeyRecord> migratedCopy(List<HostConfig> hosts, List<SshKeyRecord> keys) {
        List<HostConfig> hostCopies = new ArrayList<>();
        if (hosts != null) {
            for (HostConfig host : hosts) hostCopies.add(host == null ? null : host.copy());
        }
        List<SshKeyRecord> keyCopies = new ArrayList<>();
        if (keys != null) {
            for (SshKeyRecord key : keys) keyCopies.add(key == null ? null : key.copy());
        }
        migrate(hostCopies, keyCopies);
        return keyCopies;
    }

    private static String normalizePrivateKey(String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        return normalized.endsWith("\n") ? normalized : normalized + "\n";
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}

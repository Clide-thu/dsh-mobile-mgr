import com.jcraft.jsch.JSch;
import com.jcraft.jsch.KeyPair;

import dev.dsh.remotehosts.core.AppError;
import dev.dsh.remotehosts.core.HostConfig;
import dev.dsh.remotehosts.core.HostsMigration;
import dev.dsh.remotehosts.core.SshKeyManager;
import dev.dsh.remotehosts.core.SshKeyRecord;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Unit tests for {@code core/HostsMigration} (docs/app-spec.md ?8.3).
 *
 * v0.1.0 stored a private key inline on every host. v0.2.0 moves that material
 * into the app-wide SSH key store: on load, hosts with a non-empty
 * {@code privateKey} and an empty {@code keyId} are converted into
 * {@link SshKeyRecord} entries (deduplicated by private-key text), the host gets
 * the new {@code keyId}, and the inline secret is cleared. The migration must be
 * idempotent, must not touch any other host field, and must never lose host data
 * when a key fails to parse (leave that host untouched and continue).
 *
 * The SSH fixtures are generated with JSch directly so this test does not depend
 * on the correctness of SshKeyManager.generate; fingerprints are verified with
 * an independent SHA-256 computation.
 *
 * No JUnit: a plain main() keeps the offline toolchain dependency-free.
 */
public final class HostsMigrationTest {

    private static final String PASSPHRASE = "migration-passphrase-123";
    private static final String KEY_ID_PATTERN = "^key-[0-9a-fA-F]{12}$";

    private static int checks;
    private static int failures;

    private static String plainKeyText;
    private static String encryptedKeyText;

    private HostsMigrationTest() {
    }

    public static void main(String[] args) {
        noLegacyDataIsANoOp();
        migratesSingleHost();
        deduplicatesSharedPrivateKeys();
        isIdempotent();
        migratesPassphraseProtectedKey();
        brokenPrivateKeyIsSkippedNotDropped();
        mixedListOnlyMigratesLegacyHosts();
        reusesExistingKeyEntry();
        nullPrivateKeyIsIgnored();

        System.out.println();
        System.out.println("HostsMigrationTest: " + (checks - failures) + "/" + checks + " checks passed");
        if (failures > 0) {
            System.out.println("FAILED (" + failures + " check(s))");
            System.exit(1);
        }
        System.out.println("PASSED");
    }

    // -----------------------------------------------------------------------
    // 1. degenerate input
    // -----------------------------------------------------------------------

    private static void noLegacyDataIsANoOp() {
        List<HostConfig> hosts = new ArrayList<>();
        HostConfig modern = legacyHost("modern", "", "");
        modern.keyId = "key-aaaaaaaaaaaa";
        hosts.add(modern);
        HostConfig passwordHost = passwordHost("pw-host");
        hosts.add(passwordHost);
        List<SshKeyRecord> keys = new ArrayList<>();

        boolean changed = HostsMigration.migrate(hosts, keys);
        check("no-legacy: migration reports no change", !changed, "changed=true");
        check("no-legacy: no key entries created", keys.isEmpty(), "keys=" + keys.size());
        check("no-legacy: hosts untouched",
                modern.keyId.equals(hosts.get(0).keyId)
                        && (hosts.get(0).privateKey == null || hosts.get(0).privateKey.isEmpty()),
                "host mutated");
        check("no-legacy: password host untouched",
                isPasswordHost(hosts.get(1)),
                "password host mutated");

        check("no-legacy: empty lists are a no-op",
                !HostsMigration.migrate(new ArrayList<HostConfig>(), new ArrayList<SshKeyRecord>()),
                "changed=true for empty lists");
    }

    // -----------------------------------------------------------------------
    // 2. single migration
    // -----------------------------------------------------------------------

    private static void migratesSingleHost() {
        HostConfig host = legacyHost("alpha", plainPrivateKey(), "");
        HostConfig before = host.copy();
        List<HostConfig> hosts = new ArrayList<>();
        hosts.add(host);
        List<SshKeyRecord> keys = new ArrayList<>();

        boolean changed = HostsMigration.migrate(hosts, keys);
        check("single: migration reports a change", changed, "changed=false");
        check("single: exactly one key created", keys.size() == 1, "keys=" + keys.size());
        if (keys.size() != 1) return;

        SshKeyRecord key = keys.get(0);
        check("single: key id has key-<12 hex> format", key.id != null && key.id.matches(KEY_ID_PATTERN),
                "id=" + key.id);
        check("single: label is the host alias", "alpha".equals(key.label), "label=" + key.label);
        check("single: keyType looks like an SSH type",
                key.keyType != null && key.keyType.startsWith("ssh-"), "keyType=" + key.keyType);
        check("single: public key line is set",
                key.publicKey != null && key.publicKey.split("\\s+").length >= 2, "publicKey=" + key.publicKey);
        check("single: source is 'migrated'", "migrated".equals(key.source), "source=" + key.source);
        check("single: createdAt is an ISO instant", isIsoInstant(key.createdAt), "createdAt=" + key.createdAt);
        checkFingerprint("single", key);
        checkKeyCanBeReimported("single", key);

        check("single: host keyId points at the new key", key.id.equals(host.keyId), "keyId=" + host.keyId);
        check("single: host inline private key cleared",
                host.privateKey == null || host.privateKey.isEmpty(), "privateKey still set");
        check("single: host legacy passphrase cleared",
                host.keyPassphrase == null || host.keyPassphrase.isEmpty(), "keyPassphrase still set");
        checkHostFieldsUnchanged("single", before, host);
        check("single: migration mutates the given host object",
                key.id.equals(hosts.get(0).keyId), "list instance not updated");
    }

    // -----------------------------------------------------------------------
    // 3. dedupe + ordering
    // -----------------------------------------------------------------------

    private static void deduplicatesSharedPrivateKeys() {
        String shared = plainPrivateKey();
        List<HostConfig> hosts = new ArrayList<>();
        HostConfig alpha = legacyHost("alpha", shared, "");
        HostConfig beta = legacyHost("beta", shared, "");
        HostConfig gamma = legacyHost("gamma", encryptedPrivateKey(), PASSPHRASE);
        hosts.add(alpha);
        hosts.add(beta);
        hosts.add(gamma);
        List<SshKeyRecord> keys = new ArrayList<>();

        boolean changed = HostsMigration.migrate(hosts, keys);
        check("dedupe: migration reports a change", changed, "changed=false");
        check("dedupe: two distinct private keys yield two entries", keys.size() == 2,
                "keys=" + keys.size());
        if (keys.size() != 2) return;
        check("dedupe: shared private key is stored once", keys.get(0).id.equals(alpha.keyId),
                "alpha.keyId=" + alpha.keyId);
        check("dedupe: both hosts share the same keyId", alpha.keyId != null && alpha.keyId.equals(beta.keyId),
                "alpha=" + alpha.keyId + " beta=" + beta.keyId);
        check("dedupe: label comes from the first host using the key", "alpha".equals(keys.get(0).label),
                "label=" + keys.get(0).label);
        check("dedupe: second distinct key is appended in host order", "gamma".equals(keys.get(1).label),
                "label=" + keys.get(1).label);
        check("dedupe: passphrase carried over to the key entry", PASSPHRASE.equals(keys.get(1).passphrase),
                "passphrase set=" + !PASSPHRASE.equals(keys.get(1).passphrase));
        check("dedupe: all inline private keys cleared",
                isEmpty(alpha.privateKey) && isEmpty(beta.privateKey) && isEmpty(gamma.privateKey),
                "inline private key left behind");
    }

    // -----------------------------------------------------------------------
    // 4. idempotency
    // -----------------------------------------------------------------------

    private static void isIdempotent() {
        List<HostConfig> hosts = new ArrayList<>();
        HostConfig host = legacyHost("alpha", plainPrivateKey(), "");
        hosts.add(host);
        List<SshKeyRecord> keys = new ArrayList<>();
        check("idempotent: first run migrates", HostsMigration.migrate(hosts, keys), "first run did nothing");
        String keyId = host.keyId;
        String keyJson = keys.size() == 1 ? keys.get(0).summary() : "";
        int keyCount = keys.size();

        boolean second = HostsMigration.migrate(hosts, keys);
        check("idempotent: second run reports no change", !second, "second run changed data");
        check("idempotent: key list not duplicated", keys.size() == keyCount, "keys=" + keys.size());
        check("idempotent: host keyId unchanged", keyId != null && keyId.equals(host.keyId),
                "keyId=" + host.keyId);
        check("idempotent: key material unchanged",
                keys.size() == 1 && keyJson.equals(keys.get(0).summary()), "key entry changed");
        check("idempotent: inline private key stays empty", isEmpty(host.privateKey), "privateKey restored");
    }

    // -----------------------------------------------------------------------
    // 5. passphrase-protected legacy key
    // -----------------------------------------------------------------------

    private static void migratesPassphraseProtectedKey() {
        HostConfig host = legacyHost("passphrase-host", encryptedPrivateKey(), PASSPHRASE);
        List<HostConfig> hosts = new ArrayList<>();
        hosts.add(host);
        List<SshKeyRecord> keys = new ArrayList<>();

        boolean changed = HostsMigration.migrate(hosts, keys);
        check("passphrase: migration reports a change", changed, "changed=false");
        check("passphrase: one key created", keys.size() == 1, "keys=" + keys.size());
        if (keys.size() != 1) return;
        check("passphrase: passphrase preserved in the key entry",
                PASSPHRASE.equals(keys.get(0).passphrase), "passphrase mismatch");
        check("passphrase: host keyId set", keys.get(0).id.equals(host.keyId), "keyId=" + host.keyId);
        checkKeyCanBeReimported("passphrase", keys.get(0));
        check("passphrase: inline secret cleared",
                isEmpty(host.privateKey) && isEmpty(host.keyPassphrase), "inline secret left behind");
    }

    // -----------------------------------------------------------------------
    // 6. broken key must not lose host data
    // -----------------------------------------------------------------------

    private static void brokenPrivateKeyIsSkippedNotDropped() {
        String broken = "-----BEGIN OPENSSH PRIVATE KEY-----\n"
                + "this-is-not-a-real-key-body\n"
                + "-----END OPENSSH PRIVATE KEY-----";
        HostConfig brokenHost = legacyHost("broken-host", broken, "");
        HostConfig before = brokenHost.copy();
        List<HostConfig> onlyBroken = new ArrayList<>();
        onlyBroken.add(brokenHost);
        List<SshKeyRecord> noKeys = new ArrayList<>();
        boolean changed = HostsMigration.migrate(onlyBroken, noKeys);
        check("broken: unparsable key does not report a migration", !changed, "changed=true");
        check("broken: no key entry created", noKeys.isEmpty(), "keys=" + noKeys.size());
        check("broken: host keeps its inline private key", broken.equals(brokenHost.privateKey),
                "privateKey lost");
        check("broken: host keyId stays empty", isEmpty(brokenHost.keyId), "keyId=" + brokenHost.keyId);
        checkHostFieldsUnchanged("broken", before, brokenHost);

        // A broken host must not stop another host from migrating.
        HostConfig goodHost = legacyHost("good-host", plainPrivateKey(), "");
        List<HostConfig> mixed = new ArrayList<>();
        HostConfig brokenAgain = legacyHost("broken-again", broken, "");
        mixed.add(brokenAgain);
        mixed.add(goodHost);
        List<SshKeyRecord> keys = new ArrayList<>();
        boolean mixedChanged = HostsMigration.migrate(mixed, keys);
        check("broken: other legacy hosts still migrate", mixedChanged && keys.size() == 1,
                "changed=" + mixedChanged + " keys=" + keys.size());
        check("broken: good host migrated", keys.size() == 1 && keys.get(0).id.equals(goodHost.keyId),
                "good host keyId=" + goodHost.keyId);
        check("broken: broken neighbour left untouched",
                broken.equals(brokenAgain.privateKey) && isEmpty(brokenAgain.keyId),
                "broken host data changed");
    }

    // -----------------------------------------------------------------------
    // 7. mixed host list
    // -----------------------------------------------------------------------

    private static void mixedListOnlyMigratesLegacyHosts() {
        HostConfig legacy = legacyHost("legacy", plainPrivateKey(), "");
        HostConfig already = legacyHost("already-migrated", "", "");
        already.keyId = "key-0123456789ab";
        HostConfig password = passwordHost("password-only");
        List<HostConfig> hosts = new ArrayList<>();
        hosts.add(legacy);
        hosts.add(already);
        hosts.add(password);
        List<SshKeyRecord> keys = new ArrayList<>();

        boolean changed = HostsMigration.migrate(hosts, keys);
        check("mixed: reports a change", changed, "changed=false");
        check("mixed: only the legacy host created a key", keys.size() == 1, "keys=" + keys.size());
        if (keys.size() == 1) {
            check("mixed: legacy host points at the new key", keys.get(0).id.equals(legacy.keyId),
                    "legacy.keyId=" + legacy.keyId);
        }
        check("mixed: already-migrated host untouched", "key-0123456789ab".equals(already.keyId),
                "keyId=" + already.keyId);
        check("mixed: password host untouched", isPasswordHost(password), "password host mutated");
    }

    // -----------------------------------------------------------------------
    // 8. defensive: an existing key entry with the same private key is reused
    // -----------------------------------------------------------------------

    private static void reusesExistingKeyEntry() {
        String shared = plainPrivateKey();
        SshKeyRecord existing = new SshKeyRecord();
        existing.id = "key-999999999999";
        existing.label = "pre-existing";
        existing.keyType = "ssh-rsa";
        existing.privateKey = shared;
        existing.source = "migrated";
        List<SshKeyRecord> keys = new ArrayList<>();
        keys.add(existing);

        HostConfig host = legacyHost("alpha", shared, "");
        List<HostConfig> hosts = new ArrayList<>();
        hosts.add(host);

        boolean changed = HostsMigration.migrate(hosts, keys);
        check("existing-key: migration reports a change", changed, "changed=false");
        check("existing-key: no duplicate entry created", keys.size() == 1, "keys=" + keys.size());
        check("existing-key: host points at the existing entry",
                "key-999999999999".equals(host.keyId), "keyId=" + host.keyId);
        check("existing-key: inline private key cleared", isEmpty(host.privateKey), "privateKey left behind");
    }

    // -----------------------------------------------------------------------
    // 9. null private key
    // -----------------------------------------------------------------------

    private static void nullPrivateKeyIsIgnored() {
        HostConfig host = legacyHost("null-key", "", "");
        host.privateKey = null;
        List<HostConfig> hosts = new ArrayList<>();
        hosts.add(host);
        List<SshKeyRecord> keys = new ArrayList<>();
        try {
            boolean changed = HostsMigration.migrate(hosts, keys);
            check("null-key: no change, no crash", !changed && keys.isEmpty(),
                    "changed=" + changed + " keys=" + keys.size());
        } catch (Throwable error) {
            check("null-key: no change, no crash", false, "threw " + error);
        }
    }

    // -----------------------------------------------------------------------
    // fixtures
    // -----------------------------------------------------------------------

    private static String plainPrivateKey() {
        if (plainKeyText == null) plainKeyText = generatePrivateKey(null);
        return plainKeyText;
    }

    private static String encryptedPrivateKey() {
        if (encryptedKeyText == null) encryptedKeyText = generatePrivateKey(PASSPHRASE);
        return encryptedKeyText;
    }

    /** Generate an RSA private key with JSch; independent of SshKeyManager. */
    private static String generatePrivateKey(String passphrase) {
        KeyPair pair = null;
        try {
            pair = KeyPair.genKeyPair(new JSch(), KeyPair.RSA, 2048);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (passphrase == null) {
                pair.writePrivateKey(out);
            } else {
                pair.writePrivateKey(out, passphrase.getBytes("UTF-8"));
            }
            return new String(out.toByteArray(), "UTF-8");
        } catch (Exception error) {
            throw new IllegalStateException("could not generate a test key: " + error);
        } finally {
            if (pair != null) pair.dispose();
        }
    }

    private static HostConfig legacyHost(String alias, String privateKeyText, String passphrase) {
        HostConfig host = new HostConfig();
        host.alias = alias;
        host.hostName = alias + ".example.com";
        host.user = "alice";
        host.port = 2222;
        host.authType = HostConfig.AuthType.PRIVATE_KEY;
        host.password = "";
        host.privateKey = privateKeyText;
        host.keyPassphrase = passphrase;
        host.proxyJump = "jump.example.com";
        host.accessMode = HostConfig.AccessMode.TUNNEL;
        host.remotePort = 3080;
        host.token = "TESTTOKEN0000000000000000000000000000000000";
        host.directUrl = "";
        host.identityFiles = new ArrayList<>();
        host.identityFiles.add("~/.ssh/id_rsa");
        host.identityFiles.add("/home/alice/.ssh/id_ed25519");
        host.importedFrom = "ssh config";
        return host;
    }

    private static HostConfig passwordHost(String alias) {
        HostConfig host = new HostConfig();
        host.alias = alias;
        host.hostName = alias + ".example.com";
        host.user = "alice";
        host.port = 22;
        host.authType = HostConfig.AuthType.PASSWORD;
        host.password = "pw-secret";
        host.accessMode = HostConfig.AccessMode.TUNNEL;
        host.remotePort = 3080;
        host.token = "TESTTOKEN0000000000000000000000000000000000";
        return host;
    }

    private static boolean isPasswordHost(HostConfig host) {
        return host != null
                && HostConfig.AuthType.PASSWORD == host.authType
                && "pw-secret".equals(host.password)
                && (host.keyId == null || host.keyId.isEmpty())
                && (host.privateKey == null || host.privateKey.isEmpty());
    }

    // -----------------------------------------------------------------------
    // assertions
    // -----------------------------------------------------------------------

    private static void checkFingerprint(String label, SshKeyRecord key) {
        check(label + ": fingerprint has SHA256: prefix",
                key.fingerprint != null && key.fingerprint.startsWith("SHA256:"),
                "fingerprint=" + key.fingerprint);
        check(label + ": fingerprint has no base64 padding",
                key.fingerprint != null && key.fingerprint.indexOf('=') < 0,
                "fingerprint=" + key.fingerprint);
        try {
            String computed = computeFingerprint(key.publicKey);
            check(label + ": fingerprint matches independent SHA-256 computation",
                    computed.equals(key.fingerprint), "expected=" + computed);
        } catch (Exception error) {
            check(label + ": fingerprint matches independent SHA-256 computation", false,
                    "could not compute: " + error);
        }
    }

    /**
     * Independent implementation: {@code SHA256:} + standard base64
     * (SHA-256(public key blob)), no "=" padding — the ssh-keygen -lf alphabet.
     */
    private static String computeFingerprint(String publicKeyLine) throws Exception {
        String[] parts = publicKeyLine.trim().split("\\s+");
        if (parts.length < 2) throw new IllegalArgumentException("not a public key line");
        String base64 = parts[1];
        while (base64.length() % 4 != 0) base64 += "=";
        byte[] blob = Base64.getDecoder().decode(base64);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(blob);
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
    }

    private static void checkKeyCanBeReimported(String label, SshKeyRecord key) {
        try {
            SshKeyRecord reimported = SshKeyManager.importKeyPair(
                    key.label, key.privateKey, key.passphrase == null ? "" : key.passphrase, key.publicKey);
            check(label + ": migrated key can be re-imported with its passphrase",
                    reimported != null && key.fingerprint.equals(reimported.fingerprint),
                    "fingerprint mismatch after re-import");
        } catch (AppError error) {
            check(label + ": migrated key can be re-imported with its passphrase", false,
                    error.kind + " " + error.getMessage());
        }
    }

    private static void checkHostFieldsUnchanged(String label, HostConfig before, HostConfig after) {
        boolean same = eq(before.alias, after.alias)
                && eq(before.hostName, after.hostName)
                && eq(before.user, after.user)
                && before.port == after.port
                && before.authType == after.authType
                && eq(before.password, after.password)
                && eq(before.proxyJump, after.proxyJump)
                && before.accessMode == after.accessMode
                && before.remotePort == after.remotePort
                && eq(before.token, after.token)
                && eq(before.directUrl, after.directUrl)
                && before.identityFiles.equals(after.identityFiles)
                && eq(before.importedFrom, after.importedFrom);
        check(label + ": every other host field is unchanged", same,
                "alias=" + after.alias + " hostName=" + after.hostName + " token changed="
                        + !eq(before.token, after.token));
    }

    private static boolean isIsoInstant(String value) {
        if (value == null || value.trim().isEmpty()) return false;
        try {
            Instant.parse(value.trim());
            return true;
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }

    private static boolean eq(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static void check(String name, boolean condition, String detail) {
        checks += 1;
        if (condition) {
            System.out.println("  PASS " + name);
            return;
        }
        failures += 1;
        System.out.println("  FAIL " + name + (detail == null ? "" : " (" + detail + ")"));
    }
}

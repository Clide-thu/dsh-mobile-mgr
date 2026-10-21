import com.jcraft.jsch.Identity;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.KeyPair;

import dev.dsh.remotehosts.core.AppError;
import dev.dsh.remotehosts.core.ErrorKind;
import dev.dsh.remotehosts.core.SshKeyManager;
import dev.dsh.remotehosts.core.SshKeyRecord;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Vector;

/**
 * Desktop unit test for the v0.2.0 app-managed SSH key store
 * ({@link SshKeyManager}, docs/app-spec.md §8.2).
 *
 * No Android, no network. Covers:
 *  - id format/uniqueness, label defaults, looksLikePrivateKey;
 *  - RSA 2048/3072 generation, OpenSSH public-key line shape, SHA256 fingerprint
 *    (both via the manager and recomputed independently), private key reloadable
 *    by JSch, public blob round trip;
 *  - import with/without a public line, public/private mismatch rejection;
 *  - passphrase-protected keys: correct passphrase, wrong passphrase, missing
 *    passphrase (NEEDS_CREDENTIALS in both cases);
 *  - malformed private/public key input (BAD_REQUEST);
 *  - OpenSSH-format ed25519 and ECDSA imports;
 *  - every error message is free of private-key/passphrase material.
 *
 * Run via tools/test-jvm.ps1 (JSch jar on the classpath).
 */
public final class SshKeyManagerTest {

    private static int failures = 0;

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            System.out.println("PASS " + name);
        } else {
            failures++;
            System.out.println("FAIL " + name + (detail == null || detail.isEmpty() ? "" : " (" + detail + ")"));
        }
    }

    private static void fail(String name, String detail) {
        failures++;
        System.out.println("FAIL " + name + (detail == null || detail.isEmpty() ? "" : " (" + detail + ")"));
    }

    private static void checkThrows(String name, ErrorKind expectedKind, ThrowingCall call, String[] mustNotContain) {
        try {
            call.call();
            fail(name + " should fail", "no exception thrown");
        } catch (AppError error) {
            check(name + " kind=" + expectedKind.id, error.kind == expectedKind, String.valueOf(error.kind));
            String message = error.getMessage() == null ? "" : error.getMessage();
            boolean leaked = false;
            if (mustNotContain != null) {
                for (String secret : mustNotContain) {
                    if (secret != null && !secret.isEmpty() && message.contains(secret)) leaked = true;
                }
            }
            check(name + " message has no secret material", !leaked, message);
        }
    }

    private interface ThrowingCall {
        Object call() throws AppError;
    }

    // ------------------------------------------------------------------ helpers

    private static KeyPair generatePair(JSch jsch, int type, int bits) throws Exception {
        return bits > 0 ? KeyPair.genKeyPair(jsch, type, bits) : KeyPair.genKeyPair(jsch, type);
    }

    private static String pemPrivateKey(KeyPair pair, String passphrase) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (passphrase == null) {
            pair.writePrivateKey(out);
        } else {
            pair.writePrivateKey(out, passphrase.getBytes(StandardCharsets.UTF_8));
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String openSshPrivateKey(KeyPair pair, String passphrase) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        pair.writeOpenSSHv1PrivateKey(out,
                passphrase == null ? null : passphrase.getBytes(StandardCharsets.UTF_8));
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String publicKeyLine(KeyPair pair) {
        return pair.getKeyTypeString() + " "
                + Base64.getEncoder().encodeToString(pair.getPublicKeyBlob());
    }

    private static byte[] blobOfPublicLine(String publicKeyLine) {
        String[] tokens = publicKeyLine.trim().split("\\s+");
        return Base64.getDecoder().decode(tokens[1]);
    }

    private static String independentFingerprint(byte[] blob) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        // Standard base64 (not URL-safe) without padding: the ssh-keygen -lf format.
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest.digest(blob));
    }

    private static Identity loadIdentity(String privateText, String passphrase) throws Exception {
        JSch jsch = new JSch();
        jsch.addIdentity("test", privateText.getBytes(StandardCharsets.UTF_8), null,
                passphrase.isEmpty() ? null : passphrase.getBytes(StandardCharsets.UTF_8));
        Vector<Identity> identities = jsch.getIdentityRepository().getIdentities();
        if (identities.isEmpty()) throw new AssertionError("no identity parsed");
        return identities.get(0);
    }

    private static boolean isIsoInstant(String value) {
        if (value == null || value.length() < 16) return false;
        // Instant.toString() is like 2026-10-20T13:00:00.123Z
        return value.indexOf('T') > 0 && value.endsWith("Z");
    }

    // -------------------------------------------------------------------- tests

    private static void testNewIdAndHelpers() {
        Set<String> ids = new HashSet<>();
        boolean formatOk = true;
        for (int index = 0; index < 200; index += 1) {
            String id = SshKeyManager.newId();
            ids.add(id);
            if (!id.matches("key-[0-9a-f]{12}")) formatOk = false;
        }
        check("newId format key-<12 hex>", formatOk, "");
        check("newId uniqueness (200 ids)", ids.size() == 200, String.valueOf(ids.size()));

        check("looksLikePrivateKey PEM", SshKeyManager.looksLikePrivateKey(
                "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----"), "");
        check("looksLikePrivateKey RSA PEM", SshKeyManager.looksLikePrivateKey(
                "-----BEGIN RSA PRIVATE KEY-----\nabc\n-----END RSA PRIVATE KEY-----"), "");
        check("looksLikePrivateKey rejects plain text", !SshKeyManager.looksLikePrivateKey("hello"), "");
        check("looksLikePrivateKey rejects public key line",
                !SshKeyManager.looksLikePrivateKey("ssh-rsa AAAA user@host"), "");
        check("looksLikePrivateKey rejects null", !SshKeyManager.looksLikePrivateKey(null), "");
        check("DEFAULT_RSA_BITS is 3072", SshKeyManager.DEFAULT_RSA_BITS == 3072, "");
    }

    private static void testGenerate() throws Exception {
        checkThrows("generate 1024 bits rejected", ErrorKind.BAD_REQUEST,
                () -> SshKeyManager.generate("k", 1024), null);
        checkThrows("generate 0 bits rejected", ErrorKind.BAD_REQUEST,
                () -> SshKeyManager.generate("k", 0), null);
        checkThrows("generate 8192 bits rejected", ErrorKind.BAD_REQUEST,
                () -> SshKeyManager.generate("k", 8192), null);

        SshKeyRecord key = SshKeyManager.generate("my-key", 2048);
        check("generate: id format", key.id.matches("key-[0-9a-f]{12}"), key.id);
        check("generate: label", "my-key".equals(key.label), key.label);
        check("generate: keyType ssh-rsa", "ssh-rsa".equals(key.keyType), key.keyType);
        check("generate: source generated", "generated".equals(key.source), key.source);
        check("generate: createdAt ISO", isIsoInstant(key.createdAt), key.createdAt);
        check("generate: passphrase empty", key.passphrase == null || key.passphrase.isEmpty(), "");
        check("generate: privateKey is PEM", key.privateKey.startsWith("-----BEGIN ")
                && key.privateKey.contains("PRIVATE KEY-----"), "");
        check("generate: publicKey has 3 tokens", key.publicKey.split("\\s+").length == 3, key.publicKey);
        check("generate: publicKey type prefix", key.publicKey.startsWith("ssh-rsa "), "");
        check("generate: publicKey comment is label", key.publicKey.endsWith(" my-key"), "");
        check("generate: fingerprint SHA256 prefix",
                key.fingerprint != null && key.fingerprint.startsWith("SHA256:"), key.fingerprint);
        check("generate: fingerprint has no padding",
                key.fingerprint != null && !key.fingerprint.substring(7).contains("="), "");
        check("generate: fingerprint standard base64 alphabet",
                key.fingerprint.substring(7).matches("[A-Za-z0-9+/]+"), key.fingerprint);

        // Fingerprint is reproducible and matches an independent SHA-256 computation.
        byte[] blob = blobOfPublicLine(key.publicKey);
        check("generate: fingerprint independent recompute",
                independentFingerprint(blob).equals(key.fingerprint), independentFingerprint(blob));
        check("generate: fingerprintOfPublicKey agrees",
                SshKeyManager.fingerprintOfPublicKey(key.publicKey).equals(key.fingerprint), "");

        // The generated private key must be usable by JSch itself.
        Identity identity = loadIdentity(key.privateKey, "");
        check("generate: private key reloadable by JSch",
                Arrays.equals(identity.getPublicKeyBlob(), blob), "");
        check("generate: reloaded key type matches", key.keyType.equals(identity.getAlgName()),
                identity.getAlgName());

        SshKeyRecord other = SshKeyManager.generate("my-key-2", 2048);
        check("generate: ids are unique", !key.id.equals(other.id), key.id + " vs " + other.id);
        check("generate: distinct keys have distinct fingerprints",
                !key.fingerprint.equals(other.fingerprint), "");

        // 3072 is the default size; verify the actual key size.
        SshKeyRecord larger = SshKeyManager.generate("large", SshKeyManager.DEFAULT_RSA_BITS);
        KeyPair reloaded = KeyPair.load(new JSch(), larger.privateKey.getBytes(StandardCharsets.UTF_8), null);
        check("generate 3072: key size is 3072", reloaded.getKeySize() == 3072,
                String.valueOf(reloaded.getKeySize()));
        reloaded.dispose();
        check("generate: blank label falls back to app-key",
                "app-key".equals(SshKeyManager.generate("  ", 2048).label), "");
    }

    private static void testImportPlain() throws Exception {
        KeyPair pair = KeyPair.genKeyPair(new JSch(), KeyPair.RSA, 2048);
        String privateText = pemPrivateKey(pair, null);
        String publicLine = publicKeyLine(pair);
        byte[] blob = pair.getPublicKeyBlob();

        SshKeyRecord withPublic = SshKeyManager.importKeyPair("imported", privateText, "", publicLine);
        check("import: id format", withPublic.id.matches("key-[0-9a-f]{12}"), withPublic.id);
        check("import: label", "imported".equals(withPublic.label), withPublic.label);
        check("import: keyType ssh-rsa", "ssh-rsa".equals(withPublic.keyType), withPublic.keyType);
        check("import: source imported", "imported".equals(withPublic.source), withPublic.source);
        check("import: createdAt ISO", isIsoInstant(withPublic.createdAt), withPublic.createdAt);
        check("import: publicKey blob round trip",
                Arrays.equals(blobOfPublicLine(withPublic.publicKey), blob), "");
        check("import: publicKey comment is label", withPublic.publicKey.endsWith(" imported"), "");
        check("import: fingerprint consistent",
                SshKeyManager.fingerprintOfPublicKey(withPublic.publicKey).equals(withPublic.fingerprint), "");
        check("import: fingerprint independent recompute",
                independentFingerprint(blob).equals(withPublic.fingerprint), withPublic.fingerprint);
        check("import: private key reloadable", Arrays.equals(
                loadIdentity(withPublic.privateKey, "").getPublicKeyBlob(), blob), "");
        check("import: private key normalized to LF only", !withPublic.privateKey.contains("\r"), "");

        SshKeyRecord withoutPublic = SshKeyManager.importKeyPair("derived", privateText, "", "");
        check("import derive: same fingerprint as supplied-public import",
                withPublic.fingerprint.equals(withoutPublic.fingerprint), "");
        check("import derive: same key type", withPublic.keyType.equals(withoutPublic.keyType), "");
        check("import derive: different id", !withPublic.id.equals(withoutPublic.id), "");

        SshKeyRecord emptyLabel = SshKeyManager.importKeyPair(null, privateText, null, null);
        check("import: null label falls back to app-key", "app-key".equals(emptyLabel.label), emptyLabel.label);

        // CRLF input from a paste: normalized and accepted.
        SshKeyRecord crlf = SshKeyManager.importKeyPair("crlf", privateText.replace("\n", "\r\n"), "", publicLine);
        check("import: CRLF private key accepted", crlf.fingerprint.equals(withPublic.fingerprint), "");

        pair.dispose();
    }

    private static void testImportMismatchAndMalformed() throws Exception {
        KeyPair first = KeyPair.genKeyPair(new JSch(), KeyPair.RSA, 2048);
        KeyPair second = KeyPair.genKeyPair(new JSch(), KeyPair.RSA, 2048);
        String firstPrivate = pemPrivateKey(first, null);
        String secondPublic = publicKeyLine(second);
        String secretMaterial = "line-of-secret-private-key-content";

        checkThrows("import: mismatched public key rejected", ErrorKind.BAD_REQUEST,
                () -> SshKeyManager.importKeyPair("k", firstPrivate, "", secondPublic),
                new String[]{firstPrivate, "PRIVATE KEY"});

        checkThrows("import: non-key text rejected", ErrorKind.BAD_REQUEST,
                () -> SshKeyManager.importKeyPair("k", "hello world", "", ""), null);
        checkThrows("import: empty private key rejected", ErrorKind.BAD_REQUEST,
                () -> SshKeyManager.importKeyPair("k", "   ", "", ""), null);
        checkThrows("import: fake PEM rejected", ErrorKind.BAD_REQUEST,
                () -> SshKeyManager.importKeyPair("k",
                        "-----BEGIN RSA PRIVATE KEY-----\n" + secretMaterial + "\n-----END RSA PRIVATE KEY-----",
                        "", ""), new String[]{secretMaterial});

        first.dispose();
        second.dispose();
    }

    private static void testImportPassphrase() throws Exception {
        String passphrase = "correct horse battery";
        KeyPair pair = KeyPair.genKeyPair(new JSch(), KeyPair.RSA, 2048);
        String encryptedPem = pemPrivateKey(pair, passphrase);
        String encryptedOpenSsh = openSshPrivateKey(pair, passphrase);
        String publicLine = publicKeyLine(pair);
        byte[] blob = pair.getPublicKeyBlob();

        SshKeyRecord imported = SshKeyManager.importKeyPair("enc", encryptedPem, passphrase, publicLine);
        check("passphrase: correct PEM passphrase accepted",
                imported.passphrase.equals(passphrase), "");
        check("passphrase: fingerprint correct",
                independentFingerprint(blob).equals(imported.fingerprint), imported.fingerprint);
        check("passphrase: encrypted private key reloadable with passphrase",
                Arrays.equals(loadIdentity(imported.privateKey, imported.passphrase).getPublicKeyBlob(), blob), "");

        SshKeyRecord openSshImported = SshKeyManager.importKeyPair(
                "enc-openssh", encryptedOpenSsh, passphrase, "");
        check("passphrase: OpenSSH-format encrypted key accepted",
                openSshImported.fingerprint.equals(imported.fingerprint), "");
        check("passphrase: encrypted OpenSSH key keeps keyType",
                "ssh-rsa".equals(openSshImported.keyType), openSshImported.keyType);

        checkThrows("passphrase: wrong passphrase -> NEEDS_CREDENTIALS", ErrorKind.NEEDS_CREDENTIALS,
                () -> SshKeyManager.importKeyPair("enc", encryptedPem, "wrong-pass", ""),
                new String[]{encryptedPem, passphrase});
        checkThrows("passphrase: missing passphrase for encrypted PEM -> NEEDS_CREDENTIALS",
                ErrorKind.NEEDS_CREDENTIALS,
                () -> SshKeyManager.importKeyPair("enc", encryptedPem, "", ""),
                new String[]{encryptedPem});
        checkThrows("passphrase: missing passphrase for encrypted OpenSSH -> NEEDS_CREDENTIALS",
                ErrorKind.NEEDS_CREDENTIALS,
                () -> SshKeyManager.importKeyPair("enc", encryptedOpenSsh, "", ""),
                new String[]{encryptedOpenSsh});

        pair.dispose();
    }

    private static void testImportOtherFormats() throws Exception {
        // ed25519 exists only in the OpenSSH v1 container for JSch.
        KeyPair ed = KeyPair.genKeyPair(new JSch(), KeyPair.ED25519);
        String edPrivate = openSshPrivateKey(ed, null);
        String edPublic = publicKeyLine(ed);
        byte[] edBlob = ed.getPublicKeyBlob();
        SshKeyRecord edRecord = SshKeyManager.importKeyPair("ed", edPrivate, "", edPublic);
        check("import ed25519: keyType ssh-ed25519", "ssh-ed25519".equals(edRecord.keyType), edRecord.keyType);
        check("import ed25519: fingerprint independent recompute",
                independentFingerprint(edBlob).equals(edRecord.fingerprint), edRecord.fingerprint);
        check("import ed25519: private key reloadable",
                Arrays.equals(loadIdentity(edRecord.privateKey, "").getPublicKeyBlob(), edBlob), "");
        ed.dispose();

        KeyPair ecdsa = KeyPair.genKeyPair(new JSch(), KeyPair.ECDSA, 256);
        String ecdsaPrivate = openSshPrivateKey(ecdsa, null);
        SshKeyRecord ecdsaRecord = SshKeyManager.importKeyPair("ec", ecdsaPrivate, "", "");
        check("import ecdsa: keyType ecdsa-sha2-nistp256",
                "ecdsa-sha2-nistp256".equals(ecdsaRecord.keyType), ecdsaRecord.keyType);
        check("import ecdsa: fingerprint independent recompute",
                independentFingerprint(ecdsa.getPublicKeyBlob()).equals(ecdsaRecord.fingerprint),
                ecdsaRecord.fingerprint);
        ecdsa.dispose();
    }

    private static void testFingerprintHelpers() throws Exception {
        checkThrows("fingerprint: null rejected", ErrorKind.BAD_REQUEST,
                () -> SshKeyManager.fingerprintOfPublicKey(null), null);
        checkThrows("fingerprint: empty rejected", ErrorKind.BAD_REQUEST,
                () -> SshKeyManager.fingerprintOfPublicKey("  "), null);
        checkThrows("fingerprint: single token rejected", ErrorKind.BAD_REQUEST,
                () -> SshKeyManager.fingerprintOfPublicKey("ssh-rsa"), null);
        checkThrows("fingerprint: invalid base64 rejected", ErrorKind.BAD_REQUEST,
                () -> SshKeyManager.fingerprintOfPublicKey("ssh-rsa !!!notbase64!!!"), null);

        KeyPair pair = KeyPair.genKeyPair(new JSch(), KeyPair.RSA, 2048);
        String noComment = pair.getKeyTypeString() + " "
                + Base64.getEncoder().encodeToString(pair.getPublicKeyBlob());
        String fingerprint = SshKeyManager.fingerprintOfPublicKey(noComment);
        check("fingerprint: public line without comment accepted",
                fingerprint.startsWith("SHA256:") && fingerprint.length() > 10, fingerprint);
        check("fingerprint: unpadded base64 line accepted",
                SshKeyManager.fingerprintOfPublicKey(noComment.replace("=", "")).equals(fingerprint), "");
        check("fingerprint: deterministic",
                SshKeyManager.fingerprintOfPublicKey(noComment).equals(fingerprint), "");
        pair.dispose();
    }

    // --------------------------------------------------------------------- main

    public static void main(String[] args) {
        System.out.println("SshKeyManagerTest: app-managed SSH key store (v0.2.0)");
        try {
            testNewIdAndHelpers();
        } catch (Throwable error) {
            fail("newId/helpers", error.toString());
        }
        try {
            testGenerate();
        } catch (Throwable error) {
            fail("generate", error.toString());
        }
        try {
            testImportPlain();
        } catch (Throwable error) {
            fail("import plain", error.toString());
        }
        try {
            testImportMismatchAndMalformed();
        } catch (Throwable error) {
            fail("import mismatch/malformed", error.toString());
        }
        try {
            testImportPassphrase();
        } catch (Throwable error) {
            fail("import passphrase", error.toString());
        }
        try {
            testImportOtherFormats();
        } catch (Throwable error) {
            fail("import other formats", error.toString());
        }
        try {
            testFingerprintHelpers();
        } catch (Throwable error) {
            fail("fingerprint helpers", error.toString());
        }

        if (failures > 0) {
            System.out.println("SSH KEY MANAGER TEST FAILED (" + failures + " check(s))");
            System.exit(1);
        }
        System.out.println("SSH KEY MANAGER TEST PASSED");
    }
}

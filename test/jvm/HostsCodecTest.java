import dev.dsh.remotehosts.core.HostConfig;
import dev.dsh.remotehosts.core.HostKeyRecord;
import dev.dsh.remotehosts.core.HostsCodec;
import dev.dsh.remotehosts.core.SshKeyRecord;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for {@code core/HostsCodec}.
 *
 * This is the plain-text half of the encrypted store (docs/app-spec.md §4):
 * a {@code {version, hosts[], sshKeys[], trustedHostKeys{}}} JSON document that
 * must round-trip every HostConfig and SshKeyRecord field (including secrets,
 * which are protected by the SecretBox envelope around it), keep decoding
 * v0.1.0 documents that have no {@code sshKeys} array, and degrade to an empty
 * store on corrupt input instead of crashing the app.
 *
 * v0.2.5 removes the per-host {@code appendToken} field (the external-browser
 * versus built-in-WebView choice is a global setting): encoded documents must
 * not contain it, while older documents that still carry it must decode their
 * hosts unchanged.
 *
 * No JUnit: a plain main() keeps the offline toolchain dependency-free.
 */
public final class HostsCodecTest {

    /** Placeholder fingerprint used by the codec fixtures (never validated here). */
    private static final String TEST_FINGERPRINT = "SHA256:test-fingerprint-abcdef";

    private static int checks;
    private static int failures;

    private HostsCodecTest() {
    }

    public static void main(String[] args) {
        roundTripsEveryHostField();
        roundTripsSeveralHostsInOrder();
        roundTripsTrustedHostKeys();
        keepsJsonShape();
        preservesSecretsVerbatim();
        corruptInputYieldsEmptyStore();
        nullInputIsTolerated();
        emptyStoreRoundTrip();
        unknownJsonFieldsAreIgnored();
        encodeOfDecodedIsStable();

        // v0.2.0: SSH key store
        roundTripsSshKeys();
        sshKeySecretsSurviveTheRoundTrip();
        legacyJsonWithoutSshKeysDecodesEmpty();
        brokenSshKeysAreTolerated();
        twoArgEncodeLeavesSshKeysEmpty();
        emptySshKeysRoundTrip();
        sshKeysPreservedByDecodedEncode();

        // v0.2.5: appendToken removed (global open-mode setting instead)
        encodeOmitsRemovedAppendToken();
        legacyAppendTokenFieldIsIgnored();

        System.out.println();
        System.out.println("HostsCodecTest: " + (checks - failures) + "/" + checks + " checks passed");
        if (failures > 0) {
            System.out.println("FAILED (" + failures + " check(s))");
            System.exit(1);
        }
        System.out.println("PASSED");
    }

    // -----------------------------------------------------------------------
    // 1. full field round-trip
    // -----------------------------------------------------------------------

    private static void roundTripsEveryHostField() {
        HostConfig host = new HostConfig();
        host.alias = "alpha";
        host.hostName = "a.example.com";
        host.user = "alice";
        host.port = 2222;
        host.authType = HostConfig.AuthType.PRIVATE_KEY;
        host.password = "pw-secret";
        host.privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\n-----END OPENSSH PRIVATE KEY-----";
        host.keyPassphrase = "passphrase-secret";
        host.proxyJump = "jumpUser@bastion.example.com:2200";
        host.accessMode = HostConfig.AccessMode.TUNNEL;
        host.remotePort = 3080;
        host.token = "TESTTOKEN0000000000000000000000000000000000";
        host.directUrl = "https://alpha.example.com/dsh?token=x";
        host.identityFiles = new ArrayList<>();
        host.identityFiles.add("~/.ssh/id_ed25519");
        host.identityFiles.add("/home/alice/.ssh/id_rsa");
        host.importedFrom = "ssh config";

        List<HostConfig> hosts = new ArrayList<>();
        hosts.add(host);
        String json = HostsCodec.encode(hosts, new LinkedHashMap<String, HostKeyRecord>());
        check("encode: returns non-empty JSON", json != null && json.length() > 2,
                "json=" + (json == null ? null : "<" + json.length() + " chars>"));

        HostsCodec.Decoded decoded = HostsCodec.decode(json);
        check("decode: one host", decoded.hosts.size() == 1, "hosts=" + decoded.hosts.size());
        if (decoded.hosts.size() != 1) return;
        HostConfig back = decoded.hosts.get(0);
        check("round-trip: alias", "alpha".equals(back.alias), "got " + back.alias);
        check("round-trip: hostName", "a.example.com".equals(back.hostName), "got " + back.hostName);
        check("round-trip: user", "alice".equals(back.user), "got " + back.user);
        check("round-trip: port", back.port == 2222, "got " + back.port);
        check("round-trip: authType enum", back.authType == HostConfig.AuthType.PRIVATE_KEY,
                "got " + back.authType);
        check("round-trip: password", "pw-secret".equals(back.password), "got " + back.password);
        check("round-trip: privateKey", host.privateKey.equals(back.privateKey),
                "privateKey mismatch");
        check("round-trip: keyPassphrase", "passphrase-secret".equals(back.keyPassphrase),
                "got " + back.keyPassphrase);
        check("round-trip: proxyJump", host.proxyJump.equals(back.proxyJump), "got " + back.proxyJump);
        check("round-trip: accessMode enum", back.accessMode == HostConfig.AccessMode.TUNNEL,
                "got " + back.accessMode);
        check("round-trip: remotePort", back.remotePort == 3080, "got " + back.remotePort);
        check("round-trip: token", host.token.equals(back.token), "got " + back.token);
        check("round-trip: directUrl", host.directUrl.equals(back.directUrl), "got " + back.directUrl);
        check("round-trip: identityFiles size", back.identityFiles.size() == 2,
                "got " + back.identityFiles);
        if (back.identityFiles.size() == 2) {
            check("round-trip: identityFiles[0] order",
                    "~/.ssh/id_ed25519".equals(back.identityFiles.get(0)), "got " + back.identityFiles);
            check("round-trip: identityFiles[1] order",
                    "/home/alice/.ssh/id_rsa".equals(back.identityFiles.get(1)), "got " + back.identityFiles);
        }
        check("round-trip: importedFrom", "ssh config".equals(back.importedFrom), "got " + back.importedFrom);
    }

    private static void roundTripsSeveralHostsInOrder() {
        List<HostConfig> hosts = new ArrayList<>();
        hosts.add(host("alpha", "a.example.com"));
        hosts.add(host("beta", "b.example.com"));
        hosts.add(host("gamma", ""));
        String json = HostsCodec.encode(hosts, new LinkedHashMap<String, HostKeyRecord>());
        HostsCodec.Decoded decoded = HostsCodec.decode(json);
        check("order: three hosts survive", decoded.hosts.size() == 3, "hosts=" + decoded.hosts.size());
        if (decoded.hosts.size() == 3) {
            check("order: alias order preserved",
                    "alpha".equals(decoded.hosts.get(0).alias)
                            && "beta".equals(decoded.hosts.get(1).alias)
                            && "gamma".equals(decoded.hosts.get(2).alias),
                    "order=" + decoded.hosts.get(0).alias + "," + decoded.hosts.get(1).alias + ","
                            + decoded.hosts.get(2).alias);
            check("order: empty hostName survives the round-trip",
                    "".equals(decoded.hosts.get(2).hostName)
                            || "gamma".equals(decoded.hosts.get(2).resolvedHostName()),
                    "hostName=" + decoded.hosts.get(2).hostName);
        }
    }

    private static void roundTripsTrustedHostKeys() {
        Map<String, HostKeyRecord> trusted = new LinkedHashMap<>();
        trusted.put("a.example.com:2222",
                new HostKeyRecord("ssh-ed25519", "AAAAC3Nza...", "SHA256:abcdef", "2026-01-02T03:04:05Z"));
        trusted.put("b.example.com:22",
                new HostKeyRecord("ssh-rsa", "AAAAB3Nza...", "SHA256:ghijkl", "2026-02-03T04:05:06Z"));
        List<HostConfig> hosts = new ArrayList<>();
        hosts.add(host("alpha", "a.example.com"));
        String json = HostsCodec.encode(hosts, trusted);
        HostsCodec.Decoded decoded = HostsCodec.decode(json);
        check("trusted: both records survive", decoded.trustedHostKeys.size() == 2,
                "keys=" + decoded.trustedHostKeys.keySet());
        HostKeyRecord first = decoded.trustedHostKeys.get("a.example.com:2222");
        check("trusted: record looked up by host:port key", first != null, "missing record");
        if (first != null) {
            check("trusted: keyType", "ssh-ed25519".equals(first.keyType), "got " + first.keyType);
            check("trusted: keyBase64", "AAAAC3Nza...".equals(first.keyBase64), "got " + first.keyBase64);
            check("trusted: fingerprint", "SHA256:abcdef".equals(first.fingerprint), "got " + first.fingerprint);
            check("trusted: addedAt", "2026-01-02T03:04:05Z".equals(first.addedAt), "got " + first.addedAt);
        }
        check("trusted: order preserved",
                new ArrayList<>(decoded.trustedHostKeys.keySet())
                        .equals(new ArrayList<>(trusted.keySet())),
                "order=" + decoded.trustedHostKeys.keySet());
    }

    // -----------------------------------------------------------------------
    // 2. document shape
    // -----------------------------------------------------------------------

    private static void keepsJsonShape() {
        List<HostConfig> hosts = new ArrayList<>();
        hosts.add(host("alpha", "a.example.com"));
        String json = HostsCodec.encode(hosts, new LinkedHashMap<String, HostKeyRecord>());
        check("shape: document carries a version field", json.contains("\"version\""), "json=" + json);
        check("shape: document carries a hosts array", json.contains("\"hosts\""), "json=" + json);
        check("shape: document carries trustedHostKeys", json.contains("\"trustedHostKeys\""),
                "json=" + json);
        check("shape: host alias is serialized", json.contains("alpha"), "json=" + json);
    }

    private static void preservesSecretsVerbatim() {
        // The codec sits *inside* the encrypted envelope: it must not mangle
        // secrets, because the store round-trip has to give the tunnel engine the
        // exact private key/password/token the user typed.
        HostConfig host = host("alpha", "a.example.com");
        host.password = "p@ss \"quoted\" \\ backslash";
        host.privateKey = "line1\nline2#hash\nline3";
        host.token = "tok/en+with=special&chars";
        List<HostConfig> hosts = new ArrayList<>();
        hosts.add(host);
        HostsCodec.Decoded decoded = HostsCodec.decode(
                HostsCodec.encode(hosts, new LinkedHashMap<String, HostKeyRecord>()));
        check("secrets: password exact after round-trip",
                decoded.hosts.size() == 1 && host.password.equals(decoded.hosts.get(0).password),
                "password mismatch");
        check("secrets: privateKey exact after round-trip",
                decoded.hosts.size() == 1 && host.privateKey.equals(decoded.hosts.get(0).privateKey),
                "privateKey mismatch");
        check("secrets: token exact after round-trip",
                decoded.hosts.size() == 1 && host.token.equals(decoded.hosts.get(0).token),
                "token mismatch");
    }

    // -----------------------------------------------------------------------
    // 3. robustness
    // -----------------------------------------------------------------------

    private static void corruptInputYieldsEmptyStore() {
        String[] corrupt = {
                "",
                "   ",
                "{ not json",
                "null",
                "[]",
                "{}",
                "{\"hosts\": \"nope\"}",
        };
        for (String input : corrupt) {
            try {
                HostsCodec.Decoded decoded = HostsCodec.decode(input);
                boolean empty = decoded != null && decoded.hosts.isEmpty()
                        && decoded.trustedHostKeys.isEmpty();
                check("corrupt: decodes to an empty store without throwing",
                        empty, "input=" + preview(input) + " hosts=" + (decoded == null ? null : decoded.hosts.size()));
            } catch (Throwable error) {
                check("corrupt: decodes to an empty store without throwing", false,
                        "input=" + preview(input) + " threw " + error);
            }
        }

        // A document with one malformed section but one valid section may salvage
        // the valid part; what matters is that it never throws and still returns a
        // usable store object.
        String[] partiallyMalformed = {
                "{\"hosts\": [{\"alias\": \"x\"}], \"trustedHostKeys\": \"nope\"}",
                "{\"hosts\": [{\"alias\": \"x\"}, 42], \"trustedHostKeys\": {}}",
        };
        for (String input : partiallyMalformed) {
            try {
                HostsCodec.Decoded decoded = HostsCodec.decode(input);
                check("corrupt: a partially malformed document never throws",
                        decoded != null && decoded.hosts != null && decoded.trustedHostKeys != null,
                        "input=" + preview(input));
            } catch (Throwable error) {
                check("corrupt: a partially malformed document never throws", false,
                        "input=" + preview(input) + " threw " + error);
            }
        }
    }

    private static void nullInputIsTolerated() {
        try {
            HostsCodec.Decoded decoded = HostsCodec.decode(null);
            check("null: decode(null) yields an empty store",
                    decoded != null && decoded.hosts.isEmpty() && decoded.trustedHostKeys.isEmpty(),
                    "decoded=" + decoded);
        } catch (Throwable error) {
            check("null: decode(null) yields an empty store", false, "threw " + error);
        }
        try {
            String json = HostsCodec.encode(null, null);
            check("null: encode(null, null) returns a decodable empty store",
                    json != null && HostsCodec.decode(json).hosts.isEmpty(), "json=" + json);
        } catch (Throwable error) {
            check("null: encode(null, null) returns a decodable empty store", false, "threw " + error);
        }
    }

    private static void emptyStoreRoundTrip() {
        String json = HostsCodec.encode(new ArrayList<HostConfig>(),
                new LinkedHashMap<String, HostKeyRecord>());
        HostsCodec.Decoded decoded = HostsCodec.decode(json);
        check("empty: empty store round-trips",
                decoded.hosts.isEmpty() && decoded.trustedHostKeys.isEmpty(), "json=" + json);
    }

    private static void unknownJsonFieldsAreIgnored() {
        String json = "{\"version\":1,"
                + "\"futureField\":{\"nested\":[1,2,3]},"
                + "\"hosts\":[{\"alias\":\"alpha\",\"hostName\":\"a.example.com\",\"user\":\"alice\","
                + "\"port\":22,\"authType\":\"PASSWORD\",\"accessMode\":\"TUNNEL\",\"unknown\":true}],"
                + "\"trustedHostKeys\":{}}";
        HostsCodec.Decoded decoded = HostsCodec.decode(json);
        check("forward-compat: unknown fields do not break decoding",
                decoded.hosts.size() == 1 && "alpha".equals(decoded.hosts.get(0).alias),
                "hosts=" + decoded.hosts.size());
    }

    private static void encodeOfDecodedIsStable() {
        List<HostConfig> hosts = new ArrayList<>();
        hosts.add(host("alpha", "a.example.com"));
        Map<String, HostKeyRecord> trusted = new LinkedHashMap<>();
        trusted.put("a.example.com:22", new HostKeyRecord("ssh-rsa", "BASE64", "SHA256:f", "now"));
        HostsCodec.Decoded decoded = HostsCodec.decode(HostsCodec.encode(hosts, trusted));
        String reEncoded = HostsCodec.encode(decoded);
        HostsCodec.Decoded again = HostsCodec.decode(reEncoded);
        check("stability: decode(encode(decoded)) preserves hosts",
                again.hosts.size() == 1 && "alpha".equals(again.hosts.get(0).alias),
                "hosts=" + again.hosts.size());
        check("stability: decode(encode(decoded)) preserves trusted keys",
                again.trustedHostKeys.size() == 1 && again.trustedHostKeys.containsKey("a.example.com:22"),
                "keys=" + again.trustedHostKeys.keySet());
    }

    // -----------------------------------------------------------------------
    // v0.2.0: SSH key store
    // -----------------------------------------------------------------------

    private static void roundTripsSshKeys() {
        List<SshKeyRecord> keys = new ArrayList<>();
        keys.add(sshKey("key-0123456789ab", "app-key-1", "ssh-rsa", "generated"));
        keys.add(sshKey("key-ba9876543210", "gpu-cluster", "ssh-ed25519", "imported"));

        List<HostConfig> hosts = new ArrayList<>();
        hosts.add(host("alpha", "a.example.com"));
        String json = HostsCodec.encode(hosts, keys, new LinkedHashMap<String, HostKeyRecord>());

        HostsCodec.Decoded decoded = HostsCodec.decode(json);
        check("sshKeys: both keys survive", decoded.sshKeys.size() == 2,
                "sshKeys=" + decoded.sshKeys.size());
        check("sshKeys: hosts and trusted keys unaffected",
                decoded.hosts.size() == 1 && decoded.trustedHostKeys.isEmpty(),
                "hosts=" + decoded.hosts.size() + " trusted=" + decoded.trustedHostKeys.size());
        if (decoded.sshKeys.size() != 2) return;
        SshKeyRecord first = decoded.sshKeys.get(0);
        check("sshKeys: id", "key-0123456789ab".equals(first.id), "got " + first.id);
        check("sshKeys: label", "app-key-1".equals(first.label), "got " + first.label);
        check("sshKeys: keyType", "ssh-rsa".equals(first.keyType), "got " + first.keyType);
        check("sshKeys: publicKey", testPublicKey("ssh-rsa", "app-key-1").equals(first.publicKey),
                "publicKey mismatch");
        check("sshKeys: privateKey", testPrivateKey().equals(first.privateKey), "privateKey mismatch");
        check("sshKeys: passphrase", "".equals(first.passphrase), "got " + first.passphrase);
        check("sshKeys: fingerprint", TEST_FINGERPRINT.equals(first.fingerprint),
                "got " + first.fingerprint);
        check("sshKeys: createdAt", "2026-10-20T13:00:00Z".equals(first.createdAt),
                "got " + first.createdAt);
        check("sshKeys: source", "generated".equals(first.source), "got " + first.source);
        check("sshKeys: order preserved",
                "key-ba9876543210".equals(decoded.sshKeys.get(1).id),
                "got " + decoded.sshKeys.get(1).id);

        check("sshKeys: document carries the sshKeys array", json.contains("\"sshKeys\""),
                "json=" + preview(json));
    }

    private static void sshKeySecretsSurviveTheRoundTrip() {
        SshKeyRecord key = sshKey("key-secret000001", "with-passphrase", "ssh-rsa", "imported");
        key.privateKey = "-----BEGIN RSA PRIVATE KEY-----\nsecret-body\n-----END RSA PRIVATE KEY-----";
        key.passphrase = "key-passphrase-secret";
        List<SshKeyRecord> keys = new ArrayList<>();
        keys.add(key);
        List<HostConfig> hosts = new ArrayList<>();
        hosts.add(host("alpha", "a.example.com"));
        HostsCodec.Decoded decoded = HostsCodec.decode(
                HostsCodec.encode(hosts, keys, new LinkedHashMap<String, HostKeyRecord>()));
        check("sshKeys: one key round-trips at all", decoded.sshKeys.size() == 1,
                "sshKeys=" + decoded.sshKeys.size());
        if (decoded.sshKeys.size() != 1) return;
        check("sshKeys: privateKey exact after round-trip",
                key.privateKey.equals(decoded.sshKeys.get(0).privateKey), "privateKey mismatch");
        check("sshKeys: passphrase exact after round-trip",
                "key-passphrase-secret".equals(decoded.sshKeys.get(0).passphrase),
                "passphrase mismatch");
    }

    private static void legacyJsonWithoutSshKeysDecodesEmpty() {
        // v0.1.0 store format: no "sshKeys" field at all.
        String legacy = "{\"version\":1,\"hosts\":[{\"alias\":\"alpha\",\"hostName\":\"a.example.com\","
                + "\"user\":\"alice\",\"port\":22,\"authType\":\"PASSWORD\",\"accessMode\":\"TUNNEL\","
                + "\"privateKey\":\"-----BEGIN RSA PRIVATE KEY-----old\",\"keyPassphrase\":\"old-pass\"}],"
                + "\"trustedHostKeys\":{\"a.example.com:22\":{\"keyType\":\"ssh-rsa\","
                + "\"keyBase64\":\"BASE64\",\"fingerprint\":\"SHA256:f\",\"addedAt\":\"2026-01-01T00:00:00Z\"}}}";
        HostsCodec.Decoded decoded = HostsCodec.decode(legacy);
        check("legacy: host decoded from a v0.1.0 document", decoded.hosts.size() == 1,
                "hosts=" + decoded.hosts.size());
        check("legacy: missing sshKeys decodes as an empty list",
                decoded.sshKeys != null && decoded.sshKeys.isEmpty(),
                "sshKeys=" + (decoded.sshKeys == null ? null : decoded.sshKeys.size()));
        check("legacy: trusted keys still decoded", decoded.trustedHostKeys.size() == 1,
                "trusted=" + decoded.trustedHostKeys.size());
        if (decoded.hosts.size() == 1) {
            check("legacy: inline private key preserved for the migration to pick up",
                    decoded.hosts.get(0).privateKey != null
                            && decoded.hosts.get(0).privateKey.contains("BEGIN RSA PRIVATE KEY"),
                    "privateKey lost");
        }
    }

    private static void brokenSshKeysAreTolerated() {
        String brokenArray = "{\"version\":1,\"hosts\":[{\"alias\":\"alpha\"}],\"sshKeys\":\"nope\","
                + "\"trustedHostKeys\":{}}";
        HostsCodec.Decoded decoded = HostsCodec.decode(brokenArray);
        check("sshKeys: wrong JSON type does not throw and yields empty list",
                decoded.sshKeys != null && decoded.sshKeys.isEmpty() && decoded.hosts.size() == 1,
                "sshKeys=" + (decoded.sshKeys == null ? null : decoded.sshKeys.size()));
        check("sshKeys: hosts still decoded when sshKeys is broken", decoded.hosts.size() == 1,
                "hosts=" + decoded.hosts.size());

        String mixed = "{\"version\":1,\"hosts\":[],\"sshKeys\":["
                + "{\"id\":\"key-good0000001\",\"label\":\"good\",\"keyType\":\"ssh-rsa\","
                + "\"publicKey\":\"ssh-rsa AAAA good\",\"privateKey\":\"PEM\",\"passphrase\":\"\","
                + "\"fingerprint\":\"SHA256:aaa\",\"createdAt\":\"2026-10-20T00:00:00Z\",\"source\":\"imported\"},"
                + "42,"
                + "{\"label\":\"no-id\"},"
                + "\"junk\""
                + "],\"trustedHostKeys\":{}}";
        HostsCodec.Decoded mixedDecoded = HostsCodec.decode(mixed);
        check("sshKeys: malformed entries are skipped, no throw", mixedDecoded.sshKeys != null,
                "sshKeys=null");
        check("sshKeys: valid entry survives alongside broken ones",
                mixedDecoded.sshKeys != null && mixedDecoded.sshKeys.size() == 1
                        && "key-good0000001".equals(mixedDecoded.sshKeys.get(0).id),
                "sshKeys=" + (mixedDecoded.sshKeys == null ? null : mixedDecoded.sshKeys.size()));
    }

    private static void twoArgEncodeLeavesSshKeysEmpty() {
        List<HostConfig> hosts = new ArrayList<>();
        hosts.add(host("alpha", "a.example.com"));
        HostsCodec.Decoded decoded = HostsCodec.decode(
                HostsCodec.encode(hosts, new LinkedHashMap<String, HostKeyRecord>()));
        check("compat: encode(hosts, trusted) still works and has no sshKeys",
                decoded.hosts.size() == 1 && decoded.sshKeys.isEmpty(),
                "hosts=" + decoded.hosts.size() + " sshKeys=" + decoded.sshKeys.size());
    }

    private static void emptySshKeysRoundTrip() {
        List<SshKeyRecord> keys = new ArrayList<>();
        List<HostConfig> hosts = new ArrayList<>();
        hosts.add(host("alpha", "a.example.com"));
        HostsCodec.Decoded decoded = HostsCodec.decode(
                HostsCodec.encode(hosts, keys, new LinkedHashMap<String, HostKeyRecord>()));
        check("sshKeys: empty list round-trips", decoded.sshKeys.isEmpty()
                && decoded.hosts.size() == 1,
                "sshKeys=" + decoded.sshKeys.size() + " hosts=" + decoded.hosts.size());
    }

    private static void sshKeysPreservedByDecodedEncode() {
        List<SshKeyRecord> keys = new ArrayList<>();
        keys.add(sshKey("key-111111111111", "one", "ssh-rsa", "generated"));
        List<HostConfig> hosts = new ArrayList<>();
        hosts.add(host("alpha", "a.example.com"));
        HostsCodec.Decoded decoded = HostsCodec.decode(
                HostsCodec.encode(hosts, keys, new LinkedHashMap<String, HostKeyRecord>()));
        HostsCodec.Decoded again = HostsCodec.decode(HostsCodec.encode(decoded));
        check("stability: decode(encode(decoded)) preserves sshKeys",
                again.sshKeys.size() == 1 && "key-111111111111".equals(again.sshKeys.get(0).id),
                "sshKeys=" + again.sshKeys.size());
    }

    // -----------------------------------------------------------------------
    // v0.2.5: appendToken removed (global external-browser/WebView setting)
    // -----------------------------------------------------------------------

    private static void encodeOmitsRemovedAppendToken() {
        HostConfig host = host("alpha", "a.example.com");
        host.remotePort = 3080;
        host.token = "TOKEN";
        List<HostConfig> hosts = new ArrayList<>();
        hosts.add(host);
        String json = HostsCodec.encode(hosts, new ArrayList<SshKeyRecord>(),
                new LinkedHashMap<String, HostKeyRecord>());
        check("appendToken: encode no longer emits the removed field",
                !json.contains("\"appendToken\""), "json=" + preview(json));

        HostsCodec.Decoded decoded = HostsCodec.decode(json);
        check("appendToken: host fields still round-trip without the removed field",
                decoded.hosts.size() == 1
                        && "alpha".equals(decoded.hosts.get(0).alias)
                        && "TOKEN".equals(decoded.hosts.get(0).token)
                        && decoded.hosts.get(0).remotePort == 3080,
                "hosts=" + decoded.hosts.size());
    }

    private static void legacyAppendTokenFieldIsIgnored() {
        // v0.2.4 and older documents carry a per-host "appendToken" boolean.
        // org.json ignores the unknown field, so both values decode unchanged.
        String legacy = "{\"version\":1,\"hosts\":["
                + "{\"alias\":\"external\",\"hostName\":\"a.example.com\",\"user\":\"alice\","
                + "\"port\":22,\"authType\":\"PASSWORD\",\"accessMode\":\"TUNNEL\","
                + "\"remotePort\":3080,\"token\":\"TOKEN-EXTERNAL\",\"appendToken\":true},"
                + "{\"alias\":\"builtin\",\"hostName\":\"b.example.com\",\"user\":\"bob\","
                + "\"port\":2222,\"authType\":\"PRIVATE_KEY\",\"accessMode\":\"TUNNEL\","
                + "\"remotePort\":4080,\"token\":\"TOKEN-BUILTIN\",\"appendToken\":false}"
                + "],\"trustedHostKeys\":{}}";
        HostsCodec.Decoded decoded = HostsCodec.decode(legacy);
        check("appendToken: legacy true/false fields are ignored, both hosts decode",
                decoded.hosts.size() == 2, "hosts=" + decoded.hosts.size());
        if (decoded.hosts.size() != 2) return;

        HostConfig external = decoded.hosts.get(0);
        HostConfig builtin = decoded.hosts.get(1);
        check("appendToken: legacy true host keeps alias/token/remotePort",
                "external".equals(external.alias) && "TOKEN-EXTERNAL".equals(external.token)
                        && external.remotePort == 3080,
                "alias=" + external.alias + " token=" + external.token
                        + " remotePort=" + external.remotePort);
        check("appendToken: legacy false host keeps alias/token/remotePort",
                "builtin".equals(builtin.alias) && "TOKEN-BUILTIN".equals(builtin.token)
                        && builtin.remotePort == 4080,
                "alias=" + builtin.alias + " token=" + builtin.token
                        + " remotePort=" + builtin.remotePort);
        check("appendToken: legacy hosts keep their SSH fields",
                "b.example.com".equals(builtin.hostName) && "bob".equals(builtin.user)
                        && builtin.port == 2222
                        && builtin.authType == HostConfig.AuthType.PRIVATE_KEY,
                "hostName=" + builtin.hostName + " user=" + builtin.user
                        + " port=" + builtin.port + " authType=" + builtin.authType);

        String reencoded = HostsCodec.encode(decoded);
        check("appendToken: re-encoding a legacy document drops the field",
                !reencoded.contains("\"appendToken\""), "json=" + preview(reencoded));
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private static String testPrivateKey() {
        return "-----BEGIN OPENSSH PRIVATE KEY-----\ntest-key-body\n-----END OPENSSH PRIVATE KEY-----";
    }

    private static String testPublicKey(String type, String label) {
        return type + " AAAAB3NzaC1yc2EAAAADAQABAAABgQC7" + label;
    }

    private static SshKeyRecord sshKey(String id, String label, String keyType, String source) {
        SshKeyRecord key = new SshKeyRecord();
        key.id = id;
        key.label = label;
        key.keyType = keyType;
        key.publicKey = testPublicKey(keyType, label);
        key.privateKey = testPrivateKey();
        key.passphrase = "";
        key.fingerprint = TEST_FINGERPRINT;
        key.createdAt = "2026-10-20T13:00:00Z";
        key.source = source;
        return key;
    }

    private static HostConfig host(String alias, String hostName) {
        HostConfig host = new HostConfig();
        host.alias = alias;
        host.hostName = hostName;
        host.user = "alice";
        host.port = 22;
        return host;
    }

    private static String preview(String input) {
        if (input == null) return "null";
        String oneLine = input.replace('\n', ' ');
        return oneLine.length() <= 40 ? "\"" + oneLine + "\"" : "\"" + oneLine.substring(0, 40) + "...\"";
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

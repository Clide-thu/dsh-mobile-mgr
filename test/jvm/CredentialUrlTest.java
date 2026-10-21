import dev.dsh.remotehosts.core.CredentialUrl;

/**
 * Unit tests for {@code core/CredentialUrl}, ported from the desktop plugin's
 * test/credentials.test.mjs plus the security invariants in docs/app-spec.md §2.
 *
 * Contract under test:
 *  - parse() accepts the exact URL shape {@code dsh web} prints, keeps only the
 *    remote port and the token, defaults the port (http=80 / https=443) and
 *    rejects anything without a non-empty token. The reason shown to the user
 *    must never echo the token.
 *  - browserUrlFor() is the ONLY place a token may appear in a URL, and it must
 *    percent-encode the token so an arbitrary token cannot alter the URL shape.
 *  - maskToken() must be safe to render in the UI (never contains the token).
 *  - isValidDirectUrl() only accepts http/https URLs with a host.
 *
 * No JUnit: a plain main() keeps the offline toolchain dependency-free.
 */
public final class CredentialUrlTest {

    private static int checks;
    private static int failures;

    private CredentialUrlTest() {
    }

    public static void main(String[] args) {
        // --- parse: the shapes dsh web actually prints ----------------------
        parsesExactDshWebUrl();
        parsesLanCredentialShape();
        defaultsHttpPortTo80();
        defaultsHttpsPortTo443();
        keepsExplicitHttpsPort();
        acceptsExtraQueryParameters();
        decodesPercentEncodedToken();
        trimsSurroundingWhitespace();
        acceptsUppercaseScheme();

        // --- parse: rejection paths ----------------------------------------
        rejectsMissingToken();
        rejectsEmptyToken();
        rejectsEmptyAndMalformedInput();
        rejectsNonHttpSchemes();
        reasonNeverEchoesTheToken();

        // --- browserUrlFor: the single token outlet ------------------------
        browserUrlMatchesDesktopShape();
        browserUrlPercentEncodesToken();
        browserUrlRejectsInvalidLocalPort();

        // --- plainUrlFor: token-less URL shape (v0.2.2) --------------------
        plainUrlMatchesCleanShape();
        plainUrlRejectsInvalidLocalPort();

        // --- maskToken ------------------------------------------------------
        maskTokenNeverRevealsTheToken();
        maskTokenShortAndEmptyValues();

        // --- isValidDirectUrl ----------------------------------------------
        isValidDirectUrlAcceptsHttpAndHttps();
        isValidDirectUrlRejectsJunk();

        System.out.println();
        System.out.println("CredentialUrlTest: " + (checks - failures) + "/" + checks + " checks passed");
        if (failures > 0) {
            System.out.println("FAILED (" + failures + " check(s))");
            System.exit(1);
        }
        System.out.println("PASSED");
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private static void check(String name, boolean condition, String detail) {
        checks += 1;
        if (condition) {
            System.out.println("  PASS " + name);
            return;
        }
        failures += 1;
        System.out.println("  FAIL " + name + (detail == null ? "" : " (" + detail + ")"));
    }

    private static void check(String name, boolean condition) {
        check(name, condition, null);
    }

    // -----------------------------------------------------------------------
    // parse()
    // -----------------------------------------------------------------------

    private static void parsesExactDshWebUrl() {
        String token = "TESTTOKEN0000000000000000000000000000000000";
        CredentialUrl.Parsed parsed = CredentialUrl.parse("http://127.0.0.1:3080/?token=" + token);
        check("parse: dsh web URL is accepted", parsed.ok, parsed.reason);
        check("parse: remotePort is 3080", parsed.remotePort == 3080, "got " + parsed.remotePort);
        check("parse: token is kept verbatim", token.equals(parsed.token), "got " + parsed.token);
        check("parse: success has no reason", parsed.reason.isEmpty(), "got " + parsed.reason);
    }

    private static void parsesLanCredentialShape() {
        CredentialUrl.Parsed parsed = CredentialUrl.parse("http://10.0.0.5:3081/?token=abc123");
        check("parse: LAN-shaped credential accepted", parsed.ok, parsed.reason);
        check("parse: LAN port kept", parsed.remotePort == 3081, "got " + parsed.remotePort);
        check("parse: LAN token kept", "abc123".equals(parsed.token), "got " + parsed.token);
    }

    private static void defaultsHttpPortTo80() {
        CredentialUrl.Parsed parsed = CredentialUrl.parse("http://example.com/?token=abc");
        check("parse: http default port 80", parsed.ok && parsed.remotePort == 80,
                "ok=" + parsed.ok + " port=" + parsed.remotePort);
    }

    private static void defaultsHttpsPortTo443() {
        CredentialUrl.Parsed parsed = CredentialUrl.parse("https://example.com/?token=abc");
        check("parse: https default port 443", parsed.ok && parsed.remotePort == 443,
                "ok=" + parsed.ok + " port=" + parsed.remotePort);
    }

    private static void keepsExplicitHttpsPort() {
        CredentialUrl.Parsed parsed = CredentialUrl.parse("https://example.com:9443/?token=abc");
        check("parse: explicit https port kept", parsed.ok && parsed.remotePort == 9443,
                "ok=" + parsed.ok + " port=" + parsed.remotePort);
    }

    private static void acceptsExtraQueryParameters() {
        CredentialUrl.Parsed parsed = CredentialUrl.parse("http://127.0.0.1:3080/?foo=bar&token=abc123&baz=1");
        check("parse: token found among other query params",
                parsed.ok && "abc123".equals(parsed.token),
                "ok=" + parsed.ok + " token=" + parsed.token);
    }

    private static void decodesPercentEncodedToken() {
        CredentialUrl.Parsed parsed = CredentialUrl.parse("http://127.0.0.1:3080/?token=a%2Bb%20c%3D");
        check("parse: percent-encoded token is decoded",
                parsed.ok && "a+b c=".equals(parsed.token),
                "ok=" + parsed.ok + " token=" + parsed.token);
    }

    private static void trimsSurroundingWhitespace() {
        CredentialUrl.Parsed parsed = CredentialUrl.parse("  http://127.0.0.1:3080/?token=abc  ");
        check("parse: leading/trailing whitespace tolerated", parsed.ok, parsed.reason);
    }

    private static void acceptsUppercaseScheme() {
        CredentialUrl.Parsed parsed = CredentialUrl.parse("HTTP://127.0.0.1:3080/?token=abc");
        check("parse: scheme is case-insensitive", parsed.ok && parsed.remotePort == 3080,
                "ok=" + parsed.ok + " reason=" + parsed.reason);
    }

    private static void rejectsMissingToken() {
        CredentialUrl.Parsed parsed = CredentialUrl.parse("http://127.0.0.1:3080/");
        check("parse: URL without token is rejected", !parsed.ok, "unexpected ok");
        check("parse: missing-token reason mentions token",
                parsed.reason.toLowerCase().contains("token"), "reason=" + parsed.reason);
    }

    private static void rejectsEmptyToken() {
        CredentialUrl.Parsed parsed = CredentialUrl.parse("http://127.0.0.1:3080/?token=");
        check("parse: empty token is rejected", !parsed.ok, "unexpected ok");
    }

    private static void rejectsEmptyAndMalformedInput() {
        check("parse: null rejected", !CredentialUrl.parse(null).ok, "unexpected ok");
        check("parse: empty rejected", !CredentialUrl.parse("").ok, "unexpected ok");
        check("parse: blank rejected", !CredentialUrl.parse("   ").ok, "unexpected ok");
        check("parse: non-URL rejected", !CredentialUrl.parse("not a url").ok, "unexpected ok");
        check("parse: out-of-range port rejected",
                !CredentialUrl.parse("http://127.0.0.1:70000/?token=x").ok, "unexpected ok");
    }

    private static void rejectsNonHttpSchemes() {
        String secret = "SUPERSECRET123";
        CredentialUrl.Parsed ftp = CredentialUrl.parse("ftp://h:21/?token=" + secret);
        check("parse: ftp scheme rejected", !ftp.ok, "unexpected ok");
        check("parse: protocol reason is user-facing", ftp.reason.contains("http"), "reason=" + ftp.reason);
    }

    private static void reasonNeverEchoesTheToken() {
        String secret = "SUPERSECRET123";
        String[] rejectedInputs = {
                "ftp://h:21/?token=" + secret,
                "not a url " + secret,
                "http://h:70000/?token=" + secret,
        };
        for (String input : rejectedInputs) {
            CredentialUrl.Parsed parsed = CredentialUrl.parse(input);
            boolean safe = !parsed.ok && !parsed.reason.contains(secret);
            check("parse: rejection reason never echoes the token", safe, "reason=" + parsed.reason);
        }
    }

    // -----------------------------------------------------------------------
    // browserUrlFor()
    // -----------------------------------------------------------------------

    private static void browserUrlMatchesDesktopShape() {
        String token = "TESTTOKEN0000000000000000000000000000000000";
        String url = CredentialUrl.browserUrlFor(34567, token);
        check("browserUrlFor: exact loopback URL shape",
                ("http://127.0.0.1:34567/?token=" + token).equals(url), "got " + url);
    }

    private static void browserUrlPercentEncodesToken() {
        String token = "a b/c?d&e=f#g";
        String url = CredentialUrl.browserUrlFor(20000, token);
        check("browserUrlFor: token is percent-encoded",
                url.endsWith("?token=a%20b%2Fc%3Fd%26e%3Df%23g"), "got " + url);
        check("browserUrlFor: raw token never appears in the URL",
                !url.contains("a b/c?d&e=f#g"), "got " + url);
        check("browserUrlFor: URL still starts with loopback base",
                url.startsWith("http://127.0.0.1:20000/?token="), "got " + url);
    }

    private static void browserUrlRejectsInvalidLocalPort() {
        boolean threwZero = false;
        boolean threwHigh = false;
        try {
            CredentialUrl.browserUrlFor(0, "x");
        } catch (IllegalArgumentException expected) {
            threwZero = true;
        }
        try {
            CredentialUrl.browserUrlFor(70000, "x");
        } catch (IllegalArgumentException expected) {
            threwHigh = true;
        }
        check("browserUrlFor: port 0 rejected", threwZero, "no IllegalArgumentException");
        check("browserUrlFor: port > 65535 rejected", threwHigh, "no IllegalArgumentException");
    }

    private static void plainUrlMatchesCleanShape() {
        String url = CredentialUrl.plainUrlFor(34567);
        check("plainUrlFor: exact loopback URL shape",
                "http://127.0.0.1:34567/".equals(url), "got " + url);
        check("plainUrlFor: no query string and no token",
                !url.contains("?") && !url.contains("token"), "got " + url);
        check("plainUrlFor: another port keeps the shape",
                "http://127.0.0.1:20000/".equals(CredentialUrl.plainUrlFor(20000)),
                "got " + CredentialUrl.plainUrlFor(20000));
    }

    private static void plainUrlRejectsInvalidLocalPort() {
        boolean threwZero = false;
        boolean threwHigh = false;
        try {
            CredentialUrl.plainUrlFor(0);
        } catch (IllegalArgumentException expected) {
            threwZero = true;
        }
        try {
            CredentialUrl.plainUrlFor(70000);
        } catch (IllegalArgumentException expected) {
            threwHigh = true;
        }
        check("plainUrlFor: port 0 rejected", threwZero, "no IllegalArgumentException");
        check("plainUrlFor: port > 65535 rejected", threwHigh, "no IllegalArgumentException");
    }

    // -----------------------------------------------------------------------
    // maskToken()
    // -----------------------------------------------------------------------

    private static void maskTokenNeverRevealsTheToken() {
        String token = "TESTTOKEN0000000000000000000000000000000000";
        String masked = CredentialUrl.maskToken(token);
        check("maskToken: full token absent", !masked.contains(token), "masked=" + masked);
        check("maskToken: token differs from input", !masked.equals(token), "masked=" + masked);
        check("maskToken: shorter than the token", masked.length() < token.length(), "masked=" + masked);
        check("maskToken: keeps a 3-char prefix", masked.startsWith("TES"), "masked=" + masked);
        check("maskToken: keeps a 3-char suffix", masked.endsWith("000"), "masked=" + masked);
        check("maskToken: marks the elision", masked.contains("\u2026") || masked.contains("..."),
                "masked=" + masked);
    }

    private static void maskTokenShortAndEmptyValues() {
        check("maskToken: empty stays empty", "".equals(CredentialUrl.maskToken("")),
                "got " + CredentialUrl.maskToken(""));
        check("maskToken: null stays empty", "".equals(CredentialUrl.maskToken(null)),
                "got " + CredentialUrl.maskToken(null));
        check("maskToken: 5 chars is fully masked", "******".equals(CredentialUrl.maskToken("abcde")),
                "got " + CredentialUrl.maskToken("abcde"));
        check("maskToken: 6 chars is fully masked", "******".equals(CredentialUrl.maskToken("abcdef")),
                "got " + CredentialUrl.maskToken("abcdef"));
        String seven = CredentialUrl.maskToken("abcdefg");
        check("maskToken: 7 chars keeps 3+3",
                seven.startsWith("abc") && seven.endsWith("efg") && !seven.equals("abcdefg"),
                "got " + seven);
    }

    // -----------------------------------------------------------------------
    // isValidDirectUrl()
    // -----------------------------------------------------------------------

    private static void isValidDirectUrlAcceptsHttpAndHttps() {
        check("isValidDirectUrl: http URL", CredentialUrl.isValidDirectUrl("http://example.com"));
        check("isValidDirectUrl: https URL with path",
                CredentialUrl.isValidDirectUrl("https://example.com/some/path?x=1"));
        check("isValidDirectUrl: host:port", CredentialUrl.isValidDirectUrl("http://192.0.2.10:8080/"));
        check("isValidDirectUrl: uppercase scheme", CredentialUrl.isValidDirectUrl("HTTPS://example.com/"));
    }

    private static void isValidDirectUrlRejectsJunk() {
        check("isValidDirectUrl: empty rejected", !CredentialUrl.isValidDirectUrl(""));
        check("isValidDirectUrl: null rejected", !CredentialUrl.isValidDirectUrl(null));
        check("isValidDirectUrl: blank rejected", !CredentialUrl.isValidDirectUrl("  "));
        check("isValidDirectUrl: non-URL rejected", !CredentialUrl.isValidDirectUrl("not a url"));
        check("isValidDirectUrl: ftp rejected", !CredentialUrl.isValidDirectUrl("ftp://example.com/"));
        check("isValidDirectUrl: missing host rejected", !CredentialUrl.isValidDirectUrl("http:///just/a/path"));
    }
}

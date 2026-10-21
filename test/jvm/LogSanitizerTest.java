import dev.dsh.remotehosts.core.AppError;
import dev.dsh.remotehosts.core.ErrorKind;
import dev.dsh.remotehosts.core.HostConfig;
import dev.dsh.remotehosts.core.LogSanitizer;

/**
 * Unit tests for {@code core/LogSanitizer}.
 *
 * The security invariant from docs/app-spec.md §2/§5: tokens, passwords, private
 * keys and passphrases must never reach a log line, a notification text or an
 * exception message that is shown to the user. The sanitiser is the single exit
 * filter, so every test checks both that the secret is gone and that the
 * replacement marker is present.
 *
 * No JUnit: a plain main() keeps the offline toolchain dependency-free.
 */
public final class LogSanitizerTest {

    private static final String SECRET = "TESTTOKEN0000000000000000000000000000000000";
    private static final String OTHER_SECRET = "QQ7x1GAXn0DJkD5ycdKdH5QY8hABacDkP7XXrMtDZZZ";
    private static final String MARKER = "[redacted]";

    private static int checks;
    private static int failures;

    private LogSanitizerTest() {
    }

    public static void main(String[] args) {
        replacesSingleSecret();
        replacesEverySecretEverywhere();
        scrubsPercentEncodedForm();
        neutralTextIsUnchanged();
        nullAndEmptyInputs();
        residualQueryParameterForms();
        appErrorOverload();
        throwableOverload();
        secretsOfHost();
        multiLineMessage();

        System.out.println();
        System.out.println("LogSanitizerTest: " + (checks - failures) + "/" + checks + " checks passed");
        if (failures > 0) {
            System.out.println("FAILED (" + failures + " check(s))");
            System.exit(1);
        }
        System.out.println("PASSED");
    }

    // -----------------------------------------------------------------------
    // 1. plain replacement
    // -----------------------------------------------------------------------

    private static void replacesSingleSecret() {
        String message = "Auth failure while using token " + SECRET + " on host alpha";
        String clean = LogSanitizer.sanitize(message, SECRET);
        check("plain: secret removed", !clean.contains(SECRET), "clean=" + clean);
        check("plain: redaction marker present", clean.contains(MARKER), "clean=" + clean);
        check("plain: surrounding context preserved",
                clean.contains("Auth failure") && clean.contains("host alpha"), "clean=" + clean);
    }

    private static void replacesEverySecretEverywhere() {
        String message = "first=" + SECRET + "; second=" + OTHER_SECRET + "; again=" + SECRET;
        String clean = LogSanitizer.sanitize(message, SECRET, OTHER_SECRET);
        check("multiple: first secret removed", !clean.contains(SECRET), "clean=" + clean);
        check("multiple: second secret removed", !clean.contains(OTHER_SECRET), "clean=" + clean);
        check("multiple: both occurrences of the repeated secret removed",
                clean.indexOf(SECRET) < 0, "clean=" + clean);
        check("multiple: marker repeats", countOccurrences(clean, MARKER) >= 3, "clean=" + clean);

        String adjacent = "prefix" + SECRET + SECRET + "suffix";
        String adjacentClean = LogSanitizer.sanitize(adjacent, SECRET);
        check("multiple: adjacent repeated secrets removed", !adjacentClean.contains(SECRET),
                "clean=" + adjacentClean);
    }

    private static void scrubsPercentEncodedForm() {
        // A token/password may appear URL-encoded when it was copied out of a
        // request line; the sanitiser must redact that form too.
        String secret = "a b/c+d";
        String encoded = "a%20b%2Fc%2Bd";
        String message = "GET /?token=" + encoded + " HTTP/1.1 (raw form " + secret + ")";
        String clean = LogSanitizer.sanitize(message, secret);
        check("percent-encoded: encoded form removed", !clean.contains(encoded), "clean=" + clean);
        check("percent-encoded: raw form removed", !clean.contains(secret), "clean=" + clean);
        check("percent-encoded: marker present", clean.contains(MARKER), "clean=" + clean);
    }

    // -----------------------------------------------------------------------
    // 2. no false positives / degenerate input
    // -----------------------------------------------------------------------

    private static void neutralTextIsUnchanged() {
        String message = "SSH connect to host.example.com:22 failed: connection refused";
        String clean = LogSanitizer.sanitize(message, SECRET, OTHER_SECRET);
        check("neutral: harmless message is returned unchanged", message.equals(clean),
                "clean=" + clean);

        String noSecrets = LogSanitizer.sanitize("plain message");
        check("neutral: no secrets -> unchanged", "plain message".equals(noSecrets),
                "clean=" + noSecrets);
    }

    private static void nullAndEmptyInputs() {
        String nullText = LogSanitizer.sanitize((String) null, SECRET);
        check("degenerate: null text becomes empty string",
                nullText != null && nullText.isEmpty(), "got " + nullText);

        String blankText = LogSanitizer.sanitize("   ", SECRET);
        check("degenerate: blank text preserved", "   ".equals(blankText), "got " + blankText);

        String withNullSecret = LogSanitizer.sanitize("keep me " + SECRET, null, "", SECRET);
        boolean ok = withNullSecret.contains("keep me") && !withNullSecret.contains(SECRET);
        check("degenerate: null/empty secrets are skipped", ok, "clean=" + withNullSecret);
    }

    private static void residualQueryParameterForms() {
        // The token may be unknown to the sanitiser (e.g. a raw HTTP client line),
        // but its well-known query-parameter shape must still be redacted.
        String tokenValue = "abcdef0123456789";
        String tokenLine = "GET /?token=" + tokenValue + " HTTP/1.1";
        String cleanToken = LogSanitizer.sanitize(tokenLine);
        check("residual: unknown token= value is redacted",
                !cleanToken.contains(tokenValue) && cleanToken.contains(MARKER), "clean=" + cleanToken);

        String passwordValue = "hunter2";
        String passwordLine = "login with password=" + passwordValue + " done";
        String cleanPassword = LogSanitizer.sanitize(passwordLine);
        check("residual: unknown password= value is redacted",
                !cleanPassword.contains(passwordValue), "clean=" + cleanPassword);
    }

    // -----------------------------------------------------------------------
    // 3. AppError / Throwable outlets
    // -----------------------------------------------------------------------

    private static void appErrorOverload() {
        AppError error = AppError.unreachable("forward failed for token " + SECRET);
        AppError clean = LogSanitizer.sanitizeError(error, SECRET);
        check("AppError: kind preserved", clean != null && clean.kind == ErrorKind.UNREACHABLE,
                "clean=" + clean);
        if (clean != null) {
            check("AppError: message no longer contains the secret", !clean.getMessage().contains(SECRET),
                    "message=" + clean.getMessage());
            check("AppError: message carries the marker", clean.getMessage().contains(MARKER),
                    "message=" + clean.getMessage());
        }

        AppError nullError = LogSanitizer.sanitizeError((AppError) null, SECRET);
        check("AppError: null error tolerated", nullError == null, "got " + nullError);
    }

    private static void throwableOverload() {
        Throwable failure = new RuntimeException("auth failed: token=" + SECRET + " password=" + OTHER_SECRET);
        String clean = LogSanitizer.sanitizeThrowable(failure, SECRET, OTHER_SECRET);
        check("Throwable: token gone from exception text", !clean.contains(SECRET), "clean=" + clean);
        check("Throwable: password gone from exception text", !clean.contains(OTHER_SECRET),
                "clean=" + clean);
        check("Throwable: marker present", clean.contains(MARKER), "clean=" + clean);

        String nullClean = LogSanitizer.sanitizeThrowable((Throwable) null, SECRET);
        check("Throwable: null throwable becomes empty string",
                nullClean != null && nullClean.isEmpty(), "got " + nullClean);
    }

    private static void secretsOfHost() {
        HostConfig host = new HostConfig();
        host.alias = "alpha";
        host.token = SECRET;
        host.password = "pw-" + OTHER_SECRET;
        host.privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nkey-body\n-----END OPENSSH PRIVATE KEY-----";
        host.keyPassphrase = "pass-" + OTHER_SECRET;
        String[] secrets = LogSanitizer.secretsOf(host);
        check("secretsOf: all four secret fields collected", secrets != null && secrets.length == 4,
                "count=" + (secrets == null ? null : secrets.length));
        String joined = "";
        if (secrets != null) {
            for (String secret : secrets) joined += secret + "\n";
        }
        check("secretsOf: token included", joined.contains(SECRET), "secrets=" + secrets.length);
        check("secretsOf: password included", joined.contains("pw-" + OTHER_SECRET), "missing password");
        check("secretsOf: private key included", joined.contains("BEGIN OPENSSH PRIVATE KEY"),
                "missing privateKey");
        check("secretsOf: passphrase included", joined.contains("pass-" + OTHER_SECRET), "missing passphrase");

        HostConfig empty = new HostConfig();
        empty.alias = "empty";
        String[] none = LogSanitizer.secretsOf(empty);
        check("secretsOf: host without secrets yields an empty array",
                none != null && none.length == 0, "count=" + (none == null ? null : none.length));
        String[] nullHost = LogSanitizer.secretsOf(null);
        check("secretsOf: null host yields an empty array",
                nullHost != null && nullHost.length == 0, "count=" + (nullHost == null ? null : nullHost.length));

        String message = "auth attempt for alpha with " + host.token + " and " + host.keyPassphrase;
        String clean = LogSanitizer.sanitize(message, LogSanitizer.secretsOf(host));
        check("secretsOf: feeding the array into sanitize scrubs every field",
                !clean.contains(SECRET) && !clean.contains("pass-" + OTHER_SECRET), "clean=" + clean);
    }

    private static void multiLineMessage() {
        String message = "line one token=" + SECRET + "\nline two key=" + OTHER_SECRET + "\nline three";
        String clean = LogSanitizer.sanitize(message, SECRET, OTHER_SECRET);
        check("multi-line: no secret on any line",
                !clean.contains(SECRET) && !clean.contains(OTHER_SECRET), "clean=" + clean);
        check("multi-line: line structure preserved",
                clean.contains("line one") && clean.contains("line two") && clean.contains("line three"),
                "clean=" + clean);
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while (true) {
            index = haystack.indexOf(needle, index);
            if (index < 0) return count;
            count += 1;
            index += needle.length();
        }
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

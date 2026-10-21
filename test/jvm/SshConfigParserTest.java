import dev.dsh.remotehosts.core.HostConfig;
import dev.dsh.remotehosts.core.SshConfigParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for {@code core/SshConfigParser}.
 *
 * The semantics are ported from the desktop plugin's
 * {@code remote-hosts/lib/ssh-config.mjs} and docs/app-spec.md §3:
 *  - braces-lite OpenSSH config subset: {@code keyword value} and {@code keyword=value},
 *    case-insensitive keywords, inline {@code #} comments outside quotes;
 *  - a Host line carries multiple patterns, globbed with {@code *}/{@code ?} and
 *    matched case-insensitively; a leading {@code !} negates;
 *  - FIRST value wins per keyword across the whole flattened file (order matters,
 *    a global default written before a Host block wins too, as in OpenSSH);
 *  - only literal aliases are listed, in file order, de-duplicated;
 *  - HostName / User / Port / ProxyJump / IdentityFile are resolved;
 *  - Match blocks stop attributing directives to the preceding Host;
 *  - Include is a warning for single-text parses and is expanded in place when
 *    the caller supplies a virtual file map.
 *
 * No JUnit: a plain main() keeps the offline toolchain dependency-free.
 */
public final class SshConfigParserTest {

    private static int checks;
    private static int failures;

    private SshConfigParserTest() {
    }

    public static void main(String[] args) {
        basics();
        firstMatchWins();
        patternMatching();
        syntaxVariants();
        matchBlocks();
        includeSingleFile();
        includeVirtualFiles();
        identityFiles();
        proxyJump();
        degenerateInputs();

        System.out.println();
        System.out.println("SshConfigParserTest: " + (checks - failures) + "/" + checks + " checks passed");
        if (failures > 0) {
            System.out.println("FAILED (" + failures + " check(s))");
            System.exit(1);
        }
        System.out.println("PASSED");
    }

    // -----------------------------------------------------------------------
    // 1. basics
    // -----------------------------------------------------------------------

    private static void basics() {
        SshConfigParser.ParseResult parsed = SshConfigParser.parse(lines(
                "# a comment line",
                "Host alpha",
                "  HostName a.example.com",
                "  User alice",
                "  Port 2222",
                ""));
        checkCount("basic: one literal host", parsed.hosts.size() == 1, "hosts=" + parsed.hosts.size());
        HostConfig alpha = find(parsed, "alpha");
        check("basic: alias kept", alpha != null, "alpha missing");
        if (alpha != null) {
            check("basic: HostName parsed", "a.example.com".equals(alpha.hostName), "got " + alpha.hostName);
            check("basic: User parsed", "alice".equals(alpha.user), "got " + alpha.user);
            check("basic: Port parsed", alpha.port == 2222, "got " + alpha.port);
        }

        SshConfigParser.ParseResult noHostName = SshConfigParser.parse(lines("Host beta"));
        HostConfig beta = find(noHostName, "beta");
        check("basic: host without HostName still listed", beta != null, "beta missing");
        if (beta != null) {
            check("basic: HostName falls back to alias",
                    "beta".equals(beta.resolvedHostName()), "got " + beta.resolvedHostName());
            check("basic: port defaults to 22", beta.port == 22, "got " + beta.port);
        }
        check("basic: no warnings for clean config", noHostName.warnings.isEmpty(),
                "warnings=" + noHostName.warnings);
    }

    // -----------------------------------------------------------------------
    // 2. first-match-wins
    // -----------------------------------------------------------------------

    private static void firstMatchWins() {
        SshConfigParser.ParseResult duplicate = SshConfigParser.parse(lines(
                "Host gamma",
                "  User first",
                "Host gamma",
                "  User second"));
        checkCount("first-match-wins: duplicate alias listed once", duplicate.hosts.size() == 1,
                "hosts=" + aliasList(duplicate));
        HostConfig gamma = find(duplicate, "gamma");
        check("first-match-wins: first User wins",
                gamma != null && "first".equals(gamma.user), "got " + (gamma == null ? null : gamma.user));

        SshConfigParser.ParseResult globalBefore = SshConfigParser.parse(lines(
                "User globalUser",
                "Host alpha",
                "  User hostUser"));
        HostConfig alpha = find(globalBefore, "alpha");
        check("first-match-wins: global default before Host wins",
                alpha != null && "globalUser".equals(alpha.user),
                "got " + (alpha == null ? null : alpha.user));

        SshConfigParser.ParseResult hostBefore = SshConfigParser.parse(lines(
                "Host alpha",
                "  User hostUser",
                "User globalUser"));
        HostConfig hostAlpha = find(hostBefore, "alpha");
        check("first-match-wins: Host value before global wins",
                hostAlpha != null && "hostUser".equals(hostAlpha.user),
                "got " + (hostAlpha == null ? null : hostAlpha.user));

        SshConfigParser.ParseResult portTwice = SshConfigParser.parse(lines(
                "Host alpha",
                "  Port 2201",
                "Host alpha",
                "  Port 2202"));
        HostConfig portAlpha = find(portTwice, "alpha");
        check("first-match-wins: first Port wins",
                portAlpha != null && portAlpha.port == 2201,
                "got " + (portAlpha == null ? null : portAlpha.port));

        SshConfigParser.ParseResult globalDefaults = SshConfigParser.parse(lines(
                "Port 2200",
                "Host alpha",
                "Host beta"));
        check("first-match-wins: global defaults before the first Host apply to every host",
                find(globalDefaults, "alpha") != null && find(globalDefaults, "alpha").port == 2200
                        && find(globalDefaults, "beta") != null && find(globalDefaults, "beta").port == 2200,
                "hosts=" + aliasList(globalDefaults));

        SshConfigParser.ParseResult scoped = SshConfigParser.parse(lines(
                "Host alpha",
                "Host beta",
                "Port 2200"));
        HostConfig scopedAlpha = find(scoped, "alpha");
        HostConfig scopedBeta = find(scoped, "beta");
        check("first-match-wins: a directive after a Host line stays scoped to that Host",
                scopedAlpha != null && scopedAlpha.port == 22
                        && scopedBeta != null && scopedBeta.port == 2200,
                "alpha.port=" + (scopedAlpha == null ? null : scopedAlpha.port)
                        + " beta.port=" + (scopedBeta == null ? null : scopedBeta.port));
    }

    // -----------------------------------------------------------------------
    // 3. pattern matching / literal alias listing
    // -----------------------------------------------------------------------

    private static void patternMatching() {
        SshConfigParser.ParseResult multi = SshConfigParser.parse(lines(
                "Host alpha beta",
                "  HostName shared.example.com",
                "  User shared"));
        checkCount("patterns: multi-pattern Host lists both aliases", multi.hosts.size() == 2,
                "hosts=" + aliasList(multi));
        check("patterns: multi-pattern order is file order",
                aliasList(multi).equals(list("alpha", "beta")), "hosts=" + aliasList(multi));
        HostConfig beta = find(multi, "beta");
        check("patterns: every alias of the block inherits HostName",
                beta != null && "shared.example.com".equals(beta.hostName),
                "got " + (beta == null ? null : beta.hostName));

        SshConfigParser.ParseResult wildcards = SshConfigParser.parse(lines(
                "Host *.example.com",
                "  User globbed",
                "Host web?",
                "  User asked",
                "Host real",
                "  HostName real.example.com",
                "Host !negated literalMixed",
                "  User mixed",
                "Host *",
                "  User everyone"));
        check("patterns: wildcard patterns are not listed as hosts",
                aliasList(wildcards).equals(list("real", "literalMixed")),
                "hosts=" + aliasList(wildcards));
        HostConfig real = find(wildcards, "real");
        check("patterns: wildcard block is not applied to a non-matching alias",
                real != null && !"globbed".equals(real.user), "user=" + (real == null ? null : real.user));

        SshConfigParser.ParseResult negated = SshConfigParser.parse(lines(
                "Host alpha",
                "  User alice",
                "Host * !alpha",
                "  User wildcardDefault",
                "Host beta",
                "Host gamma",
                "  User gammaUser"));
        check("patterns: negated wildcard block applies when alias is not negated",
                find(negated, "beta") != null && "wildcardDefault".equals(find(negated, "beta").user),
                "beta.user=" + (find(negated, "beta") == null ? null : find(negated, "beta").user));
        check("patterns: negated pattern excludes the alias",
                find(negated, "alpha") != null && "alice".equals(find(negated, "alpha").user),
                "alpha.user=" + (find(negated, "alpha") == null ? null : find(negated, "alpha").user));
        check("patterns: wildcard block wins over a later Host block (first-match-wins)",
                find(negated, "gamma") != null && "wildcardDefault".equals(find(negated, "gamma").user),
                "gamma.user=" + (find(negated, "gamma") == null ? null : find(negated, "gamma").user));

        SshConfigParser.ParseResult excluded = SshConfigParser.parse(lines(
                "Host * !alpha",
                "  Port 2299",
                "Host alpha",
                "Host beta"));
        HostConfig excludedAlpha = find(excluded, "alpha");
        HostConfig excludedBeta = find(excluded, "beta");
        check("patterns: negated pattern removes the alias from the wildcard block",
                excludedAlpha != null && excludedAlpha.port == 22,
                "alpha.port=" + (excludedAlpha == null ? null : excludedAlpha.port));
        check("patterns: wildcard block still applies to the other aliases",
                excludedBeta != null && excludedBeta.port == 2299,
                "beta.port=" + (excludedBeta == null ? null : excludedBeta.port));

        SshConfigParser.ParseResult globCase = SshConfigParser.parse(lines(
                "Host beta",
                "  HostName b.example.com",
                "Host BE?A",
                "  User upperCasePattern"));
        HostConfig betaGlob = find(globCase, "beta");
        check("patterns: glob matching is case-insensitive",
                betaGlob != null && "upperCasePattern".equals(betaGlob.user),
                "user=" + (betaGlob == null ? null : betaGlob.user));
        check("patterns: wildcard pattern itself is not listed",
                aliasList(globCase).equals(list("beta")), "hosts=" + aliasList(globCase));

        SshConfigParser.ParseResult anchored = SshConfigParser.parse(lines(
                "Host alpha",
                "  User alice",
                "Host alphabet"));
        HostConfig alphabet = find(anchored, "alphabet");
        check("patterns: literal match is anchored (no prefix match)",
                alphabet != null && !"alice".equals(alphabet.user),
                "alphabet.user=" + (alphabet == null ? null : alphabet.user));

        SshConfigParser.ParseResult dedupe = SshConfigParser.parse(lines(
                "Host alpha beta alpha",
                "  HostName shared.example.com"));
        check("patterns: alias list is de-duplicated in order",
                aliasList(dedupe).equals(list("alpha", "beta")), "hosts=" + aliasList(dedupe));
    }

    // -----------------------------------------------------------------------
    // 4. syntax variants
    // -----------------------------------------------------------------------

    private static void syntaxVariants() {
        SshConfigParser.ParseResult equalsForm = SshConfigParser.parse(lines(
                "Host alpha",
                "  HostName=a.example.com",
                "  User=alice",
                "  Port=2222"));
        HostConfig alpha = find(equalsForm, "alpha");
        check("syntax: keyword=value form accepted",
                alpha != null && "a.example.com".equals(alpha.hostName)
                        && "alice".equals(alpha.user) && alpha.port == 2222,
                "host=" + alpha);

        SshConfigParser.ParseResult comments = SshConfigParser.parse(lines(
                "# whole-line comment",
                "Host alpha   # trailing comment after a pattern",
                "  User alice # trailing comment",
                "  # indented comment",
                "  HostName a.example.com#no-space-before-hash"));
        HostConfig commented = find(comments, "alpha");
        check("syntax: inline comments are stripped outside quotes",
                commented != null && "alice".equals(commented.user),
                "user=" + (commented == null ? null : commented.user));
        check("syntax: comment cut does not glue neighbour chars",
                commented != null && "a.example.com".equals(commented.hostName),
                "HostName=" + (commented == null ? null : commented.hostName));

        SshConfigParser.ParseResult quoted = SshConfigParser.parse(lines(
                "Host \"alpha\"",
                "  User \"alice\"",
                "  HostName \"a.example.com\""));
        HostConfig quotedAlpha = find(quoted, "alpha");
        check("syntax: quoted values are unquoted",
                quotedAlpha != null && "alice".equals(quotedAlpha.user)
                        && "a.example.com".equals(quotedAlpha.hostName),
                "host=" + quotedAlpha);

        SshConfigParser.ParseResult hashInQuotes = SshConfigParser.parse(lines(
                "Host alpha",
                "  User \"al#ice\""));
        HostConfig hashAlpha = find(hashInQuotes, "alpha");
        check("syntax: # inside quotes is not a comment",
                hashAlpha != null && "al#ice".equals(hashAlpha.user),
                "user=" + (hashAlpha == null ? null : hashAlpha.user));

        SshConfigParser.ParseResult keywordCase = SshConfigParser.parse(lines(
                "HOST alpha",
                "  HOSTNAME a.example.com",
                "  USER alice",
                "  PORT 2222"));
        HostConfig caseAlpha = find(keywordCase, "alpha");
        check("syntax: keywords are case-insensitive",
                caseAlpha != null && "a.example.com".equals(caseAlpha.hostName)
                        && "alice".equals(caseAlpha.user) && caseAlpha.port == 2222,
                "host=" + caseAlpha);

        SshConfigParser.ParseResult tabs = SshConfigParser.parse("Host\talpha\n\tHostName\ta.example.com\n\tUser\talice\n");
        HostConfig tabAlpha = find(tabs, "alpha");
        check("syntax: tabs separate keyword and value",
                tabAlpha != null && "a.example.com".equals(tabAlpha.hostName)
                        && "alice".equals(tabAlpha.user),
                "host=" + tabAlpha);
    }

    // -----------------------------------------------------------------------
    // 5. Match blocks
    // -----------------------------------------------------------------------

    private static void matchBlocks() {
        SshConfigParser.ParseResult parsed = SshConfigParser.parse(lines(
                "Host alpha",
                "  User alice",
                "Match host foo",
                "  User bob",
                "  Port 2299",
                "Host beta",
                "  HostName b.example.com",
                "  User bob"));
        HostConfig alpha = find(parsed, "alpha");
        HostConfig beta = find(parsed, "beta");
        check("match: directives inside Match do not leak into the previous Host",
                alpha != null && "alice".equals(alpha.user) && alpha.port == 22,
                "alpha.user=" + (alpha == null ? null : alpha.user) + " port=" + (alpha == null ? null : alpha.port));
        check("match: a Host block after Match is parsed normally",
                beta != null && "b.example.com".equals(beta.hostName) && "bob".equals(beta.user),
                "beta=" + beta);
        check("match: Match does not invent a host alias",
                aliasList(parsed).equals(list("alpha", "beta")), "hosts=" + aliasList(parsed));
    }

    // -----------------------------------------------------------------------
    // 6. Include, single text
    // -----------------------------------------------------------------------

    private static void includeSingleFile() {
        SshConfigParser.ParseResult parsed = SshConfigParser.parse(lines(
                "Host alpha",
                "  HostName a.example.com",
                "Include conf.d/work",
                "Include ~/.ssh/extra"));
        checkCount("include-single: literal hosts before Include are parsed", parsed.hosts.size() == 1,
                "hosts=" + aliasList(parsed));
        check("include-single: Include does not silently add hosts", find(parsed, "work") == null,
                "hosts=" + aliasList(parsed));
        String warnings = join(parsed.warnings);
        check("include-single: Include produces a warning",
                !parsed.warnings.isEmpty(), "warnings=" + parsed.warnings);
        check("include-single: warning names the included target",
                warnings.contains("conf.d/work"), "warnings=" + parsed.warnings);
        check("include-single: warning names every included target",
                warnings.contains("extra"), "warnings=" + parsed.warnings);
    }

    // -----------------------------------------------------------------------
    // 7. Include, virtual file map
    // -----------------------------------------------------------------------

    private static void includeVirtualFiles() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("config", lines(
                "Host alpha",
                "  HostName a.example.com",
                "Include conf.d/extra",
                "Host omega",
                "  HostName o.example.com"));
        files.put("conf.d/extra", lines(
                "Host beta",
                "  HostName b.example.com",
                "  User bob"));

        SshConfigParser.ParseResult parsed = SshConfigParser.parse(files);
        check("include-map: hosts from main file and include are merged in place",
                aliasList(parsed).equals(list("alpha", "beta", "omega")), "hosts=" + aliasList(parsed));
        HostConfig beta = find(parsed, "beta");
        check("include-map: included host fields are resolved",
                beta != null && "b.example.com".equals(beta.hostName) && "bob".equals(beta.user),
                "beta=" + beta);
        check("include-map: resolvable Include raises no warning",
                join(parsed.warnings).indexOf("conf.d/extra") < 0, "warnings=" + parsed.warnings);

        Map<String, String> withChain = new LinkedHashMap<>();
        withChain.put("config", lines("Include conf.d/one", "Host root", "  User rootUser"));
        withChain.put("conf.d/one", lines("Include two", "Host one", "  User oneUser"));
        withChain.put("conf.d/two", lines("Host two", "  User twoUser"));
        SshConfigParser.ParseResult chained = SshConfigParser.parse(withChain);
        check("include-map: nested Include is expanded in place",
                aliasList(chained).equals(list("two", "one", "root")), "hosts=" + aliasList(chained));

        Map<String, String> missing = new LinkedHashMap<>();
        missing.put("config", lines("Host alpha", "Include missing.conf", "  User alice"));
        SshConfigParser.ParseResult missingParsed = SshConfigParser.parse(missing);
        check("include-map: missing include warns but keeps parsing",
                find(missingParsed, "alpha") != null
                        && join(missingParsed.warnings).contains("missing.conf"),
                "hosts=" + aliasList(missingParsed) + " warnings=" + missingParsed.warnings);

        Map<String, String> firstWins = new LinkedHashMap<>();
        firstWins.put("config", lines(
                "Host alpha",
                "  User beforeInclude",
                "Include conf.d/more"));
        firstWins.put("conf.d/more", lines(
                "Host alpha",
                "  User fromInclude"));
        SshConfigParser.ParseResult firstWinsParsed = SshConfigParser.parse(firstWins);
        HostConfig alpha = find(firstWinsParsed, "alpha");
        check("include-map: first-match-wins spans included files",
                alpha != null && "beforeInclude".equals(alpha.user),
                "user=" + (alpha == null ? null : alpha.user));

        Map<String, String> customEntry = new LinkedHashMap<>();
        customEntry.put("custom.conf", lines("Host custom", "  User customUser"));
        SshConfigParser.ParseResult customParsed = SshConfigParser.parse(customEntry, "custom.conf");
        check("include-map: entryName selects the entry file",
                find(customParsed, "custom") != null, "hosts=" + aliasList(customParsed));
    }

    // -----------------------------------------------------------------------
    // 8. IdentityFile
    // -----------------------------------------------------------------------

    private static void identityFiles() {
        SshConfigParser.ParseResult parsed = SshConfigParser.parse(lines(
                "Host alpha",
                "  IdentityFile ~/.ssh/id_ed25519",
                "  IdentityFile=/home/alice/.ssh/id_rsa"));
        HostConfig alpha = find(parsed, "alpha");
        boolean ok = alpha != null
                && alpha.identityFiles.size() == 2
                && alpha.identityFiles.get(0).contains("id_ed25519")
                && alpha.identityFiles.get(1).contains("id_rsa");
        check("identity: repeated IdentityFile entries are collected in order", ok,
                "identityFiles=" + (alpha == null ? null : alpha.identityFiles));

        SshConfigParser.ParseResult global = SshConfigParser.parse(lines(
                "IdentityFile ~/.ssh/id_rsa",
                "Host alpha",
                "Host beta"));
        boolean all = find(global, "alpha") != null && find(global, "alpha").identityFiles.size() == 1
                && find(global, "beta") != null && find(global, "beta").identityFiles.size() == 1;
        check("identity: global IdentityFile applies to every host", all, "hosts=" + aliasList(global));

        SshConfigParser.ParseResult none = SshConfigParser.parse(lines("Host alpha"));
        check("identity: no IdentityFile yields an empty list",
                find(none, "alpha") != null && find(none, "alpha").identityFiles.isEmpty(),
                "identityFiles=" + (find(none, "alpha") == null ? null : find(none, "alpha").identityFiles));
    }

    // -----------------------------------------------------------------------
    // 9. ProxyJump
    // -----------------------------------------------------------------------

    private static void proxyJump() {
        SshConfigParser.ParseResult alias = SshConfigParser.parse(lines(
                "Host gamma",
                "  ProxyJump bastion"));
        HostConfig gamma = find(alias, "gamma");
        check("proxyjump: bare alias is kept",
                gamma != null && "bastion".equals(gamma.proxyJump),
                "proxyJump=" + (gamma == null ? null : gamma.proxyJump));

        SshConfigParser.ParseResult full = SshConfigParser.parse(lines(
                "Host gamma",
                "  ProxyJump jumpUser@jump.example.com:2222"));
        HostConfig gammaFull = find(full, "gamma");
        check("proxyjump: user@host:port form is kept verbatim",
                gammaFull != null && "jumpUser@jump.example.com:2222".equals(gammaFull.proxyJump),
                "proxyJump=" + (gammaFull == null ? null : gammaFull.proxyJump));

        SshConfigParser.ParseResult direct = SshConfigParser.parse(lines("Host gamma"));
        check("proxyjump: absent ProxyJump is empty",
                find(direct, "gamma") != null && (find(direct, "gamma").proxyJump == null
                        || find(direct, "gamma").proxyJump.isEmpty()),
                "proxyJump=" + (find(direct, "gamma") == null ? null : find(direct, "gamma").proxyJump));
    }

    // -----------------------------------------------------------------------
    // 10. degenerate input
    // -----------------------------------------------------------------------

    private static void degenerateInputs() {
        SshConfigParser.ParseResult empty = SshConfigParser.parse("");
        check("degenerate: empty text yields no hosts/warnings",
                empty.hosts.isEmpty() && empty.warnings.isEmpty(),
                "hosts=" + empty.hosts + " warnings=" + empty.warnings);

        SshConfigParser.ParseResult commentsOnly = SshConfigParser.parse(lines(
                "# just a comment",
                "",
                "   ",
                "# another"));
        check("degenerate: comments-only text yields nothing", commentsOnly.hosts.isEmpty(),
                "hosts=" + aliasList(commentsOnly));

        SshConfigParser.ParseResult hostNoArgs = SshConfigParser.parse(lines(
                "Host",
                "  HostName nope.example.com",
                "Host real",
                "  HostName real.example.com"));
        check("degenerate: Host with no patterns matches nothing and lists nothing",
                aliasList(hostNoArgs).equals(list("real")), "hosts=" + aliasList(hostNoArgs));
        check("degenerate: directives after empty Host do not leak to next host",
                find(hostNoArgs, "real") != null && "real.example.com".equals(find(hostNoArgs, "real").hostName),
                "real=" + find(hostNoArgs, "real"));
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private static String lines(String... raw) {
        StringBuilder out = new StringBuilder();
        for (String line : raw) {
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static List<String> list(String... values) {
        List<String> out = new ArrayList<>();
        for (String value : values) out.add(value);
        return out;
    }

    private static List<String> aliasList(SshConfigParser.ParseResult parsed) {
        List<String> aliases = new ArrayList<>();
        for (HostConfig host : parsed.hosts) aliases.add(host.alias);
        return aliases;
    }

    private static HostConfig find(SshConfigParser.ParseResult parsed, String alias) {
        for (HostConfig host : parsed.hosts) {
            if (alias.equals(host.alias)) return host;
        }
        return null;
    }

    private static String join(List<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) out.append(value).append('\n');
        return out.toString();
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

    private static void checkCount(String name, boolean condition, String detail) {
        check(name, condition, detail);
    }
}

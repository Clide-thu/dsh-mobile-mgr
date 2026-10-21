package dev.dsh.remotehosts.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Minimal OpenSSH client-config reader, ported from the desktop plugin's
 * {@code lib/ssh-config.mjs}. Pure Java so it is unit-testable on a desktop JVM.
 *
 * Semantics that matter (all covered by test/jvm):
 *  - A {@code Host} line carries one or more patterns; the block applies when one
 *    positive pattern matches and no {@code !}-negated pattern does. {@code *}
 *    and {@code ?} glob, case-insensitively.
 *  - Within the merged configuration for one alias the FIRST value wins, so file
 *    order is significant ("later wins" is wrong). File-global directives (before
 *    any {@code Host}) are defaults and participate in the same first-wins order.
 *  - {@code Include} is expanded in place when file contents are supplied; a
 *    single-text parse reports the include targets as warnings (Android has no
 *    {@code ~/.ssh} tree to read).
 *  - {@code Match} blocks are not modelled: directives after a {@code Match} line
 *    are no longer attributed to the preceding {@code Host} block.
 *  - Only concrete aliases are listed: wildcard {@code Host} patterns are not
 *    addressable targets and are skipped.
 */
public final class SshConfigParser {

    /** Include nesting bound, mirroring the desktop implementation. */
    private static final int MAX_INCLUDE_DEPTH = 8;

    /** Name used for the single-text entry when reporting provenance. */
    private static final String VIRTUAL_SOURCE = "config";

    private SshConfigParser() {
    }

    /** Outcome of one parse: the usable hosts plus human-readable warnings. */
    public static final class ParseResult {
        public final List<HostConfig> hosts;
        public final List<String> warnings;

        public ParseResult(List<HostConfig> hosts, List<String> warnings) {
            this.hosts = Collections.unmodifiableList(new ArrayList<>(hosts));
            this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
        }

        public static ParseResult empty() {
            return new ParseResult(new ArrayList<HostConfig>(), new ArrayList<String>());
        }
    }

    /**
     * Parse one config file's text. {@code Include} directives cannot be followed
     * (no filesystem context) and are reported as warnings.
     */
    public static ParseResult parse(String configText) {
        List<Entry> entries = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        flattenText(VIRTUAL_SOURCE, configText, entries, warnings, false, null, 0, new LinkedHashSet<String>());
        return buildResult(entries, warnings, VIRTUAL_SOURCE);
    }

    /**
     * Parse a virtual ssh directory: keys are file names (for example
     * {@code "config"}, {@code "conf.d/work"}) and values are file contents.
     * The entry point is {@code "config"} unless {@code entryName} is given.
     *
     * {@code Include} is resolved against the map (relative paths resolve from
     * the including file's directory); a missing include target becomes a
     * warning instead of an exception.
     */
    public static ParseResult parse(Map<String, String> files, String entryName) {
        List<Entry> entries = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (files == null) {
            warnings.add("没有提供任何 SSH 配置内容");
            return buildResult(entries, warnings, VIRTUAL_SOURCE);
        }
        String entry = normalizePath(entryName == null || entryName.trim().isEmpty() ? VIRTUAL_SOURCE : entryName.trim());
        Set<String> visiting = new LinkedHashSet<>();
        flattenFile(entry, files, entries, warnings, 0, visiting);
        if (entries.isEmpty() && !files.containsKey(entry)) {
            warnings.add("配置内容为空，或找不到入口文件 " + entry);
        }
        return buildResult(entries, warnings, entry);
    }

    /** Convenience overload for {@link #parse(Map, String)} with the default entry file. */
    public static ParseResult parse(Map<String, String> files) {
        return parse(files, "config");
    }

    // ------------------------------------------------------------- flattening

    /** One directive, flattened in file order and tagged with its Host patterns. */
    private static final class Entry {
        /** null = file-global default (applies to every alias). */
        final List<String> patterns;
        /** Lower-case keyword. */
        final String key;
        final String value;
        final String source;

        Entry(List<String> patterns, String key, String value, String source) {
            this.patterns = patterns;
            this.key = key;
            this.value = value;
            this.source = source;
        }
    }

    private static void flattenFile(
            String path,
            Map<String, String> files,
            List<Entry> entries,
            List<String> warnings,
            int depth,
            Set<String> visiting) {
        String normalized = normalizePath(path);
        if (depth > MAX_INCLUDE_DEPTH) {
            warnings.add("Include 层级过深，已停止展开：" + normalized);
            return;
        }
        if (!visiting.add(normalized)) {
            warnings.add("Include 出现循环引用，已跳过：" + normalized);
            return;
        }
        try {
            String text = lookupFile(files, normalized);
            if (text == null) {
                warnings.add("缺少被 Include 的文件：" + normalized);
                return;
            }
            flattenText(normalized, text, entries, warnings, true, files, depth, visiting);
        } finally {
            visiting.remove(normalized);
        }
    }

    /**
     * @param expandIncludes when true, {@code Include} lines are followed through
     *                       {@code files}; when false they become warnings.
     */
    private static void flattenText(
            String source,
            String text,
            List<Entry> entries,
            List<String> warnings,
            boolean expandIncludes,
            Map<String, String> files,
            int depth,
            Set<String> visiting) {
        if (text == null) return;
        List<String> currentPatterns = null;
        // Directives inside a `Match` block are conditional on runtime facts we do
        // not model. They must NOT be treated as file-global defaults (that would
        // leak e.g. a Match-only User/Port onto every host): skip them entirely
        // until the next `Host` line.
        boolean inMatchBlock = false;
        for (String rawLine : text.split("\r?\n", -1)) {
            Directive directive = splitDirective(rawLine);
            if (directive == null) continue;
            String key = directive.key;
            String value = directive.value;
            if (key.equals("host")) {
                // `Host` with no arguments is invalid; an empty pattern list matches
                // nothing, which is what OpenSSH effectively does for such a line.
                currentPatterns = splitTokens(value);
                entries.add(new Entry(currentPatterns, "__block__", value, source));
                inMatchBlock = false;
                continue;
            }
            if (key.equals("match")) {
                currentPatterns = null;
                inMatchBlock = true;
                continue;
            }
            if (inMatchBlock) {
                if (key.equals("include")) {
                    warnings.add("Match 块内的 Include 无法确定条件，已跳过：" + value.trim());
                }
                continue;
            }
            if (key.equals("include")) {
                List<String> targets = splitTokens(value);
                if (expandIncludes && files != null) {
                    for (String target : targets) {
                        expandInclude(source, target, files, entries, warnings, depth, visiting);
                    }
                } else if (targets.isEmpty()) {
                    warnings.add("Include 指令没有目标，已忽略");
                } else {
                    warnings.add("Include 目标无法在手机上展开，请把其中的主机合并到一份配置里再导入："
                            + value.trim());
                }
                continue;
            }
            entries.add(new Entry(currentPatterns, key, value, source));
        }
    }

    private static void expandInclude(
            String includingFile,
            String target,
            Map<String, String> files,
            List<Entry> entries,
            List<String> warnings,
            int depth,
            Set<String> visiting) {
        if (target.isEmpty()) return;
        List<String> resolved = resolveInclude(includingFile, target);
        if (isGlob(target)) {
            List<String> matches = new ArrayList<>();
            for (String candidate : resolved) {
                matches.addAll(expandGlob(files.keySet(), candidate));
            }
            if (matches.isEmpty()) {
                warnings.add("Include 目标在导入内容里不存在，已跳过：" + target);
                return;
            }
            Set<String> ordered = new TreeSet<>(matches);
            for (String match : ordered) {
                flattenFile(match, files, entries, warnings, depth + 1, visiting);
            }
            return;
        }
        for (String candidate : resolved) {
            if (lookupFile(files, candidate) != null) {
                flattenFile(candidate, files, entries, warnings, depth + 1, visiting);
                return;
            }
        }
        warnings.add("Include 目标在导入内容里不存在，已跳过：" + target);
    }

    /** Candidate virtual paths for one include target, most specific first. */
    private static List<String> resolveInclude(String includingFile, String target) {
        List<String> candidates = new ArrayList<>();
        String cleaned = target.trim().replace('\\', '/');
        while (cleaned.startsWith("./")) cleaned = cleaned.substring(2);
        if (cleaned.startsWith("~/")) {
            // No home directory exists on the phone; try both the literal path and
            // the home-relative spelling so a user-pasted config still resolves.
            candidates.add(normalizePath(cleaned.substring(2)));
            candidates.add(normalizePath(cleaned));
        } else if (cleaned.startsWith("~")) {
            candidates.add(normalizePath(cleaned.substring(1)));
        } else if (cleaned.startsWith("/")) {
            candidates.add(normalizePath(cleaned));
        } else {
            String directory = directoryOf(includingFile);
            candidates.add(normalizePath(directory.isEmpty() ? cleaned : directory + "/" + cleaned));
        }
        return candidates;
    }

    private static boolean isGlob(String value) {
        return value != null && (value.indexOf('*') >= 0 || value.indexOf('?') >= 0);
    }

    private static List<String> expandGlob(Set<String> fileNames, String pattern) {
        List<String> matches = new ArrayList<>();        Pattern compiled;
        try {
            compiled = Pattern.compile(globToRegex(normalizePath(pattern)));
        } catch (PatternSyntaxException impossible) {
            return matches;
        }
        for (String fileName : fileNames) {
            String normalized = normalizePath(fileName);
            if (compiled.matcher(normalized).matches()) matches.add(normalized);
        }
        return matches;
    }

    private static String lookupFile(Map<String, String> files, String path) {
        String normalized = normalizePath(path);
        if (files.containsKey(normalized)) return files.get(normalized);
        for (Map.Entry<String, String> entry : files.entrySet()) {
            if (normalizePath(entry.getKey()).equals(normalized)) return entry.getValue();
        }
        return null;
    }

    // -------------------------------------------------------------- resolving

    private static ParseResult buildResult(List<Entry> entries, List<String> warnings, String provenance) {
        List<String> aliases = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.patterns == null) continue;
            for (String pattern : entry.patterns) {
                if (pattern.isEmpty()) continue;
                if (pattern.startsWith("!")) continue;
                if (pattern.indexOf('*') >= 0 || pattern.indexOf('?') >= 0) continue;
                if (!aliases.contains(pattern)) aliases.add(pattern);
            }
        }
        List<HostConfig> hosts = new ArrayList<>(aliases.size());
        for (String alias : aliases) {
            hosts.add(resolveAlias(entries, alias, provenance));
        }
        return new ParseResult(hosts, warnings);
    }

    private static HostConfig resolveAlias(List<Entry> entries, String alias, String provenance) {
        // First value wins for every keyword, file order included.
        List<Entry> matching = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.patterns != null && !patternsMatch(entry.patterns, alias)) continue;
            matching.add(entry);
        }
        String hostName = firstValue(matching, "hostname");
        String user = firstValue(matching, "user");
        String portText = firstValue(matching, "port");
        String proxyJump = firstValue(matching, "proxyjump");
        String source = "";
        for (Entry entry : matching) {
            if (entry.patterns != null) {
                source = entry.source;
                break;
            }
        }

        HostConfig host = new HostConfig();
        host.alias = alias;
        host.hostName = hostName == null || hostName.trim().isEmpty() ? alias : hostName.trim();
        host.user = user == null ? "" : user.trim();
        host.port = parsePort(portText);
        if (proxyJump != null && !proxyJump.trim().isEmpty() && !"none".equalsIgnoreCase(proxyJump.trim())) {
            host.proxyJump = proxyJump.trim();
        }
        for (Entry entry : matching) {
            if (!entry.key.equals("identityfile")) continue;
            String identityFile = entry.value == null ? "" : entry.value.trim();
            if (!identityFile.isEmpty()) host.identityFiles.add(identityFile);
        }
        host.authType = HostConfig.AuthType.PASSWORD;
        host.accessMode = HostConfig.AccessMode.TUNNEL;
        host.importedFrom = source.isEmpty() ? provenance : source;
        return host;
    }

    private static String firstValue(List<Entry> matching, String key) {
        for (Entry entry : matching) {
            if (entry.key.equals(key)) return entry.value;
        }
        return null;
    }

    private static int parsePort(String text) {
        if (text == null) return 22;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return 22;
        try {
            int port = Integer.parseInt(trimmed);
            return port >= 1 && port <= 65535 ? port : 22;
        } catch (NumberFormatException malformed) {
            return 22;
        }
    }

    /**
     * OpenSSH {@code Host} pattern list matching: at least one positive pattern
     * matches and no negated pattern matches the alias.
     */
    public static boolean patternsMatch(List<String> patterns, String alias) {
        if (patterns == null || patterns.isEmpty() || alias == null) return false;
        boolean positive = false;
        for (String raw : patterns) {
            if (raw == null || raw.isEmpty()) continue;
            boolean negated = raw.startsWith("!");
            String body = negated ? raw.substring(1) : raw;
            boolean match = globMatches(body, alias);
            if (negated && match) return false;
            if (!negated && match) positive = true;
        }
        return positive;
    }

    /** Case-insensitive glob match where {@code *}/{@code ?} behave like OpenSSH. */
    public static boolean globMatches(String pattern, String value) {
        if (pattern == null || value == null) return false;
        try {
            return Pattern.compile(globToRegex(pattern), Pattern.CASE_INSENSITIVE)
                    .matcher(value).matches();
        } catch (PatternSyntaxException impossible) {
            return false;
        }
    }

    private static String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder(glob.length() + 8);
        regex.append('^');
        for (int index = 0; index < glob.length(); index += 1) {
            char ch = glob.charAt(index);
            switch (ch) {
                case '*':
                    regex.append(".*");
                    break;
                case '?':
                    regex.append('.');
                    break;
                case '\\':
                case '.':
                case '+':
                case '^':
                case '$':
                case '{':
                case '}':
                case '(':
                case ')':
                case '|':
                case '[':
                case ']':
                    regex.append('\\').append(ch);
                    break;
                default:
                    regex.append(ch);
            }
        }
        regex.append('$');
        return regex.toString();
    }

    // ---------------------------------------------------------------- lexing

    /** A parsed {@code keyword value} line (both {@code keyword value} and {@code keyword=value}). */
    private static final class Directive {
        final String key;
        final String value;

        Directive(String key, String value) {
            this.key = key;
            this.value = value;
        }
    }

    private static Directive splitDirective(String line) {
        if (line == null) return null;
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return null;

        int equals = trimmed.indexOf('=');
        int space = firstWhitespace(trimmed);
        String key;
        String rest;
        if (equals >= 0 && (space < 0 || equals < space)) {
            key = trimmed.substring(0, equals);
            rest = trimmed.substring(equals + 1);
        } else if (space >= 0) {
            key = trimmed.substring(0, space);
            rest = trimmed.substring(space + 1);
        } else {
            key = trimmed;
            rest = "";
        }
        key = key.trim().toLowerCase();

        // Strip an inline comment that is not inside quotes.
        StringBuilder value = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        for (int index = 0; index < rest.length(); index += 1) {
            char ch = rest.charAt(index);
            if (ch == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (ch == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (ch == '#' && !inSingle && !inDouble) {
                break;
            }
            value.append(ch);
        }
        if (key.isEmpty()) return null;
        return new Directive(key, stripQuotes(value.toString().trim()));
    }

    private static int firstWhitespace(String text) {
        for (int index = 0; index < text.length(); index += 1) {
            char ch = text.charAt(index);
            if (ch == ' ' || ch == '\t') return index;
        }
        return -1;
    }

    /** Mirrors the desktop reader: one leading and one trailing quote are removed. */
    private static String stripQuotes(String value) {
        String result = value;
        if (result.length() >= 2
                && (result.charAt(0) == '"' || result.charAt(0) == '\'')
                && (result.charAt(result.length() - 1) == '"' || result.charAt(result.length() - 1) == '\'')) {
            return result.substring(1, result.length() - 1);
        }
        if (!result.isEmpty() && (result.charAt(0) == '"' || result.charAt(0) == '\'')) {
            result = result.substring(1);
        }
        if (!result.isEmpty()
                && (result.charAt(result.length() - 1) == '"' || result.charAt(result.length() - 1) == '\'')) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static List<String> splitTokens(String value) {
        List<String> tokens = new ArrayList<>();
        if (value == null) return tokens;
        for (String token : value.trim().split("\\s+")) {
            if (!token.isEmpty()) tokens.add(token);
        }
        return tokens;
    }

    // ------------------------------------------------------------------ paths

    /** Normalise a virtual path: forward slashes, no {@code .}/{@code ..} segments, relative. */
    private static String normalizePath(String path) {
        if (path == null) return "";
        String cleaned = path.trim().replace('\\', '/');
        while (cleaned.startsWith("./")) cleaned = cleaned.substring(2);
        List<String> segments = new ArrayList<>();
        for (String segment : cleaned.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) continue;
            if (segment.equals("..")) {
                if (!segments.isEmpty()) segments.remove(segments.size() - 1);
                continue;
            }
            segments.add(segment);
        }
        return join(segments);
    }

    private static String directoryOf(String path) {
        String normalized = normalizePath(path);
        int slash = normalized.lastIndexOf('/');
        return slash < 0 ? "" : normalized.substring(0, slash);
    }

    private static String join(List<String> segments) {
        StringBuilder out = new StringBuilder();
        for (String segment : segments) {
            if (out.length() > 0) out.append('/');
            out.append(segment);
        }
        return out.toString();
    }
}

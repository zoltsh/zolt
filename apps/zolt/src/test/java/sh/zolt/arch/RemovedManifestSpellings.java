package sh.zolt.arch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pre-release manifest spellings the hard cut removed, and the source surface that must no
 * longer carry them (design §21.1 and §21.3 Phase 0).
 *
 * <p>The scanned surface is every tracked file {@code git ls-files} reports and the working tree
 * still holds, so a new directory, an extensionless script, or a workflow file cannot quietly
 * escape the gate. Tracked is the right boundary: it is what a review and CI see, and it inherits
 * {@code .gitignore} rather than re-deriving which directories hold build output. Java is scanned
 * through its string literals, text blocks, and comments only: the engine model deliberately keeps
 * pre-cut identifiers such as {@code ProjectConfig.dependencyPolicy()} and the {@code
 * section|coordinate} metadata keys, and those are code, not something an author ever reads or
 * writes. Every other scanned file is matched whole-line.
 *
 * <p>Removed keys are matched in both the prose form a diagnostic uses ({@code [compiler].release})
 * and the authored TOML form a manifest uses, where the table header and the key sit on separate
 * lines.
 */
final class RemovedManifestSpellings {
    /** Removed spelling id to the pattern that finds it in author-facing text. */
    static final Map<String, Pattern> SPELLINGS = spellings();

    /** Every top-level tracked root the gate must reach; repository-root files use {@code "."}. */
    static final List<String> SCANNED_ROOTS =
            List.of(".", ".github", "apps", "benchmarks", "ci", "docs", "examples", "modules", "scripts", "smoke");

    /**
     * Build output, anchored to the literal {@code build/} directory at the repository root. The
     * Java package {@code sh.zolt.build} is source and is always scanned.
     */
    private static final String SKIPPED_ROOT_DIRECTORY = "build/";
    /** Tracked bytes that are not text; everything else is read as UTF-8. */
    private static final Set<String> SKIPPED_SUFFIXES =
            Set.of(".bin", ".class", ".gif", ".gz", ".ico", ".jar", ".jpg", ".pdf", ".png", ".svg", ".zip");

    private RemovedManifestSpellings() {
    }

    private static Map<String, Pattern> spellings() {
        Map<String, Pattern> spellings = new LinkedHashMap<>();
        spellings.put("workspace-default-members", Pattern.compile("defaultMembers"));
        spellings.put("api-dependencies", Pattern.compile("\\[api\\.dependencies\\]|api\\.dependencies\\]"));
        spellings.put("runtime-dependencies", Pattern.compile("\\[runtime\\.dependencies\\]"));
        spellings.put("provided-dependencies", Pattern.compile("\\[provided\\.dependencies\\]"));
        spellings.put("dev-dependencies", Pattern.compile("\\[dev\\.dependencies\\]"));
        spellings.put("test-dependencies", Pattern.compile("\\[test\\.dependencies\\]"));
        spellings.put("annotation-processors", Pattern.compile("\\[(?:test\\.)?annotationProcessors\\]"));
        spellings.put("repository-credentials", Pattern.compile("repositoryCredentials"));
        spellings.put("dependency-policy", Pattern.compile("dependencyPolicy"));
        spellings.put("dependency-constraints", Pattern.compile("dependencyConstraints"));
        spellings.put("integration-test", Pattern.compile("(?m)integrationTest\\]|^[ \\t]*integrationTest[ \\t]*="));
        spellings.put("coverage-min-line", Pattern.compile("minLine"));
        spellings.put("coverage-min-branch", Pattern.compile("minBranch"));
        spellings.put("framework-spring-boot", Pattern.compile("framework\\.springBoot"));
        spellings.put("license-policy", Pattern.compile("licensePolicy"));
        spellings.put("generated-tool-tables",
                Pattern.compile("generated\\.execTools|generated\\.openapiTool|generated\\.openapiPresets|generated\\.protobufTool"));
        spellings.put("build-output-root", Pattern.compile("(?m)\\[build\\]\\.outputRoot|^[ \\t]*outputRoot[ \\t]*="));
        spellings.put("compiler-release", Pattern.compile("(?m)\\[compiler\\]\\.release|" + authoredKey("compiler", "release")));
        spellings.put("package-mode-symbols",
                Pattern.compile("--mode (?:thin|uber)(?![-\\w])|mode = \"(?:thin|uber)\""));
        spellings.put("platform-command",
                Pattern.compile("\\bzolt platform(?![-\\w])|\"platform\"\\s*,\\s*\"(?:add|remove|set|list)\""
                        + "|\"(?:add|remove|versions|bom|resolve)\"\\s*,\\s*\"platform\""));
        spellings.put("publish-signing-enabled",
                Pattern.compile("(?m)\\[publish\\.signing\\]\\.enabled|\\[publish\\.signing\\][^\\n]*?enabled[ \\t]*=|"
                        + authoredKey("publish\\.signing", "enabled")));
        return Map.copyOf(spellings);
    }

    /**
     * The authored TOML form of one removed key: a {@code [table]} header of its own, then the key
     * on a later line of the same table.
     */
    private static String authoredKey(String table, String key) {
        return "^[ \\t]*\\[" + table + "\\][ \\t]*$(?:\\n(?![ \\t]*\\[)[^\\n]*)*?\\n[ \\t]*" + key + "[ \\t]*=";
    }

    /** Every checked-in file the gate scans, grouped by the top-level root it lives in. */
    static Map<String, List<Path>> scannedFilesByRoot(Path repositoryRoot) throws IOException {
        Map<String, List<Path>> byRoot = new LinkedHashMap<>();
        for (String tracked : trackedFiles(repositoryRoot)) {
            if (tracked.startsWith(SKIPPED_ROOT_DIRECTORY) || !scanned(tracked)) {
                continue;
            }
            Path file = repositoryRoot.resolve(tracked);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            int slash = tracked.indexOf('/');
            String root = slash < 0 ? "." : tracked.substring(0, slash);
            byRoot.computeIfAbsent(root, key -> new ArrayList<>()).add(file);
        }
        byRoot.replaceAll((root, files) -> List.copyOf(files));
        return Map.copyOf(byRoot);
    }

    /** Every checked-in file the gate scans, sorted, relative to the repository root. */
    static List<Path> scannedFiles(Path repositoryRoot) throws IOException {
        List<Path> files = new ArrayList<>();
        scannedFilesByRoot(repositoryRoot).values().forEach(files::addAll);
        files.sort(null);
        return List.copyOf(files);
    }

    /**
     * Every removed spelling one file still carries, as {@code line -> spellingId} findings. Java
     * contributes only its string literals, text blocks, and comments. A finding is reported on the
     * line the offending spelling ends on, so a two-line authored table names the key line.
     */
    static List<Finding> findings(Path file, String displayPath) throws IOException {
        List<String> lines = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).lines().toList();
        List<String> scanned = displayPath.endsWith(".java") ? authorFacingText(lines) : lines;
        String document = String.join("\n", scanned);
        int[] lineStarts = lineStarts(scanned);

        Set<Finding> findings = new LinkedHashSet<>();
        for (Map.Entry<String, Pattern> spelling : SPELLINGS.entrySet()) {
            Matcher matcher = spelling.getValue().matcher(document);
            while (matcher.find()) {
                int index = lineOf(lineStarts, Math.max(matcher.start(), matcher.end() - 1));
                findings.add(new Finding(displayPath, index + 1, spelling.getKey(), lines.get(index).strip()));
            }
        }
        return List.copyOf(findings);
    }

    /**
     * The part of each Java line an author can read: string literal contents, text block bodies,
     * and comment text. Identifiers, types, and member references are code and are never manifest
     * spellings. Line count and order are preserved so findings keep their line numbers.
     */
    static List<String> authorFacingText(List<String> lines) {
        JavaTextScanner scanner = new JavaTextScanner();
        List<String> text = new ArrayList<>(lines.size());
        for (String line : lines) {
            text.add(scanner.next(line));
        }
        return List.copyOf(text);
    }

    /** The author-facing part of one standalone Java line, outside any text block or comment. */
    static String authorFacingText(String line) {
        return new JavaTextScanner().next(line);
    }

    private static List<String> trackedFiles(Path repositoryRoot) throws IOException {
        Process process = new ProcessBuilder("git", "ls-files", "-z")
                .directory(repositoryRoot.toFile())
                .redirectErrorStream(true)
                .start();
        byte[] output = process.getInputStream().readAllBytes();
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IOException("git ls-files failed in " + repositoryRoot + ": "
                        + new String(output, StandardCharsets.UTF_8));
            }
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while listing tracked files.", interruption);
        }
        return List.of(new String(output, StandardCharsets.UTF_8).split("\0")).stream()
                .filter(tracked -> !tracked.isEmpty())
                .toList();
    }

    private static int[] lineStarts(List<String> lines) {
        int[] starts = new int[lines.size()];
        int offset = 0;
        for (int index = 0; index < lines.size(); index++) {
            starts[index] = offset;
            offset += lines.get(index).length() + 1;
        }
        return starts;
    }

    private static int lineOf(int[] lineStarts, int position) {
        int low = 0;
        int high = lineStarts.length - 1;
        while (low < high) {
            int middle = (low + high + 1) / 2;
            if (lineStarts[middle] <= position) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return low;
    }

    private static boolean scanned(String trackedPath) {
        int slash = trackedPath.lastIndexOf('/');
        String name = trackedPath.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        return dot < 0 || !SKIPPED_SUFFIXES.contains(name.substring(dot));
    }

    record Finding(String path, int line, String spelling, String text) {
        String describe() {
            return path + ":" + line + " " + spelling + " -> " + text;
        }

        String key() {
            return path + "|" + spelling;
        }
    }
}

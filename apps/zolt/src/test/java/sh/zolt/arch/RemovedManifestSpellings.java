package sh.zolt.arch;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pre-release manifest spellings the hard cut removed, and the source surface that must no
 * longer carry them (design §21.1 and §21.3 Phase 0).
 *
 * <p>Java is scanned through its string literals and comments only: the engine model deliberately
 * keeps pre-cut identifiers such as {@code ProjectConfig.dependencyPolicy()} and the {@code
 * section|coordinate} metadata keys, and those are code, not something an author ever reads or
 * writes. Every other scanned file is matched whole-line.
 */
final class RemovedManifestSpellings {
    /** Removed spelling id to the pattern that finds it in author-facing text. */
    static final Map<String, Pattern> SPELLINGS = spellings();

    private static final Set<String> SKIPPED_DIRECTORIES =
            Set.of("target", ".git", ".zolt", "node_modules", "build");
    private static final Set<String> SCANNED_SUFFIXES =
            Set.of(".java", ".toml", ".mts", ".ts", ".json", ".md", ".txt", ".lock");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"\\\\\\n]|\\\\.)*\"");
    private static final Pattern LINE_COMMENT = Pattern.compile("//.*$");
    private static final Pattern BLOCK_COMMENT_BODY = Pattern.compile("^\\s*\\*.*$");

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
        spellings.put("integration-test", Pattern.compile("integrationTest\\]"));
        spellings.put("coverage-min-line", Pattern.compile("minLine"));
        spellings.put("coverage-min-branch", Pattern.compile("minBranch"));
        spellings.put("framework-spring-boot", Pattern.compile("framework\\.springBoot"));
        spellings.put("license-policy", Pattern.compile("licensePolicy"));
        spellings.put("generated-tool-tables",
                Pattern.compile("generated\\.execTools|generated\\.openapiTool|generated\\.openapiPresets|generated\\.protobufTool"));
        spellings.put("build-output-root", Pattern.compile("\\[build\\]\\.outputRoot"));
        spellings.put("compiler-release", Pattern.compile("\\[compiler\\]\\.release"));
        spellings.put("package-mode-symbols",
                Pattern.compile("--mode (?:thin|uber)(?![-\\w])|mode = \"(?:thin|uber)\""));
        return Map.copyOf(spellings);
    }

    /** Every checked-in file the gate scans, relative to the repository root. */
    static List<Path> scannedFiles(Path repositoryRoot) throws IOException {
        List<Path> roots = new ArrayList<>();
        roots.add(repositoryRoot.resolve("apps"));
        roots.add(repositoryRoot.resolve("modules"));
        roots.add(repositoryRoot.resolve("docs"));
        roots.add(repositoryRoot.resolve("examples"));
        roots.add(repositoryRoot.resolve("scripts"));
        roots.add(repositoryRoot.resolve("smoke"));

        List<Path> files = new ArrayList<>();
        for (Path root : roots) {
            collect(root, files);
        }
        try (var entries = Files.list(repositoryRoot)) {
            entries.filter(Files::isRegularFile).filter(RemovedManifestSpellings::scanned).forEach(files::add);
        }
        files.sort(null);
        return List.copyOf(files);
    }

    /**
     * Every removed spelling one file still carries, as {@code line -> spellingId} findings. Java
     * contributes only its string literals and comments.
     */
    static List<Finding> findings(Path file, String displayPath) throws IOException {
        boolean java = file.getFileName().toString().endsWith(".java");
        List<Finding> findings = new ArrayList<>();
        List<String> lines = Files.readAllLines(file);
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            String scanned = java ? authorFacingText(line) : line;
            if (scanned.isBlank()) {
                continue;
            }
            for (Map.Entry<String, Pattern> spelling : SPELLINGS.entrySet()) {
                if (spelling.getValue().matcher(scanned).find()) {
                    findings.add(new Finding(displayPath, index + 1, spelling.getKey(), line.strip()));
                }
            }
        }
        return List.copyOf(findings);
    }

    /**
     * The part of one Java line an author can read: string literal contents plus comment text.
     * Identifiers, types, and member references are code and are never manifest spellings.
     */
    static String authorFacingText(String line) {
        StringBuilder text = new StringBuilder();
        Matcher literal = STRING_LITERAL.matcher(line);
        while (literal.find()) {
            text.append(literal.group()).append(' ');
        }
        String withoutLiterals = literal.reset().replaceAll("\"\"");
        Matcher comment = LINE_COMMENT.matcher(withoutLiterals);
        if (comment.find()) {
            text.append(comment.group()).append(' ');
        } else if (BLOCK_COMMENT_BODY.matcher(line).matches()) {
            text.append(line);
        }
        return text.toString();
    }

    private static void collect(Path root, List<Path> files) throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                return SKIPPED_DIRECTORIES.contains(directory.getFileName().toString())
                        ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (scanned(file)) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static boolean scanned(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && SCANNED_SUFFIXES.contains(name.substring(dot));
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

package sh.zolt.arch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.arch.ArchitectureDiagnostics.describe;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.arch.RemovedManifestSpellings.Finding;

/**
 * Design §21.1 leaves no removed manifest spelling in the tree, and §21.3 Phase 0 makes that a
 * source gate rather than a review habit. Historical prose keeps its own allowlist entry with the
 * reason it is history.
 */
final class RemovedManifestSpellingGuardrailTest {
    private static final Path ALLOWLIST = RepositoryPaths.appRoot()
            .resolve("src/test/resources/sh/zolt/arch/removed-manifest-spelling-allowlist.txt");

    @Test
    void checkedInSourcesCarryNoRemovedManifestSpelling() throws IOException {
        Path repositoryRoot = RepositoryPaths.root();
        Map<String, AllowlistEntry> allowlist = readAllowlist(ALLOWLIST);
        List<Finding> findings = new ArrayList<>();
        for (Path file : RemovedManifestSpellings.scannedFiles(repositoryRoot)) {
            findings.addAll(RemovedManifestSpellings.findings(file, RepositoryPaths.displayPath(file)));
        }

        List<String> violations = new ArrayList<>();
        Set<String> matchedAllowlistKeys = new TreeSet<>();
        for (Finding finding : findings) {
            if (allowlist.containsKey(finding.key())) {
                matchedAllowlistKeys.add(finding.key());
                continue;
            }
            violations.add(finding.describe());
        }
        allowlist.keySet().stream()
                .filter(key -> !matchedAllowlistKeys.contains(key))
                .sorted()
                .forEach(key -> violations.add(key + " no longer carries that spelling; remove the allowlist entry"));

        assertTrue(
                violations.isEmpty(),
                () -> "Removed manifest spellings are still present:\n"
                        + describe(violations)
                        + "\nRewrite the text in the final manifest language, or add an allowlist entry"
                        + " naming the historical or machine-identity reason it must stay.");
    }

    @Test
    void everyTrackedRootIsScanned() throws IOException {
        Map<String, List<Path>> byRoot = RemovedManifestSpellings.scannedFilesByRoot(RepositoryPaths.root());

        for (String root : RemovedManifestSpellings.SCANNED_ROOTS) {
            assertTrue(
                    !byRoot.getOrDefault(root, List.of()).isEmpty(),
                    () -> "Root `" + root + "` contributed no scanned files; the gate is blind to it.");
        }
        assertEquals(
                Set.copyOf(RemovedManifestSpellings.SCANNED_ROOTS),
                byRoot.keySet(),
                "A new tracked top-level root must be named here so its file count is gated too.");
    }

    @Test
    void javaPackagesNamedLikeBuildDirectoriesAreScanned() throws IOException {
        List<Path> scanned = RemovedManifestSpellings.scannedFiles(RepositoryPaths.root());

        assertTrue(
                scanned.stream()
                        .map(RepositoryPaths::displayPath)
                        .anyMatch(path -> path.startsWith("modules/zolt-build/src/main/java/sh/zolt/build/")),
                "the Java package sh.zolt.build is source, not a build output directory");
    }

    @Test
    void everyRequiredSpellingIsGated() {
        assertEquals(
                Set.of(
                        "workspace-default-members",
                        "api-dependencies",
                        "runtime-dependencies",
                        "provided-dependencies",
                        "dev-dependencies",
                        "test-dependencies",
                        "annotation-processors",
                        "repository-credentials",
                        "dependency-policy",
                        "dependency-constraints",
                        "integration-test",
                        "coverage-min-line",
                        "coverage-min-branch",
                        "framework-spring-boot",
                        "license-policy",
                        "generated-tool-tables",
                        "build-output-root",
                        "compiler-release",
                        "package-mode-symbols",
                        "platform-command",
                        "publish-signing-enabled"),
                RemovedManifestSpellings.SPELLINGS.keySet());
    }

    @Test
    void javaScanReadsOnlyStringLiteralsAndComments() {
        assertEquals(
                "",
                RemovedManifestSpellings.authorFacingText("        return config.dependencyPolicy();").strip(),
                "engine identifiers are code, not a manifest spelling");
        assertTrue(
                RemovedManifestSpellings.authorFacingText("    throw new X(\"[dependencyPolicy].exclude\");")
                        .contains("[dependencyPolicy].exclude"));
        assertTrue(
                RemovedManifestSpellings.authorFacingText("        // [dependencyConstraints] is gone")
                        .contains("[dependencyConstraints]"));
    }

    @Test
    void javaScanReadsTextBlockAndBlockCommentBodies() {
        assertEquals(
                List.of("", "[workspace]", "defaultMembers = [\"apps/api\"]", "", ""),
                RemovedManifestSpellings.authorFacingText(List.of(
                                "        Files.writeString(manifest, \"\"\"",
                                "                [workspace]",
                                "                defaultMembers = [\"apps/api\"]",
                                "                \"\"\");",
                                "        int defaultMembers = 1;"))
                        .stream()
                        .map(String::strip)
                        .toList(),
                "text block bodies are authored TOML; the surrounding code is not");
        assertTrue(
                RemovedManifestSpellings.authorFacingText(List.of(
                                "/**",
                                " * {@code [api.dependencies]} is gone",
                                " */"))
                        .get(1)
                        .contains("[api.dependencies]"));
    }

    @Test
    void scannerReportsEveryRemovedSpellingInOneFile(@TempDir Path tempDir) throws IOException {
        Path manifest = tempDir.resolve("zolt.toml");
        Files.writeString(manifest, """
                [workspace]
                defaultMembers = ["apps/api"]

                [api.dependencies]
                "org.slf4j:slf4j-api" = "2.0.17"

                [compiler]
                encoding = "UTF-8"
                release = "21"
                """);

        assertEquals(
                List.of(
                        "zolt.toml:2 workspace-default-members",
                        "zolt.toml:4 api-dependencies",
                        "zolt.toml:9 compiler-release"),
                RemovedManifestSpellings.findings(manifest, "zolt.toml").stream()
                        .map(finding -> finding.path() + ":" + finding.line() + " " + finding.spelling())
                        .sorted()
                        .toList());
    }

    @Test
    void scannerReadsRemovedSpellingsFromJavaTextBlocks(@TempDir Path tempDir) throws IOException {
        Path source = tempDir.resolve("Fixture.java");
        Files.writeString(source, """
                final class Fixture {
                    String manifest() {
                        return \"""
                                [build]
                                outputRoot = "target"
                                \""";
                    }

                    int outputRoot = 1;
                }
                """);

        assertEquals(
                List.of("Fixture.java:5 build-output-root"),
                RemovedManifestSpellings.findings(source, "Fixture.java").stream()
                        .map(finding -> finding.path() + ":" + finding.line() + " " + finding.spelling())
                        .toList());
    }

    @Test
    void keyShapedSpellingsIgnoreTheSameKeyInAnotherTable(@TempDir Path tempDir) throws IOException {
        Path manifest = tempDir.resolve("zolt.toml");
        Files.writeString(manifest, """
                [publishing.repositories.company]
                url = "https://repo.example.test/releases"
                release = "company-releases"
                """);

        assertEquals(List.of(), RemovedManifestSpellings.findings(manifest, "zolt.toml"));
    }

    @Test
    void allowlistParserRequiresAPathSpellingAndReason(@TempDir Path tempDir) throws IOException {
        Path allowlist = tempDir.resolve("allowlist.txt");
        Files.writeString(allowlist, """
                # path|spelling|reason
                docs/breaking-changes.md|dependency-policy|historical prose
                """);

        assertEquals(
                Map.of(
                        "docs/breaking-changes.md|dependency-policy",
                        new AllowlistEntry("docs/breaking-changes.md", "dependency-policy", "historical prose")),
                readAllowlist(allowlist));
    }

    @Test
    void allowlistParserRejectsMalformedLines(@TempDir Path tempDir) throws IOException {
        Path allowlist = tempDir.resolve("allowlist.txt");
        Files.writeString(allowlist, "docs/breaking-changes.md|dependency-policy\n");

        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> readAllowlist(allowlist));

        assertEquals(
                "Invalid removed-manifest-spelling allowlist line: docs/breaking-changes.md|dependency-policy",
                exception.getMessage());
    }

    @Test
    void allowlistParserRejectsUnknownSpellings(@TempDir Path tempDir) throws IOException {
        Path allowlist = tempDir.resolve("allowlist.txt");
        Files.writeString(allowlist, "docs/breaking-changes.md|not-a-spelling|because\n");

        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> readAllowlist(allowlist));

        assertEquals(
                "Unknown removed manifest spelling `not-a-spelling` in the allowlist.",
                exception.getMessage());
    }

    private static Map<String, AllowlistEntry> readAllowlist(Path path) throws IOException {
        return ArchitectureAllowlistSupport.readAllowlist(
                path,
                RemovedManifestSpellingGuardrailTest::parseAllowlistLine,
                AllowlistEntry::key,
                "Duplicate removed-manifest-spelling allowlist entry: ");
    }

    private static Optional<AllowlistEntry> parseAllowlistLine(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return Optional.empty();
        }
        String[] parts = trimmed.split("\\|", -1);
        if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
            throw new IllegalArgumentException("Invalid removed-manifest-spelling allowlist line: " + line);
        }
        if (!RemovedManifestSpellings.SPELLINGS.containsKey(parts[1])) {
            throw new IllegalArgumentException(
                    "Unknown removed manifest spelling `" + parts[1] + "` in the allowlist.");
        }
        return Optional.of(new AllowlistEntry(parts[0], parts[1], parts[2]));
    }

    private record AllowlistEntry(String path, String spelling, String reason) {
        String key() {
            return path + "|" + spelling;
        }
    }
}

package sh.zolt.cli.packaging;

import sh.zolt.cli.CliTestSupport;
import sh.zolt.cli.CliTestRepository;


import static sh.zolt.cli.packaging.CheckPackageContentsCommandTestSupport.writeMainSource;
import static sh.zolt.cli.packaging.CheckPackageContentsCommandTestSupport.writeProjectConfig;
import static sh.zolt.cli.CliTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CheckPackageContentsEvidenceTest {
    @TempDir
    private Path tempDir;

    @Test
    void checkContextCiRequiresPackageArtifactWhenConfigured() throws IOException {
        Path projectDir = tempDir.resolve("check-context-ci-require-package-missing");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), CliTestSupport.memberConfig("check-context-ci-require-package-missing"));
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        CommandResult result = execute(
                "check",
                "--context", "ci",
                "--require-package",
                "--check", "package-contents",
                "--cwd", projectDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("error package-contents target/check-context-ci-require-package-missing-0.1.0.jar CI context requires the configured package artifact, but it is missing."));
        assertTrue(result.stdout().contains("next: Run `zolt package` before `zolt check --context ci --require-package`."));
        assertEquals("", result.stderr());
    }

    @Test
    void checkContextCiAcceptsRequiredPackageArtifactWithFreshEvidence() throws IOException {
        Path projectDir = tempDir.resolve("check-context-ci-require-package-ok");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        writeMainSource(projectDir, """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                    }
                }
                """);
        CommandResult packageResult = execute(
                "package",
                "--cwd", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString());

        CommandResult result = execute(
                "check",
                "--context", "ci",
                "--require-package",
                "--check", "package-contents",
                "--cwd", projectDir.toString());

        assertEquals(0, packageResult.exitCode());
        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("ok package-contents demo Package mode `thin` has 0 dependency dispositions."));
        assertEquals("", result.stderr());
    }

    @Test
    void packageModeOverridePreservesConfiguredOutputsAndFreshEvidence() throws IOException {
        Path projectDir = tempDir.resolve("package-mode-override-evidence");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        Files.writeString(
                projectDir.resolve("zolt.toml"),
                Files.readString(projectDir.resolve("zolt.toml"))
                        + "\n[package]\nmode = \"thin\"\nsources = true\n");
        writeMainSource(projectDir, """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                    }
                }
                """);
        Path cache = tempDir.resolve("package-mode-override-cache");

        CommandResult packaged = execute(
                "package",
                "--mode", "thin",
                "--cwd", projectDir.toString(),
                "--cache-root", cache.toString());
        CommandResult checked = execute(
                "check",
                "--context", "ci",
                "--require-package",
                "--check", "package-contents",
                "--cwd", projectDir.toString(),
                "--cache-root", cache.toString());

        assertEquals(0, packaged.exitCode(), packaged.stderr());
        assertTrue(Files.isRegularFile(projectDir.resolve("target/demo-0.1.0-sources.jar")));
        assertEquals(0, checked.exitCode(), checked.stdout());
        assertTrue(checked.stdout().contains(
                "ok package-contents demo Package mode `thin` has 0 dependency dispositions."));
        assertEquals("", checked.stderr());
    }

    @Test
    void checkPackageContentsReportsMissingEvidenceForExistingArchive() throws IOException {
        Path projectDir = tempDir.resolve("check-package-contents-missing-evidence");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        writeMainSource(projectDir, """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                    }
                }
                """);
        CommandResult packageResult = execute(
                "package",
                "--cwd", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString());
        Path jarPath = projectDir.resolve("target/demo-0.1.0.jar");
        Files.delete(projectDir.resolve("target/demo-0.1.0.jar.zolt-package.json"));

        CommandResult result = execute(
                "check",
                "--cwd", projectDir.toString(),
                "--check", "package-contents");

        assertEquals(0, packageResult.exitCode());
        assertEquals(1, result.exitCode());
        assertTrue(Files.exists(jarPath));
        assertTrue(result.stdout().contains("error package-contents target/demo-0.1.0.jar Package artifact exists, but package evidence manifest is missing."));
        assertTrue(result.stdout().contains("next: Run `zolt package` to regenerate target/demo-0.1.0.jar.zolt-package.json."));
        assertEquals("", result.stderr());
    }

    @Test
    void checkPackageContentsReportsStalePackageEvidence() throws IOException {
        Path projectDir = tempDir.resolve("check-package-contents-stale-evidence");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        writeMainSource(projectDir, """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                    }
                }
                """);
        CommandResult packageResult = execute(
                "package",
                "--cwd", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString());
        Path jarPath = projectDir.resolve("target/demo-0.1.0.jar");
        Files.writeString(jarPath, "tampered\n", StandardOpenOption.APPEND);

        CommandResult result = execute(
                "check",
                "--cwd", projectDir.toString(),
                "--check", "package-contents");

        assertEquals(0, packageResult.exitCode());
        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains(
                "error package-contents target/demo-0.1.0.jar.zolt-package.json Package evidence is stale for `target/demo-0.1.0.jar`: package output `main` changed after packaging"));
        assertTrue(result.stdout().contains("next: Run `zolt package` to regenerate the artifact and evidence manifest."));
        assertEquals("", result.stderr());
    }

    @Test
    void checkPackageContentsRequiresTheThinRuntimeClasspathSidecar()
            throws IOException {
        Path projectDir = tempDir.resolve("thin-sidecar-evidence");
        writeProjectConfig(
                projectDir,
                "https://repo.maven.apache.org/maven2");
        writeMainSource(projectDir, mainSource());
        Path cache = tempDir.resolve("thin-sidecar-cache");
        CommandResult packaged = execute(
                "package",
                "--cwd", projectDir.toString(),
                "--cache-root", cache.toString());
        Files.delete(projectDir.resolve(
                "target/demo-0.1.0.runtime-classpath"));

        CommandResult checked = execute(
                "check",
                "--context", "ci",
                "--require-package",
                "--check", "package-contents",
                "--cwd", projectDir.toString());

        assertEquals(0, packaged.exitCode(), packaged.stderr());
        assertEquals(1, checked.exitCode());
        assertTrue(checked.stdout().contains(
                "package output `runtime-classpath` is missing"));
        assertEquals("", checked.stderr());
    }

    @Test
    void checkPackageContentsVerifiesSupplementalArtifacts()
            throws IOException {
        Path projectDir = tempDir.resolve("supplemental-evidence");
        writeProjectConfig(
                projectDir,
                "https://repo.maven.apache.org/maven2");
        Files.writeString(
                projectDir.resolve("zolt.toml"),
                Files.readString(projectDir.resolve("zolt.toml"))
                        + "\n[package]\nsources = true\n");
        writeMainSource(projectDir, mainSource());
        Path cache = tempDir.resolve("supplemental-cache");
        CommandResult packaged = execute(
                "package",
                "--cwd", projectDir.toString(),
                "--cache-root", cache.toString());
        Files.writeString(
                projectDir.resolve("target/demo-0.1.0-sources.jar"),
                "tampered\n",
                StandardOpenOption.APPEND);

        CommandResult checked = execute(
                "check",
                "--context", "ci",
                "--require-package",
                "--check", "package-contents",
                "--cwd", projectDir.toString());

        assertEquals(0, packaged.exitCode(), packaged.stderr());
        assertEquals(1, checked.exitCode());
        assertTrue(checked.stdout().contains(
                "package output `sources` changed after packaging"));
        assertEquals("", checked.stderr());
    }

    @Test
    void changedResolvedDependencyBlocksQualityAndPublishWithoutRepackaging()
            throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            addArtifact(repository, "dependency-a");
            addArtifact(repository, "dependency-b");
            Path projectDir = tempDir.resolve("changed-package-input");
            writeDependencyProject(
                    projectDir,
                    repository,
                    "dependency-a");
            writeMainSource(projectDir, mainSource());
            Path cache = tempDir.resolve("changed-package-input-cache");

            CommandResult firstResolve = execute(
                    "resolve",
                    "--cwd", projectDir.toString(),
                    "--cache-root", cache.toString());
            CommandResult packaged = execute(
                    "package",
                    "--cwd", projectDir.toString(),
                    "--cache-root", cache.toString());
            writeDependencyProject(
                    projectDir,
                    repository,
                    "dependency-b");
            CommandResult secondResolve = execute(
                    "resolve",
                    "--cwd", projectDir.toString(),
                    "--cache-root", cache.toString());

            CommandResult checked = execute(
                    "check",
                    "--context", "ci",
                    "--require-package",
                    "--check", "package-contents",
                    "--cwd", projectDir.toString());
            CommandResult publish = execute(
                    "publish",
                    "--dry-run",
                    "--cwd", projectDir.toString());

            assertEquals(0, firstResolve.exitCode(), firstResolve.stderr());
            assertEquals(0, packaged.exitCode(), packaged.stderr());
            assertEquals(0, secondResolve.exitCode(), secondResolve.stderr());
            assertEquals(1, checked.exitCode());
            assertTrue(checked.stdout().contains(
                    "package inputs changed after the artifact was packaged"));
            assertEquals(1, publish.exitCode());
            assertTrue(publish.stdout().contains(
                    "package inputs changed after the artifact was packaged"));
        }
    }

    private static String mainSource() {
        return """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                    }
                }
                """;
    }

    private static void writeDependencyProject(
            Path projectDir,
            CliTestRepository repository,
            String artifact) throws IOException {
        Files.createDirectories(projectDir);
        Files.writeString(
                projectDir.resolve("zolt.toml"),
                CliTestSupport.memberConfig("changed-package-input")
                        + """
                        main = "com.example.Main"

                        [repositories.test]
                        url = "%s"

                        [dependencies]
                        "org.example:%s" = "1.0.0"

                        [package]
                        mode = "uber-jar"

                        [publish]
                        release = "test"

                        [publish.repositories.test]
                        url = "%s"
                        """.formatted(
                                repository.baseUri(),
                                artifact,
                                repository.baseUri()));
    }

    private static void addArtifact(
            CliTestRepository repository,
            String artifact) {
        repository.addArtifact(
                "org.example",
                artifact,
                "1.0.0",
                """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.example</groupId>
                  <artifactId>%s</artifactId>
                  <version>1.0.0</version>
                </project>
                """.formatted(artifact));
    }
}

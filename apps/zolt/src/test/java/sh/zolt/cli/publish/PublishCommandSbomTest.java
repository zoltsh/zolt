package sh.zolt.cli.publish;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.memberConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.lockfile.ZoltLockfile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end coverage that {@code zolt publish --sbom --dry-run} attaches a CycloneDX SBOM as a
 * supplemental artifact (classifier {@code cyclonedx}, extension {@code json}) with checksum
 * sidecars, and that it is absent by default.
 */
final class PublishCommandSbomTest {
    @TempDir
    private Path tempDir;

    @Test
    void publishSbomRoutesCycloneDxArtifactWithSidecarsAndIsAbsentByDefault() throws IOException {
        Path projectDir = tempDir.resolve("publish-sbom");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("demo") + """
                main = "com.example.Main"

                [repositories]
                central = "https://repo.maven.apache.org/maven2"

                [dependencies]

                [test.dependencies]

                [package.metadata]
                name = "Demo Library"
                description = "Demo library for SBOM publishing."
                url = "https://example.com/demo"

                [publish]
                releaseRepository = "company-releases"

                [publish.repositories.company-releases]
                url = "https://repo.example.test/releases"

                [build]
                outputRoot = ".zolt/build"
                source = "src/main/java"
                test = "src/test/java"
                output = ".zolt/build/classes"
                testOutput = ".zolt/build/test-classes"
                """);
        Path source = projectDir.resolve("src/main/java/com/example/Main.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example;

                /** Entry point. */
                public final class Main {
                    private Main() {
                    }

                    public static void main(String[] args) {
                    }
                }
                """);

        CommandResult packageResult = execute(
                "package",
                "--cwd", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString());
        assertEquals(0, packageResult.exitCode(), packageResult.stderr());

        CommandResult withoutSbom = execute("publish", "--dry-run", "--cwd", projectDir.toString());
        assertEquals(0, withoutSbom.exitCode(), withoutSbom.stderr());
        assertFalse(withoutSbom.stdout().contains("cyclonedx"), withoutSbom.stdout());

        CommandResult withSbom = execute("publish", "--dry-run", "--sbom", "--cwd", projectDir.toString());
        assertEquals(0, withSbom.exitCode(), withSbom.stderr());
        assertTrue(withSbom.stdout().contains(
                "upload path: com/example/demo/0.1.0/demo-0.1.0-cyclonedx.json"), withSbom.stdout());
        assertTrue(withSbom.stdout().contains(
                "com/example/demo/0.1.0/demo-0.1.0-cyclonedx.json.sha256: "), withSbom.stdout());

        Path sbomFile = projectDir.resolve(".zolt/build/publish/demo-0.1.0-cyclonedx.json");
        assertTrue(Files.isRegularFile(sbomFile), "SBOM artifact should be written for publishing");
        assertTrue(Files.readString(sbomFile).contains("\"bomFormat\": \"CycloneDX\""), "written SBOM is CycloneDX");
    }

    @Test
    void publishSbomRefusesAmbiguousCurrentGraphBeforePlanningUploads() throws IOException {
        Path projectDir = tempDir.resolve("ambiguous-publish-sbom");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("demo"));
        Files.writeString(projectDir.resolve("zolt.lock"), """
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "org.example:parent"
                version = "1.0.0"
                lane = "implementation"
                resolvedScope = "compile"

                [[package]]
                id = "org.example:parent"
                version = "1.0.0"
                source = "maven-central"
                scope = "compile"
                direct = true
                jar = "org/example/parent/1.0.0/parent-1.0.0.jar"
                jarSha256 = "%s"
                dependencies = ["org.example:shared:1.0.0"]

                [[package]]
                id = "org.example:shared"
                version = "1.0.0"
                source = "maven-central"
                scope = "compile"
                direct = false
                jar = "org/example/shared/1.0.0/shared-1.0.0.jar"
                jarSha256 = "%s"
                dependencies = []

                [[package]]
                id = "org.example:shared"
                version = "1.0.0"
                source = "maven-central"
                scope = "provided"
                direct = false
                jar = "org/example/shared/1.0.0/shared-1.0.0.jar"
                jarSha256 = "%s"
                dependencies = []
                """.formatted("1".repeat(64), "2".repeat(64), "2".repeat(64)));

        CommandResult result =
                execute("publish", "--dry-run", "--sbom", "--cwd", projectDir.toString());

        assertNotEquals(0, result.exitCode());
        assertTrue(result.stderr().contains("ambiguous"), result.stderr());
        assertTrue(result.stderr().contains("zolt resolve"), result.stderr());
        assertTrue(
                result.stderr().contains("version " + ZoltLockfile.CURRENT_VERSION),
                result.stderr());
        assertFalse(Files.exists(projectDir.resolve("target/publish")));
    }
}

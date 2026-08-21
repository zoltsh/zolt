package sh.zolt.cli.publish;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.memberConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end coverage that a real {@code zolt package} run with sources and Javadoc enabled
 * produces publish-grade jars, records them in the package evidence manifest with the standard
 * {@code sources}/{@code javadoc} classifiers and {@code jar} type, and that {@code zolt publish
 * --dry-run} then routes them to the {@code -sources.jar}/{@code -javadoc.jar} Maven upload paths
 * with checksum sidecars.
 */
final class PublishCommandSourcesJavadocTest {
    @TempDir
    private Path tempDir;

    @Test
    void packageGeneratesSourcesAndJavadocJarsThatPublishRoutesWithClassifiers() throws IOException {
        Path projectDir = tempDir.resolve("publish-sources-javadoc");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("demo") + """
                main = "com.example.Main"
                description = "Demo library for sources and javadoc publishing."
                url = "https://example.com/demo"

                [repositories]
                central = "https://repo.maven.apache.org/maven2"

                [dependencies]

                [dependencies.test]

                [package]
                sources = true
                javadoc = true

                [publish]
                release = "company-releases"

                [publish.repositories.company-releases]
                url = "https://repo.example.test/releases"

                [build.output]
                root = ".zolt/build"
                """);
        Path source = projectDir.resolve("src/main/java/com/example/Main.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example;

                /** Entry point for the demo library. */
                public final class Main {
                    private Main() {
                    }

                    /**
                     * Runs the demo.
                     *
                     * @param args ignored
                     */
                    public static void main(String[] args) {
                    }
                }
                """);

        CommandResult packageResult = execute(
                "package",
                "--cwd", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString());
        assertEquals(0, packageResult.exitCode(), packageResult.stderr());

        Path sourcesJar = projectDir.resolve(".zolt/build/demo-0.1.0-sources.jar");
        Path javadocJar = projectDir.resolve(".zolt/build/demo-0.1.0-javadoc.jar");
        assertTrue(Files.isRegularFile(sourcesJar), "sources jar should be generated");
        assertTrue(Files.isRegularFile(javadocJar), "javadoc jar should be generated");

        // The same run that generated the jars records them in the package evidence manifest with
        // the publish classifiers and jar type.
        String evidence = Files.readString(projectDir.resolve(".zolt/build/demo-0.1.0.jar.zolt-package.json"));
        assertTrue(evidence.contains("\"classifier\": \"sources\""), evidence);
        assertTrue(evidence.contains("\"classifier\": \"javadoc\""), evidence);
        assertTrue(evidence.contains("\"type\": \"jar\""), evidence);

        CommandResult dryRun = execute(
                "publish",
                "--dry-run",
                "--cwd", projectDir.toString());
        assertEquals(0, dryRun.exitCode(), dryRun.stderr());
        assertTrue(dryRun.stdout().contains(
                "upload path: com/example/demo/0.1.0/demo-0.1.0-sources.jar"), dryRun.stdout());
        assertTrue(dryRun.stdout().contains(
                "upload path: com/example/demo/0.1.0/demo-0.1.0-javadoc.jar"), dryRun.stdout());
        assertTrue(dryRun.stdout().contains(
                "com/example/demo/0.1.0/demo-0.1.0-sources.jar.sha256: "), dryRun.stdout());
        assertTrue(dryRun.stdout().contains(
                "com/example/demo/0.1.0/demo-0.1.0-javadoc.jar.sha1: "), dryRun.stdout());
    }
}

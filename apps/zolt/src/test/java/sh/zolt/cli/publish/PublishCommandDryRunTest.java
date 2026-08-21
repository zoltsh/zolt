package sh.zolt.cli.publish;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.memberConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PublishCommandDryRunTest {
    @TempDir
    private Path tempDir;
    @Test
    void publishDryRunRoutesReleaseArtifactWithoutUploading() throws IOException {
        Path projectDir = tempDir.resolve("publish-dry-run-release");
        writeProjectConfig(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), Files.readString(projectDir.resolve("zolt.toml")) + """

                [publish]
                release = "company-releases"
                snapshot = "company-snapshots"

                [publish.repositories.company-releases]
                url = "https://repo.example.test/releases"

                [publish.repositories.company-snapshots]
                url = "https://repo.example.test/snapshots"
                """);
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
                "--color=always",
                "--progress=always",
                "publish",
                "--dry-run",
                "--directory", projectDir.toString());
        assertEquals(0, packageResult.exitCode());
        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Zolt publish dry run"));
        assertTrue(result.stdout().contains("Coordinate: com.example:demo:0.1.0"));
        assertTrue(result.stdout().contains("Version kind: release"));
        assertTrue(result.stdout().contains("Target repository: company-releases"));
        assertTrue(result.stdout().contains("Target URL: https://repo.example.test/releases"));
        assertTrue(result.stdout().contains("Artifact path: .zolt/build/demo-0.1.0.jar"));
        assertTrue(result.stdout().contains("Artifact upload path: com/example/demo/0.1.0/demo-0.1.0.jar"));
        assertTrue(result.stdout().contains("Evidence: .zolt/build/demo-0.1.0.jar.zolt-package.json"));
        assertTrue(result.stdout().contains("Generated POM: .zolt/build/publish/demo-0.1.0.pom"));
        assertTrue(result.stdout().contains("POM checksum: sha256:"));
        assertTrue(result.stdout().contains("POM upload path: com/example/demo/0.1.0/demo-0.1.0.pom"));
        assertTrue(result.stdout().contains("Checksum sidecars:"));
        assertTrue(result.stdout().contains("com/example/demo/0.1.0/demo-0.1.0.jar.md5: "));
        assertTrue(result.stdout().contains("com/example/demo/0.1.0/demo-0.1.0.jar.sha1: "));
        assertTrue(result.stdout().contains("com/example/demo/0.1.0/demo-0.1.0.jar.sha256: "));
        assertTrue(result.stdout().contains("com/example/demo/0.1.0/demo-0.1.0.pom.sha256: "));
        assertTrue(result.stdout().contains("Status: ready"));
        assertTrue(result.stdout().contains("No upload was performed."));
        assertTrue(result.stderr().contains("\u001B[36mPreparing\u001B[0m publish dry run..."));
        assertTrue(result.stderr().contains("\u001B[32mPrepared\u001B[0m publish dry run"));
        assertFalse(result.stderr().contains("\u001B[36mPreparing publish dry run...")
                || result.stderr().contains("\u001B[32mPrepared publish dry run"));
        assertTrue(Files.exists(projectDir.resolve(".zolt/build/publish/demo-0.1.0.pom")));
    }
    private static void writeProjectConfig(Path projectDir) throws IOException {
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("demo") + """
                main = "com.example.Main"

                [repositories.test]
                url = "https://repo.maven.apache.org/maven2"

                [dependencies]

                [dependencies.test]

                [build.output]
                root = ".zolt/build"
                """);
    }

    private static void writeMainSource(Path projectDir, String content) throws IOException {
        Path source = projectDir.resolve("src/main/java/com/example/Main.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
    }
}

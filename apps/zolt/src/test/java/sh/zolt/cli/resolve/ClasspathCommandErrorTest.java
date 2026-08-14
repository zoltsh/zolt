package sh.zolt.cli.resolve;

import sh.zolt.cli.CliTestRepository;

import static sh.zolt.cli.CliTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ClasspathCommandErrorTest {
    @TempDir
    private Path tempDir;

    @Test
    void classpathRejectsStaleExistingLockfileWhenProjectConfigExists() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            repository.addArtifact("com.example", "app", "1.0.0", """
                    <project>
                      <groupId>com.example</groupId>
                      <artifactId>app</artifactId>
                      <version>1.0.0</version>
                    </project>
                    """);
            repository.addArtifact("com.example", "extra", "1.0.0", """
                    <project>
                      <groupId>com.example</groupId>
                      <artifactId>extra</artifactId>
                      <version>1.0.0</version>
                    </project>
                    """);
            Path projectDir = tempDir.resolve("classpath-stale-lock");
            Path cacheRoot = tempDir.resolve("classpath-stale-lock-cache");
            ClasspathCommandTestSupport.writeProjectConfig(
                    projectDir,
                    repository.baseUri().toString(),
                    Map.of("com.example:app", "1.0.0"));
            CommandResult resolve = execute(
                    "resolve",
                    "--cwd", projectDir.toString(),
                    "--cache-root", cacheRoot.toString());
            ClasspathCommandTestSupport.writeProjectConfig(
                    projectDir,
                    repository.baseUri().toString(),
                    Map.of(
                            "com.example:app", "1.0.0",
                            "com.example:extra", "1.0.0"));

            CommandResult result = execute(
                    "classpath",
                    "--cwd", projectDir.toString(),
                    "--cache-root", cacheRoot.toString(),
                    "compile");

            assertEquals(0, resolve.exitCode());
            assertEquals(1, result.exitCode());
            assertTrue(result.stderr().contains("zolt.lock is out of date"));
            assertTrue(result.stderr().contains("Run `zolt resolve` to refresh it"));
            assertEquals("", result.stdout());
        }
    }

    @Test
    void classpathFailsWhenCachedJarDoesNotMatchLockfileHash() throws IOException {
        Path projectDir = tempDir.resolve("demo-corrupted-cache");
        Path cacheRoot = tempDir.resolve("cache-corrupted");
        Files.createDirectories(projectDir);
        String checksum = "0".repeat(64);
        Path jar = cacheRoot.resolve("blobs/v2/sha256/" + checksum + "/compile-lib-1.0.0.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "corrupted jar bytes");
        Files.writeString(projectDir.resolve("zolt.lock"), """
                version = 6

                [[package]]
                id = "com.example:compile-lib"
                version = "1.0.0"
                source = "maven-central"
                scope = "compile"
                direct = true
                jar = "blobs/v2/sha256/%s/compile-lib-1.0.0.jar"
                jarSha256 = "%s"
                dependencies = []
                """.formatted(checksum, checksum));

        CommandResult result = execute(
                "classpath",
                "--cwd", projectDir.toString(),
                "--cache-root", cacheRoot.toString(),
                "compile");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains(
                "Cached jar integrity check failed for com.example:compile-lib:1.0.0"));
        assertTrue(result.stderr().contains(
                "Expected 0000000000000000000000000000000000000000000000000000000000000000"));
        assertTrue(result.stderr().contains("Remove the cache entry or run `zolt resolve`"));
    }

    @Test
    void classpathRejectsUnknownKindWithSupportedKinds() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 1\n");

        CommandResult result = execute("classpath", "--cwd", projectDir.toString(), "processor-test");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Unknown classpath kind `processor-test`"));
        assertTrue(result.stderr().contains("compile, runtime, test, processor, test-processor, quarkus-deployment, or audit"));
    }

    @Test
    void classpathReportsMissingLockfileCleanly() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        Files.createDirectories(projectDir);

        CommandResult result = execute("classpath", "--cwd", projectDir.toString(), "runtime");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("error: Could not read zolt.lock"));
    }
}

package sh.zolt.arch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BootstrapZoltJvmTest {
    @TempDir
    private Path tempDir;

    @Test
    void contentAddressedLockDownloadsFromTheMavenRepositoryPath() throws IOException, InterruptedException {
        String digest = "a".repeat(64);
        Path lockfile = tempDir.resolve("zolt.lock");
        Files.writeString(lockfile, """
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "com.example:demo"
                version = "1.2.3"
                variant = "jar|tests"
                lane = "implementation"
                resolvedScope = "compile"

                [[package]]
                id = "com.example:demo"
                version = "1.2.3"
                source = "central"
                scope = "compile"
                direct = true
                jar = "blobs/v2/sha256/%s/demo-1.2.3-tests.jar"
                jarSha256 = "%s"
                dependencies = []
                """.formatted(digest, digest));
        Path root = RepositoryPaths.root();
        ProcessBuilder builder = new ProcessBuilder(
                "bash",
                root.resolve("scripts/bootstrap-zolt-jvm").toString(),
                "--bootstrap-list-missing");
        builder.directory(root.toFile());
        builder.redirectErrorStream(true);
        builder.environment().put("ZOLT_LOCKFILE", lockfile.toString());
        builder.environment().put("ZOLT_CACHE_ROOT", tempDir.resolve("cache").toString());
        builder.environment().put("ZOLT_MAVEN_CENTRAL", "https://repo.example/maven2");

        Process process = builder.start();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "bootstrap missing-artifact probe timed out");
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);

        assertEquals(1, process.exitValue(), output);
        assertTrue(output.contains("cache/blobs/v2/sha256/" + digest + "/demo-1.2.3-tests.jar"), output);
        assertTrue(
                output.contains("source: https://repo.example/maven2/com/example/demo/1.2.3/demo-1.2.3-tests.jar"),
                output);
        assertFalse(output.contains("repo.example/maven2/blobs/v2/"), output);
    }

    @Test
    void rejectsPreV7LockBeforeCacheOrNetworkWork() throws IOException, InterruptedException {
        Path lockfile = tempDir.resolve("legacy.lock");
        Files.writeString(lockfile, """
                version = 6

                [[package]]
                id = "com.example:demo"
                version = "1.2.3"
                source = "central"
                scope = "compile"
                direct = true
                jar = "blobs/v2/sha256/%s/demo-1.2.3.jar"
                jarSha256 = "%s"
                dependencies = []
                """.formatted("a".repeat(64), "a".repeat(64)));
        Path cache = tempDir.resolve("legacy-cache");
        Path root = RepositoryPaths.root();
        ProcessBuilder builder = new ProcessBuilder(
                "bash",
                root.resolve("scripts/bootstrap-zolt-jvm").toString(),
                "--bootstrap-list-missing");
        builder.directory(root.toFile());
        builder.redirectErrorStream(true);
        builder.environment().put("ZOLT_LOCKFILE", lockfile.toString());
        builder.environment().put("ZOLT_CACHE_ROOT", cache.toString());
        builder.environment().put("ZOLT_MAVEN_CENTRAL", "https://network-must-not-be-used.invalid");

        Process process = builder.start();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "bootstrap version check timed out");
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);

        assertEquals(1, process.exitValue(), output);
        assertTrue(output.contains("uses unsupported version 6; current version is 7"), output);
        assertFalse(output.contains("missing bootstrap artifact"), output);
        assertFalse(Files.exists(cache), output);
    }

    @Test
    void rejectsNestedVersionBeforeCacheOrNetworkWork() throws IOException, InterruptedException {
        Path lockfile = tempDir.resolve("nested-version.lock");
        Files.writeString(lockfile, """
                [metadata]
                version = 7

                [[package]]
                id = "com.example:demo"
                version = "1.2.3"
                source = "central"
                scope = "compile"
                direct = true
                jar = "blobs/v2/sha256/%s/demo-1.2.3.jar"
                jarSha256 = "%s"
                dependencies = []
                """.formatted("a".repeat(64), "a".repeat(64)));
        Path cache = tempDir.resolve("nested-version-cache");
        Path root = RepositoryPaths.root();
        ProcessBuilder builder = new ProcessBuilder(
                "bash",
                root.resolve("scripts/bootstrap-zolt-jvm").toString(),
                "--bootstrap-list-missing");
        builder.directory(root.toFile());
        builder.redirectErrorStream(true);
        builder.environment().put("ZOLT_LOCKFILE", lockfile.toString());
        builder.environment().put("ZOLT_CACHE_ROOT", cache.toString());
        builder.environment().put("ZOLT_MAVEN_CENTRAL", "https://network-must-not-be-used.invalid");

        Process process = builder.start();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "bootstrap version check timed out");
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);

        assertEquals(1, process.exitValue(), output);
        assertTrue(output.contains("missing or malformed top-level version"), output);
        assertFalse(output.contains("missing bootstrap artifact"), output);
        assertFalse(Files.exists(cache), output);
    }
}

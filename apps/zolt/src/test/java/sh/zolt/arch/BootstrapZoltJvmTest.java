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
                version = 6

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
}

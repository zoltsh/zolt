package sh.zolt.cli.quality;

import sh.zolt.cli.CliTestRepository;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.memberConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CheckExecutionContextOfflineReadyCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void checkContextCiRequireOfflineReadyPassesWithSeededCache() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            repository.addArtifact("com.example", "app", "1.0.0", """
                    <project>
                      <groupId>com.example</groupId>
                      <artifactId>app</artifactId>
                      <version>1.0.0</version>
                    </project>
                    """);
            Path projectDir = tempDir.resolve("check-context-ci-offline-ready-ok");
            Path cacheRoot = tempDir.resolve("cache-offline-ready-ok");
            writeProjectConfig(projectDir, repository.baseUri().toString(), Map.of("com.example:app", "1.0.0"));
            CommandResult resolve = execute(
                    "resolve",
                    "--cwd", projectDir.toString(),
                    "--cache-root", cacheRoot.toString());

            CommandResult result = execute(
                    "check",
                    "--context", "ci",
                    "--require-offline-ready",
                    "--cwd", projectDir.toString(),
                    "--cache-root", cacheRoot.toString());

            assertEquals(0, resolve.exitCode());
            assertEquals(0, result.exitCode());
            assertTrue(result.stdout().contains("ok lockfile zolt.lock zolt.lock matches zolt.toml and locked artifacts are available from the local cache."));
            assertEquals("", result.stderr());
        }
    }

    @Test
    void checkContextCiRequireOfflineReadyReportsMissingCache() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            repository.addArtifact("com.example", "app", "1.0.0", """
                    <project>
                      <groupId>com.example</groupId>
                      <artifactId>app</artifactId>
                      <version>1.0.0</version>
                    </project>
                    """);
            Path projectDir = tempDir.resolve("check-context-ci-offline-ready-missing-cache");
            Path seededCache = tempDir.resolve("cache-offline-ready-seeded");
            Path emptyCache = tempDir.resolve("cache-offline-ready-empty");
            writeProjectConfig(projectDir, repository.baseUri().toString(), Map.of("com.example:app", "1.0.0"));
            CommandResult resolve = execute(
                    "resolve",
                    "--cwd", projectDir.toString(),
                    "--cache-root", seededCache.toString());

            CommandResult result = execute(
                    "check",
                    "--context", "ci",
                    "--require-offline-ready",
                    "--cwd", projectDir.toString(),
                    "--cache-root", emptyCache.toString());

            assertEquals(0, resolve.exitCode());
            assertEquals(1, result.exitCode());
            assertTrue(result.stdout().contains("error lockfile zolt.lock Offline mode requires cached POM"));
            assertTrue(result.stdout().contains("next: Run `zolt resolve` to seed the cache, then retry `zolt check --context ci --require-offline-ready`."));
            assertEquals("", result.stderr());
        }
    }

    private static void writeProjectConfig(
            Path projectDir,
            String repositoryUrl,
            Map<String, String> dependencies) throws IOException {
        Files.createDirectories(projectDir);
        StringBuilder config = new StringBuilder(memberConfig("demo") + """
                main = "com.example.Main"

                [repositories]
                central = false

                [repositories.test]
                url = "%s"

                [dependencies]
                """.formatted(repositoryUrl));
        dependencies.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> config.append('"')
                        .append(entry.getKey())
                        .append("\" = \"")
                        .append(entry.getValue())
                        .append("\"\n"));
        config.append("""

                [dependencies.test]
                """);
        Files.writeString(projectDir.resolve("zolt.toml"), config.toString());
    }
}

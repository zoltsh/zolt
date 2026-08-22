package sh.zolt.cli.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;

/**
 * Design §4.5: "A resolving command started from a member updates the complete authoritative
 * workspace snapshot." Before this routed, {@code cd apps/api && zolt resolve} composed the member
 * against its workspace root and then resolved it as if it stood alone, writing
 * {@code apps/api/zolt.lock} — a file the language does not have — and leaving the root lock, and the
 * sibling's half of the graph, untouched.
 */
final class MemberResolveRoutingTest {
    @TempDir
    private Path tempDir;

    @Test
    void memberResolveUpdatesRootLockAndCreatesNoMemberLock() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            repository.addArtifact("com.example", "api-only", "1.0.0", pom("api-only"));
            repository.addArtifact("com.example", "sibling-only", "1.0.0", pom("sibling-only"));
            Path workspaceDir = workspace(repository);
            Path apiDir = workspaceDir.resolve("apps/api");
            Path cacheRoot = tempDir.resolve("cache");

            CommandResult result = execute(
                    "resolve",
                    "--cwd", apiDir.toString(),
                    "--cache-root", cacheRoot.toString());

            assertEquals(0, result.exitCode(), result.stderr());
            assertFalse(
                    Files.exists(apiDir.resolve("zolt.lock")),
                    "a member-directory resolve must never create a member-local lock");
            String rootLock = Files.readString(workspaceDir.resolve("zolt.lock"));
            // The COMPLETE snapshot: the started-in member and its sibling's own graph.
            assertTrue(rootLock.contains("com.example:api-only"), rootLock);
            assertTrue(rootLock.contains("com.example:sibling-only"), rootLock);
            assertTrue(rootLock.contains("workspaceResolutionInputFingerprint"), rootLock);
        }
    }

    /** The next step a member-directory resolve names is the one that works from where the user is. */
    @Test
    void memberResolveNamesTheMemberDirectoryNextStep() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            repository.addArtifact("com.example", "api-only", "1.0.0", pom("api-only"));
            repository.addArtifact("com.example", "sibling-only", "1.0.0", pom("sibling-only"));
            Path apiDir = workspace(repository).resolve("apps/api");

            CommandResult result = execute(
                    "resolve",
                    "--cwd", apiDir.toString(),
                    "--cache-root", tempDir.resolve("cache").toString());

            assertEquals(0, result.exitCode(), result.stderr());
            assertTrue(result.stdout().contains("zolt build"), result.stdout());
            assertFalse(result.stdout().contains("zolt build --workspace"), result.stdout());
        }
    }

    private Path workspace(CliTestRepository repository) throws IOException {
        Path workspaceDir = tempDir.resolve("workspace");
        Path apiDir = workspaceDir.resolve("apps/api");
        Path coreDir = workspaceDir.resolve("libs/core");
        Files.createDirectories(apiDir);
        Files.createDirectories(coreDir);
        Files.writeString(workspaceDir.resolve("zolt.toml"), """
                [workspace]
                name = "platform"

                [workspace.members]
                include = ["apps/*", "libs/*"]

                [workspace.project]
                group = "com.example"
                version = "0.1.0"
                java = %s

                [repositories]
                central = false

                [repositories.test]
                url = "%s"
                """.formatted(Runtime.version().feature(), repository.baseUri()));
        Files.writeString(apiDir.resolve("zolt.toml"), """
                [project]
                name = "api"

                [dependencies]
                "com.example:api-only" = "1.0.0"
                """);
        Files.writeString(coreDir.resolve("zolt.toml"), """
                [project]
                name = "core"

                [dependencies]
                "com.example:sibling-only" = "1.0.0"
                """);
        return workspaceDir;
    }

    private static String pom(String artifactId) {
        return """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>%s</artifactId>
                  <version>1.0.0</version>
                </project>
                """.formatted(artifactId);
    }
}

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CheckDependencyMetadataCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void checkDependencyMetadataPassesForOptionalPublishOnlyAndExclusions() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            repository.addArtifact("org.example", "lib", "1.0.0", """
                    <project>
                      <groupId>org.example</groupId>
                      <artifactId>lib</artifactId>
                      <version>1.0.0</version>
                      <dependencies>
                        <dependency>
                          <groupId>org.example</groupId>
                          <artifactId>excluded</artifactId>
                          <version>1.0.0</version>
                        </dependency>
                        <dependency>
                          <groupId>org.example</groupId>
                          <artifactId>kept</artifactId>
                          <version>1.0.0</version>
                        </dependency>
                      </dependencies>
                    </project>
                    """);
            repository.addArtifact("org.example", "kept", "1.0.0", pom("org.example", "kept", "1.0.0"));
            Path projectDir = tempDir.resolve("check-dependency-metadata");
            Files.createDirectories(projectDir);
            Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("check-dependency-metadata") + """

                    [repositories.test]
                    url = "%s"

                    [dependencies]
                    "org.example:lib" = { version = "1.0.0", optional = true, exclude = ["org.example:excluded"] }
                    "org.example:publish-only" = { version = "1.0.0", publishOnly = true }
                    """.formatted(repository.baseUri()));
            Path cacheRoot = tempDir.resolve("cache");
            CommandResult resolve = execute("resolve", "--cwd", projectDir.toString(), "--cache-root", cacheRoot.toString());
            assertEquals(0, resolve.exitCode());

            CommandResult result = execute(
                    "check",
                    "--cwd", projectDir.toString(),
                    "--cache-root", cacheRoot.toString(),
                    "--check", "dependency-metadata");

            assertEquals(0, result.exitCode());
            assertTrue(result.stdout().contains("ok dependency-metadata org.example:lib Dependency metadata for `org.example:lib` is represented in zolt.lock."));
            assertTrue(result.stdout().contains("ok dependency-metadata org.example:publish-only Publish-only dependency `org.example:publish-only` is kept out of zolt.lock classpaths."));
            assertEquals("", result.stderr());
        }
    }

    @Test
    void checkDependencyMetadataReportsPublishOnlyLeakage() throws IOException {
        Path projectDir = tempDir.resolve("check-publish-only-leak");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("check-publish-only-leak") + """

                [dependencies]
                "org.example:publish-only" = { version = "1.0.0", publishOnly = true }
                """);
        Files.writeString(projectDir.resolve("zolt.lock"), """
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "org.example:publish-only"
                version = "1.0.0"
                lane = "implementation"
                resolvedScope = "compile"

                [[package]]
                id = "org.example:publish-only"
                version = "1.0.0"
                source = "maven-central"
                scope = "compile"
                direct = true
                dependencies = []
                """);

        CommandResult result = execute("check", "--cwd", projectDir.toString(), "--check", "dependency-metadata");

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("error dependency-metadata org.example:publish-only Publish-only dependency `org.example:publish-only` is present in zolt.lock."));
        assertTrue(result.stdout().contains("next: Run `zolt resolve`; if it remains, remove publishOnly = true"));
        assertEquals("", result.stderr());
    }

    @Test
    void checkDependencyMetadataReportsExcludedDependencyOnDirectEdge() throws IOException {
        Path projectDir = tempDir.resolve("check-exclusion-leak");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("check-exclusion-leak") + """

                [dependencies]
                "org.example:lib" = { version = "1.0.0", exclude = ["org.example:excluded"] }
                """);
        Files.writeString(projectDir.resolve("zolt.lock"), """
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "org.example:lib"
                version = "1.0.0"
                lane = "implementation"
                resolvedScope = "compile"

                [[package]]
                id = "org.example:lib"
                version = "1.0.0"
                source = "maven-central"
                scope = "compile"
                direct = true
                dependencies = ["org.example:excluded:1.0.0:jar:runtime"]
                """);

        CommandResult result = execute("check", "--cwd", projectDir.toString(), "--check", "dependency-metadata");

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("error dependency-metadata org.example:lib Excluded dependency `org.example:excluded` is still present"));
        assertTrue(result.stdout().contains("next: Check [dependencies].org.example:lib.exclusions and run `zolt resolve`."));
        assertEquals("", result.stderr());
    }

    @Test
    void checkWorkspaceDependencyMetadataValidatesExportedApiEdges() throws IOException {
        Path workspaceDir = tempDir.resolve("check-workspace-dependency-metadata");
        Path apiDir = workspaceDir.resolve("api");
        Path bindingDir = workspaceDir.resolve("binding");
        Files.createDirectories(apiDir);
        Files.createDirectories(bindingDir);
        Files.writeString(workspaceDir.resolve("zolt.toml"), """
                [workspace]
                name = "check-workspace-dependency-metadata"

                [workspace.members]
                include = ["api", "binding"]
                """);
        Files.writeString(apiDir.resolve("zolt.toml"), memberConfig("api"));
        Files.writeString(bindingDir.resolve("zolt.toml"), memberConfig("binding") + """

                [dependencies.api]
                "com.example:api" = { workspace = true }
                """);
        Path cacheRoot = tempDir.resolve("cache");
        CommandResult resolve = execute(
                "resolve",
                "--workspace",
                "--cwd", workspaceDir.toString(),
                "--cache-root", cacheRoot.toString());
        assertEquals(0, resolve.exitCode());

        CommandResult result = execute(
                "check",
                "--workspace",
                "--cwd", workspaceDir.toString(),
                "--cache-root", cacheRoot.toString(),
                "--check", "dependency-metadata");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("ok dependency-metadata binding com.example:api Workspace API dependency `com.example:api` is exported through zolt.lock."));
        assertEquals("", result.stderr());
    }

    private static String pom(String groupId, String artifactId, String version) {
        return """
                <project>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(groupId, artifactId, version);
    }
}

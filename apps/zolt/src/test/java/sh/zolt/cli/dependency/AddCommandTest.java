package sh.zolt.cli.dependency;

import sh.zolt.cli.CliTestRepository;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.memberConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AddCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void addRefreshesLockfileByDefault() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            repository.addArtifact("com.example", "app", "1.0.0", """
                    <project>
                      <groupId>com.example</groupId>
                      <artifactId>app</artifactId>
                      <version>1.0.0</version>
                    </project>
                    """);
            Path projectDir = tempDir.resolve("demo");
            writeProjectConfig(projectDir, repository.baseUri().toString());

            CommandResult result = execute(
                    "add",
                    "--cwd", projectDir.toString(),
                    "--cache-root", tempDir.resolve("cache").toString(),
                    "com.example:app:1.0.0");

            assertEquals(0, result.exitCode(), result.stderr());
            assertTrue(result.stdout().contains("Added dependency com.example:app:1.0.0 to [dependencies]"));
            assertTrue(result.stdout().contains("Resolved 1 packages"));
            assertTrue(result.stdout().contains("2 downloaded"));
            assertTrue(Files.exists(projectDir.resolve("zolt.lock")));
        }
    }

    @Test
    void memberAddUpdatesRootLockAndNeverCreatesMemberLock() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            repository.addArtifact("com.example", "app", "1.0.0", """
                    <project>
                      <groupId>com.example</groupId>
                      <artifactId>app</artifactId>
                      <version>1.0.0</version>
                    </project>
                    """);
            Path workspace = tempDir.resolve("workspace");
            Path member = workspace.resolve("apps/api");
            Files.createDirectories(member);
            Files.writeString(workspace.resolve("zolt.toml"), """
                    [workspace]
                    name = "workspace"

                    [workspace.members]
                    include = ["apps/api"]

                    [repositories.test]
                    url = "%s"
                    """.formatted(repository.baseUri().toString()));
            writeMemberConfig(member, Map.of());
            Path cache = tempDir.resolve("workspace-cache");
            CommandResult initialResolve = execute(
                    "resolve", "--workspace", "--cwd", member.toString(), "--cache-root", cache.toString());
            assertEquals(0, initialResolve.exitCode(), initialResolve.stderr());
            String originalRootLock = Files.readString(workspace.resolve("zolt.lock"));

            CommandResult result = execute(
                    "add",
                    "--cwd", member.toString(),
                    "--cache-root", cache.toString(),
                    "com.example:app:1.0.0");

            assertEquals(0, result.exitCode(), result.stderr());
            String updatedRootLock = Files.readString(workspace.resolve("zolt.lock"));
            assertFalse(originalRootLock.equals(updatedRootLock));
            assertTrue(updatedRootLock.contains("com.example:app"), updatedRootLock);
            assertFalse(Files.exists(member.resolve("zolt.lock")));
        }
    }

    @Test
    void failedMemberRemoveRestoresMemberManifestAndRootLock() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            repository.addArtifact("com.example", "old", "1.0.0", pom("old", "1.0.0"));
            Path workspace = tempDir.resolve("remove-workspace");
            Path member = workspace.resolve("apps/api");
            Files.createDirectories(member);
            Files.writeString(workspace.resolve("zolt.toml"), """
                    [workspace]
                    name = "workspace"

                    [workspace.members]
                    include = ["apps/api"]

                    [repositories.test]
                    url = "%s"
                    """.formatted(repository.baseUri().toString()));
            writeMemberConfig(member, Map.of("com.example:old", "1.0.0"));
            Path cache = tempDir.resolve("remove-workspace-cache");
            CommandResult initialResolve = execute(
                    "resolve", "--workspace", "--cwd", member.toString(), "--cache-root", cache.toString());
            assertEquals(0, initialResolve.exitCode(), initialResolve.stderr());
            Path manifest = member.resolve("zolt.toml");
            String withMissingDependency = Files.readString(manifest).replace(
                    "[dependencies]\n",
                    "[dependencies]\n\"com.example:missing\" = \"1.0.0\"\n");
            Files.writeString(manifest, withMissingDependency);
            String originalRootLock = Files.readString(workspace.resolve("zolt.lock"));

            CommandResult result = execute(
                    "remove",
                    "--cwd", member.toString(),
                    "--cache-root", cache.toString(),
                    "com.example:old");

            assertEquals(1, result.exitCode());
            assertEquals(withMissingDependency, Files.readString(manifest));
            assertEquals(originalRootLock, Files.readString(workspace.resolve("zolt.lock")));
            assertFalse(Files.exists(member.resolve("zolt.lock")));
            assertFalse(Files.exists(workspace.resolve(".zolt/manifest-edits/YXBwcy9hcGk")));
        }
    }

    @Test
    void memberUpdateUsesWholeWorkspaceResolution() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            repository.addArtifact("com.example", "library", "1.0.0", pom("library", "1.0.0"));
            repository.addArtifact("com.example", "library", "1.1.0", pom("library", "1.1.0"));
            repository.addMetadata("com.example", "library", """
                    <metadata>
                      <groupId>com.example</groupId>
                      <artifactId>library</artifactId>
                      <versioning>
                        <latest>1.1.0</latest>
                        <release>1.1.0</release>
                        <versions><version>1.0.0</version><version>1.1.0</version></versions>
                        <lastUpdated>20260809000000</lastUpdated>
                      </versioning>
                    </metadata>
                    """);
            Path workspace = tempDir.resolve("update-workspace");
            Path member = workspace.resolve("apps/api");
            Files.createDirectories(member);
            Files.writeString(workspace.resolve("zolt.toml"), """
                    [workspace]
                    name = "workspace"

                    [workspace.members]
                    include = ["apps/api"]

                    [repositories.test]
                    url = "%s"
                    """.formatted(repository.baseUri().toString()));
            writeMemberConfig(member, Map.of("com.example:library", "1.0.0"));
            Path cache = tempDir.resolve("update-workspace-cache");
            CommandResult initialResolve = execute(
                    "resolve", "--workspace", "--cwd", member.toString(), "--cache-root", cache.toString());
            assertEquals(0, initialResolve.exitCode(), initialResolve.stderr());

            CommandResult result = execute(
                    "update",
                    "--cwd", member.toString(),
                    "--cache-root", cache.toString());

            assertEquals(0, result.exitCode(), result.stderr());
            assertTrue(Files.readString(member.resolve("zolt.toml"))
                    .contains("\"com.example:library\" = \"1.1.0\""));
            assertTrue(Files.readString(workspace.resolve("zolt.lock")).contains("version = \"1.1.0\""));
            assertFalse(Files.exists(member.resolve("zolt.lock")));
        }
    }

    private static void writeProjectConfig(Path projectDir) throws IOException {
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
    }

    private static void writeProjectConfig(Path projectDir, String repositoryUrl) throws IOException {
        writeProjectConfig(projectDir, repositoryUrl, Map.of());
    }

    private static void writeProjectConfig(
            Path projectDir,
            String repositoryUrl,
            Map<String, String> dependencies) throws IOException {
        writeProjectConfig(projectDir, repositoryUrl, dependencies, true);
    }

    /** A workspace member inherits the root repository universe and declares none of its own. */
    private static void writeMemberConfig(
            Path memberDir,
            Map<String, String> dependencies) throws IOException {
        writeProjectConfig(memberDir, null, dependencies, false);
    }

    private static void writeProjectConfig(
            Path projectDir,
            String repositoryUrl,
            Map<String, String> dependencies,
            boolean ownRepository) throws IOException {
        Files.createDirectories(projectDir);
        StringBuilder config = new StringBuilder(memberConfig("demo") + """
                main = "com.example.Main"
                """ + (ownRepository ? """

                [repositories.test]
                url = "%s"
                """.formatted(repositoryUrl) : "") + """

                [dependencies]
                """);
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

    private static int occurrences(String value, String needle) {
        int count = 0;
        int index = value.indexOf(needle);
        while (index >= 0) {
            count++;
            index = value.indexOf(needle, index + needle.length());
        }
        return count;
    }

    private static String pom(String artifact, String version) {
        return """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(artifact, version);
    }
}

package sh.zolt.cli.workspace;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.writeFakeConsoleJar;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GeneratedWorkspaceInitFlowTest {
    @TempDir
    private Path tempDir;

    @Test
    void generatedWorkspaceResolvesBuildsTestsAndRuns() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            runGeneratedWorkspaceFlow(repository);
        }
    }

    private void runGeneratedWorkspaceFlow(CliTestRepository repository) throws IOException {
        Path cacheRoot = tempDir.resolve("cache");
        addJUnitArtifacts(repository);

        CommandResult init = execute(
                "init",
                "--workspace",
                "--directory", tempDir.toString(),
                "platform");

        Path workspaceDir = tempDir.resolve("platform");
        Path appDir = workspaceDir.resolve("apps/platform");
        assertEquals(0, init.exitCode());
        assertTrue(init.stdout().contains("Created Zolt workspace at"));
        assertTrue(Files.exists(workspaceDir.resolve("zolt.toml")));
        assertFalse(Files.exists(workspaceDir.resolve("zolt-workspace.toml")));
        assertTrue(Files.exists(appDir.resolve("zolt.toml")));

        String workspaceToml = Files.readString(workspaceDir.resolve("zolt.toml"));
        assertTrue(workspaceToml.contains("[workspace]"));
        assertTrue(workspaceToml.contains("[workspace.members]"), workspaceToml);
        assertTrue(workspaceToml.contains("default = [\"apps/platform\"]"), workspaceToml);
        assertTrue(workspaceToml.contains("include = [\"apps/platform\"]"), workspaceToml);
        useTestRepository(workspaceDir.resolve("zolt.toml"), repository);

        CommandResult resolve = execute(
                "resolve",
                "--workspace",
                "--cwd", workspaceDir.toString(),
                "--cache-root", cacheRoot.toString());
        assertEquals(0, resolve.exitCode());
        assertEquals("", resolve.stderr());
        assertTrue(resolve.stdout().contains("Resolved 3 packages"));
        assertTrue(Files.exists(workspaceDir.resolve("zolt.lock")));
        assertTrue(Files.readString(workspaceDir.resolve("zolt.lock"))
                .contains("id = \"org.junit.jupiter:junit-jupiter\""));

        CommandResult build = execute(
                "build",
                "--workspace",
                "--cwd", workspaceDir.toString(),
                "--cache-root", cacheRoot.toString());
        assertEquals(0, build.exitCode());
        assertEquals("", build.stderr());
        assertTrue(build.stdout().contains("Compiled 1 main source files in apps/platform"));
        assertTrue(Files.exists(appDir.resolve("target/classes/com/example/Main.class")));

        CommandResult run = execute(
                "run",
                "--workspace",
                "--cwd", workspaceDir.toString(),
                "--cache-root", cacheRoot.toString());
        assertEquals(0, run.exitCode());
        assertEquals("", run.stderr());
        assertTrue(run.stdout().contains("Hello from platform!"));
        assertTrue(run.stdout().contains("Ran com.example.Main in apps/platform"));

        CommandResult test = execute(
                "test",
                "--workspace",
                "--cwd", workspaceDir.toString(),
                "--cache-root", cacheRoot.toString());

        assertEquals(0, test.exitCode());
        assertEquals("", test.stderr());
        assertTrue(test.stdout().contains("fake console"));
        assertTrue(test.stdout().contains("Tests passed in apps/platform"));
        assertTrue(test.stdout().contains("Tests passed for 1 workspace members"));
        assertTrue(Files.exists(appDir.resolve("target/test-classes/com/example/MainTest.class")));
    }

    private void addJUnitArtifacts(CliTestRepository repository) throws IOException {
        repository.addArtifact(
                "org.junit.jupiter",
                "junit-jupiter",
                "5.14.4",
                """
                <project>
                  <groupId>org.junit.jupiter</groupId>
                  <artifactId>junit-jupiter</artifactId>
                  <version>5.14.4</version>
                  <dependencies>
                    <dependency>
                      <groupId>org.junit.jupiter</groupId>
                      <artifactId>junit-jupiter-api</artifactId>
                      <version>5.14.4</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        repository.addArtifact(
                "org.junit.jupiter",
                "junit-jupiter-api",
                "5.14.4",
                pom("org.junit.jupiter", "junit-jupiter-api", "5.14.4"),
                bundledJUnitJar());
        Path fakeConsole = tempDir.resolve("fake-console/junit-platform-console-1.14.4.jar");
        writeFakeConsoleJar(fakeConsole);
        repository.addArtifact(
                "org.junit.platform",
                "junit-platform-console",
                "1.14.4",
                pom("org.junit.platform", "junit-platform-console", "1.14.4"),
                Files.readAllBytes(fakeConsole));
    }

    private static byte[] bundledJUnitJar() throws IOException {
        try {
            Path jar = Path.of(Test.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
            return Files.readAllBytes(jar);
        } catch (Exception exception) {
            throw new IOException("Could not read the bundled JUnit test jar.", exception);
        }
    }

    /**
     * A generated manifest is sparse and names no repository, so the fixture appends the closed
     * universe it needs. Only the workspace root may own it (design 8.7).
     */
    private static void useTestRepository(Path config, CliTestRepository repository) throws IOException {
        Files.writeString(config, Files.readString(config) + """

                [repositories]
                central = false

                [repositories.test]
                url = "%s"
                """.formatted(repository.baseUri()));
    }

    private static String pom(String group, String artifact, String version) {
        return """
                <project>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(group, artifact, version);
    }
}

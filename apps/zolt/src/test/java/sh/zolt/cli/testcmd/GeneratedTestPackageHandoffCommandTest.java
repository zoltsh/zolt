package sh.zolt.cli.testcmd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GeneratedTestPackageHandoffCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void testPreservesJvmToolPackagesAndPackagesFreshTestsJar()
            throws IOException {
        Path cache = tempDir.resolve("jvm-cache");
        Path project = GeneratedTestCommandFixture.jvmProject(
                tempDir.resolve("jvm-project"),
                cache,
                "jvm-project",
                false,
                true);

        assertSuccess(run(project, cache, "test"));
        assertTrue(Files.isRegularFile(project.resolve(
                "target/test-classes/com/example/generated/GeneratedFixture.class")));
        CommandResult packaged = run(project, cache, "package");

        assertSuccess(packaged);
        assertTrue(Files.isRegularFile(project.resolve(
                "target/jvm-project-0.1.0-tests.jar")));
    }

    @Test
    void workspaceTestPreservesJvmToolGroupPackages()
            throws IOException {
        Path cache = tempDir.resolve("workspace-cache");
        Path workspace = tempDir.resolve("workspace");
        Path member = GeneratedTestCommandFixture.jvmWorkspace(
                workspace,
                cache,
                false);

        CommandResult result = execute(
                "test",
                "--workspace",
                "--all",
                "--cwd", workspace.toString(),
                "--cache-root", cache.toString());

        assertSuccess(result);
        assertTrue(Files.isRegularFile(member.resolve(
                "target/test-classes/com/example/generated/GeneratedFixture.class")));
    }

    @Test
    void workspaceIntegrationTestPreservesJvmToolGroupPackages()
            throws IOException {
        Path cache = tempDir.resolve("workspace-integration-cache");
        Path workspace = tempDir.resolve("workspace-integration");
        Path member = GeneratedTestCommandFixture.jvmWorkspace(
                workspace,
                cache,
                true);

        CommandResult result = execute(
                "integration-test",
                "--workspace",
                "--all",
                "--cwd", workspace.toString(),
                "--cache-root", cache.toString());

        assertSuccess(result);
        assertTrue(Files.isRegularFile(member.resolve(
                "target/integration-test-classes/com/example/generated/GeneratedFixture.class")));
    }

    @Test
    void testPreservesOpenApiToolPackages()
            throws IOException {
        Path cache = tempDir.resolve("openapi-cache");
        Path project = GeneratedTestCommandFixture.openApiProject(
                tempDir.resolve("openapi-project"),
                cache);

        CommandResult result = run(project, cache, "test");

        assertSuccess(result);
        assertTrue(Files.isRegularFile(project.resolve(
                "target/test-classes/com/example/generated/GeneratedApi.class")));
    }

    @Test
    void projectRunnerKeepsExternalRuntimePackages()
            throws IOException {
        Path cache = tempDir.resolve("project-runner-cache");
        Path project =
                GeneratedTestCommandFixture.projectRunnerProject(
                        tempDir.resolve("project-runner"),
                        cache);

        CommandResult result = run(project, cache, "test");

        assertSuccess(result);
        assertEquals(
                "runtime-present\n",
                Files.readString(project.resolve(
                        "target/test-classes/runtime.txt")));
    }

    @Test
    void integrationTestPreservesJvmToolPackages()
            throws IOException {
        Path cache = tempDir.resolve("integration-cache");
        Path project = GeneratedTestCommandFixture.jvmProject(
                tempDir.resolve("integration-project"),
                cache,
                "integration-project",
                true,
                false);

        CommandResult result =
                run(project, cache, "integration-test");

        assertSuccess(result);
        assertTrue(Files.isRegularFile(project.resolve(
                "target/integration-test-classes/com/example/generated/GeneratedFixture.class")));
    }

    private static CommandResult run(
            Path project,
            Path cache,
            String command) {
        return execute(
                command,
                "--cwd", project.toString(),
                "--cache-root", cache.toString());
    }

    private static void assertSuccess(CommandResult result) {
        assertEquals(
                0,
                result.exitCode(),
                () -> result.stdout() + result.stderr());
    }
}

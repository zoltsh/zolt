package sh.zolt.cli.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GeneratedTestPackageScopeCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void mainOnlyPackagePlanQualityAndPublishIgnoreUnavailableTestTool()
            throws IOException {
        Path project = project(
                "main-only-test-tool",
                unavailableProcessGenerator(false));
        Path cache = tempDir.resolve("main-only-cache");

        CommandResult plan = run(project, cache, "package", "--plan");
        CommandResult packaged = run(project, cache, "package");
        CommandResult quality = run(
                project,
                cache,
                "check",
                "--check",
                "package-contents");
        CommandResult publish = run(
                project,
                cache,
                "publish",
                "--dry-run");

        assertSuccess(plan);
        assertSuccess(packaged);
        assertSuccess(quality);
        assertSuccess(publish);
    }

    @Test
    void testsPackageReportsUnavailableTestToolActionably()
            throws IOException {
        Path project = project(
                "tests-missing-tool",
                unavailableProcessGenerator(true));

        CommandResult result = run(
                project,
                tempDir.resolve("tests-missing-cache"),
                "package",
                "--plan");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains(
                "[generated.test.fixtures] could not find process binary "
                        + "`zolt-missing-test-generator`"));
        assertTrue(result.stderr().contains(
                "Install `zolt-missing-test-generator`"));
    }

    @Test
    void changingTestGeneratorDoesNotStaleMainOnlyPackage()
            throws IOException {
        Path project = project(
                "main-test-change",
                declaredTestGenerator(false, "fixtures-v1.sql"));
        Path cache = tempDir.resolve("main-test-change-cache");
        assertSuccess(run(project, cache, "package"));

        writeConfig(
                project,
                "main-test-change",
                declaredTestGenerator(false, "fixtures-v2.sql"));
        CommandResult checked = run(
                project,
                cache,
                "check",
                "--check",
                "package-contents");

        assertSuccess(checked);
        assertTrue(checked.stdout().contains(
                "ok package-contents main-test-change"));
    }

    @Test
    void changingTestGeneratorStalesPackageWithTestsJar()
            throws IOException {
        Path project = project(
                "tests-test-change",
                declaredTestGenerator(true, "fixtures-v1.sql"));
        Path testClass = project.resolve(
                "target/test-classes/com/example/DemoTest.class");
        Files.createDirectories(testClass.getParent());
        Files.write(testClass, new byte[] {1, 2, 3});
        Path cache = tempDir.resolve("tests-test-change-cache");
        assertSuccess(run(project, cache, "package"));

        writeConfig(
                project,
                "tests-test-change",
                declaredTestGenerator(true, "fixtures-v2.sql"));
        CommandResult checked = run(
                project,
                cache,
                "check",
                "--check",
                "package-contents");

        assertEquals(1, checked.exitCode());
        assertTrue(checked.stdout().contains(
                "supplemental package input `tests` changed after packaging"));
        assertTrue(checked.stdout().contains(
                "Run `zolt package` to regenerate"));
    }

    @Test
    void workspaceMemberUsesTheSameSelectedOutputBoundary()
            throws IOException {
        Path workspace = tempDir.resolve("workspace-test-tool");
        Path member = workspace.resolve("apps/app");
        Files.createDirectories(member);
        Files.writeString(workspace.resolve("zolt-workspace.toml"), """
                [workspace]
                name = "test-tool-workspace"
                members = ["apps/app"]
                """);
        writeProject(
                member,
                "app",
                unavailableProcessGenerator(false));
        Path cache = tempDir.resolve("workspace-test-tool-cache");
        assertSuccess(runWorkspace(workspace, cache, "resolve"));
        assertSuccess(runWorkspace(workspace, cache, "package"));
        assertTrue(Files.isRegularFile(
                member.resolve("target/app-0.1.0.jar")));

        writeConfig(
                member,
                "app",
                unavailableProcessGenerator(true));
        assertSuccess(runWorkspace(workspace, cache, "resolve"));
        CommandResult result =
                runWorkspace(workspace, cache, "package");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains(
                "[generated.test.fixtures] could not find process binary "
                        + "`zolt-missing-test-generator`"));
    }

    private Path project(String name, String generatedConfig)
            throws IOException {
        Path project = tempDir.resolve(name);
        Files.createDirectories(project);
        writeProject(project, name, generatedConfig);
        Files.writeString(project.resolve("zolt.lock"), "version = 1\n");
        return project;
    }

    private static void writeProject(
            Path project,
            String name,
            String generatedConfig) throws IOException {
        writeConfig(project, name, generatedConfig);
        Path source = project.resolve(
                "src/main/java/com/example/Main.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example;

                public final class Main {
                    private Main() {
                    }
                }
                """);
        Files.writeString(project.resolve("fixtures.sql"), "seed\n");
    }

    private static void writeConfig(
            Path project,
            String name,
            String generatedConfig) throws IOException {
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "com.example"
                java = "%s"

                %s
                [publish]
                releaseRepository = "company-releases"

                [publish.repositories.company-releases]
                url = "https://repo.example.test/releases"
                """.formatted(
                name,
                currentJavaMajorVersion(),
                generatedConfig));
    }

    private static String unavailableProcessGenerator(
            boolean packageTests) {
        return """
                [generated.execTools.test-generator]
                runner = "process"
                binary = "zolt-missing-test-generator"
                versionCommand = ["zolt-missing-test-generator", "--version"]
                allowUnpinnedTool = true

                [generated.test.fixtures]
                kind = "exec"
                tool = "test-generator"
                inputs = ["fixtures.sql"]
                output = "target/generated/test-fixtures"
                produces = "test-resources"

                [package]
                tests = %s

                """.formatted(packageTests);
    }

    private static String declaredTestGenerator(
            boolean packageTests,
            String input) {
        return """
                [generated.test.fixtures]
                kind = "declared-root"
                language = "java"
                inputs = ["%s"]
                output = "target/generated/test-fixtures"
                required = false

                [package]
                tests = %s

                """.formatted(input, packageTests);
    }

    private static CommandResult run(
            Path project,
            Path cache,
            String... command) {
        String[] args =
                java.util.Arrays.copyOf(command, command.length + 4);
        args[command.length] = "--cwd";
        args[command.length + 1] = project.toString();
        args[command.length + 2] = "--cache-root";
        args[command.length + 3] = cache.toString();
        return execute(args);
    }

    private static CommandResult runWorkspace(
            Path workspace,
            Path cache,
            String command) {
        if ("resolve".equals(command)) {
            return execute(
                    command,
                    "--workspace",
                    "--cwd",
                    workspace.toString(),
                    "--cache-root",
                    cache.toString());
        }
        return execute(
                command,
                "--workspace",
                "--all",
                "--cwd",
                workspace.toString(),
                "--cache-root",
                cache.toString());
    }

    private static void assertSuccess(CommandResult result) {
        assertEquals(
                0,
                result.exitCode(),
                () -> result.stdout() + result.stderr());
    }

    private static String currentJavaMajorVersion() {
        String version = System.getProperty("java.version");
        String[] parts = version.split("[._+-]", -1);
        return parts.length >= 2 && "1".equals(parts[0])
                ? parts[1]
                : parts[0];
    }
}

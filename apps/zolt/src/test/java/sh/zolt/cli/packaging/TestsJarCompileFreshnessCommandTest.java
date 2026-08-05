package sh.zolt.cli.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import sh.zolt.build.BuildResultWithClasspaths;
import sh.zolt.build.BuildService;
import sh.zolt.build.testruntime.TestRunService;
import sh.zolt.build.testruntime.compile.TestCompileService;
import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.ZoltTomlParser;
import sh.zolt.workspace.packaging.WorkspacePackageService;
import sh.zolt.workspace.service.WorkspaceBuildPlan;
import sh.zolt.workspace.service.WorkspaceBuildResult;
import sh.zolt.workspace.service.WorkspaceMember;
import sh.zolt.workspace.service.WorkspaceSelectionRequest;
import sh.zolt.workspace.service.WorkspacePlanTarget;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TestsJarCompileFreshnessCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void packageQualityAndPublishRejectTestSourceChangedAfterCompile()
            throws IOException {
        Path project = project(
                tempDir.resolve("stale-tests-commands"),
                "stale-tests-commands");
        Path cache = tempDir.resolve("stale-tests-cache");
        compileTests(project, cache);
        assertSuccess(run(project, cache, "package"));

        Files.writeString(
                project.resolve(
                        "src/test/java/com/example/DemoTest.java"),
                """
                package com.example;
                final class DemoTest {
                    int changed;
                }
                """);

        CommandResult packaged = run(
                project,
                cache,
                "package");
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

        assertEquals(1, packaged.exitCode());
        assertContains(
                packaged,
                "compiled test output is not current");
        assertContains(packaged, "Run `zolt test`");
        assertEquals(1, quality.exitCode());
        assertContains(
                quality,
                "supplemental package input `tests` changed after packaging");
        assertEquals(1, publish.exitCode());
        assertContains(
                publish,
                "stale package evidence: supplemental package input `tests` changed after packaging");
        assertContains(publish, "tests");
    }

    @Test
    void workspaceMemberPackageRejectsStaleTestCompile()
            throws IOException {
        Path workspace = tempDir.resolve("workspace-stale-tests");
        Path member = project(
                workspace.resolve("apps/app"),
                "app");
        Files.writeString(
                workspace.resolve("zolt-workspace.toml"),
                """
                [workspace]
                name = "stale-tests-workspace"
                members = ["apps/app"]
                """);
        Path cache = tempDir.resolve("workspace-stale-tests-cache");
        assertSuccess(runWorkspace(
                workspace,
                cache,
                "resolve"));
        compileWorkspaceTests(workspace, cache);
        assertSuccess(runWorkspace(
                workspace,
                cache,
                "package"));

        Files.writeString(
                member.resolve(
                        "src/test/java/com/example/DemoTest.java"),
                """
                package com.example;
                final class DemoTest {
                    String changed;
                }
                """);

        CommandResult result = runWorkspace(
                workspace,
                cache,
                "package");

        assertEquals(1, result.exitCode());
        assertContains(
                result,
                "compiled test output is not current");
        assertContains(result, "Run `zolt test`");
    }

    private static Path project(
            Path project,
            String name) throws IOException {
        Files.createDirectories(project);
        Files.writeString(
                project.resolve("zolt.toml"),
                """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "com.example"
                java = "%s"

                [package]
                tests = true

                [publish]
                releaseRepository = "company-releases"

                [publish.repositories.company-releases]
                url = "https://repo.example.test/releases"
                """.formatted(
                        name,
                        currentJavaMajorVersion()));
        write(
                project,
                "src/main/java/com/example/Main.java",
                """
                package com.example;
                public final class Main {
                }
                """);
        write(
                project,
                "src/test/java/com/example/DemoTest.java",
                """
                package com.example;
                final class DemoTest {
                }
                """);
        return project;
    }

    private static void write(
            Path project,
            String relative,
            String content) throws IOException {
        Path path = project.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private static void compileTests(
            Path project,
            Path cache) {
        ProjectConfig config = new ZoltTomlParser().parse(
                project.resolve("zolt.toml"));
        BuildResultWithClasspaths build =
                new BuildService().buildWithClasspaths(
                        project,
                        config,
                        cache,
                        false);
        new TestCompileService().compileTests(
                project,
                config,
                build.classpaths(),
                build.buildResult());
    }

    private static void compileWorkspaceTests(
            Path workspace,
            Path cache) {
        WorkspacePackageService packages =
                new WorkspacePackageService();
        WorkspaceBuildPlan plan = packages.planPackages(
                WorkspacePlanTarget.at(workspace),
                cache,
                new WorkspaceSelectionRequest(true, List.of()));
        WorkspaceBuildResult build =
                packages.buildPackageInputs(plan, cache);
        TestRunService tests = new TestRunService();
        for (WorkspaceBuildResult.MemberBuildResult memberBuild :
                build.members()) {
            WorkspaceMember member = plan.workspace().members().stream()
                    .filter(candidate -> candidate.path().equals(
                            memberBuild.member()))
                    .findFirst()
                    .orElseThrow();
            tests.compileTests(
                    member.directory(),
                    member.config(),
                    memberBuild.classpaths(),
                    memberBuild.result());
        }
    }

    private static CommandResult run(
            Path project,
            Path cache,
            String... command) {
        String[] arguments = java.util.Arrays.copyOf(
                command,
                command.length + 4);
        arguments[command.length] = "--cwd";
        arguments[command.length + 1] = project.toString();
        arguments[command.length + 2] = "--cache-root";
        arguments[command.length + 3] = cache.toString();
        return execute(arguments);
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

    private static void assertContains(
            CommandResult result,
            String expected) {
        assertTrue(
                (result.stdout() + result.stderr())
                        .contains(expected),
                () -> result.stdout() + result.stderr());
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

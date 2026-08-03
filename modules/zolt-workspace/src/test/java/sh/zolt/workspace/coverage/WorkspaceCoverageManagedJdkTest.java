package sh.zolt.workspace.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.coverage.CoverageReportSettings;
import sh.zolt.build.coverage.CoverageService;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import sh.zolt.resolve.ResolveService;
import sh.zolt.workspace.WorkspaceConfig;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspaceCoverageManagedJdkTest {
    @TempDir
    private Path tempDir;

    @Test
    void reportUsesCapturedManagedJdkForHighestIncludedRelease()
            throws Exception {
        Path root = tempDir.resolve("workspace");
        Path cacheRoot = tempDir.resolve("cache");
        WorkspaceMember java17 = member(root, "modules/legacy", "17");
        WorkspaceMember java21 = member(root, "apps/api", "21");
        Workspace workspace = new Workspace(
                root,
                root.resolve("zolt.toml"),
                new WorkspaceConfig(
                        "workspace",
                        List.of(java17.path(), java21.path()),
                        List.of(),
                        Map.of(),
                        Map.of()),
                List.of(java17, java21),
                List.of(),
                List.of(java17.path(), java21.path()));
        WorkspaceMember reportMember =
                WorkspaceCoverageExecution.reportMember(
                        List.of(java17, java21));
        assertEquals(java21, reportMember);

        Path marker = tempDir.resolve("managed-java.txt");
        Path java = fakeJava(tempDir.resolve("managed/bin/java"), marker);
        AtomicReference<WorkspaceMember> requestedMember =
                new AtomicReference<>();
        WorkspaceCoverageService.CoverageReporterFactory factory =
                WorkspaceCoverageDefaults.reporterFactory(checker ->
                        new CoverageService(checker, new ResolveService()));
        WorkspaceCoverageService.CoverageReporter reporter = factory.create(
                workspace,
                reportMember,
                (requestedWorkspace, member) -> {
                    requestedMember.set(member);
                    return requiredVersion -> new JdkStatus(
                            Optional.of(java.getParent().getParent()),
                            Optional.of(java),
                            Optional.of(java.getParent().resolve("javac")),
                            Optional.of(java.getParent().resolve("jar")),
                            Optional.of("21"),
                            requiredVersion);
                });

        ZoltLockfile lockfile = coverageLockfile();
        writeTooling(cacheRoot);
        var tooling = reporter.lockedCoverageTooling(lockfile, cacheRoot);
        Path execFile = root.resolve("target/coverage/jacoco.exec");
        Files.createDirectories(execFile.getParent());
        Files.write(execFile, new byte[0]);
        reporter.runReport(
                root,
                reportMember.config(),
                CoverageReportSettings.defaults(),
                execFile,
                tooling.cliClasspath(),
                List.of(root.resolve("target/classes")),
                List.of(root.resolve("src/main/java")));

        assertEquals(java21, requestedMember.get());
        assertTrue(Files.readString(marker).contains(java.toString()));
        assertTrue(Files.readString(marker)
                .contains("org.jacoco.cli.internal.Main"));
    }

    private static WorkspaceMember member(
            Path root,
            String path,
            String java) {
        ProjectConfig config = ProjectConfigs.withDirectDependencies(
                new ProjectMetadata(
                        path.replace('/', '-'),
                        "0.1.0",
                        "com.example",
                        java,
                        Optional.empty()),
                Map.of(),
                Map.of(),
                Map.of(),
                BuildSettings.defaults());
        return new WorkspaceMember(path, root.resolve(path), config);
    }

    private static Path fakeJava(Path java, Path marker)
            throws Exception {
        Files.createDirectories(java.getParent());
        Files.writeString(java, """
                #!/bin/sh
                printf 'java=%%s args=%%s\n' "$0" "$*" > "%s"
                """.formatted(marker));
        assertTrue(java.toFile().setExecutable(true));
        return java;
    }

    private static ZoltLockfile coverageLockfile() {
        return new ZoltLockfileReader().read("""
                version = 1

                [[package]]
                id = "org.jacoco:org.jacoco.agent"
                version = "0.8.14"
                source = "maven-central"
                scope = "tool-coverage"
                direct = false
                jar = "org/jacoco/org.jacoco.agent/0.8.14/org.jacoco.agent-0.8.14-runtime.jar"
                dependencies = []

                [[package]]
                id = "org.jacoco:org.jacoco.cli"
                version = "0.8.14"
                source = "maven-central"
                scope = "tool-coverage"
                direct = false
                jar = "org/jacoco/org.jacoco.cli/0.8.14/org.jacoco.cli-0.8.14.jar"
                dependencies = []
                """);
    }

    private static void writeTooling(Path cacheRoot)
            throws Exception {
        Path agent = cacheRoot.resolve(
                "org/jacoco/org.jacoco.agent/0.8.14/org.jacoco.agent-0.8.14-runtime.jar");
        Path cli = cacheRoot.resolve(
                "org/jacoco/org.jacoco.cli/0.8.14/org.jacoco.cli-0.8.14.jar");
        Files.createDirectories(agent.getParent());
        Files.createDirectories(cli.getParent());
        Files.write(agent, new byte[0]);
        Files.write(cli, new byte[0]);
    }
}

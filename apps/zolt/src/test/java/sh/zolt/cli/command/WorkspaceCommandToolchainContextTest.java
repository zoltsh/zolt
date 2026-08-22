package sh.zolt.cli.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.testruntime.TestRunService;
import sh.zolt.doctor.JdkChecker;
import sh.zolt.framework.FrameworkTestRunner;
import sh.zolt.resolve.ResolveService;
import sh.zolt.workspace.WorkspaceConfigException;
import sh.zolt.workspace.discovery.ManifestWorkspaceLoader;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceBuildPlan;
import sh.zolt.workspace.service.WorkspaceBuildResult;
import sh.zolt.workspace.service.WorkspaceSelectionRequest;
import sh.zolt.workspace.test.WorkspaceTestCompileResult;
import sh.zolt.workspace.test.WorkspaceTestService;
import sh.zolt.workspace.test.WorkspaceTestToolchainMetrics;
import sh.zolt.workspace.service.WorkspacePlanTarget;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

final class WorkspaceCommandToolchainContextTest {
    @TempDir
    private Path tempDir;

    @Test
    void twoHundredMembersShareMainAndTestToolchainIdentities()
            throws IOException {
        Path root = tempDir.resolve("large-workspace");
        Files.createDirectories(root);
        StringBuilder members = new StringBuilder();
        for (int index = 0; index < 200; index++) {
            if (!members.isEmpty()) {
                members.append(", ");
            }
            String path = "modules/member-" + index;
            members.append('"').append(path).append('"');
            writeMember(
                    root.resolve(path),
                    "member-" + index,
                    toolchainConfig(currentJavaVersion()));
        }
        Files.writeString(root.resolve("zolt.toml"), """
                [workspace]
                name = "large-toolchain-workspace"

                [workspace.members]
                include = [%s]
                """.formatted(members));
        Files.writeString(root.resolve("zolt.lock"), "version = 7\n");
        Workspace workspace = capturedWorkspace(root);
        CommandToolchainOptions options = options();
        List<JdkChecker> runtimeCheckers = new ArrayList<>();
        CommandToolchainOptions.WorkspaceCommandToolchains toolchains =
                options.workspaceTestToolchains(
                        (compileChecker, runtimeChecker) -> {
                            runtimeCheckers.add(runtimeChecker);
                            return testRunService(
                                    compileChecker,
                                    runtimeChecker);
                        },
                        "test");
        LinkedHashSet<Object> mainIdentities = new LinkedHashSet<>();

        for (var member : workspace.members()) {
            JdkChecker checker =
                    toolchains.mainCheckers().forMember(workspace, member);
            Object key = toolchains.mainCheckers().cacheKey(
                    workspace,
                    member,
                    checker);
            mainIdentities.add(key);
            toolchains.mainCheckers().compileIdentity(
                    workspace,
                    member,
                    checker,
                    key);
            toolchains.testRunServices().forMember(workspace, member);
        }

        assertEquals(1, mainIdentities.size());
        assertEquals(200, runtimeCheckers.size());
        assertEquals(
                new WorkspaceTestToolchainMetrics(1, 0, 0, 0, 0),
                toolchains.testRunServices().toolchainMetrics());

        for (JdkChecker runtimeChecker : runtimeCheckers) {
            runtimeChecker.detect(currentJavaVersion());
        }

        assertEquals(
                new WorkspaceTestToolchainMetrics(1, 0, 0, 1, 199),
                toolchains.testRunServices().toolchainMetrics());
    }

    @Test
    void testCompilationUsesCapturedToolchainAfterPlanValidation()
            throws IOException {
        Path root = tempDir.resolve("captured-workspace");
        Path member = root.resolve("apps/api");
        Files.createDirectories(root);
        Files.writeString(root.resolve("zolt.toml"), """
                [workspace]
                name = "captured-toolchain-workspace"

                [workspace.members]
                include = ["apps/api"]
                """);
        writeMember(
                member,
                "api",
                """

                [toolchain.java]
                version = %s
                features = []
                policy = "prefer-managed"
                """.formatted(currentJavaVersion()));
        writeSource(
                member.resolve("src/main/java/com/example/App.java"),
                """
                package com.example;

                public final class App {
                }
                """);
        writeSource(
                member.resolve("src/test/java/com/example/AppTest.java"),
                """
                package com.example;

                public final class AppTest {
                    private final App app = new App();
                }
                """);
        AtomicBoolean mutated = new AtomicBoolean();
        CommandToolchainOptions.WorkspaceCommandToolchains toolchains =
                options().workspaceTestToolchains(
                        (compileChecker, runtimeChecker) -> {
                            if (mutated.compareAndSet(false, true)) {
                                try {
                                    writeMember(
                                            member,
                                            "api",
                                            """

                                            [toolchain.java]
                                            version = 999
                                            distribution = "temurin"
                                            features = []
                                            policy = "require-managed"
                                            """);
                                } catch (IOException exception) {
                                    throw new UncheckedIOException(exception);
                                }
                            }
                            return testRunService(
                                    compileChecker,
                                    runtimeChecker);
                        },
                        "test");
        WorkspaceTestService service =
                new WorkspaceTestService().withMemberServices(
                        toolchains.mainCheckers(),
                        toolchains.testRunServices());
        Path cacheRoot = root.resolve(".cache");
        WorkspaceBuildPlan plan = service.planTests(
                WorkspacePlanTarget.at(root),
                cacheRoot,
                WorkspaceSelectionRequest.defaults());
        WorkspaceBuildResult buildResult =
                service.buildTestCompileInputs(plan, cacheRoot);

        WorkspaceTestCompileResult result =
                service.compileTests(plan, buildResult);

        assertEquals(1, result.testSourceCount());
        assertEquals(1, result.testCompilationExecutedCount());
        assertEquals(
                new WorkspaceTestToolchainMetrics(1, 1, 1, 0, 0),
                result.toolchainMetrics());
        assertEquals(true, mutated.get());
    }

    /**
     * Design §11.4: the selected test JDK must execute classes compiled for the effective project
     * release. The final language enforces that when the workspace is composed, so a member whose
     * release outruns the shared test runtime is rejected with the same diagnostic wherever it sits
     * in the member list.
     */
    @Test
    void testRuntimeReleaseValidationIsIndependentOfMemberOrder()
            throws IOException {
        Path lowerFirst = mixedReleaseWorkspace(
                tempDir.resolve("lower-first"),
                "\"modules/java-17\", \"modules/java-21\"");
        Path higherFirst = mixedReleaseWorkspace(
                tempDir.resolve("higher-first"),
                "\"modules/java-21\", \"modules/java-17\"");

        WorkspaceConfigException lower = assertThrows(
                WorkspaceConfigException.class,
                () -> capturedWorkspace(lowerFirst));
        WorkspaceConfigException higher = assertThrows(
                WorkspaceConfigException.class,
                () -> capturedWorkspace(higherFirst));

        assertTrue(
                lower.getMessage().contains(
                        "Effective test Java runtime release 17 "
                                + "cannot execute project Java release 21."),
                lower.getMessage());
        assertEquals(
                lower.getMessage().replace(lowerFirst.toString(), "<root>"),
                higher.getMessage().replace(higherFirst.toString(), "<root>"));
    }

    /** A workspace whose shared test runtime cannot execute one of its two member releases. */
    private static Path mixedReleaseWorkspace(Path root, String include)
            throws IOException {
        Files.createDirectories(root);
        Files.writeString(root.resolve("zolt.toml"), """
                [workspace]
                name = "mixed-release-workspace"

                [workspace.members]
                include = [%s]

                [toolchain.java]
                version = 21
                features = []
                policy = "prefer-managed"

                [toolchain.java.test]
                version = 17
                """.formatted(include));
        writeMember(root.resolve("modules/java-17"), "java-17", "17", "");
        writeMember(root.resolve("modules/java-21"), "java-21", "21", "");
        Files.writeString(root.resolve("zolt.lock"), "version = 7\n");
        return root;
    }

    private CommandToolchainOptions options() {
        CommandToolchainOptions options = new CommandToolchainOptions();
        new CommandLine(options).parseArgs(
                "--toolchain-install-root",
                tempDir.resolve("toolchains").toString());
        return options;
    }

    private static TestRunService testRunService(
            JdkChecker compileChecker,
            JdkChecker runtimeChecker) {
        return new TestRunService(
                compileChecker,
                runtimeChecker,
                FrameworkTestRunner.none(),
                new ResolveService());
    }

    private static Workspace capturedWorkspace(Path root)
            throws IOException {
        Workspace discovered =
                new ManifestWorkspaceLoader().discover(root).orElseThrow();
        Path lockfile = root.resolve("zolt.lock");
        return discovered.withInputs(
                discovered.inputs().withContent(
                        lockfile,
                        Files.readAllBytes(lockfile)));
    }

    private static void writeMember(
            Path directory,
            String name,
            String extra) throws IOException {
        writeMember(
                directory,
                name,
                currentJavaVersion(),
                extra);
    }

    private static void writeMember(
            Path directory,
            String name,
            String release,
            String extra) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("zolt.toml"), """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "com.example"
                java = %s
                %s
                """.formatted(name, release, extra));
    }

    private static void writeSource(
            Path path,
            String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private static String toolchainConfig(String version) {
        return """

                [toolchain.java]
                version = %s
                features = []
                policy = "prefer-managed"

                [toolchain.java.test]
                version = %s
                """.formatted(version, version);
    }

    private static String currentJavaVersion() {
        String version = System.getProperty("java.version");
        String[] parts = version.split("[._+-]", -1);
        return parts.length >= 2 && "1".equals(parts[0])
                ? parts[1]
                : parts[0];
    }

}

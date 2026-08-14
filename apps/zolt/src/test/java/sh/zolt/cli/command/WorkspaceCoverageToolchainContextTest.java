package sh.zolt.cli.command;

import static sh.zolt.cli.CliTestSupport.writeFakeConsoleJar;
import static sh.zolt.cli.ContentAddressedLockTestSupport.write;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.testruntime.TestReportSettings;
import sh.zolt.build.testruntime.TestRunService;
import sh.zolt.cli.toolchain.ManagedJavaToolchainTestFixture;
import sh.zolt.framework.FrameworkTestRunner;
import sh.zolt.project.toolchain.JavaDistribution;
import sh.zolt.project.toolchain.JavaFeature;
import sh.zolt.project.toolchain.JavaToolchainRequest;
import sh.zolt.project.toolchain.ToolchainPolicy;
import sh.zolt.resolve.ResolveService;
import sh.zolt.test.TestSelection;
import sh.zolt.test.runtime.TestJvmArguments;
import sh.zolt.toolchain.lock.JavaToolchainLayout;
import sh.zolt.toolchain.lock.LockedJavaToolchain;
import sh.zolt.toolchain.lock.ToolchainLockfileService;
import sh.zolt.toolchain.platform.HostPlatform;
import sh.zolt.toolchain.store.ToolchainStore;
import sh.zolt.workspace.service.WorkspaceBuildPlan;
import sh.zolt.workspace.service.WorkspaceBuildResult;
import sh.zolt.workspace.service.WorkspaceSelectionRequest;
import sh.zolt.workspace.test.WorkspaceTestResult;
import sh.zolt.workspace.test.WorkspaceTestService;
import sh.zolt.workspace.service.WorkspacePlanTarget;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

final class WorkspaceCoverageToolchainContextTest {
    @TempDir
    private Path tempDir;

    @Test
    void coverageUsesCapturedBuildAndTestRuntimeToolchains()
            throws IOException {
        Path root = tempDir.resolve("coverage-workspace");
        Path member = root.resolve("apps/api");
        Path cacheRoot = root.resolve(".cache");
        String javaVersion = currentJavaVersion();
        Files.createDirectories(member);
        Files.writeString(root.resolve("zolt.toml"), workspaceConfig(
                javaVersion,
                "temurin",
                javaVersion,
                "graalvm-community"));
        Files.writeString(member.resolve("zolt.toml"), """
                [project]
                name = "api"
                version = "0.1.0"
                group = "com.example"
                java = "%s"
                """.formatted(javaVersion));
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
        writeFakeConsoleJar(cacheRoot.resolve(
                "org/junit/platform/junit-platform-console-standalone/1.11.4/junit-platform-console-standalone-1.11.4.jar"));
        write(root.resolve("zolt.lock"), cacheRoot, """
                version = 5

                [[package]]
                id = "org.junit.platform:junit-platform-console-standalone"
                version = "1.11.4"
                source = "maven-central"
                scope = "test"
                direct = true
                jar = "org/junit/platform/junit-platform-console-standalone/1.11.4/junit-platform-console-standalone-1.11.4.jar"
                members = ["apps/api"]
                dependencies = []
                """);

        LockedJavaToolchain buildToolchain =
                ManagedJavaToolchainTestFixture.locked();
        LockedJavaToolchain runtimeToolchain = runtimeToolchain(javaVersion);
        new ToolchainLockfileService().writeJava(
                root.resolve("zolt.lock"),
                List.of(buildToolchain, runtimeToolchain));
        ToolchainStore store = new ToolchainStore(
                tempDir.resolve("toolchains"));
        Path buildJavacMarker = root.resolve("build-javac-marker.txt");
        Path runtimeJavacMarker = root.resolve("runtime-javac-marker.txt");
        ManagedJavaToolchainTestFixture.installManagedToolchain(
                store,
                buildToolchain,
                buildJavacMarker);
        ManagedJavaToolchainTestFixture.installManagedToolchain(
                store,
                runtimeToolchain,
                runtimeJavacMarker);
        assertNotEquals(
                store.java(buildToolchain),
                store.java(runtimeToolchain));

        AtomicBoolean mutated = new AtomicBoolean();
        CommandToolchainOptions.WorkspaceCommandToolchains toolchains =
                options().workspaceCoverageToolchains(
                        (compileChecker, runtimeChecker) -> {
                            if (mutated.compareAndSet(false, true)) {
                                try {
                                    Files.writeString(
                                            root.resolve("zolt.toml"),
                                            workspaceConfig(
                                                    "999",
                                                    "temurin",
                                                    "999",
                                                    "temurin"));
                                } catch (IOException exception) {
                                    throw new UncheckedIOException(exception);
                                }
                            }
                            return new TestRunService(
                                    compileChecker,
                                    runtimeChecker,
                                    FrameworkTestRunner.none(),
                                    new ResolveService());
                        });
        WorkspaceTestService service =
                new WorkspaceTestService().withMemberServices(
                        toolchains.mainCheckers(),
                        toolchains.testRunServices());
        WorkspaceBuildPlan plan = service.planTests(
                WorkspacePlanTarget.at(root),
                cacheRoot,
                WorkspaceSelectionRequest.defaults());
        WorkspaceBuildResult buildResult =
                service.buildTestInputs(plan, cacheRoot);
        Path coverageAgent = root.resolve("tools/fake-coverage-agent.jar");
        Files.createDirectories(coverageAgent.getParent());
        Files.write(coverageAgent, new byte[0]);

        WorkspaceTestResult result = service.runTests(
                plan,
                buildResult,
                cacheRoot,
                TestSelection.empty(),
                new TestJvmArguments(List.of(
                        "-javaagent:"
                                + coverageAgent.toAbsolutePath().normalize()
                                + "=destfile="
                                + root.resolve("target/coverage/jacoco.exec")
                                + ",append=true")),
                TestReportSettings.disabled(),
                List.of(),
                "all",
                null);

        assertTrue(mutated.get());
        assertTrue(Files.readString(root.resolve("zolt.toml"))
                .contains("version = \"999\""));
        assertTrue(Files.readString(buildJavacMarker)
                .contains("javac=" + store.javac(buildToolchain)));
        assertFalse(Files.exists(runtimeJavacMarker));
        String output = result.members().getFirst().result().output();
        assertTrue(output.contains("java=" + store.java(runtimeToolchain)), output);
        assertFalse(output.contains("java=" + store.java(buildToolchain)), output);
        assertTrue(output.contains("-javaagent:" + coverageAgent), output);
        assertEquals(1, result.toolchainMetrics().lockfileParses());
        assertEquals(1, result.toolchainMetrics().mainIdentityCalculations());
        assertEquals(
                1,
                result.toolchainMetrics()
                        .testRuntimeIdentityCalculations());
    }

    private CommandToolchainOptions options() {
        CommandToolchainOptions options = new CommandToolchainOptions();
        new CommandLine(options).parseArgs(
                "--toolchain-target", "linux-x64",
                "--toolchain-install-root",
                tempDir.resolve("toolchains").toString());
        return options;
    }

    private static LockedJavaToolchain runtimeToolchain(
            String javaVersion) {
        JavaToolchainRequest request = new JavaToolchainRequest(
                javaVersion,
                JavaDistribution.GRAALVM_COMMUNITY,
                Set.<JavaFeature>of(),
                ToolchainPolicy.REQUIRE_MANAGED);
        return new LockedJavaToolchain(
                "java-graalvm-community-" + javaVersion,
                request,
                HostPlatform.parse("linux-x64"),
                javaVersion,
                JavaDistribution.GRAALVM_COMMUNITY,
                "test:java-graalvm-community-" + javaVersion,
                "https://example.test/jdk.tar.gz",
                "0".repeat(64),
                JavaToolchainLayout.standard(false));
    }

    private static String workspaceConfig(
            String buildVersion,
            String buildDistribution,
            String runtimeVersion,
            String runtimeDistribution) {
        return """
                [workspace]
                name = "coverage-toolchain-workspace"
                members = ["apps/api"]

                [toolchain.java]
                version = "%s"
                distribution = "%s"
                features = []
                policy = "require-managed"

                [toolchain.java.test]
                version = "%s"
                distribution = "%s"
                """.formatted(
                        buildVersion,
                        buildDistribution,
                        runtimeVersion,
                        runtimeDistribution);
    }

    private static void writeSource(Path path, String content)
            throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private static String currentJavaVersion() {
        String version = System.getProperty("java.version");
        String[] parts = version.split("[._+-]", -1);
        return parts.length >= 2 && "1".equals(parts[0])
                ? parts[1]
                : parts[0];
    }
}

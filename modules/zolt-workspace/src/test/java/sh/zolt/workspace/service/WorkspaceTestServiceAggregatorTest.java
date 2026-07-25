package sh.zolt.workspace.service;

import static sh.zolt.workspace.service.WorkspaceTestServiceTestSupport.createFakeConsoleJar;
import static sh.zolt.workspace.service.WorkspaceTestServiceTestSupport.member;
import static sh.zolt.workspace.service.WorkspaceTestServiceTestSupport.oneTestSuccessfulSummary;
import static sh.zolt.workspace.service.WorkspaceTestServiceTestSupport.source;
import static sh.zolt.workspace.service.WorkspaceTestServiceTestSupport.workspace;
import static sh.zolt.workspace.service.WorkspaceTestServiceTestSupport.zeroTestsFoundSummary;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.test.runtime.TestRunException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guards : a workspace member whose {@code [test.dependencies]} declare the
 * {@code junit-jupiter} aggregator (engine + params, launched via the auto-injected
 * {@code junit-platform-console}) must actually run its tests, and must never report
 * "Tests passed" when the JUnit Platform discovered zero tests while test classes compiled.
 */
final class WorkspaceTestServiceAggregatorTest {
    private final WorkspaceTestService service = new WorkspaceTestService();

    @TempDir
    private Path tempDir;

    @Test
    void aggregatorMembersRunTheirTests() throws IOException {
        Path cacheRoot = tempDir.resolve("cache");
        // The junit-jupiter aggregator launches through the auto-injected junit-platform-console
        // (NOT the console-standalone). Emit a real test count so the run is genuinely green.
        createFakeConsoleJar(
                tempDir,
                cacheRoot.resolve("org/junit/platform/junit-platform-console/1.11.4/junit-platform-console-1.11.4.jar"),
                oneTestSuccessfulSummary());
        aggregatorWorkspace();

        WorkspaceTestResult result = service.test(tempDir, cacheRoot);

        assertTrue(result.members().stream()
                .map(WorkspaceTestResult.MemberTestRunResult::result)
                .allMatch(member -> member.output().contains("[         1 tests found           ]")));
        assertTrue(Files.exists(tempDir.resolve("core/target/test-classes/com/acme/core/CoreTest.class")));
        assertTrue(Files.exists(tempDir.resolve("api/target/test-classes/com/acme/api/ApiTest.class")));
    }

    @Test
    void memberWithCompiledTestsButZeroTestsFoundFailsLoudly() throws IOException {
        Path cacheRoot = tempDir.resolve("cache");
        // A member whose test classes compiled but whose engine discovers nothing: the console
        // reports "[ 0 tests found ]". This must hard-fail, never report "Tests passed".
        createFakeConsoleJar(
                tempDir,
                cacheRoot.resolve("org/junit/platform/junit-platform-console/1.11.4/junit-platform-console-1.11.4.jar"),
                zeroTestsFoundSummary());
        aggregatorWorkspace();

        TestRunException exception = assertThrows(
                TestRunException.class,
                () -> service.test(tempDir, cacheRoot));

        assertTrue(exception.getMessage().contains("No tests were discovered"));
        assertTrue(exception.getMessage().contains("test source file(s) compiled"));
    }

    private void aggregatorWorkspace() throws IOException {
        workspace(tempDir, """
                [workspace]
                name = "acme-platform"
                members = ["core", "api"]
                """);
        member(tempDir, "core", "core", aggregatorTestDependencies());
        source(tempDir, "core/src/main/java/com/acme/core/Core.java", """
                package com.acme.core;

                public final class Core {
                    public static String message() {
                        return "core";
                    }
                }
                """);
        source(tempDir, "core/src/test/java/com/acme/core/CoreTest.java", """
                package com.acme.core;

                public final class CoreTest {
                    public String message() {
                        return Core.message();
                    }
                }
                """);
        member(tempDir, "api", "api", aggregatorTestDependencies());
        source(tempDir, "api/src/main/java/com/acme/api/Api.java", """
                package com.acme.api;

                public final class Api {
                    public static String message() {
                        return "api";
                    }
                }
                """);
        source(tempDir, "api/src/test/java/com/acme/api/ApiTest.java", """
                package com.acme.api;

                public final class ApiTest {
                    public String message() {
                        return Api.message();
                    }
                }
                """);
        Files.writeString(tempDir.resolve("zolt.lock"), aggregatorLockfile());
    }

    private static String aggregatorTestDependencies() {
        return """

                [test.dependencies]
                "org.junit.jupiter:junit-jupiter" = "5.11.4"
                """;
    }

    private static String aggregatorLockfile() {
        // Mirrors the aggregator's resolved test-scope closure: the junit-jupiter engine plus the
        // auto-injected junit-platform-console (the launch entry point), all visible to both members.
        return """
                version = 5

                [[package]]
                id = "org.junit.jupiter:junit-jupiter"
                version = "5.11.4"
                source = "maven-central"
                scope = "test"
                direct = true
                jar = "org/junit/jupiter/junit-jupiter/5.11.4/junit-jupiter-5.11.4.jar"
                members = ["core", "api"]
                dependencies = []

                [[package]]
                id = "org.junit.jupiter:junit-jupiter-engine"
                version = "5.11.4"
                source = "maven-central"
                scope = "test"
                direct = false
                jar = "org/junit/jupiter/junit-jupiter-engine/5.11.4/junit-jupiter-engine-5.11.4.jar"
                members = ["core", "api"]
                dependencies = []

                [[package]]
                id = "org.junit.platform:junit-platform-console"
                version = "1.11.4"
                source = "maven-central"
                scope = "test"
                direct = false
                jar = "org/junit/platform/junit-platform-console/1.11.4/junit-platform-console-1.11.4.jar"
                members = ["core", "api"]
                dependencies = []
                """;
    }
}

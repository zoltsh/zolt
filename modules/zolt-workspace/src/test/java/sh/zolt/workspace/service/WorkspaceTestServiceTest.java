package sh.zolt.workspace.service;

import static sh.zolt.workspace.service.WorkspaceTestServiceTestSupport.createFakeConsoleJar;
import static sh.zolt.workspace.service.WorkspaceTestServiceTestSupport.member;
import static sh.zolt.workspace.service.WorkspaceTestServiceTestSupport.oneTestSuccessfulSummary;
import static sh.zolt.workspace.service.WorkspaceTestServiceTestSupport.source;
import static sh.zolt.workspace.service.WorkspaceTestServiceTestSupport.workspace;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.test.TestSelection;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspaceTestServiceTest {
    private final WorkspaceTestService service = new WorkspaceTestService();

    @TempDir
    private Path tempDir;

    @Test
    void testsWorkspaceMembersInDependencyOrder() throws IOException {
        Path cacheRoot = tempDir.resolve("cache");
        createFakeConsoleJar(tempDir, cacheRoot.resolve(
                "org/junit/platform/junit-platform-console-standalone/1.11.4/junit-platform-console-standalone-1.11.4.jar"));
        workspace(tempDir, """
                [workspace]
                name = "acme-platform"
                members = ["apps/api", "modules/core"]
                """);
        member(tempDir, "modules/core", "core", "");
        source(tempDir, "modules/core/src/main/java/com/acme/core/Core.java", """
                package com.acme.core;

                public final class Core {
                    private Core() {
                    }

                    public static String message() {
                        return "core";
                    }
                }
                """);
        member(tempDir, "apps/api", "api", """

                [dependencies]
                "com.acme:core" = { workspace = "modules/core" }
                """);
        source(tempDir, "apps/api/src/main/java/com/acme/api/Api.java", """
                package com.acme.api;

                import com.acme.core.Core;

                public final class Api {
                    private Api() {
                    }

                    public static String message() {
                        return Core.message();
                    }
                }
                """);
        source(tempDir, "apps/api/src/test/java/com/acme/api/ApiTest.java", """
                package com.acme.api;

                import com.acme.core.Core;

                public final class ApiTest {
                    public String message() {
                        return Api.message() + Core.message();
                    }
                }
                """);
        Files.writeString(tempDir.resolve("zolt.lock"), """
                version = 5

                [[package]]
                id = "com.acme:core"
                version = "0.1.0"
                source = "workspace"
                scope = "compile"
                direct = true
                workspace = "modules/core"
                workspaceOutput = "target/classes"
                dependencies = []

                [[package]]
                id = "org.junit.platform:junit-platform-console-standalone"
                version = "1.11.4"
                source = "maven-central"
                scope = "test"
                direct = true
                jar = "org/junit/platform/junit-platform-console-standalone/1.11.4/junit-platform-console-standalone-1.11.4.jar"
                members = ["apps/api", "modules/core"]
                dependencies = []
                """);

        WorkspaceTestResult result = service.test(tempDir.resolve("apps/api"), cacheRoot);

        assertFalse(result.resolvedLockfile());
        assertEquals(List.of("modules/core", "apps/api"), result.builtMembers().stream()
                .map(WorkspaceBuildResult.MemberBuildResult::member)
                .toList());
        assertEquals(List.of("modules/core", "apps/api"), result.members().stream()
                .map(WorkspaceTestResult.MemberTestRunResult::member)
                .toList());
        assertEquals(2, result.mainSourceCount());
        assertEquals(1, result.testSourceCount());
        assertTrue(result.members().stream()
                .map(WorkspaceTestResult.MemberTestRunResult::result)
                .allMatch(member -> member.output().contains("fake console")));
        assertTrue(Files.exists(tempDir.resolve("modules/core/target/classes/com/acme/core/Core.class")));
        assertTrue(Files.exists(tempDir.resolve("apps/api/target/classes/com/acme/api/Api.class")));
        assertTrue(Files.exists(tempDir.resolve("apps/api/target/test-classes/com/acme/api/ApiTest.class")));
        assertFalse(Files.exists(tempDir.resolve("apps/api/zolt.lock")));
        assertFalse(Files.exists(tempDir.resolve("modules/core/zolt.lock")));
    }

    @Test
    void testsSelectedWorkspaceMembersAfterBuildingDependencies() throws IOException {
        Path cacheRoot = tempDir.resolve("cache");
        createFakeConsoleJar(tempDir, cacheRoot.resolve(
                "org/junit/platform/junit-platform-console-standalone/1.11.4/junit-platform-console-standalone-1.11.4.jar"));
        workspace(tempDir, """
                [workspace]
                name = "acme-platform"
                members = ["apps/api", "modules/core", "apps/worker"]
                """);
        member(tempDir, "modules/core", "core", "");
        source(tempDir, "modules/core/src/main/java/com/acme/core/Core.java", """
                package com.acme.core;

                public final class Core {
                    private Core() {
                    }

                    public static String message() {
                        return "core";
                    }
                }
                """);
        member(tempDir, "apps/api", "api", """

                [dependencies]
                "com.acme:core" = { workspace = "modules/core" }
                """);
        source(tempDir, "apps/api/src/main/java/com/acme/api/Api.java", """
                package com.acme.api;

                import com.acme.core.Core;

                public final class Api {
                    private Api() {
                    }

                    public static String message() {
                        return Core.message();
                    }
                }
                """);
        source(tempDir, "apps/api/src/test/java/com/acme/api/ApiTest.java", """
                package com.acme.api;

                public final class ApiTest {
                    public String message() {
                        return Api.message();
                    }
                }
                """);
        member(tempDir, "apps/worker", "worker", "");
        source(tempDir, "apps/worker/src/main/java/com/acme/worker/Worker.java", """
                package com.acme.worker;

                public final class Worker {
                }
                """);
        Files.writeString(tempDir.resolve("zolt.lock"), """
                version = 5

                [[package]]
                id = "com.acme:core"
                version = "0.1.0"
                source = "workspace"
                scope = "compile"
                direct = true
                workspace = "modules/core"
                workspaceOutput = "target/classes"
                dependencies = []

                [[package]]
                id = "org.junit.platform:junit-platform-console-standalone"
                version = "1.11.4"
                source = "maven-central"
                scope = "test"
                direct = true
                jar = "org/junit/platform/junit-platform-console-standalone/1.11.4/junit-platform-console-standalone-1.11.4.jar"
                members = ["apps/api", "apps/worker", "modules/core"]
                dependencies = []
                """);

        WorkspaceTestResult result = service.test(
                tempDir,
                cacheRoot,
                new WorkspaceSelectionRequest(false, List.of("apps/api")));

        assertEquals(List.of("modules/core", "apps/api"), result.builtMembers().stream()
                .map(WorkspaceBuildResult.MemberBuildResult::member)
                .toList());
        assertEquals(List.of("apps/api"), result.members().stream()
                .map(WorkspaceTestResult.MemberTestRunResult::member)
                .toList());
        assertTrue(Files.exists(tempDir.resolve("modules/core/target/classes/com/acme/core/Core.class")));
        assertTrue(Files.exists(tempDir.resolve("apps/api/target/classes/com/acme/api/Api.class")));
        assertTrue(Files.exists(tempDir.resolve("apps/api/target/test-classes/com/acme/api/ApiTest.class")));
        assertFalse(Files.exists(tempDir.resolve("apps/worker/target/classes/com/acme/worker/Worker.class")));
    }

    @Test
    void runsIntegrationTestsFromIntegrationSourceRoots() throws IOException {
        Path cacheRoot = tempDir.resolve("cache");
        createFakeConsoleJar(
                tempDir,
                cacheRoot.resolve(
                        "org/junit/platform/junit-platform-console-standalone/1.11.4/junit-platform-console-standalone-1.11.4.jar"),
                oneTestSuccessfulSummary());
        workspace(tempDir, """
                [workspace]
                name = "acme-platform"
                members = ["apps/api"]
                """);
        member(tempDir, "apps/api", "api", "");
        source(tempDir, "apps/api/src/main/java/com/acme/api/Api.java", """
                package com.acme.api;

                public final class Api {
                    private Api() {
                    }

                    public static String message() {
                        return "api";
                    }
                }
                """);
        source(tempDir, "apps/api/src/integration-test/java/com/acme/api/ApiIT.java", """
                package com.acme.api;

                public final class ApiIT {
                    public String message() {
                        return Api.message();
                    }
                }
                """);
        Files.writeString(tempDir.resolve("zolt.lock"), """
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
        WorkspaceBuildPlan plan = service.planTests(tempDir, cacheRoot, WorkspaceSelectionRequest.defaults());
        WorkspaceBuildResult buildResult = service.buildTestInputs(plan, cacheRoot);

        WorkspaceTestResult result = service.runIntegrationTests(
                plan,
                buildResult,
                cacheRoot,
                TestSelection.empty(),
                null,
                null,
                List.of("failed"));

        assertEquals(List.of("apps/api"), result.members().stream()
                .map(WorkspaceTestResult.MemberTestRunResult::member)
                .toList());
        assertEquals(1, result.testSourceCount());
        assertTrue(result.testRuntimeClasspathEntryCount() > 0);
        assertTrue(result.members().getFirst().result().output().contains("[         1 tests found           ]"));
        assertTrue(Files.exists(tempDir.resolve("apps/api/target/integration-test-classes/com/acme/api/ApiIT.class")));
        assertEquals(Optional.empty(), result.members().getFirst().result().reportsDirectory());
    }

}

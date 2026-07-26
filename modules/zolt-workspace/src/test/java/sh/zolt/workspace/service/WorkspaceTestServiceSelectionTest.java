package sh.zolt.workspace.service;

import static sh.zolt.workspace.service.WorkspaceTestServiceTestSupport.createFakeConsoleJar;
import static sh.zolt.workspace.service.WorkspaceTestServiceTestSupport.member;
import static sh.zolt.workspace.service.WorkspaceTestServiceTestSupport.source;
import static sh.zolt.workspace.service.WorkspaceTestServiceTestSupport.workspace;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.test.TestSelection;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspaceTestServiceSelectionTest {
    private final WorkspaceTestService service = new WorkspaceTestService();

    @TempDir
    private Path tempDir;

    @Test
    void appliesTestSelectionToSelectedWorkspaceMembersAfterBuildingDependencies() throws IOException {
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
        TestSelection testSelection = TestSelection.fromCli(
                List.of(),
                List.of("*ApiTest"),
                List.of("fast"),
                List.of("slow"));

        WorkspaceTestResult result = service.test(
                tempDir,
                cacheRoot,
                new WorkspaceSelectionRequest(false, List.of("apps/api")),
                testSelection);

        assertEquals(List.of("modules/core", "apps/api"), result.builtMembers().stream()
                .map(WorkspaceBuildResult.MemberBuildResult::member)
                .toList());
        assertEquals(List.of("apps/api"), result.members().stream()
                .map(WorkspaceTestResult.MemberTestRunResult::member)
                .toList());
        assertEquals(testSelection, result.members().getFirst().result().testSelection());
        assertEquals(1, result.testPatternCount());
        assertEquals(1, result.testIncludedTagCount());
        assertEquals(1, result.testExcludedTagCount());
        assertTrue(Files.exists(tempDir.resolve("modules/core/target/classes/com/acme/core/Core.class")));
        assertTrue(Files.exists(tempDir.resolve("apps/api/target/test-classes/com/acme/api/ApiTest.class")));
    }
}

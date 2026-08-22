package sh.zolt.workspace.service;

import static sh.zolt.workspace.service.WorkspaceTestServiceTestSupport.member;
import static sh.zolt.workspace.service.WorkspaceTestServiceTestSupport.source;
import static sh.zolt.workspace.service.WorkspaceTestServiceTestSupport.workspace;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.testruntime.TestRunService;
import sh.zolt.workspace.service.WorkspacePlanTarget;
import sh.zolt.workspace.test.WorkspaceTestService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspaceTestDependencyVisibilityTest {
    private final WorkspaceTestService service = new WorkspaceTestService();

    @TempDir
    private Path tempDir;

    @Test
    void testOnlyWorkspaceDependencyCompilesTestsButNeverEntersMainLanes() throws IOException {
        workspace(tempDir, """
                [workspace]
                name = "acme-platform"

                [workspace.members]
                include = ["modules/test-support", "apps/api"]
                """);
        member(tempDir, "modules/test-support", "test-support", "");
        source(tempDir, "modules/test-support/src/main/java/com/acme/testing/Fixture.java", """
                package com.acme.testing;

                public final class Fixture {
                    private Fixture() {
                    }

                    public static String value() {
                        return "fixture";
                    }
                }
                """);
        member(tempDir, "apps/api", "api", """

                [dependencies.test]
                "com.acme:test-support" = { workspace = true }
                """);
        source(tempDir, "apps/api/src/main/java/com/acme/api/Api.java", """
                package com.acme.api;

                public final class Api {
                    private Api() {
                    }
                }
                """);
        source(tempDir, "apps/api/src/test/java/com/acme/api/ApiTest.java", """
                package com.acme.api;

                import com.acme.testing.Fixture;

                public final class ApiTest {
                    public String value() {
                        return Fixture.value();
                    }
                }
                """);

        Path cacheRoot = tempDir.resolve("cache");
        WorkspaceBuildPlan plan =
                service.planTests(WorkspacePlanTarget.at(tempDir.resolve("apps/api")), cacheRoot, WorkspaceSelectionRequest.defaults());
        WorkspaceBuildResult build = service.buildTestInputs(plan, cacheRoot);
        WorkspaceBuildResult.MemberBuildResult app = build.members().stream()
                .filter(member -> member.member().equals("apps/api"))
                .findFirst()
                .orElseThrow();
        Path testSupportOutput = tempDir.resolve("modules/test-support/target/classes").normalize();

        assertFalse(app.classpaths().compile().entries().contains(testSupportOutput));
        assertFalse(app.classpaths().runtime().entries().contains(testSupportOutput));
        assertTrue(app.classpaths().test().entries().contains(testSupportOutput));
        assertTrue(app.classpaths().testCompile().entries().contains(testSupportOutput));

        WorkspaceMember appMember = plan.workspace().members().stream()
                .filter(member -> member.path().equals("apps/api"))
                .findFirst()
                .orElseThrow();
        new TestRunService().compileTests(
                appMember.directory(),
                appMember.config(),
                app.classpaths(),
                app.result());

        assertTrue(Files.exists(tempDir.resolve("apps/api/target/test-classes/com/acme/api/ApiTest.class")));
    }
}

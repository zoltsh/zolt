package sh.zolt.workspace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.workspace.service.WorkspaceTestServiceTestSupport.member;
import static sh.zolt.workspace.service.WorkspaceTestServiceTestSupport.source;
import static sh.zolt.workspace.service.WorkspaceTestServiceTestSupport.workspace;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A test-source edit moves the test lane of exactly one member: no other member's test classpath is
 * projected, and no member's main compile classpath is projected at all.
 */
final class WorkspaceSparseTestLanePlanningTest {
    private final WorkspaceTestService service = new WorkspaceTestService();

    @TempDir
    private Path tempDir;

    @BeforeEach
    void createWorkspace() throws IOException {
        workspace(tempDir, """
                [workspace]
                name = "acme-platform"
                members = ["apps/a", "apps/b"]
                """);
        member(tempDir, "apps/a", "a", "");
        member(tempDir, "apps/b", "b", "");
        source(tempDir, "apps/a/src/main/java/com/example/a/AppA.java", """
                package com.example.a;

                public final class AppA {
                }
                """);
        source(tempDir, "apps/b/src/main/java/com/example/b/AppB.java", """
                package com.example.b;

                public final class AppB {
                }
                """);
        source(tempDir, "apps/a/src/test/java/com/example/a/AppATest.java", """
                package com.example.a;

                public final class AppATest {
                }
                """);
        source(tempDir, "apps/b/src/test/java/com/example/b/AppBTest.java", """
                package com.example.b;

                public final class AppBTest {
                }
                """);
    }

    @Test
    void warmTestCompileAdmitsNothingAndCompilesNoTests() {
        compile();

        Compilation result = compile();

        assertEquals(0, result.metrics().membersAdmitted());
        assertEquals(0, result.metrics().classpathCalculations());
        assertEquals(0, result.metrics().testClasspathCalculations());
        assertEquals(2, result.compiled().testCompilationSkippedCount());
    }

    @Test
    void testSourceEditCompilesOneMemberAndProjectsOneTestClasspath() throws IOException {
        compile();
        source(tempDir, "apps/a/src/test/java/com/example/a/AppATest.java", """
                package com.example.a;

                public final class AppATest {
                    static final String NAME = "a";
                }
                """);

        Compilation result = compile();

        assertEquals(0, result.metrics().memberPipelineInvocations());
        assertEquals(1, result.metrics().testClasspathCalculations());
        assertEquals(1, result.metrics().classpathCalculations());
        assertFalse(skipped(result).get("apps/a"), "the edited member recompiles its tests");
        assertTrue(skipped(result).get("apps/b"), "the untouched member does not");
    }

    @Test
    void mainSourceEditStillRecompilesThatMembersTests() throws IOException {
        compile();
        source(tempDir, "apps/a/src/main/java/com/example/a/AppA.java", """
                package com.example.a;

                public final class AppA {
                    public static String name() {
                        return "a";
                    }
                }
                """);

        Compilation result = compile();

        assertEquals(1, result.metrics().memberPipelineInvocations());
        assertFalse(
                skipped(result).get("apps/a"),
                "a rebuilt main output re-enters the member's test compile");
        assertTrue(skipped(result).get("apps/b"));
    }

    private static Map<String, Boolean> skipped(Compilation compilation) {
        return compilation.compiled().members().stream()
                .collect(Collectors.toMap(
                        WorkspaceTestCompileResult.MemberTestCompileResult::member,
                        entry -> entry.result().testCompilationSkipped()));
    }

    private Compilation compile() {
        Path cacheRoot = tempDir.resolve("cache");
        WorkspaceBuildPlan plan = service.planTests(
                WorkspacePlanTarget.at(tempDir),
                cacheRoot,
                WorkspaceSelectionRequest.defaults());
        WorkspaceBuildResult build = service.buildTestCompileInputs(plan, cacheRoot);
        WorkspaceTestCompileResult compiled = service.compileTests(plan, build);
        return new Compilation(plan.executionContext().metrics(), compiled);
    }

    private record Compilation(
            WorkspaceExecutionContext.Metrics metrics,
            WorkspaceTestCompileResult compiled) {
    }
}

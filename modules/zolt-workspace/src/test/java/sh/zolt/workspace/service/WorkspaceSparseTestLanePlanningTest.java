package sh.zolt.workspace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.workspace.service.WorkspaceTestServiceTestSupport.member;
import static sh.zolt.workspace.service.WorkspaceTestServiceTestSupport.source;
import static sh.zolt.workspace.service.WorkspaceTestServiceTestSupport.workspace;

import java.io.IOException;
import java.nio.file.Files;
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
        source(tempDir, "apps/a/src/test/resources/fixture.txt", "A");
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

    /**
     * A test-resource edit reaches the test output. The member's test sources did not move, so
     * nothing recompiles; the refresh the copy lane owes still has to happen, or the run reads the
     * bytes from the previous command.
     */
    @Test
    void testResourceEditIsServedFromTheTestOutput() throws IOException {
        compile();
        assertEquals(
                "A",
                Files.readString(tempDir.resolve("apps/a/target/test-classes/fixture.txt")));
        source(tempDir, "apps/a/src/test/resources/fixture.txt", "B");

        Compilation result = compile();

        assertEquals(
                "B",
                Files.readString(tempDir.resolve("apps/a/target/test-classes/fixture.txt")),
                "the edited test resource is what the test lane serves");
        assertEquals(0, result.metrics().memberPipelineInvocations());
        assertEquals(1, refreshedResources(result).get("apps/a"), "the copy lane ran for the edit");
        assertEquals(0, refreshedResources(result).get("apps/b"), "the untouched member is skipped");
        assertTrue(
                skipped(result).get("apps/a"),
                "a resource-only edit refreshes the copy lane without recompiling tests");
    }

    /** A deleted copy of a test resource is restored even though no input digest moved. */
    @Test
    void deletedTestResourceOutputIsRestored() throws IOException {
        compile();
        Files.delete(tempDir.resolve("apps/a/target/test-classes/fixture.txt"));

        compile();

        assertEquals(
                "A",
                Files.readString(tempDir.resolve("apps/a/target/test-classes/fixture.txt")));
    }

    private static Map<String, Integer> refreshedResources(Compilation compilation) {
        return compilation.compiled().members().stream()
                .collect(Collectors.toMap(
                        WorkspaceTestCompileResult.MemberTestCompileResult::member,
                        entry -> entry.result().resourceCount()));
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

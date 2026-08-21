package sh.zolt.workspace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The sparse-planning contract, stated as counters: a member that stage 0 leaves alone costs no
 * classpath, no scheduler slot, and no pipeline invocation.
 */
final class WorkspaceSparsePlanningMetricsTest extends WorkspaceBuildServiceTestSupport {
    private final WorkspaceBuildService service = new WorkspaceBuildService();

    @BeforeEach
    void createChain() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"

                [workspace.members]
                include = ["modules/core", "modules/util", "apps/api"]
                """);
        member("modules/core", "core", "");
        source("modules/core/src/main/java/com/acme/core/Core.java", """
                package com.acme.core;

                public final class Core {
                    public static String message() {
                        return "core";
                    }
                }
                """);
        member("modules/util", "util", """

                [dependencies]
                "com.acme:core" = { workspace = true }
                """);
        source("modules/util/src/main/java/com/acme/util/Util.java", """
                package com.acme.util;

                import com.acme.core.Core;

                public final class Util {
                    public static String message() {
                        return Core.message();
                    }
                }
                """);
        member("apps/api", "api", """

                [dependencies]
                "com.acme:util" = { workspace = true }
                """);
        source("apps/api/src/main/java/com/acme/api/Api.java", """
                package com.acme.api;

                import com.acme.util.Util;

                public final class Api {
                    public static String message() {
                        return Util.message();
                    }
                }
                """);
        source("apps/api/src/main/resources/application.properties", "message=first\n");
    }

    @Test
    void fullCleanBuildAdmitsEveryMember() {
        WorkspaceBuildResult result = build();

        assertEquals(3, result.executionMetrics().membersConsidered());
        assertEquals(3, result.executionMetrics().membersAdmitted());
        assertEquals(3, result.executionMetrics().memberPipelineInvocations());
        assertEquals(3, result.executionMetrics().classpathCalculations());
    }

    @Test
    void warmNoOpAdmitsNothingAndBuildsNoClasspath() {
        build();

        WorkspaceBuildResult second = build();

        assertEquals(3, second.executionMetrics().membersConsidered());
        assertEquals(0, second.executionMetrics().membersAdmitted());
        assertEquals(0, second.executionMetrics().memberPipelineInvocations());
        assertEquals(0, second.executionMetrics().classpathCalculations());
        assertEquals(0, second.executionMetrics().runtimeClasspathCalculations());
        assertEquals(0, second.executionMetrics().testClasspathCalculations());
        assertEquals(3, second.mainCompilationSkippedCount());
    }

    @Test
    void resourceOnlyEditAdmitsOnlyTheResourceMember() throws IOException {
        build();
        Files.writeString(
                tempDir.resolve("apps/api/src/main/resources/application.properties"),
                "message=second\n");

        WorkspaceBuildResult result = build();

        assertEquals(1, result.executionMetrics().membersAdmitted());
        assertEquals(1, result.executionMetrics().memberPipelineInvocations());
        assertEquals(1, result.executionMetrics().classpathCalculations());
        assertEquals(
                "message=second\n",
                Files.readString(tempDir.resolve("apps/api/target/classes/application.properties")));
    }

    @Test
    void leafImplementationEditBuildsOneMemberAndStopsWhenTheAbiHolds() throws IOException {
        build();
        source("modules/core/src/main/java/com/acme/core/Core.java", """
                package com.acme.core;

                public final class Core {
                    public static String message() {
                        return "core-v2";
                    }
                }
                """);

        WorkspaceBuildResult result = build();

        assertEquals(1, result.executionMetrics().memberPipelineInvocations());
        assertEquals(1, result.executionMetrics().classpathCalculations());
        assertEquals(2, result.mainCompilationSkippedCount());
    }

    /**
     * A changed ABI reaches the direct dependent, which recompiles against it; the dependent's own
     * ABI is unmoved, so the wave stops there. Every member downstream of the edit is admitted —
     * the scheduler cannot know in advance where the wave dies — but only the two that are actually
     * invalidated pay for a classpath.
     */
    @Test
    void sharedApiEditReachesExactlyTheMembersItInvalidates() throws IOException {
        build();
        source("modules/core/src/main/java/com/acme/core/Core.java", """
                package com.acme.core;

                public final class Core {
                    public static String message() {
                        return "core";
                    }

                    public static String extra() {
                        return "extra";
                    }
                }
                """);

        WorkspaceBuildResult result = build();

        assertEquals(3, result.executionMetrics().membersAdmitted());
        assertEquals(2, result.executionMetrics().memberPipelineInvocations());
        assertEquals(2, result.executionMetrics().classpathCalculations());
    }

    @Test
    void abiChangeThatKeepsMovingReachesTheLastDependent() throws IOException {
        build();
        source("modules/util/src/main/java/com/acme/util/Util.java", """
                package com.acme.util;

                import com.acme.core.Core;

                public final class Util {
                    public static String message() {
                        return Core.message();
                    }

                    public static String extra() {
                        return "extra";
                    }
                }
                """);

        WorkspaceBuildResult result = build();

        assertEquals(2, result.executionMetrics().membersAdmitted());
        assertEquals(2, result.executionMetrics().memberPipelineInvocations());
        assertEquals(2, result.executionMetrics().classpathCalculations());
        assertEquals(1, result.mainCompilationSkippedCount());
    }

    @Test
    void deletedMemberOutputAdmitsOnlyThatMember() throws IOException {
        build();
        Files.delete(tempDir.resolve("modules/util/target/classes/com/acme/util/Util.class"));

        WorkspaceBuildResult result = build();

        assertEquals(1, result.executionMetrics().memberPipelineInvocations());
        assertTrue(
                Files.isRegularFile(
                        tempDir.resolve("modules/util/target/classes/com/acme/util/Util.class")),
                "the missing output is restored");
    }

    private WorkspaceBuildResult build() {
        return service.build(tempDir, tempDir.resolve("cache"), false);
    }
}

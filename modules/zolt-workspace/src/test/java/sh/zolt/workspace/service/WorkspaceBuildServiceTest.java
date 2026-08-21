package sh.zolt.workspace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;

final class WorkspaceBuildServiceTest extends WorkspaceBuildServiceTestSupport {
    private final WorkspaceBuildService service = new WorkspaceBuildService();

    @Test
    void buildsWorkspaceMembersInDependencyOrder() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"

                [workspace.members]
                include = ["apps/api", "modules/core"]
                """);
        member("modules/core", "core", "");
        source("modules/core/src/main/java/com/acme/core/Core.java", """
                package com.acme.core;

                public final class Core {
                    private Core() {
                    }

                    public static String message() {
                        return "core";
                    }
                }
                """);
        member("apps/api", "api", """

                [dependencies]
                "com.acme:core" = { workspace = true }
                """);
        source("apps/api/src/main/java/com/acme/api/Api.java", """
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

        WorkspaceBuildResult result = service.build(tempDir.resolve("apps/api"), tempDir.resolve("cache"), false);

        assertTrue(result.resolvedLockfile());
        assertEquals(List.of("modules/core", "apps/api"), result.members().stream()
                .map(WorkspaceBuildResult.MemberBuildResult::member)
                .toList());
        assertEquals(2, result.sourceCount());
        assertEquals(2, result.buildWaveCount());
        assertEquals(2, result.executionMetrics().classpathCalculations());
        assertEquals(0, result.executionMetrics().packageCalculations());
        assertTrue(result.executionMetrics().graphConstructionNanos() > 0L);
        assertTrue(result.executionMetrics().memberExecutionNanos() > 0L);
        assertTrue(result.executionMetrics().schedulerIdleNanos() >= 0L);
        assertEquals(1, result.executionMetrics().readyQueuePeak());
        assertTrue(result.members().stream()
                .allMatch(member -> member.classpaths().runtime().entries().isEmpty()));
        assertTrue(result.members().stream()
                .allMatch(member -> member.classpaths().test().entries().isEmpty()));
        assertTrue(result.members().stream()
                .allMatch(member -> member.classpathPackages().isEmpty()));
        assertTrue(result.members().get(1).classpaths().compile().entries()
                .contains(tempDir.resolve("modules/core/target/classes")));
        assertTrue(Files.exists(tempDir.resolve("modules/core/target/classes/com/acme/core/Core.class")));
        assertTrue(Files.exists(tempDir.resolve("apps/api/target/classes/com/acme/api/Api.class")));
        assertTrue(Files.exists(tempDir.resolve("zolt.lock")));
    }

    @Test
    void buildsSelectedWorkspaceMembersAndDependencies() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"

                [workspace.members]
                include = ["apps/api", "modules/core", "apps/worker"]
                """);
        member("modules/core", "core", "");
        source("modules/core/src/main/java/com/acme/core/Core.java", """
                package com.acme.core;

                public final class Core {
                    private Core() {
                    }

                    public static String message() {
                        return "core";
                    }
                }
                """);
        member("apps/api", "api", """

                [dependencies]
                "com.acme:core" = { workspace = true }
                """);
        source("apps/api/src/main/java/com/acme/api/Api.java", """
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
        member("apps/worker", "worker", "");
        source("apps/worker/src/main/java/com/acme/worker/Worker.java", """
                package com.acme.worker;

                public final class Worker {
                }
                """);

        WorkspaceBuildResult result = service.build(
                tempDir,
                tempDir.resolve("cache"),
                false,
                new WorkspaceSelectionRequest(false, List.of("apps/api")));

        assertEquals(List.of("modules/core", "apps/api"), result.members().stream()
                .map(WorkspaceBuildResult.MemberBuildResult::member)
                .toList());
        assertEquals(2, result.sourceCount());
        assertTrue(Files.exists(tempDir.resolve("modules/core/target/classes/com/acme/core/Core.class")));
        assertTrue(Files.exists(tempDir.resolve("apps/api/target/classes/com/acme/api/Api.class")));
        assertFalse(Files.exists(tempDir.resolve("apps/worker/target/classes/com/acme/worker/Worker.class")));
    }
}

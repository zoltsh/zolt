package sh.zolt.workspace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.workspace.state.WorkspaceStateStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspaceBuildServiceIncrementalTest {
    private final WorkspaceBuildService service = new WorkspaceBuildService();

    @TempDir
    private Path tempDir;

    @Test
    void repeatedWorkspaceBuildSkipsAllMembersWhenInputsAreCurrent() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"
                members = ["modules/core", "modules/util", "apps/api"]
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
        member("modules/util", "util", "");
        source("modules/util/src/main/java/com/acme/util/Util.java", """
                package com.acme.util;

                public final class Util {
                    private Util() {
                    }

                    public static String message() {
                        return "util";
                    }
                }
                """);
        member("apps/api", "api", """

                [dependencies]
                "com.acme:core" = { workspace = "modules/core" }
                "com.acme:util" = { workspace = "modules/util" }
                """);
        source("apps/api/src/main/java/com/acme/api/Api.java", """
                package com.acme.api;

                import com.acme.core.Core;
                import com.acme.util.Util;

                public final class Api {
                    private Api() {
                    }

                    public static String message() {
                        return Core.message() + Util.message();
                    }
                }
                """);

        WorkspaceBuildResult first = service.build(tempDir.resolve("apps/api"), tempDir.resolve("cache"), false);
        WorkspaceBuildResult second = service.build(tempDir.resolve("apps/api"), tempDir.resolve("cache"), false);

        assertEquals(3, first.mainCompilationExecutedCount());
        assertEquals(0, first.mainCompilationSkippedCount());
        assertEquals(0, second.mainCompilationExecutedCount());
        assertEquals(3, second.mainCompilationSkippedCount());
        assertEquals(3, second.executionMetrics().membersConsidered());
        assertEquals(3, second.executionMetrics().membersDeclaredClean());
        assertEquals(0, second.executionMetrics().memberPipelineInvocations());
        assertEquals(0L, second.mainFingerprintCheckNanos());
    }

    @Test
    void resourceEditCopiesOnlyTheResourceWithoutCompiling() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"
                members = ["apps/api"]
                """);
        member("apps/api", "api", "");
        source("apps/api/src/main/java/com/acme/api/Api.java", """
                package com.acme.api;

                public final class Api {
                }
                """);
        source("apps/api/src/main/resources/application.properties", "message=first\n");
        service.build(tempDir, tempDir.resolve("cache"), false);
        Files.writeString(
                tempDir.resolve("apps/api/src/main/resources/application.properties"),
                "message=second\n");

        WorkspaceBuildResult result =
                service.build(tempDir, tempDir.resolve("cache"), false);

        assertEquals(1, result.mainCompilationSkippedCount());
        assertEquals(1, result.executionMetrics().memberPipelineInvocations());
        assertEquals(
                "message=second\n",
                Files.readString(
                        tempDir.resolve("apps/api/target/classes/application.properties")));
    }

    @Test
    void corruptWorkspaceStateFallsBackToCanonicalMemberPipeline() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"
                members = ["apps/api"]
                """);
        member("apps/api", "api", "");
        source("apps/api/src/main/java/com/acme/api/Api.java", """
                package com.acme.api;

                public final class Api {
                }
                """);
        service.build(tempDir, tempDir.resolve("cache"), false);
        Path state = new WorkspaceStateStore().path(tempDir);
        Files.writeString(state, "corrupt");

        WorkspaceBuildResult result =
                service.build(tempDir, tempDir.resolve("cache"), false);

        assertEquals(1, result.executionMetrics().memberPipelineInvocations());
        assertTrue(result.mainFingerprintCheckNanos() > 0L);
        assertTrue(Files.readString(state).startsWith("version=2\nchecksum="));
    }

    private void workspace(String content) throws IOException {
        Files.writeString(tempDir.resolve("zolt-workspace.toml"), content);
    }

    private void member(String path, String name, String extraToml) throws IOException {
        Path member = tempDir.resolve(path);
        Files.createDirectories(member);
        Files.writeString(member.resolve("zolt.toml"), """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "com.acme"
                java = "%s"
                %s""".formatted(name, currentJavaMajorVersion(), extraToml));
    }

    private void source(String path, String content) throws IOException {
        Path source = tempDir.resolve(path);
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
    }

    private static String currentJavaMajorVersion() {
        String version = System.getProperty("java.version");
        String[] parts = version.split("[._+-]", -1);
        if (parts.length >= 2 && "1".equals(parts[0])) {
            return parts[1];
        }
        return parts[0];
    }
}

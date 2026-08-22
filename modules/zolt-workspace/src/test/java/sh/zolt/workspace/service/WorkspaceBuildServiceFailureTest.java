package sh.zolt.workspace.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.JavacException;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

final class WorkspaceBuildServiceFailureTest extends WorkspaceBuildServiceTestSupport {
    private final WorkspaceBuildService service = new WorkspaceBuildService();

    @Test
    void failsWhenMemberImportsWorkspaceProjectWithoutDeclaringDependency() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"

                [workspace.members]
                include = ["modules/core", "modules/extra", "apps/api", "apps/worker"]
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
        member("modules/extra", "extra", "");
        source("modules/extra/src/main/java/com/acme/extra/Extra.java", """
                package com.acme.extra;

                public final class Extra {
                    private Extra() {
                    }

                    public static String message() {
                        return "extra";
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
                import com.acme.extra.Extra;

                public final class Api {
                    private Api() {
                    }

                    public static String message() {
                        return Core.message() + Extra.message();
                    }
                }
                """);
        member("apps/worker", "worker", """

                [dependencies]
                "com.acme:extra" = { workspace = true }
                """);
        source("apps/worker/src/main/java/com/acme/worker/Worker.java", """
                package com.acme.worker;

                import com.acme.extra.Extra;

                public final class Worker {
                    private Worker() {
                    }

                    public static String message() {
                        return Extra.message();
                    }
                }
                """);

        JavacException exception = assertThrows(
                JavacException.class,
                () -> service.build(
                        tempDir,
                        tempDir.resolve("cache"),
                        false,
                        new WorkspaceSelectionRequest(true, List.of())));

        assertTrue(exception.getMessage().contains("javac failed with exit code"));
        assertTrue(exception.getMessage().contains("Api.java"));
        assertTrue(exception.getMessage().contains("com.acme.extra"));
    }
}

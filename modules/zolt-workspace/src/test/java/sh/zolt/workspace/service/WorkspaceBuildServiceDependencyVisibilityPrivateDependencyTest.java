package sh.zolt.workspace.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.JavacException;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

final class WorkspaceBuildServiceDependencyVisibilityPrivateDependencyTest
        extends WorkspaceBuildServiceDependencyVisibilityTestSupport {

    @Test
    void downstreamMemberCannotCompileAgainstPrivateWorkspaceDependency() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"

                [workspace.members]
                include = ["modules/internal", "modules/core", "apps/api"]
                """);
        member("modules/internal", "internal", "");
        source("modules/internal/src/main/java/com/acme/internal/Internal.java", """
                package com.acme.internal;

                public final class Internal {
                    private Internal() {
                    }

                    public static String value() {
                        return "internal";
                    }
                }
                """);
        member("modules/core", "core", """

                [dependencies]
                "com.acme:internal" = { workspace = true }
                """);
        source("modules/core/src/main/java/com/acme/core/Core.java", """
                package com.acme.core;

                import com.acme.internal.Internal;

                public final class Core {
                    private Core() {
                    }

                    public static String value() {
                        return Internal.value();
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
                import com.acme.internal.Internal;

                public final class Api {
                    private Api() {
                    }

                    public static String value() {
                        return Core.value() + Internal.value();
                    }
                }
                """);

        JavacException exception = assertThrows(
                JavacException.class,
                () -> service.build(tempDir.resolve("apps/api"), tempDir.resolve("cache"), false));

        assertTrue(exception.getMessage().contains("Api.java"));
        assertTrue(exception.getMessage().contains("com.acme.internal"));
        assertTrue(Files.exists(
                tempDir.resolve("modules/internal/target/classes/com/acme/internal/Internal.class")));
        assertTrue(Files.exists(tempDir.resolve("modules/core/target/classes/com/acme/core/Core.class")));
        assertFalse(Files.exists(tempDir.resolve("apps/api/target/classes/com/acme/api/Api.class")));
    }

    @Test
    void downstreamMemberCannotCompileAgainstPrivateDependencyOfWorkspaceDependency() throws IOException {
        addJarArtifact(
                "com.example",
                "contract",
                "1.0.0",
                "com.example.contract.Contract",
                """
                package com.example.contract;

                public final class Contract {
                }
                """);
        addJarArtifact(
                "com.example",
                "internal",
                "1.0.0",
                "com.example.internal.Internal",
                """
                package com.example.internal;

                public final class Internal {
                    private Internal() {
                    }

                    public static String value() {
                        return "internal";
                    }
                }
                """);
        workspace("""
                [workspace]
                name = "acme-platform"

                [workspace.members]
                include = ["modules/core", "apps/api"]

                [repositories]
                central = false

                [repositories.test]
                url = "%s"
                """.formatted(baseUri));
        member("modules/core", "core", """

                [dependencies.api]
                "com.example:contract" = "1.0.0"

                [dependencies]
                "com.example:internal" = "1.0.0"
                """);
        source("modules/core/src/main/java/com/acme/core/Core.java", """
                package com.acme.core;

                import com.example.contract.Contract;
                import com.example.internal.Internal;

                public final class Core {
                    private Core() {
                    }

                    public static Contract contract() {
                        Internal.value();
                        return new Contract();
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
                import com.example.internal.Internal;

                public final class Api {
                    private Api() {
                    }

                    public static String message() {
                        Core.contract();
                        return Internal.value();
                    }
                }
                """);

        JavacException exception = assertThrows(
                JavacException.class,
                () -> service.build(tempDir.resolve("apps/api"), tempDir.resolve("cache"), false));

        assertTrue(exception.getMessage().contains("javac failed with exit code"));
        assertTrue(exception.getMessage().contains("Api.java"));
        assertTrue(exception.getMessage().contains("com.example.internal"));
        assertTrue(exception.getMessage().contains("move it to [dependencies.api]"));
        assertTrue(Files.exists(tempDir.resolve("modules/core/target/classes/com/acme/core/Core.class")));
        assertFalse(Files.exists(tempDir.resolve("apps/api/target/classes/com/acme/api/Api.class")));
    }
}

package sh.zolt.workspace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

final class WorkspaceBuildServiceDependencyVisibilityExportedApiTest
        extends WorkspaceBuildServiceDependencyVisibilityTestSupport {

    @Test
    void downstreamMemberCompilesAgainstExportedWorkspaceDependency() throws IOException {
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

                [dependencies.api]
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

        WorkspaceBuildResult result =
                service.build(tempDir.resolve("apps/api"), tempDir.resolve("cache"), false);

        assertEquals(3, result.sourceCount());
        assertTrue(Files.exists(tempDir.resolve("apps/api/target/classes/com/acme/api/Api.class")));
    }

    @Test
    void downstreamMemberCompilesAgainstExportedApiDependency() throws IOException {
        addJarArtifact(
                "com.example",
                "contract",
                "1.0.0",
                "com.example.contract.Contract",
                """
                package com.example.contract;

                public final class Contract {
                    private final String value;

                    public Contract(String value) {
                        this.value = value;
                    }

                    public String value() {
                        return value;
                    }
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
                        return new Contract(Internal.value());
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
                import com.example.contract.Contract;

                public final class Api {
                    private Api() {
                    }

                    public static String message() {
                        Contract contract = Core.contract();
                        return contract.value();
                    }
                }
                """);

        WorkspaceBuildResult result = service.build(tempDir.resolve("apps/api"), tempDir.resolve("cache"), false);

        assertEquals(2, result.sourceCount());
        assertTrue(Files.exists(tempDir.resolve("apps/api/target/classes/com/acme/api/Api.class")));
    }

    @Test
    void downstreamMemberCompilesAgainstExportedApiDependenciesTransitiveCompileClosure() throws IOException {
        addJarArtifact(
                "com.example",
                "api-types",
                "1.0.0",
                "com.example.types.ApiType",
                """
                package com.example.types;

                public record ApiType(String value) {
                }
                """);
        addJarArtifactWithDependency(
                "com.example",
                "api-lib",
                "1.0.0",
                "com.example.api.ApiContract",
                """
                package com.example.api;

                import com.example.types.ApiType;

                public final class ApiContract {
                    private ApiContract() {
                    }

                    public static ApiType value() {
                        return new ApiType("exported");
                    }
                }
                """,
                "com.example",
                "api-types",
                "1.0.0");
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
                "com.example:api-lib" = "1.0.0"
                """);
        source("modules/core/src/main/java/com/acme/core/Core.java", """
                package com.acme.core;

                import com.example.api.ApiContract;
                import com.example.types.ApiType;

                public final class Core {
                    private Core() {
                    }

                    public static ApiType type() {
                        return ApiContract.value();
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
                import com.example.types.ApiType;

                public final class Api {
                    private Api() {
                    }

                    public static String message() {
                        ApiType type = Core.type();
                        return type.value();
                    }
                }
                """);

        WorkspaceBuildResult result = service.build(tempDir.resolve("apps/api"), tempDir.resolve("cache"), false);

        assertEquals(2, result.sourceCount());
        assertTrue(Files.exists(tempDir.resolve("apps/api/target/classes/com/acme/api/Api.class")));
    }
}

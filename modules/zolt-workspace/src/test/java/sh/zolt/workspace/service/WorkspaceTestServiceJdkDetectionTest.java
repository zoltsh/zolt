package sh.zolt.workspace.service;

import static sh.zolt.workspace.service.WorkspaceTestServiceTestSupport.createFakeConsoleJar;
import static sh.zolt.workspace.service.WorkspaceTestServiceTestSupport.executable;
import static sh.zolt.workspace.service.WorkspaceTestServiceTestSupport.member;
import static sh.zolt.workspace.service.WorkspaceTestServiceTestSupport.source;
import static sh.zolt.workspace.service.WorkspaceTestServiceTestSupport.workspace;
import static org.junit.jupiter.api.Assertions.assertEquals;

import sh.zolt.doctor.JdkChecker;
import sh.zolt.doctor.JdkStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspaceTestServiceJdkDetectionTest {
    @TempDir
    private Path tempDir;

    @Test
    void sharesCachedJdkDetectionAcrossWorkspaceBuildAndTestExecution() throws IOException {
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
        CachingJdkChecker jdkChecker = new CachingJdkChecker();
        WorkspaceTestService service = new WorkspaceTestService(jdkChecker);

        WorkspaceTestResult result = service.test(
                tempDir,
                cacheRoot,
                new WorkspaceSelectionRequest(false, List.of("apps/api")));

        assertEquals(List.of("modules/core", "apps/api"), result.builtMembers().stream()
                .map(WorkspaceBuildResult.MemberBuildResult::member)
                .toList());
        assertEquals(List.of("apps/api"), result.members().stream()
                .map(WorkspaceTestResult.MemberTestRunResult::member)
                .toList());
        assertEquals(4, jdkChecker.detectCalls());
        assertEquals(1, jdkChecker.toolchainReads());
    }

    private static final class CachingJdkChecker implements JdkChecker {
        private int detectCalls;
        private int toolchainReads;
        private JdkStatus status;

        @Override
        public JdkStatus detect(String requiredVersion) {
            detectCalls++;
            if (status == null) {
                toolchainReads++;
                Path javaHome = Path.of(System.getProperty("java.home"));
                status = new JdkStatus(
                        Optional.of(javaHome),
                        Optional.of(javaHome.resolve("bin").resolve(executable("java"))),
                        Optional.of(javaHome.resolve("bin").resolve(executable("javac"))),
                        Optional.of(javaHome.resolve("bin").resolve(executable("jar"))),
                        Optional.of(requiredVersion),
                        requiredVersion);
            }
            return status;
        }

        int detectCalls() {
            return detectCalls;
        }

        int toolchainReads() {
            return toolchainReads;
        }
    }
}

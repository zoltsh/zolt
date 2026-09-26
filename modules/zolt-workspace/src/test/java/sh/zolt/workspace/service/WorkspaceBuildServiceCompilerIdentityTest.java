package sh.zolt.workspace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import sh.zolt.doctor.JdkChecker;
import sh.zolt.doctor.JdkStatus;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class WorkspaceBuildServiceCompilerIdentityTest extends WorkspaceBuildServiceTestSupport {
    @Test
    void compilerChangeCannotBypassTheCleanMemberPipeline() throws IOException {
        MutableJdkChecker checker = new MutableJdkChecker("compiler-a");
        WorkspaceBuildService service = new WorkspaceBuildService(checker);
        workspace("""
                [workspace]
                name = "acme-platform"

                [workspace.members]
                include = ["apps/api"]
                """);
        member("apps/api", "api", "");
        source("apps/api/src/main/java/com/acme/api/Api.java", """
                package com.acme.api;

                public final class Api {
                }
                """);

        service.build(tempDir, tempDir.resolve("cache"), false);
        WorkspaceBuildResult warm = service.build(tempDir, tempDir.resolve("cache"), false);
        checker.identity = "compiler-b";
        WorkspaceBuildResult changed = service.build(tempDir, tempDir.resolve("cache"), false);

        assertEquals(1, warm.mainCompilationSkippedCount());
        assertEquals(0, warm.executionMetrics().memberPipelineInvocations());
        assertEquals(1, changed.mainCompilationExecutedCount());
        assertFalse(changed.members().getFirst().result().mainCompilationSkipped());
        assertEquals(
                "compiler-identity-changed",
                changed.members().getFirst().result().mainIncrementalFallbackReason());
    }

    private static final class MutableJdkChecker implements JdkChecker {
        private String identity;

        private MutableJdkChecker(String identity) {
            this.identity = identity;
        }

        @Override
        public JdkStatus detect(String requiredVersion) {
            Path javaHome = Path.of(System.getProperty("java.home"));
            return new JdkStatus(
                    Optional.of(javaHome),
                    Optional.of(javaHome.resolve("bin").resolve(executable("java"))),
                    Optional.of(javaHome.resolve("bin").resolve(executable("javac"))),
                    Optional.of(javaHome.resolve("bin").resolve(executable("jar"))),
                    Optional.of(requiredVersion + ".0.1"),
                    Optional.of(identity),
                    requiredVersion);
        }

        private static String executable(String name) {
            return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
                    ? name + ".exe"
                    : name;
        }
    }
}

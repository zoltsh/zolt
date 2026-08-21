package sh.zolt.workspace.service;

import static sh.zolt.workspace.service.WorkspaceTestServiceTestSupport.lock;

import static sh.zolt.workspace.service.WorkspaceTestServiceTestSupport.member;
import static sh.zolt.workspace.service.WorkspaceTestServiceTestSupport.source;
import static sh.zolt.workspace.service.WorkspaceTestServiceTestSupport.workspace;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.testruntime.compile.TestCompileResult;
import sh.zolt.workspace.service.WorkspacePlanTarget;
import sh.zolt.workspace.test.WorkspaceTestCompileResult;
import sh.zolt.workspace.test.WorkspaceTestService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspaceTestDependencyIncrementalTest {
    @TempDir
    private Path tempDir;

    @Test
    void changingOneMembersTestPackageDoesNotInvalidateMainOrSiblingTestCompile()
            throws IOException {
        Path cacheRoot = tempDir.resolve("cache");
        prepareWorkspace(cacheRoot);
        WorkspaceTestService service = new WorkspaceTestService();

        compile(service, cacheRoot);
        writeJar(
                cacheRoot.resolve(
                        "com/example/test-support/2.0.0/test-support-2.0.0.jar"),
                "changed.txt");
        writeLockfile("2.0.0");

        WorkspaceTestCompileResult changed = compile(service, cacheRoot);
        Map<String, WorkspaceBuildResult.MemberBuildResult> builds =
                changed.builtMembers().stream()
                        .collect(Collectors.toMap(
                                WorkspaceBuildResult.MemberBuildResult::member,
                                Function.identity()));
        Map<String, TestCompileResult> tests = changed.members().stream()
                .collect(Collectors.toMap(
                        WorkspaceTestCompileResult.MemberTestCompileResult::member,
                        WorkspaceTestCompileResult.MemberTestCompileResult::result));

        assertEquals(2, changed.mainCompilationSkippedCount());
        assertTrue(builds.get("apps/a").result().mainCompilationSkipped());
        assertTrue(builds.get("apps/b").result().mainCompilationSkipped());
        assertFalse(tests.get("apps/a").testCompilationSkipped());
        assertTrue(tests.get("apps/b").testCompilationSkipped());
    }

    private WorkspaceTestCompileResult compile(
            WorkspaceTestService service,
            Path cacheRoot) {
        WorkspaceBuildPlan plan = service.planTests(
                WorkspacePlanTarget.at(tempDir),
                cacheRoot,
                WorkspaceSelectionRequest.defaults());
        WorkspaceBuildResult build =
                service.buildTestCompileInputs(plan, cacheRoot);
        return service.compileTests(plan, build);
    }

    private void prepareWorkspace(Path cacheRoot) throws IOException {
        workspace(tempDir, """
                [workspace]
                name = "incremental-test-dependency"
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
        writeJar(
                cacheRoot.resolve(
                        "com/example/test-support/1.0.0/test-support-1.0.0.jar"),
                "initial.txt");
        writeLockfile("1.0.0");
    }

    private void writeLockfile(String version) throws IOException {
        lock(tempDir, """
                version = 7

                [[dependencyRoot]]
                member = "apps/a"
                id = "com.example:test-support"
                version = "%s"
                lane = "test"
                resolvedScope = "test"

                [[package]]
                id = "com.example:test-support"
                version = "%s"
                source = "maven-central"
                scope = "test"
                direct = true
                jar = "com/example/test-support/%s/test-support-%s.jar"
                members = ["apps/a"]
                dependencies = []
                """.formatted(version, version, version, version));
    }

    private static void writeJar(Path path, String entryName)
            throws IOException {
        Files.createDirectories(path.getParent());
        try (JarOutputStream output =
                new JarOutputStream(Files.newOutputStream(path))) {
            output.putNextEntry(new JarEntry(entryName));
            output.write(entryName.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }
}

package sh.zolt.workspace.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.workspace.service.WorkspaceTestServiceTestSupport.member;
import static sh.zolt.workspace.service.WorkspaceTestServiceTestSupport.source;
import static sh.zolt.workspace.service.WorkspaceTestServiceTestSupport.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.workspace.test.WorkspaceTestCompileResult;
import sh.zolt.workspace.test.WorkspaceTestService;

/** The workspace clean fast path honors Kotlin compiler outputs recorded by the test fingerprint. */
final class WorkspaceKotlinTestOutputValidationTest {
    private final WorkspaceTestService service = new WorkspaceTestService();

    @TempDir
    private Path tempDir;

    @BeforeEach
    void createWorkspace() throws IOException {
        workspace(tempDir, """
                [workspace]
                name = "kotlin-test-output"

                [workspace.members]
                include = ["app"]
                """);
        member(tempDir, "app", "app", "");
        source(tempDir, "app/src/main/java/com/example/App.java", """
                package com.example;

                public final class App {
                }
                """);
        source(tempDir, "app/src/test/java/com/example/AppTest.java", """
                package com.example;

                public final class AppTest {
                }
                """);
    }

    @Test
    void deletedKotlinModuleRecordedByTestFingerprintRecompilesTests() throws IOException {
        assertFalse(compile().members().getFirst().result().testCompilationSkipped());

        Path module = tempDir.resolve("app/target/test-classes/META-INF/app_test.kotlin_module");
        Files.createDirectories(module.getParent());
        Files.writeString(module, "module metadata");
        recordTestFingerprintOutput(module);

        assertTrue(compile().members().getFirst().result().testCompilationSkipped());
        Files.delete(module);

        assertFalse(
                compile().members().getFirst().result().testCompilationSkipped(),
                "missing Kotlin module metadata must bypass the workspace clean fast path");
    }

    private WorkspaceTestCompileResult compile() {
        Path cacheRoot = tempDir.resolve("cache");
        WorkspaceBuildPlan plan = service.planTests(
                WorkspacePlanTarget.at(tempDir),
                cacheRoot,
                WorkspaceSelectionRequest.defaults());
        WorkspaceBuildResult build = service.buildTestCompileInputs(plan, cacheRoot);
        return service.compileTests(plan, build);
    }

    private void recordTestFingerprintOutput(Path output) throws IOException {
        Path memberDirectory = tempDir.resolve("app");
        Path fingerprint = memberDirectory.resolve(
                "target/test-classes/.zolt-build-test.fingerprint");
        String recorded = memberDirectory.relativize(output).toString().replace('\\', '/');
        Files.writeString(fingerprint, Files.readString(fingerprint) + recorded + "\n");
    }
}

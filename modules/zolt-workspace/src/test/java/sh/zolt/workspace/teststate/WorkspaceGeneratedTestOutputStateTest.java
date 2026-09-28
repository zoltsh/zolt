package sh.zolt.workspace.teststate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.workspace.service.WorkspaceTestServiceTestSupport.createFakeConsoleJar;
import static sh.zolt.workspace.service.WorkspaceTestServiceTestSupport.zeroTestsFoundSummary;

import sh.zolt.workspace.WorkspaceContentAddressedLockTestSupport;
import sh.zolt.workspace.service.WorkspaceBuildPlan;
import sh.zolt.workspace.service.WorkspaceBuildResult;
import sh.zolt.workspace.service.WorkspacePlanTarget;
import sh.zolt.workspace.service.WorkspaceSelectionRequest;
import sh.zolt.workspace.test.WorkspaceTestCompileResult;
import sh.zolt.workspace.test.WorkspaceTestService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Test annotation-processor output participates in the outer workspace freshness decision. */
final class WorkspaceGeneratedTestOutputStateTest {
    private final WorkspaceTestService service = new WorkspaceTestService();

    @TempDir
    private Path tempDir;

    @BeforeEach
    void createWorkspace() throws IOException {
        createFakeConsoleJar(
                tempDir,
                cacheRoot().resolve(
                        "org/junit/platform/junit-platform-console-standalone/1.11.4/"
                                + "junit-platform-console-standalone-1.11.4.jar"),
                zeroTestsFoundSummary());
        workspace("""
                [workspace]
                name = "generated-test-output-state"

                [workspace.members]
                default = ["apps/api"]
                include = ["apps/api", "modules/test-processor"]
                """);
        member("apps/api", "api", """

                [dependencies.test-processor]
                "com.acme:test-processor" = { workspace = true }
                """);
        member("modules/test-processor", "test-processor", "");
        source("apps/api/src/main/java/com/example/Api.java", """
                package com.example;

                public final class Api {
                }
                """);
        source("apps/api/src/test/java/com/example/ApiTest.java", """
                package com.example;

                public final class ApiTest {
                }
                """);
        testProcessor();
        lock();
    }

    @Test
    void changedOutputCannotBypassTheCanonicalCompileGate() throws IOException {
        WorkspaceTestCompileResult first = compileUnit();
        assertFalse(first.members().getFirst().result().testCompilationSkipped());
        assertTrue(compileUnit().members().getFirst().result().testCompilationSkipped());

        Path generatedSource = tempDir.resolve(
                "apps/api/target/generated/test-sources/annotations/com/example/GeneratedTestSupport.java");
        assertTrue(Files.readString(generatedSource).contains("generated"));
        Files.writeString(
                generatedSource,
                Files.readString(generatedSource).replace("generated", "tampered"));

        PendingCompile changed = pendingCompile();
        assertTrue(changed.build().membersRequiringTestCompile().contains("apps/api"));
        assertEquals(0, changed.build().executionMetrics().memberPipelineInvocations());
        WorkspaceTestCompileResult repaired =
                service.compileTests(changed.plan(), changed.build());
        assertFalse(repaired.members().getFirst().result().testCompilationSkipped());
        assertTrue(Files.readString(generatedSource).contains("generated"));
        assertFalse(Files.readString(generatedSource).contains("tampered"));

        PendingCompile finalWarm = pendingCompile();
        assertFalse(finalWarm.build().membersRequiringTestCompile().contains("apps/api"));
        assertTrue(service.compileTests(finalWarm.plan(), finalWarm.build())
                .members()
                .getFirst()
                .result()
                .testCompilationSkipped());
    }

    private PendingCompile pendingCompile() {
        WorkspaceBuildPlan plan = service.planTests(
                WorkspacePlanTarget.at(tempDir),
                cacheRoot(),
                WorkspaceSelectionRequest.defaults());
        return new PendingCompile(plan, service.buildTestCompileInputs(plan, cacheRoot()));
    }

    private WorkspaceTestCompileResult compileUnit() {
        PendingCompile pending = pendingCompile();
        return service.compileTests(pending.plan(), pending.build());
    }

    private void testProcessor() throws IOException {
        source("modules/test-processor/src/main/java/com/example/TestSourceProcessor.java", """
                package com.example;

                import java.io.IOException;
                import java.io.Writer;
                import java.util.Set;
                import javax.annotation.processing.AbstractProcessor;
                import javax.annotation.processing.RoundEnvironment;
                import javax.annotation.processing.SupportedAnnotationTypes;
                import javax.annotation.processing.SupportedSourceVersion;
                import javax.lang.model.SourceVersion;
                import javax.lang.model.element.TypeElement;
                import javax.tools.JavaFileObject;

                @SupportedAnnotationTypes("*")
                @SupportedSourceVersion(SourceVersion.RELEASE_17)
                public final class TestSourceProcessor extends AbstractProcessor {
                    private boolean generated;

                    @Override
                    public boolean process(
                            Set<? extends TypeElement> annotations,
                            RoundEnvironment roundEnvironment) {
                        if (generated || roundEnvironment.processingOver()) {
                            return false;
                        }
                        generated = true;
                        try {
                            JavaFileObject file = processingEnv.getFiler()
                                    .createSourceFile("com.example.GeneratedTestSupport");
                            try (Writer writer = file.openWriter()) {
                                writer.write("package com.example;\\n");
                                writer.write("public final class GeneratedTestSupport {\\n");
                                writer.write("    public static String value() { return \\\"generated\\\"; }\\n");
                                writer.write("}\\n");
                            }
                        } catch (IOException exception) {
                            throw new RuntimeException(exception);
                        }
                        return false;
                    }
                }
                """);
        source(
                "modules/test-processor/src/main/resources/META-INF/services/"
                        + "javax.annotation.processing.Processor",
                "com.example.TestSourceProcessor\n");
    }

    private void lock() throws IOException {
        WorkspaceContentAddressedLockTestSupport.write(
                tempDir.resolve("zolt.lock"),
                cacheRoot(),
                """
                        version = 7

                        [[package]]
                        id = "org.junit.platform:junit-platform-console-standalone"
                        version = "1.11.4"
                        source = "maven-central"
                        scope = "test"
                        direct = false
                        jar = "org/junit/platform/junit-platform-console-standalone/1.11.4/junit-platform-console-standalone-1.11.4.jar"
                        members = ["apps/api"]
                        dependencies = []

                        [[package]]
                        id = "com.acme:test-processor"
                        version = "0.1.0"
                        source = "workspace"
                        scope = "test-processor"
                        direct = true
                        workspace = "modules/test-processor"
                        workspaceOutput = "target/classes"
                        members = ["apps/api"]
                        dependencies = []

                        [[dependencyRoot]]
                        member = "apps/api"
                        id = "com.acme:test-processor"
                        version = "0.1.0"
                        lane = "test-processor"
                        resolvedScope = "test-processor"
                        """);
    }

    private void workspace(String content) throws IOException {
        Files.writeString(tempDir.resolve("zolt.toml"), content);
    }

    private void member(String path, String name, String extraToml) throws IOException {
        Path member = tempDir.resolve(path);
        Files.createDirectories(member);
        Files.writeString(member.resolve("zolt.toml"), """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "com.acme"
                java = %s
                %s""".formatted(name, Runtime.version().feature(), extraToml));
    }

    private void source(String path, String content) throws IOException {
        Path source = tempDir.resolve(path);
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
    }

    private Path cacheRoot() {
        return tempDir.resolve("cache");
    }

    private record PendingCompile(
            WorkspaceBuildPlan plan,
            WorkspaceBuildResult build) {
    }
}

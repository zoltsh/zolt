package sh.zolt.workspace.teststate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.workspace.service.WorkspaceTestServiceTestSupport.createFakeConsoleJar;
import static sh.zolt.workspace.service.WorkspaceTestServiceTestSupport.zeroTestsFoundSummary;

import sh.zolt.build.BuildException;
import sh.zolt.build.incremental.IncrementalCompileState;
import sh.zolt.build.incremental.IncrementalCompileStateCodec;
import sh.zolt.test.TestSelection;
import sh.zolt.test.runtime.TestRunException;
import sh.zolt.workspace.WorkspaceContentAddressedLockTestSupport;
import sh.zolt.workspace.service.WorkspaceBuildPlan;
import sh.zolt.workspace.service.WorkspaceBuildResult;
import sh.zolt.workspace.service.WorkspacePlanTarget;
import sh.zolt.workspace.service.WorkspaceSelectionRequest;
import sh.zolt.workspace.state.WorkspaceMemberState;
import sh.zolt.workspace.state.WorkspaceStateStore;
import sh.zolt.workspace.test.WorkspaceTestCompileResult;
import sh.zolt.workspace.test.WorkspaceTestService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Test-lane workspace state is a commit record, not a promise made before compilation. */
final class WorkspaceTestStatePublicationTest {
    private final WorkspaceTestService service = new WorkspaceTestService();
    private final WorkspaceStateStore stateStore = new WorkspaceStateStore();

    @TempDir
    private Path tempDir;

    @BeforeEach
    void createWorkspace() throws IOException {
        Path cacheRoot = cacheRoot();
        createFakeConsoleJar(
                tempDir,
                cacheRoot.resolve(
                        "org/junit/platform/junit-platform-console-standalone/1.11.4/"
                                + "junit-platform-console-standalone-1.11.4.jar"),
                zeroTestsFoundSummary());
        workspace(tempDir, """
                [workspace]
                name = "test-state-publication"

                [workspace.members]
                include = ["apps/api"]
                """);
        member(tempDir, "apps/api", "api", "");
        source(tempDir, "apps/api/src/main/java/com/example/Api.java", """
                package com.example;

                public final class Api {
                }
                """);
        writeJavaTest("before");
        source(tempDir, "apps/api/src/integration-test/java/com/example/ApiIT.java", """
                package com.example;

                public final class ApiIT {
                }
                """);
        lock(tempDir, """
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
                """);
    }

    @Test
    void failedTestPreflightDoesNotPublishItsCandidateState() throws IOException {
        compileUnit();
        WorkspaceMemberState committed = memberState();
        Path testClass = tempDir.resolve(
                "apps/api/target/test-classes/com/example/ApiTest.class");
        byte[] priorClass = Files.readAllBytes(testClass);
        writeJavaTest("after");
        enableGroovyAndKotlinTestRoots();
        source(tempDir, "apps/api/src/test/groovy/com/example/GroovyTest.groovy", """
                package com.example

                final class GroovyTest {
                }
                """);
        source(tempDir, "apps/api/src/test/kotlin/com/example/KotlinTest.kt", """
                package com.example

                class KotlinTest
                """);

        PendingCompile failed = pendingCompile();

        assertTrue(failed.build().membersRequiringTestCompile().contains("apps/api"));
        assertEquals(committed.testCompileKey(), memberState().testCompileKey());
        BuildException first = assertThrows(
                BuildException.class,
                () -> service.compileTests(failed.plan(), failed.build()));
        assertTrue(first.getMessage().contains("combines Groovy and Kotlin"));
        assertEquals(committed.testCompileKey(), memberState().testCompileKey());
        assertArrayEquals(priorClass, Files.readAllBytes(testClass));

        PendingCompile retry = pendingCompile();
        assertTrue(retry.build().membersRequiringTestCompile().contains("apps/api"));
        assertThrows(
                BuildException.class,
                () -> service.compileTests(retry.plan(), retry.build()));

        Files.delete(tempDir.resolve(
                "apps/api/src/test/groovy/com/example/GroovyTest.groovy"));
        Files.delete(tempDir.resolve(
                "apps/api/src/test/kotlin/com/example/KotlinTest.kt"));
        WorkspaceTestCompileResult repaired = compileUnit();

        assertFalse(repaired.members().getFirst().result().testCompilationSkipped());
        assertNotEquals(committed.testCompileKey(), memberState().testCompileKey());
        assertFalse(Arrays.equals(priorClass, Files.readAllBytes(testClass)));
        assertTrue(compileUnit().members().getFirst().result().testCompilationSkipped());
    }

    @Test
    void generatedTestSourcesAndInputsReachTheCanonicalCompileGate() throws IOException {
        enableDeclaredTestRoot();
        source(tempDir, "apps/api/fixtures.sql", "before\n");
        writeDeclaredTestSource("before");

        WorkspaceTestCompileResult first = compileUnit();
        Path declaredClass = tempDir.resolve(
                "apps/api/target/test-classes/com/example/DeclaredTestSupport.class");
        byte[] priorClass = Files.readAllBytes(declaredClass);
        assertFalse(first.members().getFirst().result().testCompilationSkipped());
        PendingCompile warm = pendingCompile();
        assertTrue(warm.build().membersRequiringTestCompile().contains("apps/api"));
        assertTrue(service.compileTests(warm.plan(), warm.build())
                .members()
                .getFirst()
                .result()
                .testCompilationSkipped());

        writeDeclaredTestSource("after");
        PendingCompile sourceEdit = pendingCompile();
        assertTrue(sourceEdit.build().membersRequiringTestCompile().contains("apps/api"));
        assertEquals(0, sourceEdit.build().executionMetrics().memberPipelineInvocations());
        WorkspaceTestCompileResult recompiled =
                service.compileTests(sourceEdit.plan(), sourceEdit.build());
        assertFalse(recompiled.members().getFirst().result().testCompilationSkipped());
        assertFalse(Arrays.equals(priorClass, Files.readAllBytes(declaredClass)));

        Files.writeString(tempDir.resolve("apps/api/fixtures.sql"), "after\n");
        PendingCompile inputEdit = pendingCompile();
        assertTrue(inputEdit.build().membersRequiringTestCompile().contains("apps/api"));
        assertEquals(0, inputEdit.build().executionMetrics().memberPipelineInvocations());
        WorkspaceTestCompileResult producerRecompiled =
                service.compileTests(inputEdit.plan(), inputEdit.build());
        assertFalse(producerRecompiled.members().getFirst().result().testCompilationSkipped());
        PendingCompile finalWarm = pendingCompile();
        assertTrue(finalWarm.build().membersRequiringTestCompile().contains("apps/api"));
        assertTrue(service.compileTests(finalWarm.plan(), finalWarm.build())
                .members()
                .getFirst()
                .result()
                .testCompilationSkipped());
    }

    @Test
    void failedRunDoesNotCommitCompiledTestOutputAndExactRevertRecompiles() throws IOException {
        compileUnit();
        WorkspaceMemberState committed = memberState();
        String committedManifest = committed.testOutputManifestDigest();
        Path testClass = tempDir.resolve(
                "apps/api/target/test-classes/com/example/ApiTest.class");
        byte[] committedClass = Files.readAllBytes(testClass);
        assertEquals(committedManifest, innerTestManifest());
        writeJavaTest("after");
        PendingCompile failed = pendingRun();

        assertTrue(failed.build().membersRequiringTestCompile().contains("apps/api"));
        TestRunException failure = assertThrows(
                TestRunException.class,
                () -> service.runTests(
                        failed.plan(),
                        failed.build(),
                        cacheRoot(),
                        TestSelection.fromCli(
                                List.of("com.example.ApiTest"),
                                List.of(),
                                List.of(),
                                List.of())));

        assertTrue(failure.getMessage().contains("Selected tests did not match any tests"));
        assertEquals(committedManifest, memberState().testOutputManifestDigest());
        assertNotEquals(committedManifest, innerTestManifest());

        writeJavaTest("before");
        WorkspaceTestCompileResult repaired = compileUnit();

        assertFalse(repaired.members().getFirst().result().testCompilationSkipped());
        assertArrayEquals(committedClass, Files.readAllBytes(testClass));
        assertEquals(committedManifest, innerTestManifest());
        assertEquals(committedManifest, memberState().testOutputManifestDigest());
        assertTrue(compileUnit().members().getFirst().result().testCompilationSkipped());
    }

    @Test
    void integrationCompilationDoesNotCommitPendingUnitTestState() throws IOException {
        compileUnit();
        WorkspaceMemberState committed = memberState();
        writeJavaTest("after");
        PendingCompile integration = pendingRun();

        service.runIntegrationTests(
                integration.plan(),
                integration.build(),
                cacheRoot(),
                TestSelection.empty(),
                null,
                null,
                List.of());

        assertEquals(committed.testCompileKey(), memberState().testCompileKey());
        WorkspaceTestCompileResult unit = compileUnit();
        assertFalse(unit.members().getFirst().result().testCompilationSkipped());
        assertNotEquals(committed.testCompileKey(), memberState().testCompileKey());
    }

    private PendingCompile pendingCompile() {
        WorkspaceBuildPlan plan = plan();
        return new PendingCompile(
                plan,
                service.buildTestCompileInputs(plan, cacheRoot()));
    }

    private PendingCompile pendingRun() {
        WorkspaceBuildPlan plan = plan();
        return new PendingCompile(
                plan,
                service.buildTestInputs(plan, cacheRoot()));
    }

    private WorkspaceTestCompileResult compileUnit() {
        PendingCompile pending = pendingCompile();
        return service.compileTests(pending.plan(), pending.build());
    }

    private WorkspaceBuildPlan plan() {
        return service.planTests(
                WorkspacePlanTarget.at(tempDir),
                cacheRoot(),
                WorkspaceSelectionRequest.defaults());
    }

    private WorkspaceMemberState memberState() {
        return stateStore.read(tempDir).member("apps/api").orElseThrow();
    }

    private String innerTestManifest() {
        Path output = tempDir.resolve("apps/api/target/test-classes")
                .toAbsolutePath()
                .normalize();
        return new IncrementalCompileStateCodec()
                .read(IncrementalCompileState.testStatePath(output))
                .orElseThrow()
                .outputManifestDigest();
    }

    private void enableGroovyAndKotlinTestRoots() throws IOException {
        Path manifest = tempDir.resolve("apps/api/zolt.toml");
        Files.writeString(
                manifest,
                Files.readString(manifest) + """

                        [test.sources]
                        groovy = ["src/test/groovy"]
                        kotlin = ["src/test/kotlin"]
                        """);
    }

    private void enableDeclaredTestRoot() throws IOException {
        Path manifest = tempDir.resolve("apps/api/zolt.toml");
        Files.writeString(
                manifest,
                Files.readString(manifest) + """

                        [generated.test.fixtures]
                        kind = "declared-root"
                        language = "java"
                        inputs = ["fixtures.sql"]
                        output = "generated/test/java"
                        """);
    }

    private void writeDeclaredTestSource(String value) throws IOException {
        source(tempDir, "apps/api/generated/test/java/com/example/DeclaredTestSupport.java", """
                package com.example;

                public final class DeclaredTestSupport {
                    public static String value() {
                        return "%s";
                    }
                }
                """.formatted(value));
    }

    private void writeJavaTest(String value) throws IOException {
        source(tempDir, "apps/api/src/test/java/com/example/ApiTest.java", """
                package com.example;

                public final class ApiTest {
                    public static String value() {
                        return "%s";
                    }
                }
                """.formatted(value));
    }

    private Path cacheRoot() {
        return tempDir.resolve("cache");
    }

    private static void workspace(Path root, String content) throws IOException {
        Files.writeString(root.resolve("zolt.toml"), content);
    }

    private static void member(
            Path root,
            String path,
            String name,
            String extraToml) throws IOException {
        Path member = root.resolve(path);
        Files.createDirectories(member);
        Files.writeString(member.resolve("zolt.toml"), """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "com.acme"
                java = %s
                %s""".formatted(name, Runtime.version().feature(), extraToml));
    }

    private static void source(Path root, String path, String content) throws IOException {
        Path source = root.resolve(path);
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
    }

    private static void lock(Path root, String content) throws IOException {
        WorkspaceContentAddressedLockTestSupport.write(
                root.resolve("zolt.lock"),
                root.resolve("cache"),
                content);
    }

    private record PendingCompile(
            WorkspaceBuildPlan plan,
            WorkspaceBuildResult build) {
    }
}

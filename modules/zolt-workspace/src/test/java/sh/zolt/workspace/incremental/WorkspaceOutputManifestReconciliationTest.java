package sh.zolt.workspace.incremental;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.JavacException;
import sh.zolt.build.incremental.IncrementalCompileSummaryReader;
import sh.zolt.workspace.service.WorkspaceBuildPlan;
import sh.zolt.workspace.service.WorkspaceBuildResult;
import sh.zolt.workspace.service.WorkspaceBuildService;
import sh.zolt.workspace.service.WorkspacePlanTarget;
import sh.zolt.workspace.service.WorkspaceSelectionRequest;
import sh.zolt.workspace.state.WorkspaceMemberState;
import sh.zolt.workspace.state.WorkspaceState;
import sh.zolt.workspace.state.WorkspaceStateStore;
import sh.zolt.workspace.test.WorkspaceTestService;

/** Outer workspace state reconciles inner manifests left by a failed command. */
final class WorkspaceOutputManifestReconciliationTest {
    private final WorkspaceBuildService service = new WorkspaceBuildService();

    @TempDir
    private Path tempDir;

    @Test
    void exactProviderSourceRevertAfterDependentFailureRebuildsTheProvider() throws IOException {
        twoMemberWorkspace();
        String original = providerSource("String", "\"value\"");
        write("modules/provider/src/main/java/com/acme/provider/Provider.java", original);
        service.build(tempDir, tempDir.resolve("cache"), false);
        WorkspaceStateStore store = new WorkspaceStateStore();
        String baseline = store.read(tempDir)
                .member("modules/provider")
                .orElseThrow()
                .mainOutputManifestDigest();

        write(
                "modules/provider/src/main/java/com/acme/provider/Provider.java",
                providerSource("int", "1"));
        assertThrows(
                JavacException.class,
                () -> service.build(tempDir, tempDir.resolve("cache"), false));
        String outerAfterFailure = store.read(tempDir)
                .member("modules/provider")
                .orElseThrow()
                .mainOutputManifestDigest();
        String innerAfterFailure = providerInnerManifest();
        assertEquals(baseline, outerAfterFailure);
        assertNotEquals(baseline, innerAfterFailure);

        write("modules/provider/src/main/java/com/acme/provider/Provider.java", original);

        WorkspaceBuildResult recovered =
                assertDoesNotThrow(() -> service.build(tempDir, tempDir.resolve("cache"), false));
        assertTrue(recovered.members().stream()
                .filter(member -> "modules/provider".equals(member.member()))
                .noneMatch(member -> member.result().mainCompilationSkipped()));
        String reconciledOuter = store.read(tempDir)
                .member("modules/provider")
                .orElseThrow()
                .mainOutputManifestDigest();
        assertEquals(providerInnerManifest(), reconciledOuter);
        assertEquals(baseline, reconciledOuter);
    }

    @Test
    void changedInnerMainAndTestManifestsScheduleTheirOwnLanes() throws IOException {
        assertInnerMainAndTestManifestsScheduleTheirOwnLanes(
                "older-main-manifest", "older-test-manifest");
    }

    @Test
    void unrecordedInnerMainAndTestManifestsScheduleTheirOwnLanes() throws IOException {
        assertInnerMainAndTestManifestsScheduleTheirOwnLanes("", "");
    }

    @Test
    void unrecordedInnerTestManifestSchedulesOnlyTheTestLane() throws IOException {
        singleMemberWorkspaceWithTests();
        WorkspaceTestService tests = new WorkspaceTestService();
        WorkspaceBuildPlan initialPlan = tests.planTests(
                WorkspacePlanTarget.at(tempDir),
                tempDir.resolve("cache"),
                WorkspaceSelectionRequest.defaults());
        WorkspaceBuildResult initialBuild =
                tests.buildTestCompileInputs(initialPlan, tempDir.resolve("cache"));
        tests.compileTests(initialPlan, initialBuild);

        WorkspaceStateStore store = new WorkspaceStateStore();
        WorkspaceState recorded = store.read(tempDir);
        WorkspaceMemberState app = recorded.member("app").orElseThrow();
        Map<String, WorkspaceMemberState> stale = new LinkedHashMap<>(recorded.members());
        stale.put(
                "app",
                withOutputManifests(app, app.mainOutputManifestDigest(), ""));
        store.write(tempDir, new WorkspaceState(stale, recorded.files()));

        WorkspaceBuildPlan reconciliationPlan = tests.planTests(
                WorkspacePlanTarget.at(tempDir),
                tempDir.resolve("cache"),
                WorkspaceSelectionRequest.defaults());
        WorkspaceBuildResult reconciliation =
                tests.buildTestCompileInputs(reconciliationPlan, tempDir.resolve("cache"));

        assertEquals(0, reconciliation.executionMetrics().memberPipelineInvocations());
        assertTrue(reconciliation.membersRequiringTestCompile().contains("app"));
    }

    private void assertInnerMainAndTestManifestsScheduleTheirOwnLanes(
            String recordedMainManifest,
            String recordedTestManifest) throws IOException {
        singleMemberWorkspaceWithTests();
        WorkspaceTestService tests = new WorkspaceTestService();
        WorkspaceBuildPlan initialPlan = tests.planTests(
                WorkspacePlanTarget.at(tempDir),
                tempDir.resolve("cache"),
                WorkspaceSelectionRequest.defaults());
        WorkspaceBuildResult initialBuild =
                tests.buildTestCompileInputs(initialPlan, tempDir.resolve("cache"));
        tests.compileTests(initialPlan, initialBuild);

        WorkspaceStateStore store = new WorkspaceStateStore();
        WorkspaceState recorded = store.read(tempDir);
        WorkspaceMemberState app = recorded.member("app").orElseThrow();
        Map<String, WorkspaceMemberState> stale = new LinkedHashMap<>(recorded.members());
        stale.put(
                "app",
                withOutputManifests(app, recordedMainManifest, recordedTestManifest));
        store.write(tempDir, new WorkspaceState(stale, recorded.files()));

        WorkspaceBuildPlan reconciliationPlan = tests.planTests(
                WorkspacePlanTarget.at(tempDir),
                tempDir.resolve("cache"),
                WorkspaceSelectionRequest.defaults());
        WorkspaceBuildResult reconciliation =
                tests.buildTestCompileInputs(reconciliationPlan, tempDir.resolve("cache"));

        assertEquals(1, reconciliation.executionMetrics().memberPipelineInvocations());
        assertTrue(reconciliation.membersRequiringTestCompile().contains("app"));
        tests.compileTests(reconciliationPlan, reconciliation);
        WorkspaceMemberState reconciled = store.read(tempDir).member("app").orElseThrow();
        assertNotEquals(recordedMainManifest, reconciled.mainOutputManifestDigest());
        assertNotEquals(recordedTestManifest, reconciled.testOutputManifestDigest());
    }

    private void twoMemberWorkspace() throws IOException {
        write("zolt.toml", """
                [workspace]
                name = "output-reconciliation"

                [workspace.members]
                include = ["modules/provider", "apps/consumer"]
                """);
        member("modules/provider", "provider", "");
        member("apps/consumer", "consumer", """

                [dependencies]
                "com.acme:provider" = { workspace = true }
                """);
        write("apps/consumer/src/main/java/com/acme/consumer/Consumer.java", """
                package com.acme.consumer;

                import com.acme.provider.Provider;

                public final class Consumer {
                    public static String value() {
                        return Provider.value();
                    }
                }
                """);
    }

    private void singleMemberWorkspaceWithTests() throws IOException {
        write("zolt.toml", """
                [workspace]
                name = "manifest-lanes"

                [workspace.members]
                include = ["app"]
                """);
        member("app", "app", "");
        write("app/src/main/java/com/acme/App.java", """
                package com.acme;

                public final class App {
                }
                """);
        write("app/src/test/java/com/acme/AppTest.java", """
                package com.acme;

                public final class AppTest {
                }
                """);
    }

    private void member(String path, String name, String extra) throws IOException {
        write(path + "/zolt.toml", """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "com.acme"
                java = %s
                %s""".formatted(name, Runtime.version().feature(), extra));
    }

    private void write(String relative, String content) throws IOException {
        Path path = tempDir.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private static WorkspaceMemberState withOutputManifests(
            WorkspaceMemberState state,
            String main,
            String test) {
        return new WorkspaceMemberState(
                state.configDigest(),
                state.toolchainDigest(),
                state.mainSourceTreeDigest(),
                state.resourceTreeDigest(),
                state.generatedInputDigest(),
                state.mainCompileKey(),
                main,
                state.publicAbiDigest(),
                state.packagePrivateAbiDigest(),
                state.testCompileKey(),
                state.testResourceTreeDigest(),
                test,
                state.processorInputDigest(),
                state.generatedOutputDigest());
    }

    private String providerInnerManifest() {
        return new IncrementalCompileSummaryReader()
                .readMain(tempDir.resolve("modules/provider/target/classes"))
                .orElseThrow()
                .outputManifestDigest();
    }

    private static String providerSource(String type, String value) {
        return """
                package com.acme.provider;

                public final class Provider {
                    public static %s value() {
                        return %s;
                    }
                }
                """.formatted(type, value);
    }
}

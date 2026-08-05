package sh.zolt.workspace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.doctor.JdkDetector;
import sh.zolt.workspace.state.WorkspaceMemberState;
import sh.zolt.workspace.state.WorkspaceState;
import sh.zolt.workspace.state.WorkspaceStateStore;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Stage 0 states why a member cannot be left alone, one reason per class of input, decided without
 * a single classpath having been constructed.
 */
final class WorkspaceDirtyPlannerReasonTest extends WorkspaceBuildServiceTestSupport {
    private final WorkspaceBuildService service = new WorkspaceBuildService();

    @BeforeEach
    void buildOnce() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"
                members = ["modules/core", "apps/api"]
                """);
        member("modules/core", "core", "");
        source("modules/core/src/main/java/com/acme/core/Core.java", """
                package com.acme.core;

                public final class Core {
                    public static String message() {
                        return "core";
                    }
                }
                """);
        member("apps/api", "api", """

                [dependencies]
                "com.acme:core" = { workspace = "modules/core" }
                """);
        source("apps/api/src/main/java/com/acme/api/Api.java", """
                package com.acme.api;

                import com.acme.core.Core;

                public final class Api {
                    public static String message() {
                        return Core.message();
                    }
                }
                """);
        source("apps/api/src/main/resources/application.properties", "message=first\n");
        source("apps/api/src/test/resources/fixture.txt", "A");
        service.build(tempDir, tempDir.resolve("cache"), false);
    }

    @Test
    void currentInputsProduceNoReasons() {
        assertEquals(List.of(), reasons().get("apps/api"));
        assertEquals(List.of(), reasons().get("modules/core"));
    }

    @Test
    void mainSourceEditIsReportedAsAMainSourceChange() throws IOException {
        source("apps/api/src/main/java/com/acme/api/Api.java", """
                package com.acme.api;

                import com.acme.core.Core;

                public final class Api {
                    public static String message() {
                        return Core.message() + "!";
                    }
                }
                """);

        assertEquals(
                List.of(WorkspaceDirtyReason.MAIN_SOURCE_CHANGED),
                reasons().get("apps/api"));
    }

    @Test
    void resourceEditIsReportedAsAResourceChange() throws IOException {
        Files.writeString(
                tempDir.resolve("apps/api/src/main/resources/application.properties"),
                "message=second\n");

        assertEquals(
                List.of(
                        WorkspaceDirtyReason.RESOURCE_CHANGED,
                        WorkspaceDirtyReason.RESOURCE_OUTPUT_MISSING),
                reasons().get("apps/api"));
    }

    @Test
    void configEditIsReportedAsAConfigChange() throws IOException {
        member("modules/core", "core", """

                [build.metadata]
                buildInfo = true
                """);

        assertTrue(reasons().get("modules/core").contains(WorkspaceDirtyReason.CONFIG_CHANGED));
    }

    @Test
    void deletedOutputIsReportedAsAMissingOutput() throws IOException {
        Files.delete(tempDir.resolve("modules/core/target/classes/com/acme/core/Core.class"));

        assertEquals(
                List.of(WorkspaceDirtyReason.OUTPUT_MISSING),
                reasons().get("modules/core"));
    }

    /**
     * The dependent's recorded token for its dependency no longer matches the ABI sitting next to
     * that dependency's output, which is exactly the case a stale-classpath digest used to catch.
     */
    @Test
    void aDependencyAbiThatMovedSinceTheLastBuildIsReportedOnTheDependent() {
        WorkspaceStateStore store = new WorkspaceStateStore();
        WorkspaceState state = store.read(tempDir);
        WorkspaceMemberState core = state.member("modules/core").orElseThrow();
        Map<String, WorkspaceMemberState> rewritten =
                new LinkedHashMap<>(state.members());
        rewritten.put(
                "modules/core",
                new WorkspaceMemberState(
                        core.configDigest(),
                        core.toolchainDigest(),
                        core.mainSourceTreeDigest(),
                        core.resourceTreeDigest(),
                        core.generatedInputDigest(),
                        core.mainCompileKey(),
                        core.mainOutputManifestDigest(),
                        "abi-from-an-older-build",
                        core.packagePrivateAbiDigest(),
                        core.testCompileKey(),
                        core.testResourceTreeDigest(),
                        core.testOutputManifestDigest()));
        store.write(tempDir, new WorkspaceState(rewritten));

        assertEquals(
                List.of(WorkspaceDirtyReason.DEPENDENCY_ABI_CHANGED),
                reasons().get("apps/api"));
    }

    @Test
    void aMovedLockPackageIsReportedAsAResolutionInputChange() throws IOException {
        var lock = tempDir.resolve("zolt.lock");
        Files.writeString(
                lock,
                Files.readString(lock)
                        .replace("workspaceOutput = \"target/classes\"", "workspaceOutput = \"build/classes\""));

        List<WorkspaceDirtyReason> apiReasons = reasons().get("apps/api");
        assertTrue(apiReasons.contains(WorkspaceDirtyReason.RESOLUTION_INPUT_CHANGED));
        assertFalse(apiReasons.contains(WorkspaceDirtyReason.MAIN_SOURCE_CHANGED));
    }

    /** A lock edit that touches nothing on the member's lanes must not admit anyone. */
    @Test
    void aLockEditOutsideThePackageRecordsLeavesEveryMemberClean() throws IOException {
        var lock = tempDir.resolve("zolt.lock");
        Files.writeString(lock, Files.readString(lock) + "\n# rewritten by an unrelated tool\n");

        assertEquals(List.of(), reasons().get("apps/api"));
        assertEquals(List.of(), reasons().get("modules/core"));
    }

    @Test
    void testSourceEditIsReportedOnlyWhenTheCommandCompilesTests() throws IOException {
        source("apps/api/src/test/java/com/acme/api/ApiTest.java", """
                package com.acme.api;

                public final class ApiTest {
                }
                """);

        assertFalse(reasons().get("apps/api").contains(WorkspaceDirtyReason.TEST_SOURCE_CHANGED));
        assertTrue(testReasons().get("apps/api").contains(WorkspaceDirtyReason.TEST_SOURCE_CHANGED));
    }

    /** The test lane's own resource input, mirrored on the main lane's {@code RESOURCE_CHANGED}. */
    @Test
    void testResourceEditIsReportedOnlyWhenTheCommandCompilesTests() throws IOException {
        Files.writeString(tempDir.resolve("apps/api/src/test/resources/fixture.txt"), "B");

        assertFalse(reasons().get("apps/api").contains(WorkspaceDirtyReason.TEST_RESOURCE_CHANGED));
        assertTrue(testReasons().get("apps/api").contains(WorkspaceDirtyReason.TEST_RESOURCE_CHANGED));
    }

    private Map<String, List<WorkspaceDirtyReason>> reasons() {
        return reasons(WorkspaceBuildRequirements.mainBuild());
    }

    private Map<String, List<WorkspaceDirtyReason>> testReasons() {
        return reasons(WorkspaceBuildRequirements.testCompile());
    }

    private Map<String, List<WorkspaceDirtyReason>> reasons(
            WorkspaceBuildRequirements requirements) {
        WorkspaceBuildPlan plan = service.planBuild(
                WorkspacePlanTarget.at(tempDir),
                tempDir.resolve("cache"),
                false,
                WorkspaceSelectionRequest.defaults());
        WorkspaceExecutionContext context = plan.executionContext();
        Map<String, WorkspaceMember> membersByPath = new LinkedHashMap<>();
        plan.workspace().members().forEach(member -> membersByPath.put(member.path(), member));
        WorkspaceJdkCheckerResolver checkers =
                WorkspaceJdkCheckerResolver.fixed(new JdkDetector());
        Map<String, WorkspaceBuildRequirements> requirementsByMember = new LinkedHashMap<>();
        Map<String, String> toolchains = new LinkedHashMap<>();
        for (String member : plan.selection().includedMembers()) {
            requirementsByMember.put(member, requirements);
            toolchains.put(
                    member,
                    context.toolchainIndex().compileIdentity(
                            checkers,
                            plan.workspace(),
                            membersByPath.get(member)));
        }
        WorkspaceDirtyPlan dirtyPlan = new WorkspaceDirtyPlanner().plan(
                context,
                plan.selection(),
                membersByPath,
                requirementsByMember,
                toolchains);
        Map<String, List<WorkspaceDirtyReason>> byMember = new LinkedHashMap<>();
        dirtyPlan.members().forEach((member, memberPlan) ->
                byMember.put(member, memberPlan.reasons()));
        return byMember;
    }
}

package sh.zolt.workspace.resolve;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.ConflictSelectionReason;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockConflict;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.DependencyConstraint;
import sh.zolt.project.DependencyConstraintKind;
import sh.zolt.project.DependencyPolicySettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import sh.zolt.resolve.ResolveException;
import sh.zolt.resolve.ResolutionVariant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class WorkspaceMediationPolicyTest {
    private static final PackageId LIB = new PackageId("com.example", "lib");
    private static final ResolutionVariant VARIANT =
            new ResolutionVariant(LIB, LockArtifactVariant.defaultVariant());
    private static final LockConflict CONFLICT = new LockConflict(
            LIB,
            "2.0.0",
            List.of("1.0.0", "2.0.0"),
            ConflictSelectionReason.DIRECT_DEPENDENCY);

    @Test
    void failOnConflictAppliesOnlyToAffectedMembers() {
        List<LockPackage> candidates = List.of(
                candidate("apps/api", "1.0.0"),
                candidate("apps/worker", "2.0.0"));
        Map<ResolutionVariant, String> selection = Map.of(VARIANT, "2.0.0");

        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> WorkspaceMediationPolicyEnforcer.enforce(
                        candidates,
                        List.of(CONFLICT),
                        selection,
                        Map.of(
                                "apps/api", config(true, Optional.empty()),
                                "apps/worker", config(false, Optional.empty())),
                        "zolt resolve --workspace"));
        assertTrue(exception.getMessage().contains("apps/api"));
        assertDoesNotThrow(() -> WorkspaceMediationPolicyEnforcer.enforce(
                candidates,
                List.of(CONFLICT),
                selection,
                Map.of(
                        "apps/api", config(false, Optional.empty()),
                        "apps/worker", config(false, Optional.empty()),
                        "apps/unaffected", config(true, Optional.empty())),
                "zolt resolve --workspace"));
    }

    @Test
    void crossMemberDirectDependencyCannotOverrideStrictConstraint() {
        List<LockPackage> candidates = List.of(
                candidate("apps/strict", "1.0.0"),
                candidate("apps/direct", "2.0.0"));

        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> WorkspaceMediationPolicyEnforcer.enforce(
                        candidates,
                        List.of(CONFLICT),
                        Map.of(VARIANT, "2.0.0"),
                        Map.of(
                                "apps/strict", config(false, Optional.of("1.0.0")),
                                "apps/direct", config(false, Optional.empty())),
                        "zolt resolve --workspace"));

        assertTrue(exception.getMessage().contains("apps/strict"));
        assertTrue(exception.getMessage().contains("strict constraint"));
        assertTrue(exception.getMessage().contains("1.0.0"));
        assertTrue(exception.getMessage().contains("2.0.0"));
    }

    @Test
    void localDirectPrecedenceIsNotMisreportedAsWorkspaceOverride() {
        assertDoesNotThrow(() -> WorkspaceMediationPolicyEnforcer.enforce(
                List.of(candidate("apps/api", "1.0.0")),
                List.of(),
                Map.of(VARIANT, "1.0.0"),
                Map.of("apps/api", config(false, Optional.of("2.0.0"))),
                "zolt resolve --workspace"));
    }

    @Test
    void recordsDistinctWorkspaceMediationEffects() {
        assertEquals(
                List.of("workspace-mediation"),
                WorkspaceMediationPolicyEffects.from(
                                List.of(
                                        candidate("apps/api", "1.0.0"),
                                        candidate("apps/worker", "2.0.0")),
                                Map.of(VARIANT, "2.0.0"))
                        .stream()
                        .map(effect -> effect.kind())
                        .toList());
    }

    private static ProjectConfig config(
            boolean failOnConflict,
            Optional<String> strictVersion) {
        Map<String, DependencyConstraint> constraints = strictVersion
                .map(version -> Map.of(
                        LIB.toString(),
                        new DependencyConstraint(
                                LIB.toString(),
                                version,
                                DependencyConstraintKind.STRICT,
                                Optional.empty())))
                .orElseGet(Map::of);
        return ProjectConfigs.withDirectDependencies(
                        new ProjectMetadata(
                                "demo",
                                "0.1.0",
                                "com.example",
                                "21",
                                Optional.empty()),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        BuildSettings.defaults())
                .withDependencyPolicy(new DependencyPolicySettings(
                        List.of(), constraints, failOnConflict));
    }

    private static LockPackage candidate(String member, String version) {
        String base = "com/example/lib/" + version + "/lib-" + version;
        return new LockPackage(
                LIB,
                version,
                "maven-central",
                DependencyScope.COMPILE,
                true,
                Optional.of(base + ".jar"),
                Optional.of(base + ".pom"),
                Optional.of("jar-" + version),
                Optional.of("pom-" + version),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of(member),
                List.of(),
                List.of(),
                List.of());
    }
}

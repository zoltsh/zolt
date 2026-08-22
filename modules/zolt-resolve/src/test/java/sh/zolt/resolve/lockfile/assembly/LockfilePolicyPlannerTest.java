package sh.zolt.resolve.lockfile.assembly;

import static org.junit.jupiter.api.Assertions.assertEquals;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockPolicyEffect;
import sh.zolt.project.DependencyConstraint;
import sh.zolt.project.DependencyConstraintKind;
import sh.zolt.project.DependencyMetadata;
import sh.zolt.resolve.DependencyPolicyEffect;
import sh.zolt.resolve.graph.PackageNode;
import sh.zolt.resolve.metadata.platform.ManagedVersion;
import sh.zolt.resolve.selection.SelectedDependencyScope;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class LockfilePolicyPlannerTest {
    private static final PackageId GUAVA = new PackageId("com.google.guava", "guava");
    private static final PackageNode GUAVA_NODE = new PackageNode(GUAVA, "33.4.8-jre");

    @Test
    void directVersionRefPolicyUsesMatchingScopeAndVersion() {
        List<String> policies = LockfilePolicyPlanner.policiesFor(
                GUAVA_NODE,
                new SelectedDependencyScope(DependencyScope.COMPILE, true),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(DependencyMetadata.key("dependencies", GUAVA.toString()), new DependencyMetadata(
                        "dependencies",
                        GUAVA.toString(),
                        "33.4.8-jre",
                        "guava",
                        false,
                        null,
                        false,
                        false,
                        List.of())),
                List.of());

        assertEquals(List.of("version-ref: com.google.guava:guava -> 33.4.8-jre from [versions].guava"), policies);
    }

    @Test
    void directManagedVersionPolicyUsesSelectedPlatformSource() {
        List<String> policies = LockfilePolicyPlanner.policiesFor(
                GUAVA_NODE,
                new SelectedDependencyScope(DependencyScope.TEST, true),
                Map.of(),
                Map.of(GUAVA, List.of(DependencyScope.TEST)),
                Map.of(GUAVA, new ManagedVersion("33.4.8-jre", "com.example:platform:1.0.0")),
                Map.of(),
                List.of());

        assertEquals(
                List.of("managed-version: com.google.guava:guava -> 33.4.8-jre from com.example:platform:1.0.0"),
                policies);
    }

    @Test
    void transitiveStrictVersionPolicyUsesGraphPolicyWhenPresent() {
        List<String> policies = LockfilePolicyPlanner.policiesFor(
                GUAVA_NODE,
                new SelectedDependencyScope(DependencyScope.COMPILE, false),
                Map.of(GUAVA.toString(), new DependencyConstraint(
                        GUAVA.toString(),
                        "33.4.8-jre",
                        DependencyConstraintKind.STRICT,
                        Optional.of("baseline"))),
                Map.of(),
                Map.of(),
                Map.of(),
                List.of(new DependencyPolicyEffect(
                        "strict-version",
                        GUAVA,
                        Optional.of("32.0.0-jre"),
                        Optional.of("transitive"),
                        "strict-version: com.google.guava:guava requested 32.0.0-jre -> 33.4.8-jre (baseline)")));

        assertEquals(List.of("strict-version: com.google.guava:guava requested 32.0.0-jre -> 33.4.8-jre (baseline)"), policies);
    }

    @Test
    void transitiveStrictVersionPolicyFallsBackToConstraintReason() {
        List<String> policies = LockfilePolicyPlanner.policiesFor(
                GUAVA_NODE,
                new SelectedDependencyScope(DependencyScope.COMPILE, false),
                Map.of(GUAVA.toString(), new DependencyConstraint(
                        GUAVA.toString(),
                        "33.4.8-jre",
                        DependencyConstraintKind.STRICT,
                        Optional.of("baseline"))),
                Map.of(),
                Map.of(),
                Map.of(),
                List.of());

        assertEquals(List.of("strict-version: com.google.guava:guava -> 33.4.8-jre (baseline)"), policies);
    }

    @Test
    void lockPolicyEffectsAreStableDistinctAndSorted() {
        List<LockPolicyEffect> effects = LockfilePolicyPlanner.lockPolicyEffects(List.of(
                new DependencyPolicyEffect("strict-version", GUAVA, Optional.of("33.4.8-jre"), Optional.empty(), "b"),
                new DependencyPolicyEffect("strict-version", GUAVA, Optional.of("33.4.8-jre"), Optional.empty(), "a"),
                new DependencyPolicyEffect("strict-version", GUAVA, Optional.of("33.4.8-jre"), Optional.empty(), "a")));

        assertEquals(List.of("a", "b"), effects.stream().map(LockPolicyEffect::policy).toList());
    }
}

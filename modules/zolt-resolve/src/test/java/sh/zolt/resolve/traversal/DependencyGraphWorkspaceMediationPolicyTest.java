package sh.zolt.resolve.traversal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.project.DependencyPolicySettings;
import sh.zolt.resolve.ResolutionVariant;
import sh.zolt.resolve.SnapshotAllowance;
import sh.zolt.resolve.graph.ResolutionGraph;
import sh.zolt.resolve.metadata.platform.ManagedVersion;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class DependencyGraphWorkspaceMediationPolicyTest extends DependencyGraphTraverserTestSupport {
    @Test
    void workspaceMediationReplacesManagedEffectWithoutMisstatingItsDecision() {
        PackageId library = new PackageId("com.example", "lib");
        ResolutionVariant variant =
                new ResolutionVariant(library, LockArtifactVariant.defaultVariant());
        MapBackedMetadataSource source = new MapBackedMetadataSource();
        source.put("com.example:root:1.0.0", pom(
                "com.example",
                "root",
                "1.0.0",
                List.of(dependency("com.example", "lib", "1.0.0"))));
        source.put(
                "com.example:lib:3.0.0",
                pom("com.example", "lib", "3.0.0", List.of()));

        ResolutionGraph graph = new DependencyGraphTraverser(
                        source,
                        DependencyPolicySettings.defaults(),
                        Map.of(library, new ManagedVersion(
                                "2.0.0", "com.example:platform:1.0.0")),
                        "zolt resolve --workspace",
                        SnapshotAllowance.none(),
                        Map.of(variant, "3.0.0"),
                        Map.of(variant, "3.0.0"))
                .traverse(List.of(direct("com.example", "root", "1.0.0")));

        assertEquals(
                List.of("workspace-mediation"),
                graph.policyEffects().stream()
                        .map(effect -> effect.kind())
                        .toList());
        assertEquals(
                List.of("workspace-mediation: com.example:lib requested 2.0.0 -> 3.0.0"),
                graph.policyEffects().stream()
                        .map(effect -> effect.policy())
                        .toList());
    }
}

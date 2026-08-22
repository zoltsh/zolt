package sh.zolt.manifest.effective;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import sh.zolt.dependency.DependencyLane;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.DependencySelector;
import sh.zolt.manifest.WorkspaceMemberPath;
import sh.zolt.manifest.authored.AuthoredDependency;
import sh.zolt.manifest.authored.AuthoredDependencyMetadata;

final class EffectiveDependencyLaneOrderingTest {
    @Test
    void graphFactsUseCanonicalImplementationBeforeApiOrder() {
        WorkspaceMemberPath owner = new WorkspaceMemberPath("apps/api");
        AuthoredDependency apiWorkspace = dependency(
                DependencyLane.API, "com.example:api", new DependencySelector.Workspace());
        AuthoredDependency implementationWorkspace = dependency(
                DependencyLane.IMPLEMENTATION, "com.example:impl", new DependencySelector.Workspace());
        List<EffectiveWorkspaceDependencyEdge> edges = List.of(
                        new EffectiveWorkspaceDependencyEdge(
                                owner, new WorkspaceMemberPath("modules/api"), apiWorkspace),
                        new EffectiveWorkspaceDependencyEdge(
                                owner, new WorkspaceMemberPath("modules/impl"), implementationWorkspace))
                .stream()
                .sorted()
                .toList();
        List<EffectiveManagedDependencyRequest> managed = List.of(
                        new EffectiveManagedDependencyRequest(owner, dependency(
                                DependencyLane.API, "com.example:api", new DependencySelector.Managed())),
                        new EffectiveManagedDependencyRequest(owner, dependency(
                                DependencyLane.IMPLEMENTATION, "com.example:impl", new DependencySelector.Managed())))
                .stream()
                .sorted()
                .toList();

        assertEquals(DependencyLane.IMPLEMENTATION, edges.getFirst().declaration().lane());
        assertEquals(DependencyLane.API, edges.getLast().declaration().lane());
        assertEquals(DependencyLane.IMPLEMENTATION, managed.getFirst().declaration().lane());
        assertEquals(DependencyLane.API, managed.getLast().declaration().lane());
    }

    private static AuthoredDependency dependency(
            DependencyLane lane,
            String coordinate,
            DependencySelector selector) {
        return new AuthoredDependency(
                lane,
                new DependencyCoordinate(coordinate),
                selector,
                AuthoredDependencyMetadata.none());
    }
}

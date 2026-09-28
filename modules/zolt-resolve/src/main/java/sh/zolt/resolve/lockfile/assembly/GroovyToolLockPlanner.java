package sh.zolt.resolve.lockfile.assembly;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.resolve.graph.PackageNode;
import sh.zolt.resolve.selection.SelectedDependencyScope;
import sh.zolt.resolve.selection.SelectedDependencyScopes;
import java.util.List;
import java.util.Map;

/** Builds lock rows for the isolated Groovy compiler closure without exec-tool group attribution. */
final class GroovyToolLockPlanner {
    private GroovyToolLockPlanner() {
    }

    static List<LockPackagePlan> plans(GroovyToolResolution resolution) {
        Map<PackageNode, List<SelectedDependencyScope>> selectedScopes = SelectedDependencyScopes.from(
                resolution.graph(), resolution.selection(), resolution.directRequests());
        return resolution.selection().selectedNodes().stream()
                .map(node -> LockPackagePlan.of(
                        node,
                        groovyScope(selectedScopes.get(node)),
                        resolution.graph(),
                        resolution.selection(),
                        List.of()))
                .toList();
    }

    private static SelectedDependencyScope groovyScope(List<SelectedDependencyScope> scopes) {
        if (scopes != null) {
            for (SelectedDependencyScope scope : scopes) {
                if (scope.scope() == DependencyScope.TOOL_GROOVY) {
                    return scope;
                }
            }
        }
        return new SelectedDependencyScope(DependencyScope.TOOL_GROOVY, false);
    }
}

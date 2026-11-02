package sh.zolt.resolve.lockfile.assembly;

import sh.zolt.resolve.graph.PackageNode;
import sh.zolt.resolve.selection.SelectedDependencyScope;
import sh.zolt.resolve.selection.SelectedDependencyScopes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Builds lock rows for isolated compiler closures without exec-tool group attribution. */
final class CompilerToolLockPlanner {
    private CompilerToolLockPlanner() {
    }

    static List<LockPackagePlan> plans(List<CompilerToolResolution> resolutions) {
        List<LockPackagePlan> plans = new ArrayList<>();
        for (CompilerToolResolution resolution : CompilerToolResolution.ordered(resolutions)) {
            plans.addAll(plans(resolution));
        }
        return List.copyOf(plans);
    }

    private static List<LockPackagePlan> plans(CompilerToolResolution resolution) {
        Map<PackageNode, List<SelectedDependencyScope>> selectedScopes = SelectedDependencyScopes.from(
                resolution.graph(), resolution.selection(), resolution.directRequests());
        return resolution.selection().selectedNodes().stream()
                .map(node -> LockPackagePlan.of(
                        node,
                        compilerScope(resolution, selectedScopes.get(node)),
                        resolution.graph(),
                        resolution.selection(),
                        List.of()))
                .toList();
    }

    private static SelectedDependencyScope compilerScope(
            CompilerToolResolution resolution,
            List<SelectedDependencyScope> scopes) {
        if (scopes != null) {
            for (SelectedDependencyScope scope : scopes) {
                if (scope.scope() == resolution.scope()) {
                    return scope;
                }
            }
        }
        return new SelectedDependencyScope(resolution.scope(), false);
    }
}

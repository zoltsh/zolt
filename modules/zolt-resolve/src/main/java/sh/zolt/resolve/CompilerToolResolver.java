package sh.zolt.resolve;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.resolve.lockfile.assembly.CompilerToolResolution;
import sh.zolt.resolve.materialization.session.RepositorySession;
import sh.zolt.resolve.metadata.platform.ManagedVersion;
import sh.zolt.resolve.request.DependencyRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Resolves registered compiler-tool scopes independently and in stable scope order. */
final class CompilerToolResolver {
    private static final List<CompilerTool> TOOLS = List.of(
            new CompilerTool(DependencyScope.TOOL_GROOVY, "Groovy"));

    private CompilerToolResolver() {
    }

    static boolean isCompilerToolScope(DependencyScope scope) {
        return TOOLS.stream().anyMatch(tool -> tool.scope() == scope);
    }

    static List<CompilerToolResolution> resolve(
            DependencyGraphResolver graphResolver,
            RepositorySession context,
            Map<PackageId, ManagedVersion> managedVersionDetails,
            List<DependencyRequest> directRequests,
            ResolveOptions options,
            SnapshotAllowance snapshotAllowance) {
        List<CompilerToolResolution> resolutions = new ArrayList<>();
        for (CompilerTool tool : TOOLS.stream()
                .sorted(Comparator.comparing(entry -> entry.scope().lockfileName()))
                .toList()) {
            List<DependencyRequest> requests = directRequests.stream()
                    .filter(request -> request.scope() == tool.scope())
                    .toList();
            if (requests.isEmpty()) {
                continue;
            }
            DependencyGraphResolution resolution = graphResolver.resolve(
                    context,
                    context.config().dependencyPolicy(),
                    managedVersionDetails,
                    requests,
                    context,
                    options.retryCommand(),
                    snapshotAllowance);
            resolutions.add(new CompilerToolResolution(
                    tool.scope(),
                    tool.compilerName(),
                    resolution.graph(),
                    resolution.selection(),
                    requests));
        }
        return CompilerToolResolution.ordered(resolutions);
    }

    private record CompilerTool(DependencyScope scope, String compilerName) {
    }
}

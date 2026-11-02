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
import java.util.Optional;

/** Resolves registered compiler-tool scopes independently and in stable scope order. */
final class CompilerToolResolver {
    private static final List<CompilerTool> TOOLS = List.of(
            new CompilerTool(DependencyScope.TOOL_GROOVY, "Groovy", "toolchain.groovy"),
            new CompilerTool(DependencyScope.TOOL_KOTLIN, "Kotlin", "toolchain.kotlin"));

    private CompilerToolResolver() {
    }

    static boolean isCompilerToolScope(DependencyScope scope) {
        return TOOLS.stream().anyMatch(tool -> tool.scope() == scope);
    }

    static void requireUnrelocatedRoot(
            DependencyRequest original,
            DependencyRequest relocated,
            String retryCommand) {
        Optional<CompilerTool> compilerTool = TOOLS.stream()
                .filter(tool -> tool.scope() == original.scope())
                .findFirst();
        if (compilerTool.isEmpty() || sameCoordinate(original, relocated)) {
            return;
        }
        CompilerTool tool = compilerTool.orElseThrow();
        throw ResolveException.actionable(
                "The POM for configured " + tool.compilerName() + " compiler root `"
                        + coordinate(original) + "` in [" + tool.manifestSection()
                        + "] relocates to `" + coordinate(relocated)
                        + "`. Zolt requires compiler roots to preserve their exact configured Maven coordinate.",
                "Select a non-relocated " + tool.compilerName() + " compiler version in ["
                        + tool.manifestSection() + "], then run `" + retryCommand + "` again.");
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

    private static boolean sameCoordinate(DependencyRequest left, DependencyRequest right) {
        return left.packageId().equals(right.packageId())
                && left.requestedVersion().equals(right.requestedVersion())
                && left.artifactVariant().equals(right.artifactVariant());
    }

    private static String coordinate(DependencyRequest request) {
        String gav = request.packageId() + ":" + request.requestedVersion();
        return request.artifactVariant().isDefault()
                ? gav
                : gav + ":" + request.artifactVariant().key();
    }

    private record CompilerTool(
            DependencyScope scope,
            String compilerName,
            String manifestSection) {
    }
}

package sh.zolt.resolve.request;

import sh.zolt.dependency.PackageId;
import sh.zolt.maven.CoordinateParser;
import sh.zolt.project.DependencyPolicyExclusion;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.ResolveException;
import sh.zolt.resolve.SnapshotAllowance;
import java.util.List;
import java.util.Map;

public final class DependencyRequestPlanner {
    private final DirectDependencyRequestPlanner directDependencyRequestPlanner;
    private final ToolingDependencyContributor toolingDependencyContributor;

    public DependencyRequestPlanner(CoordinateParser coordinateParser) {
        this(
                new DirectDependencyRequestPlanner(coordinateParser),
                new ToolingDependencyContributor(coordinateParser));
    }

    DependencyRequestPlanner(
            CoordinateParser coordinateParser,
            ToolingDependencyContributor toolingDependencyContributor) {
        this(new DirectDependencyRequestPlanner(coordinateParser), toolingDependencyContributor);
    }

    DependencyRequestPlanner(
            DirectDependencyRequestPlanner directDependencyRequestPlanner,
            ToolingDependencyContributor toolingDependencyContributor) {
        this.directDependencyRequestPlanner = directDependencyRequestPlanner == null
                ? new DirectDependencyRequestPlanner(new CoordinateParser())
                : directDependencyRequestPlanner;
        this.toolingDependencyContributor = toolingDependencyContributor == null
                ? new ToolingDependencyContributor(new CoordinateParser())
                : toolingDependencyContributor;
    }

    public List<DependencyRequest> plan(
            ProjectConfig config,
            Map<PackageId, String> projectManagedVersions,
            boolean includeCoverageTooling) {
        return plan(config, projectManagedVersions, includeCoverageTooling, "zolt resolve", SnapshotAllowance.none());
    }

    public List<DependencyRequest> plan(
            ProjectConfig config,
            Map<PackageId, String> projectManagedVersions,
            boolean includeCoverageTooling,
            String retryCommand) {
        return plan(config, projectManagedVersions, includeCoverageTooling, retryCommand, SnapshotAllowance.none());
    }

    public List<DependencyRequest> plan(
            ProjectConfig config,
            Map<PackageId, String> projectManagedVersions,
            boolean includeCoverageTooling,
            String retryCommand,
            SnapshotAllowance snapshotAllowance) {
        List<DependencyRequest> requests = directDependencyRequestPlanner.plan(
                config,
                projectManagedVersions,
                retryCommand,
                snapshotAllowance);
        toolingDependencyContributor.contribute(config, projectManagedVersions, requests, includeCoverageTooling);
        validateDirectRequestsAllowed(config, requests, retryCommand);
        return List.copyOf(requests);
    }

    private static void validateDirectRequestsAllowed(
            ProjectConfig config,
            List<DependencyRequest> directRequests,
            String retryCommand) {
        List<DependencyPolicyExclusion> exclusions = config.dependencyPolicy().exclusions();
        if (exclusions.isEmpty()) {
            return;
        }
        for (DependencyRequest request : directRequests) {
            for (DependencyPolicyExclusion exclusion : exclusions) {
                if (exclusion.group().equals(request.packageId().groupId())
                        && exclusion.artifact().equals(request.packageId().artifactId())) {
                    String reason = exclusion.reason()
                            .map(value -> " Reason: " + value + ".")
                            .orElse("");
                    throw new ResolveException(
                            "Dependency policy excludes direct dependency `"
                                    + request.packageId()
                                    + "`."
                                    + reason
                                    + " Remove the direct dependency or remove the matching [dependencies.policy].deny entry, then run `"
                                    + retryCommand
                                    + "` again.");
                }
            }
        }
    }
}

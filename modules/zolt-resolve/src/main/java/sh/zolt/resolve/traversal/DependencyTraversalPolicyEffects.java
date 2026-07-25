package sh.zolt.resolve.traversal;

import sh.zolt.dependency.PackageId;
import sh.zolt.project.DependencyConstraint;
import sh.zolt.resolve.DependencyPolicyEffect;
import sh.zolt.resolve.graph.PackageNode;
import sh.zolt.resolve.metadata.platform.ManagedVersion;
import java.util.Optional;

final class DependencyTraversalPolicyEffects {
    private DependencyTraversalPolicyEffects() {
    }

    static DependencyPolicyEffect strictVersion(
            PackageId packageId,
            Optional<String> requestedVersion,
            PackageNode source,
            DependencyConstraint constraint) {
        String policy = "strict-version: "
                + packageId
                + " requested "
                + requestedVersion.orElse("<missing>")
                + " -> "
                + constraint.version();
        return new DependencyPolicyEffect(
                "strict-version",
                packageId,
                requestedVersion,
                Optional.of(sourceCoordinate(source)),
                constraint.reason()
                        .map(reason -> policy + " (" + reason + ")")
                        .orElse(policy));
    }

    static DependencyPolicyEffect managedVersion(
            PackageId packageId,
            Optional<String> requestedVersion,
            PackageNode source,
            ManagedVersion managedVersion) {
        String policy = "managed-version: "
                + packageId
                + " -> "
                + managedVersion.version()
                + " from "
                + managedVersion.platform();
        return new DependencyPolicyEffect(
                "managed-version",
                packageId,
                requestedVersion,
                Optional.of(sourceCoordinate(source)),
                policy);
    }

    static DependencyPolicyEffect workspaceMediation(
            PackageId packageId,
            String requestedVersion,
            String selectedVersion,
            PackageNode source) {
        return new DependencyPolicyEffect(
                "workspace-mediation",
                packageId,
                Optional.of(requestedVersion),
                Optional.of(sourceCoordinate(source)),
                "workspace-mediation: "
                        + packageId
                        + " requested "
                        + requestedVersion
                        + " -> "
                        + selectedVersion);
    }

    private static String sourceCoordinate(PackageNode node) {
        return node.packageId() + ":" + node.selectedVersion();
    }
}

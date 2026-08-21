package sh.zolt.workspace.publish;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import sh.zolt.dependency.DependencyLane;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockDependencyRoot;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.ProjectConfig;
import sh.zolt.publish.PublishDependencyRootCoverage;
import sh.zolt.publish.PublishException;

/** Projects one member's authored publication roots from an aggregated workspace lock. */
public final class WorkspaceMemberPomLockProjection {
    /**
     * Selects only the requested member's publishable authored roots. Versions, variants, lanes,
     * optionality, and publish-only declarations come exclusively from lockfile-v7 root facts.
     * {@code memberConfig} remains part of the public call shape because the POM generator separately
     * uses it for publication metadata that is not represented in the lock, such as exclusions.
     */
    public ZoltLockfile project(
            String memberPath,
            ProjectConfig memberConfig,
            ZoltLockfile aggregatedLock) {
        if (aggregatedLock.version() != ZoltLockfile.CURRENT_VERSION) {
            throw new PublishException(
                    "Workspace publication requires zolt.lock version " + ZoltLockfile.CURRENT_VERSION
                            + ", but found version " + aggregatedLock.version()
                            + ". Run `zolt resolve --workspace` to regenerate the lockfile.");
        }
        List<LockDependencyRoot> roots = aggregatedLock.dependencyRoots().stream()
                .filter(root -> root.member().equals(memberPath))
                .filter(root -> published(root.lane()))
                .map(WorkspaceMemberPomLockProjection::asStandaloneRoot)
                .toList();

        Map<String, LockPackage> packages = new LinkedHashMap<>();
        for (LockDependencyRoot root : roots) {
            if (root.publishOnly()) {
                continue;
            }
            LockPackage selected = aggregatedLock.packages().stream()
                    .filter(candidate -> selects(root, candidate))
                    .findFirst()
                    .orElseThrow(() -> missingPackage(memberPath, root));
            LockPackage standalone = asStandalonePackage(selected);
            packages.putIfAbsent(packageKey(standalone), standalone);
        }
        ZoltLockfile projected = new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.copyOf(packages.values()),
                List.of(),
                List.of(),
                List.of(),
                roots);
        PublishDependencyRootCoverage.requireComplete(memberConfig, projected);
        return projected;
    }

    private static boolean published(DependencyLane lane) {
        return switch (lane) {
            case API, IMPLEMENTATION, RUNTIME, PROVIDED -> true;
            case DEV, TEST, PROCESSOR, TEST_PROCESSOR -> false;
        };
    }

    private static LockDependencyRoot asStandaloneRoot(LockDependencyRoot root) {
        return new LockDependencyRoot(
                ".",
                root.packageId(),
                root.version(),
                root.variant(),
                root.lane(),
                root.resolvedScope(),
                root.optional(),
                root.publishOnly());
    }

    private static LockPackage asStandalonePackage(LockPackage lockPackage) {
        return new LockPackage(
                lockPackage.packageId(),
                lockPackage.version(),
                lockPackage.source(),
                lockPackage.scope(),
                lockPackage.direct(),
                lockPackage.jar(),
                lockPackage.pom(),
                lockPackage.jarSha256(),
                lockPackage.pomSha256(),
                lockPackage.artifact(),
                lockPackage.artifactType(),
                lockPackage.artifactSha256(),
                lockPackage.workspace(),
                lockPackage.workspaceOutput(),
                lockPackage.dependencies(),
                List.of(),
                lockPackage.exportedBy(),
                lockPackage.policies(),
                lockPackage.toolGroups());
    }

    private static boolean selects(LockDependencyRoot root, LockPackage candidate) {
        return root.packageId().equals(candidate.packageId())
                && root.version().equals(candidate.version())
                && root.variant().equals(LockArtifactVariant.of(candidate))
                && root.resolvedScope().orElseThrow() == candidate.scope();
    }

    private static String packageKey(LockPackage lockPackage) {
        return lockPackage.packageId() + ":" + lockPackage.version() + ":"
                + LockArtifactVariant.of(lockPackage).key() + ":" + lockPackage.scope().lockfileName();
    }

    private static PublishException missingPackage(String memberPath, LockDependencyRoot root) {
        return new PublishException(
                "Workspace zolt.lock dependency root for member `" + memberPath + "` selects missing package `"
                        + root.packageId() + ":" + root.version() + ":" + root.variant().key() + ":"
                        + root.resolvedScope().orElseThrow().lockfileName()
                        + "`. Run `zolt resolve --workspace` to regenerate the lock before publishing.");
    }
}

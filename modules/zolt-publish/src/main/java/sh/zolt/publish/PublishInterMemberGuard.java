package sh.zolt.publish;

import sh.zolt.dependency.DependencyLane;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockDependencyRoot;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Inter-member completeness: a member's POM must never reference a sibling coordinate consumers
 * cannot resolve. This pure helper reports, for a member's projected publish lock, the
 * workspace-provided (inter-member) sibling coordinates that are absent from the publish set.
 *
 * <p>The workspace publish orchestrator turns a non-empty result into a Phase-1 blocker (multi-member
 * publish) or a warning (single-member publish of a workspace member renders correctly but the
 * sibling must be published at the same version).
 */
public final class PublishInterMemberGuard {
    private PublishInterMemberGuard() {
    }

    /**
     * @param memberProjectedLock the member's single-project-shaped projected lock
     * @param publishSetCoordinates {@code group:artifact} of every member in the publish set
     * @return the {@code group:artifact} of each inter-member sibling dependency absent from the set,
     *     in declaration order, de-duplicated
     */
    public static List<String> missingSiblings(ZoltLockfile memberProjectedLock, Set<String> publishSetCoordinates) {
        if (memberProjectedLock.version() != ZoltLockfile.CURRENT_VERSION) {
            throw new PublishException(
                    "Inter-member publication checks require zolt.lock version "
                            + ZoltLockfile.CURRENT_VERSION + ", but found version "
                            + memberProjectedLock.version() + ". Run `zolt resolve --workspace` to regenerate the lockfile.");
        }
        List<String> missing = new ArrayList<>();
        for (LockDependencyRoot root : memberProjectedLock.dependencyRoots()) {
            if (root.publishOnly() || !publishedLane(root.lane())) {
                continue;
            }
            LockPackage selected = memberProjectedLock.packages().stream()
                    .filter(candidate -> selects(root, candidate))
                    .findFirst()
                    .orElseThrow();
            if (selected.workspace().isEmpty()) {
                continue;
            }
            String coordinate = root.packageId().groupId() + ":" + root.packageId().artifactId();
            if (!publishSetCoordinates.contains(coordinate) && !missing.contains(coordinate)) {
                missing.add(coordinate);
            }
        }
        return List.copyOf(missing);
    }

    private static boolean selects(LockDependencyRoot root, LockPackage candidate) {
        return root.packageId().equals(candidate.packageId())
                && root.version().equals(candidate.version())
                && root.variant().equals(LockArtifactVariant.of(candidate))
                && root.resolvedScope().orElseThrow() == candidate.scope();
    }

    private static boolean publishedLane(DependencyLane lane) {
        return switch (lane) {
            case API, IMPLEMENTATION, RUNTIME, PROVIDED -> true;
            case DEV, TEST, PROCESSOR, TEST_PROCESSOR -> false;
        };
    }
}

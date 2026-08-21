package sh.zolt.lockfile;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import sh.zolt.dependency.DependencyLane;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.manifest.WorkspaceMemberPath;

/** One member-qualified direct declaration with authored lane and resolved scope kept separate. */
public record LockDependencyRoot(
        String member,
        PackageId packageId,
        String version,
        LockArtifactVariant variant,
        DependencyLane lane,
        Optional<DependencyScope> resolvedScope,
        boolean optional,
        boolean publishOnly) {
    private static final Set<DependencyLane> OPTIONAL_LANES = EnumSet.of(
            DependencyLane.API,
            DependencyLane.IMPLEMENTATION,
            DependencyLane.RUNTIME);
    private static final Set<DependencyLane> PUBLISH_ONLY_LANES = EnumSet.of(
            DependencyLane.API,
            DependencyLane.IMPLEMENTATION,
            DependencyLane.RUNTIME,
            DependencyLane.PROVIDED);

    public LockDependencyRoot {
        member = new WorkspaceMemberPath(member).value();
        packageId = Objects.requireNonNull(packageId, "Lock dependency root package must not be null.");
        Objects.requireNonNull(version, "Lock dependency root version must not be null.");
        if (version.isBlank()) {
            throw new IllegalArgumentException("Lock dependency root version must not be blank.");
        }
        variant = variant == null ? LockArtifactVariant.defaultVariant() : variant;
        lane = Objects.requireNonNull(lane, "Lock dependency root lane must not be null.");
        resolvedScope = resolvedScope == null ? Optional.empty() : resolvedScope;

        if (optional && !OPTIONAL_LANES.contains(lane)) {
            throw new IllegalArgumentException(
                    "Optional lock dependency roots are not meaningful in the " + lane + " lane.");
        }
        if (publishOnly && !PUBLISH_ONLY_LANES.contains(lane)) {
            throw new IllegalArgumentException(
                    "Publish-only lock dependency roots are not allowed in the " + lane + " lane.");
        }
        if (publishOnly && resolvedScope.isPresent()) {
            throw new IllegalArgumentException(
                    "A publish-only lock dependency root must not have a resolved scope.");
        }
        if (!publishOnly && resolvedScope.isEmpty()) {
            throw new IllegalArgumentException(
                    "A resolved lock dependency root requires a resolved scope.");
        }
    }
}

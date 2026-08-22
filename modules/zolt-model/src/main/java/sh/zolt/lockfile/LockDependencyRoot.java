package sh.zolt.lockfile;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import sh.zolt.dependency.DependencyLane;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.WorkspaceMemberPath;
import sh.zolt.maven.CoordinateParseException;
import sh.zolt.maven.MavenRepositoryValue;

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
        new DependencyCoordinate(packageId.toString());
        Objects.requireNonNull(version, "Lock dependency root version must not be null.");
        if (version.isBlank()) {
            throw new IllegalArgumentException("Lock dependency root version must not be blank.");
        }
        variant = variant == null ? LockArtifactVariant.defaultVariant() : variant;
        validateVariant(variant);
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

    /** Whether this resolved root selects this exact package occurrence, including member attribution. */
    public boolean selects(LockPackage lockPackage) {
        if (publishOnly || lockPackage == null) {
            return false;
        }
        return packageId.equals(lockPackage.packageId())
                && version.equals(lockPackage.version())
                && variant.equals(LockArtifactVariant.of(lockPackage))
                && resolvedScope.orElseThrow() == lockPackage.scope()
                && (lockPackage.members().isEmpty()
                        ? member.equals(".")
                        : lockPackage.members().contains(member));
    }

    private static void validateVariant(LockArtifactVariant variant) {
        if (variant.extension().contains("|")
                || variant.classifier().filter(value -> value.contains("|")).isPresent()) {
            throw new IllegalArgumentException(
                    "Lock dependency root variant `" + variant.key() + "` is not a canonical artifact variant key.");
        }
        try {
            MavenRepositoryValue.extension(variant.extension());
            variant.classifier().ifPresent(MavenRepositoryValue::classifier);
        } catch (CoordinateParseException exception) {
            throw new IllegalArgumentException(
                    "Lock dependency root variant `" + variant.key() + "` is not repository-safe.",
                    exception);
        }
    }
}

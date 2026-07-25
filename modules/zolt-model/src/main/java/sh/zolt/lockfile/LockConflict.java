package sh.zolt.lockfile;

import sh.zolt.dependency.PackageId;
import sh.zolt.dependency.ConflictSelectionReason;
import java.util.List;
import java.util.Optional;

/**
 * A recorded version mediation. {@code toolGroup} names the isolated exec-tool closure whose own
 * resolution mediated this conflict (see Hole 1); it is empty for a mediation in the main project graph.
 * The attribution keeps the audit trail unambiguous about WHICH closure conflicted when the same GA
 * mediates in more than one place.
 *
 * <p>{@code variant} qualifies the mediation to a single artifact variant when the workspace layer
 * mediates within a variant lane rather than across a whole {@code groupId:artifactId}: two variants of
 * one GA (a plain jar and a classified jar) are distinct artifacts that mediate independently, so a
 * conflict among one variant's versions carries that variant. It is additive and follows the
 * {@code toolGroup} precedent — empty for the default variant, so a lock whose conflicts are all plain
 * jars stays byte-identical.
 */
public record LockConflict(
        PackageId packageId,
        String selectedVersion,
        List<String> requestedVersions,
        ConflictSelectionReason reason,
        Optional<String> toolGroup,
        Optional<LockArtifactVariant> variant,
        List<String> members) {
    public LockConflict {
        requestedVersions = List.copyOf(requestedVersions);
        toolGroup = toolGroup == null ? Optional.empty() : toolGroup;
        // The default variant carries no discriminator, so a caller passing an explicit default jar and a
        // caller passing nothing collapse to the same empty — keeping conflict dedup, sort, and codec
        // output byte-identical for variant-free locks.
        variant = variant == null ? Optional.empty() : variant.filter(value -> !value.isDefault());
        members = members == null ? List.of() : List.copyOf(members);
    }

    public LockConflict(
            PackageId packageId,
            String selectedVersion,
            List<String> requestedVersions,
            ConflictSelectionReason reason,
            Optional<String> toolGroup,
            Optional<LockArtifactVariant> variant) {
        this(
                packageId,
                selectedVersion,
                requestedVersions,
                reason,
                toolGroup,
                variant,
                List.of());
    }

    public LockConflict(
            PackageId packageId,
            String selectedVersion,
            List<String> requestedVersions,
            ConflictSelectionReason reason,
            Optional<String> toolGroup) {
        this(
                packageId,
                selectedVersion,
                requestedVersions,
                reason,
                toolGroup,
                Optional.empty(),
                List.of());
    }

    public LockConflict(
            PackageId packageId,
            String selectedVersion,
            List<String> requestedVersions,
            ConflictSelectionReason reason) {
        this(
                packageId,
                selectedVersion,
                requestedVersions,
                reason,
                Optional.empty(),
                Optional.empty(),
                List.of());
    }
}

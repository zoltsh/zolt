package sh.zolt.resolve.version;

import sh.zolt.dependency.ConflictSelectionReason;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.resolve.request.DependencyRequest;
import java.util.List;

public record VersionConflict(
        PackageId packageId,
        LockArtifactVariant variant,
        List<DependencyRequest> requests,
        String selectedVersion,
        ConflictSelectionReason selectionReason,
        boolean active) {
    public VersionConflict {
        variant = variant == null ? LockArtifactVariant.defaultVariant() : variant;
        requests = List.copyOf(requests);
    }

    public VersionConflict(
            PackageId packageId,
            LockArtifactVariant variant,
            List<DependencyRequest> requests,
            String selectedVersion,
            ConflictSelectionReason selectionReason) {
        this(packageId, variant, requests, selectedVersion, selectionReason, true);
    }

    public VersionConflict(
            PackageId packageId,
            List<DependencyRequest> requests,
            String selectedVersion,
            ConflictSelectionReason selectionReason) {
        this(packageId, LockArtifactVariant.defaultVariant(), requests, selectedVersion, selectionReason, true);
    }
}

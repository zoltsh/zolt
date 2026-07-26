package sh.zolt.classpath;

import sh.zolt.dependency.PackageId;
import java.nio.file.Path;

public record ResolvedPackage(
        PackageId packageId,
        String selectedVersion,
        boolean direct,
        Path pomPath,
        Path jarPath,
        NestedArtifactIdentity artifactIdentity) {
    public ResolvedPackage {
        artifactIdentity = artifactIdentity == null
                ? NestedArtifactIdentity.external(packageId, selectedVersion)
                : artifactIdentity;
    }

    public ResolvedPackage(
            PackageId packageId,
            String selectedVersion,
            boolean direct,
            Path pomPath,
            Path jarPath) {
        this(
                packageId,
                selectedVersion,
                direct,
                pomPath,
                jarPath,
                NestedArtifactIdentity.external(packageId, selectedVersion));
    }
}

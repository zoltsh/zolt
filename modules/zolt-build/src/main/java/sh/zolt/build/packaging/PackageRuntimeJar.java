package sh.zolt.build.packaging;

import sh.zolt.classpath.NestedArtifactIdentity;
import sh.zolt.dependency.PackageId;
import java.nio.file.Path;

public record PackageRuntimeJar(
        PackageId packageId,
        String version,
        Path jarPath,
        NestedArtifactIdentity artifactIdentity) {
    public PackageRuntimeJar {
        artifactIdentity = artifactIdentity == null
                ? NestedArtifactIdentity.external(packageId, version)
                : artifactIdentity;
    }

    public PackageRuntimeJar(PackageId packageId, String version, Path jarPath) {
        this(
                packageId,
                version,
                jarPath,
                NestedArtifactIdentity.external(packageId, version));
    }
}

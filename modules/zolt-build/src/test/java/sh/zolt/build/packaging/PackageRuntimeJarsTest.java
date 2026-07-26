package sh.zolt.build.packaging;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import sh.zolt.build.PackageException;
import sh.zolt.classpath.NestedArtifactIdentity;
import sh.zolt.dependency.PackageId;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class PackageRuntimeJarsTest {
    @Test
    void sourceKindSeparatesExternalAndWorkspaceArtifacts() {
        PackageId packageId = new PackageId("com.example", "provider");
        PackageRuntimeJar external = new PackageRuntimeJar(
                packageId,
                "1.0.0",
                Path.of("provider-1.0.0.jar"),
                NestedArtifactIdentity.external(packageId, "1.0.0"));
        PackageRuntimeJar workspace = new PackageRuntimeJar(
                packageId,
                "1.0.0",
                Path.of("target/classes"),
                NestedArtifactIdentity.workspace(packageId, "1.0.0"));

        assertNotEquals(
                PackageRuntimeJars.nestedJarName(external),
                PackageRuntimeJars.nestedJarName(workspace));
    }

    @Test
    void duplicateNestedPathsFailBeforeArchiveAssembly() {
        PackageId packageId = new PackageId("com.example", "provider");
        PackageRuntimeJar first =
                new PackageRuntimeJar(packageId, "1.0.0", Path.of("first.jar"));
        PackageRuntimeJar duplicate =
                new PackageRuntimeJar(packageId, "1.0.0", Path.of("second.jar"));

        assertThrows(
                PackageException.class,
                () -> PackageRuntimeJars.requireUniqueNestedPaths(
                        "WEB-INF/lib/",
                        List.of(first, duplicate)));
    }
}

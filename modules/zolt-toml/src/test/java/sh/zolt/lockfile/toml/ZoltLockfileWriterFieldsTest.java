package sh.zolt.lockfile.toml;

import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.DependencyLane;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockDependencyRoot;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ZoltLockfileWriterFieldsTest {
    private final ZoltLockfileWriter writer = new ZoltLockfileWriter();

    @Test
    void writesInternalToolingScopeNames() {
        ZoltLockfile lockfile = lockfile(List.of(
                        lockPackage("com.example", "processor", "1.0.0", DependencyScope.PROCESSOR, true, Optional.empty(), Optional.empty(), List.of()),
                        lockPackage("com.example", "test-processor", "1.0.0", DependencyScope.TEST_PROCESSOR, true, Optional.empty(), Optional.empty(), List.of()),
                        lockPackage("io.quarkus", "quarkus-rest-deployment", "3.33.0", DependencyScope.QUARKUS_DEPLOYMENT, false, Optional.empty(), Optional.empty(), List.of()),
                        lockPackage("org.jacoco", "org.jacoco.cli", "0.8.14", DependencyScope.TOOL_COVERAGE, false, Optional.empty(), Optional.empty(), List.of())));

        String output = writer.write(lockfile);

        assertTrue(output.contains("scope = \"processor\""));
        assertTrue(output.contains("scope = \"test-processor\""));
        assertTrue(output.contains("scope = \"quarkus-deployment\""));
        assertTrue(output.contains("scope = \"tool-coverage\""));
    }

    @Test
    void writesWorkspacePackageFields() {
        ZoltLockfile lockfile = lockfile(List.of(new LockPackage(
                        new PackageId("com.acme", "core"),
                        "0.1.0",
                        "workspace",
                        DependencyScope.COMPILE,
                        true,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of("modules/core"),
                        Optional.of("target/classes"),
                        List.of())));

        String output = writer.write(lockfile);

        assertTrue(output.contains("source = \"workspace\""));
        assertTrue(output.contains("workspace = \"modules/core\""));
        assertTrue(output.contains("workspaceOutput = \"target/classes\""));
    }

    @Test
    void writesNonJarArtifactFields() {
        ZoltLockfile lockfile = lockfile(List.of(new LockPackage(
                        new PackageId("io.quarkus.platform", "quarkus-bom-quarkus-platform-properties"),
                        "3.33.0",
                        "maven-central",
                        DependencyScope.QUARKUS_DEPLOYMENT,
                        false,
                        Optional.empty(),
                        Optional.of("io/quarkus/platform/quarkus-bom-quarkus-platform-properties/3.33.0/quarkus-bom-quarkus-platform-properties-3.33.0.pom"),
                        Optional.empty(),
                        Optional.of("pom-checksum"),
                        Optional.of("io/quarkus/platform/quarkus-bom-quarkus-platform-properties/3.33.0/quarkus-bom-quarkus-platform-properties-3.33.0.properties"),
                        Optional.of("properties"),
                        Optional.of("properties-checksum"),
                        Optional.empty(),
                        Optional.empty(),
                        List.of(),
                        List.of(),
                        List.of())));

        String output = writer.write(lockfile);

        assertTrue(output.contains("artifact = \"io/quarkus/platform/quarkus-bom-quarkus-platform-properties/3.33.0/quarkus-bom-quarkus-platform-properties-3.33.0.properties\""));
        assertTrue(output.contains("artifactType = \"properties\""));
        assertTrue(output.contains("artifactSha256 = \"properties-checksum\""));
    }

    @Test
    void writesPackageMembersDeterministically() {
        ZoltLockfile lockfile = lockfile(List.of(new LockPackage(
                        new PackageId("com.example", "demo"),
                        "1.0.0",
                        "maven-central",
                        DependencyScope.COMPILE,
                        true,
                        Optional.of("com/example/demo/1.0.0/demo-1.0.0.jar"),
                        Optional.of("com/example/demo/1.0.0/demo-1.0.0.pom"),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        List.of(),
                        List.of("modules/core", "apps/api"))));

        String output = writer.write(lockfile);

        assertTrue(output.contains("members = [\"apps/api\", \"modules/core\"]"));
    }

    @Test
    void writesExportedByMembersDeterministically() {
        ZoltLockfile lockfile = lockfile(List.of(new LockPackage(
                        new PackageId("com.example", "contract"),
                        "1.0.0",
                        "maven-central",
                        DependencyScope.COMPILE,
                        true,
                        Optional.of("com/example/contract/1.0.0/contract-1.0.0.jar"),
                        Optional.of("com/example/contract/1.0.0/contract-1.0.0.pom"),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        List.of(),
                        List.of(),
                        List.of("modules/web", "modules/api"))));

        String output = writer.write(lockfile);

        assertTrue(output.contains("exportedBy = [\"modules/api\", \"modules/web\"]"));
    }

    private static LockPackage lockPackage(
            String groupId,
            String artifactId,
            String version,
            DependencyScope scope,
            boolean direct,
            Optional<String> jarSha256,
            Optional<String> pomSha256,
            List<String> dependencies) {
        String base = groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/" + artifactId + "-" + version;
        return new LockPackage(
                new PackageId(groupId, artifactId),
                version,
                "maven-central",
                scope,
                direct,
                Optional.of(base + ".jar"),
                Optional.of(base + ".pom"),
                jarSha256,
                pomSha256,
                dependencies);
    }

    private static ZoltLockfile lockfile(List<LockPackage> packages) {
        List<LockDependencyRoot> roots = packages.stream()
                .filter(LockPackage::direct)
                .map(lockPackage -> new LockDependencyRoot(
                        ".",
                        lockPackage.packageId(),
                        lockPackage.version(),
                        LockArtifactVariant.of(lockPackage),
                        lane(lockPackage.scope()),
                        Optional.of(lockPackage.scope()),
                        false,
                        false))
                .toList();
        return new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                Optional.empty(),
                Optional.empty(),
                List.of(),
                packages,
                List.of(),
                List.of(),
                List.of(),
                roots);
    }

    private static DependencyLane lane(DependencyScope scope) {
        return switch (scope) {
            case COMPILE -> DependencyLane.IMPLEMENTATION;
            case PROCESSOR -> DependencyLane.PROCESSOR;
            case TEST_PROCESSOR -> DependencyLane.TEST_PROCESSOR;
            default -> throw new IllegalArgumentException("No authored lane for " + scope + ".");
        };
    }
}

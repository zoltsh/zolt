package sh.zolt.lockfile.toml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.ConflictSelectionReason;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockConflict;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.LockPolicyEffect;
import sh.zolt.lockfile.ZoltLockfile;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ZoltLockfileReaderFieldsTest {
    private final ZoltLockfileReader reader = new ZoltLockfileReader();

    @Test
    void readsPackageFields() throws IOException {
        ZoltLockfile lockfile = reader.read(ZoltLockfileReaderTestSupport.golden());
        LockPackage guava = lockfile.packages().stream()
                .filter(lockPackage -> lockPackage.packageId().equals(new PackageId("com.google.guava", "guava")))
                .findFirst()
                .orElseThrow();

        assertEquals("33.4.0-jre", guava.version());
        assertEquals("maven-central", guava.source());
        assertEquals(DependencyScope.COMPILE, guava.scope());
        assertTrue(guava.direct());
        assertEquals("jar-checksum", guava.jarSha256().orElseThrow());
        assertEquals(List.of(
                "com.google.guava:failureaccess:1.0.2",
                "org.slf4j:slf4j-api:2.0.16"), guava.dependencies());
    }

    @Test
    void readsConflictFields() throws IOException {
        LockConflict conflict = reader.read(ZoltLockfileReaderTestSupport.golden()).conflicts().getFirst();

        assertEquals(new PackageId("org.slf4j", "slf4j-api"), conflict.packageId());
        assertEquals("2.0.16", conflict.selectedVersion());
        assertEquals(List.of("1.7.36", "2.0.16"), conflict.requestedVersions());
        assertEquals(ConflictSelectionReason.DIRECT_DEPENDENCY, conflict.reason());
    }

    @Test
    void readsDiscardedGraphConflictProvenance() {
        LockConflict conflict = reader.read("""
                version = 5

                [[conflict]]
                id = "com.example:driver"
                selected = "1.0.0"
                requested = ["1.0.0", "3.0.0"]
                reason = "selected materialized graph wins"
                """).conflicts().getFirst();

        assertEquals(ConflictSelectionReason.SELECTED_GRAPH, conflict.reason());
    }

    @Test
    void readsPolicyEffects() {
        ZoltLockfile lockfile = reader.read("""
                version = 1

                [[policy]]
                kind = "global-exclusion"
                id = "commons-logging:commons-logging"
                requested = "1.2"
                source = "org.springframework.boot:spring-boot-starter-web:3.3.6"
                policy = "[dependencyPolicy].exclude commons-logging:commons-logging (Use jcl-over-slf4j)"
                """);

        LockPolicyEffect effect = lockfile.policyEffects().getFirst();
        assertEquals("global-exclusion", effect.kind());
        assertEquals(new PackageId("commons-logging", "commons-logging"), effect.packageId());
        assertEquals("1.2", effect.requestedVersion().orElseThrow());
        assertEquals("org.springframework.boot:spring-boot-starter-web:3.3.6", effect.source().orElseThrow());
        assertEquals("[dependencyPolicy].exclude commons-logging:commons-logging (Use jcl-over-slf4j)", effect.policy());
    }

    @Test
    void readsInternalToolingScopes() {
        ZoltLockfile lockfile = reader.read("""
                version = 1

                [[package]]
                id = "com.example:processor"
                version = "1.0.0"
                source = "maven-central"
                scope = "processor"
                direct = true
                dependencies = []

                [[package]]
                id = "com.example:test-processor"
                version = "1.0.0"
                source = "maven-central"
                scope = "test-processor"
                direct = true
                dependencies = []

                [[package]]
                id = "io.quarkus:quarkus-rest-deployment"
                version = "3.33.0"
                source = "maven-central"
                scope = "quarkus-deployment"
                direct = false
                dependencies = []

                [[package]]
                id = "org.jacoco:org.jacoco.cli"
                version = "0.8.14"
                source = "maven-central"
                scope = "tool-coverage"
                direct = false
                dependencies = []
                """);

        assertEquals(DependencyScope.PROCESSOR, lockfile.packages().get(0).scope());
        assertEquals(DependencyScope.TEST_PROCESSOR, lockfile.packages().get(1).scope());
        assertEquals(DependencyScope.QUARKUS_DEPLOYMENT, lockfile.packages().get(2).scope());
        assertEquals(DependencyScope.TOOL_COVERAGE, lockfile.packages().get(3).scope());
    }

    @Test
    void readsWorkspacePackageFields() {
        ZoltLockfile lockfile = reader.read("""
                version = 1

                [[package]]
                id = "com.acme:core"
                version = "0.1.0"
                source = "workspace"
                scope = "compile"
                direct = true
                workspace = "modules/core"
                workspaceOutput = "target/classes"
                dependencies = []
                """);

        LockPackage lockPackage = lockfile.packages().getFirst();
        assertEquals("workspace", lockPackage.source());
        assertEquals("modules/core", lockPackage.workspace().orElseThrow());
        assertEquals("target/classes", lockPackage.workspaceOutput().orElseThrow());
        assertEquals(List.of(), lockPackage.members());
    }

    @Test
    void readsOptionalPackageMembers() {
        ZoltLockfile lockfile = reader.read("""
                version = 1

                [[package]]
                id = "com.example:demo"
                version = "1.0.0"
                source = "maven-central"
                scope = "compile"
                direct = true
                members = ["apps/api", "modules/core"]
                dependencies = []
                """);

        assertEquals(List.of("apps/api", "modules/core"), lockfile.packages().getFirst().members());
    }

    @Test
    void readsOptionalExportedByMembers() {
        ZoltLockfile lockfile = reader.read("""
                version = 1

                [[package]]
                id = "com.example:contract"
                version = "1.0.0"
                source = "maven-central"
                scope = "compile"
                direct = true
                exportedBy = ["modules/api"]
                dependencies = []
                """);

        assertEquals(List.of("modules/api"), lockfile.packages().getFirst().exportedBy());
    }

    @Test
    void readsNonJarArtifactFields() {
        ZoltLockfile lockfile = reader.read("""
                version = 1

                [[package]]
                id = "io.quarkus.platform:quarkus-bom-quarkus-platform-properties"
                version = "3.33.0"
                source = "maven-central"
                scope = "quarkus-deployment"
                direct = false
                pom = "io/quarkus/platform/quarkus-bom-quarkus-platform-properties/3.33.0/quarkus-bom-quarkus-platform-properties-3.33.0.pom"
                pomSha256 = "pom-checksum"
                artifact = "io/quarkus/platform/quarkus-bom-quarkus-platform-properties/3.33.0/quarkus-bom-quarkus-platform-properties-3.33.0.properties"
                artifactType = "properties"
                artifactSha256 = "properties-checksum"
                dependencies = []
                """);

        LockPackage lockPackage = lockfile.packages().getFirst();

        assertTrue(lockPackage.jar().isEmpty());
        assertEquals("properties", lockPackage.artifactType().orElseThrow());
        assertEquals(
                "io/quarkus/platform/quarkus-bom-quarkus-platform-properties/3.33.0/quarkus-bom-quarkus-platform-properties-3.33.0.properties",
                lockPackage.artifact().orElseThrow());
        assertEquals("properties-checksum", lockPackage.artifactSha256().orElseThrow());
    }
}

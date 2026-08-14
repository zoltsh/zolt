package sh.zolt.lockfile;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.error.ActionableException;

final class ContentAddressedLockCapabilityTest {
    private static final String DIGEST = "a".repeat(64);
    private static final String OTHER_DIGEST = "b".repeat(64);

    @Test
    void refusesReadableLocksThatPredateContentAddressedCachePaths() {
        LockPackage legacyPackage = lockPackage(
                Optional.of("com/example/demo/1.0.0/demo.jar"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        for (int version = 1; version < ContentAddressedLockCapability.MINIMUM_VERSION; version++) {
            ZoltLockfile lockfile = new ZoltLockfile(version, List.of(legacyPackage), List.of());

            ActionableException exception = assertThrows(
                    ActionableException.class,
                    () -> ContentAddressedLockCapability.requireArtifactCachePaths(
                            lockfile, "zolt resolve"),
                    "version " + version);

            assertTrue(exception.getMessage().contains("version " + version));
            assertTrue(exception.getMessage().contains("content-addressed artifact cache path"));
            assertTrue(exception.getMessage().contains("zolt resolve"));
        }
    }

    @Test
    void acceptsLegacyLocksThatDoNotMaterializeArtifactCachePaths() {
        assertDoesNotThrow(() -> ContentAddressedLockCapability.requireArtifactCachePaths(
                new ZoltLockfile(1, List.of(), List.of()),
                "zolt resolve"));
    }

    @Test
    void acceptsTheFirstCapableSchemaAndNewerSchemas() {
        assertDoesNotThrow(() -> ContentAddressedLockCapability.requireArtifactCachePaths(
                new ZoltLockfile(ContentAddressedLockCapability.MINIMUM_VERSION, List.of(), List.of()),
                "zolt resolve"));
        assertDoesNotThrow(() -> ContentAddressedLockCapability.requireArtifactCachePaths(
                new ZoltLockfile(ContentAddressedLockCapability.MINIMUM_VERSION + 1, List.of(), List.of()),
                "zolt resolve"));
    }

    @Test
    void acceptsCompleteContentAddressedArtifactMetadata() {
        LockPackage lockPackage = lockPackage(
                Optional.of(path(DIGEST, "demo.jar")),
                Optional.of(DIGEST),
                Optional.of(path(OTHER_DIGEST, "demo.pom")),
                Optional.of(OTHER_DIGEST),
                Optional.of(path(DIGEST, "demo.properties")),
                Optional.of("properties"),
                Optional.of(DIGEST));

        assertDoesNotThrow(() -> require(lockPackage));
    }

    @Test
    void rejectsPathAndChecksumAsymmetry() {
        assertViolation(
                lockPackage(
                        Optional.of(path(DIGEST, "demo.jar")),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()),
                "`jar` and `jarSha256` must be recorded together");
        assertViolation(
                lockPackage(
                        Optional.empty(),
                        Optional.of(DIGEST),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()),
                "`jar` and `jarSha256` must be recorded together");
    }

    @Test
    void rejectsIncompleteSecondaryArtifactTriples() {
        List<Optional<String>> present = List.of(
                Optional.of(path(DIGEST, "demo.properties")),
                Optional.of("properties"),
                Optional.of(DIGEST));
        for (int omitted = 0; omitted < present.size(); omitted++) {
            List<Optional<String>> fields = new ArrayList<>(present);
            fields.set(omitted, Optional.empty());
            assertViolation(
                    lockPackage(
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            fields.get(0),
                            fields.get(1),
                            fields.get(2)),
                    "secondary artifact must record");
        }
    }

    @Test
    void rejectsLegacyAndMalformedContentAddressedPaths() {
        assertJarPathViolation("com/example/demo/1.0.0/demo.jar", DIGEST, "must start");
        assertJarPathViolation("blobs/v2/sha256/" + DIGEST.toUpperCase() + "/demo.jar", DIGEST, "lowercase");
        assertJarPathViolation("blobs/v2/sha256/short/demo.jar", DIGEST, "one SHA-256 directory");
        assertJarPathViolation(path(DIGEST, "nested/demo.jar"), DIGEST, "one artifact filename");
        assertJarPathViolation(path(DIGEST, " "), DIGEST, "filename must not be blank");
        assertJarPathViolation(path(DIGEST, "demo.jar"), OTHER_DIGEST, "must equal `jarSha256`");
    }

    @Test
    void rejectsMalformedRecordedChecksums() {
        assertJarPathViolation(path(DIGEST, "demo.jar"), "A".repeat(64), "lowercase hexadecimal");
        assertJarPathViolation(path(DIGEST, "demo.jar"), "a".repeat(63), "64 lowercase");
    }

    private static void assertJarPathViolation(String path, String checksum, String message) {
        assertViolation(
                lockPackage(
                        Optional.of(path),
                        Optional.of(checksum),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()),
                message);
    }

    private static void assertViolation(LockPackage lockPackage, String expected) {
        ActionableException exception = assertThrows(ActionableException.class, () -> require(lockPackage));
        assertTrue(exception.getMessage().contains("com.example:demo:1.0.0"));
        assertTrue(exception.getMessage().contains(expected), exception.getMessage());
        assertTrue(exception.getMessage().contains("zolt resolve"));
    }

    private static void require(LockPackage lockPackage) {
        ContentAddressedLockCapability.requireArtifactCachePaths(
                new ZoltLockfile(ZoltLockfile.CURRENT_VERSION, List.of(lockPackage), List.of()),
                "zolt resolve");
    }

    private static LockPackage lockPackage(
            Optional<String> jar,
            Optional<String> jarSha256,
            Optional<String> pom,
            Optional<String> pomSha256,
            Optional<String> artifact,
            Optional<String> artifactType,
            Optional<String> artifactSha256) {
        return new LockPackage(
                new PackageId("com.example", "demo"),
                "1.0.0",
                "test",
                DependencyScope.COMPILE,
                true,
                jar,
                pom,
                jarSha256,
                pomSha256,
                artifact,
                artifactType,
                artifactSha256,
                List.of());
    }

    private static String path(String digest, String filename) {
        return "blobs/v2/sha256/" + digest + "/" + filename;
    }
}

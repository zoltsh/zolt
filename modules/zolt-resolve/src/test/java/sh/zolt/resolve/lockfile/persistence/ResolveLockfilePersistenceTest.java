package sh.zolt.resolve.lockfile.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.DependencyLane;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockDependencyRoot;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.lockfile.toml.ZoltLockfileWriter;
import sh.zolt.resolve.ResolveException;
import sh.zolt.resolve.ResolveOptions;
import sh.zolt.resolve.metrics.ResolveMetrics;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ResolveLockfilePersistenceTest {
    private static final PackageId APP = new PackageId("com.example", "app");

    @TempDir
    Path tempDir;

    private final ZoltLockfileWriter writer = new ZoltLockfileWriter();
    private final ZoltLockfileReader reader = new ZoltLockfileReader();
    private final ResolveLockfilePersistence persistence = new ResolveLockfilePersistence(writer);

    @Test
    void lockedPrepareFailsWhenLockfileIsMissing() {
        Path lockfilePath = tempDir.resolve("zolt.lock");

        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> persistence.prepare(lockfilePath, true, ResolveOptions.defaults()));

        assertTrue(exception.getMessage().contains("Locked resolve requires zolt.lock"));
        assertTrue(exception.getMessage().contains("Run `zolt resolve` to create it"));
    }

    @Test
    void prepareCarriesForwardCoverageToolingFromExistingLockfile() {
        Path lockfilePath = tempDir.resolve("zolt.lock");
        writer.write(lockfilePath, lockfile("maven-central", DependencyScope.TOOL_COVERAGE));

        ResolveOptions options = persistence.prepare(lockfilePath, false, ResolveOptions.defaults());

        assertTrue(options.includeCoverageTooling());
    }

    @Test
    void prepareIgnoresUnreadableExistingLockfileWhenCheckingCoverageTooling() throws Exception {
        Path lockfilePath = tempDir.resolve("zolt.lock");
        Files.writeString(lockfilePath, "not a lockfile");

        ResolveOptions options = persistence.prepare(lockfilePath, false, ResolveOptions.defaults());

        assertFalse(options.includeCoverageTooling());
    }

    @Test
    void lockedPrepareRejectsExistingLocalOverlayOriginsBeforeResolution() {
        Path lockfilePath = tempDir.resolve("zolt.lock");
        writer.write(lockfilePath, lockfile("local-overlay:maven-local", DependencyScope.COMPILE));

        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> persistence.prepare(
                        lockfilePath,
                        true,
                        new ResolveOptions(false, List.of(), true)));

        assertTrue(exception.getMessage().contains("Local repository overlay artifacts are not allowed"));
        assertTrue(exception.getMessage().contains("refresh zolt.lock from configured repositories"));
    }

    @Test
    void persistWritesUnlockedLockfileAndRecordsWriteTiming() {
        Path lockfilePath = tempDir.resolve("zolt.lock");

        ResolveMetrics metrics = persistence.persist(
                lockfilePath,
                lockfile("maven-central", DependencyScope.COMPILE),
                ResolveMetrics.empty(),
                false,
                ResolveOptions.defaults());

        assertTrue(metrics.lockfileWriteNanos() > 0);
        assertEquals(0, metrics.lockfileVerificationNanos());
        assertEquals(1, reader.read(lockfilePath).packages().size());
    }

    @Test
    void persistPreservesJavaToolchainMetadataOnUnlockedResolve() throws Exception {
        Path lockfilePath = tempDir.resolve("zolt.lock");
        Files.writeString(lockfilePath, withJavaToolchainBlock(
                writer.write(lockfile("maven-central", DependencyScope.COMPILE))));

        persistence.persist(
                lockfilePath,
                lockfile("maven-central", DependencyScope.COMPILE, "2.0.0"),
                ResolveMetrics.empty(),
                false,
                ResolveOptions.defaults());

        String content = Files.readString(lockfilePath);
        assertTrue(content.contains("[[toolchain.java]]"));
        assertTrue(content.contains("layout.executables.nativeImage = \"lib/svm/bin/native-image\""));
        assertEquals(1, reader.read(lockfilePath).packages().size());
    }

    @Test
    void persistVerifiesLockedLockfileAndDoesNotRewriteIt() throws Exception {
        Path lockfilePath = tempDir.resolve("zolt.lock");
        ZoltLockfile lockfile = lockfile("maven-central", DependencyScope.COMPILE);
        writer.write(lockfilePath, lockfile);
        String existing = Files.readString(lockfilePath);

        ResolveMetrics metrics = persistence.persist(
                lockfilePath,
                lockfile,
                ResolveMetrics.empty(),
                true,
                ResolveOptions.defaults());

        assertEquals(existing, Files.readString(lockfilePath));
        assertEquals(0, metrics.lockfileWriteNanos());
        assertTrue(metrics.lockfileVerificationNanos() > 0);
    }

    @Test
    void persistVerifiesLockedLockfileWithJavaToolchainMetadata() throws Exception {
        Path lockfilePath = tempDir.resolve("zolt.lock");
        ZoltLockfile lockfile = lockfile("maven-central", DependencyScope.COMPILE);
        String existing = withJavaToolchainBlock(writer.write(lockfile));
        Files.writeString(lockfilePath, existing);

        ResolveMetrics metrics = persistence.persist(
                lockfilePath,
                lockfile,
                ResolveMetrics.empty(),
                true,
                ResolveOptions.defaults());

        assertEquals(existing, Files.readString(lockfilePath));
        assertEquals(0, metrics.lockfileWriteNanos());
        assertTrue(metrics.lockfileVerificationNanos() > 0);
    }

    @Test
    void persistReportsActionableLockedMismatch() {
        Path lockfilePath = tempDir.resolve("zolt.lock");
        writer.write(lockfilePath, lockfile("maven-central", DependencyScope.COMPILE, "1.0.0"));

        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> persistence.persist(
                        lockfilePath,
                        lockfile("maven-central", DependencyScope.COMPILE, "2.0.0"),
                        ResolveMetrics.empty(),
                        true,
                        ResolveOptions.defaults()));

        assertTrue(exception.getMessage().contains("zolt.lock is out of date"));
        assertTrue(exception.getMessage().contains("Run `zolt resolve` to refresh it"));
    }

    @Test
    void persistRejectsNewLocalOverlayLockfileWhenOverlaysAreDisabled() {
        Path lockfilePath = tempDir.resolve("zolt.lock");

        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> persistence.persist(
                        lockfilePath,
                        lockfile("local-overlay:maven-local", DependencyScope.COMPILE),
                        ResolveMetrics.empty(),
                        false,
                        new ResolveOptions(false, List.of(), true)));

        assertTrue(exception.getMessage().contains("Local repository overlay artifacts are not allowed"));
        assertFalse(Files.exists(lockfilePath));
    }

    private static ZoltLockfile lockfile(String source, DependencyScope scope) {
        return lockfile(source, scope, "1.0.0");
    }

    private static ZoltLockfile lockfile(String source, DependencyScope scope, String version) {
        String jarSha256 = "a".repeat(64);
        String pomSha256 = "b".repeat(64);
        return new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of(new LockPackage(
                        APP,
                        version,
                        source,
                        scope,
                        true,
                        Optional.of("blobs/v2/sha256/" + jarSha256 + "/app-" + version + ".jar"),
                        Optional.of("blobs/v2/sha256/" + pomSha256 + "/app-" + version + ".pom"),
                        Optional.of(jarSha256),
                        Optional.of(pomSha256),
                        List.of())),
                List.of(),
                List.of(),
                List.of(),
                dependencyRoots(scope, version));
    }

    private static List<LockDependencyRoot> dependencyRoots(DependencyScope scope, String version) {
        return lane(scope)
                .map(lane -> List.of(new LockDependencyRoot(
                        ".",
                        APP,
                        version,
                        LockArtifactVariant.defaultVariant(),
                        lane,
                        Optional.of(scope),
                        false,
                        false)))
                .orElseGet(List::of);
    }

    private static Optional<DependencyLane> lane(DependencyScope scope) {
        return switch (scope) {
            case COMPILE -> Optional.of(DependencyLane.IMPLEMENTATION);
            case RUNTIME -> Optional.of(DependencyLane.RUNTIME);
            case PROVIDED -> Optional.of(DependencyLane.PROVIDED);
            case DEV -> Optional.of(DependencyLane.DEV);
            case TEST -> Optional.of(DependencyLane.TEST);
            case PROCESSOR -> Optional.of(DependencyLane.PROCESSOR);
            case TEST_PROCESSOR -> Optional.of(DependencyLane.TEST_PROCESSOR);
            default -> Optional.empty();
        };
    }

    private static String withJavaToolchainBlock(String content) {
        return content.stripTrailing() + """

                [[toolchain.java]]
                id = "java-graalvm-community-21-native-image"
                request.version = "21"
                request.distribution = "graalvm-community"
                request.features = ["native-image"]
                request.policy = "prefer-managed"
                platform.os = "macos"
                platform.arch = "aarch64"
                resolved.version = "21"
                resolved.distribution = "graalvm-community"
                artifact.catalog = "builtin:java-graalvm-community-21-native-image"
                layout.javaHome = "Contents/Home"
                layout.executables.java = "bin/java"
                layout.executables.javac = "bin/javac"
                layout.executables.jar = "bin/jar"
                layout.executables.nativeImage = "lib/svm/bin/native-image"
                """;
    }
}

package sh.zolt.lockfile.toml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.lockfile.ZoltLockfile;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@code workspaceResolutionInputFingerprint} is an optional annotation on lock schema 5, not a new
 * schema. It must survive a round trip, must be absent rather than fatal on locks written before it,
 * must not raise the version, and must stay outside the canonical bytes {@code --locked} compares.
 */
final class WorkspaceResolutionInputFingerprintCodecTest {
    private static final String FINGERPRINT =
            "sha256:164c737c8e49586cfe07a1a43cb26de94a794e3bb100c4c80c359e428e4693db";

    private final ZoltLockfileWriter writer = new ZoltLockfileWriter();
    private final ZoltLockfileReader reader = new ZoltLockfileReader();

    @Test
    void roundTripsThroughTheWriterAndReader() {
        String written = writer.write(lockfile(Optional.of(FINGERPRINT)));

        assertTrue(written.contains("workspaceResolutionInputFingerprint = \"" + FINGERPRINT + "\""));
        assertEquals(
                Optional.of(FINGERPRINT),
                reader.read(written).workspaceResolutionInputFingerprint());
    }

    @Test
    void keepsTheLockfileVersionAtFive() {
        assertEquals(5, ZoltLockfile.CURRENT_VERSION);
        assertEquals(5, reader.read(writer.write(lockfile(Optional.of(FINGERPRINT)))).version());
    }

    @Test
    void isOmittedEntirelyWhenAbsent() {
        String written = writer.write(lockfile(Optional.empty()));

        assertFalse(written.contains("workspaceResolutionInputFingerprint"));
        assertTrue(reader.read(written).workspaceResolutionInputFingerprint().isEmpty());
    }

    @Test
    void readsALockWrittenBeforeTheAnnotationExisted() {
        ZoltLockfile lockfile = reader.read("""
                version = 5
                projectResolutionFingerprint = "sha256:abc"

                [[package]]
                id = "org.slf4j:slf4j-api"
                version = "2.0.17"
                source = "maven-central"
                scope = "compile"
                direct = true
                dependencies = []
                """);

        assertTrue(lockfile.workspaceResolutionInputFingerprint().isEmpty());
        assertEquals(1, lockfile.packages().size());
    }

    @Test
    void ignoresTopLevelKeysItDoesNotKnow() {
        ZoltLockfile lockfile = reader.read("""
                version = 5
                projectResolutionFingerprint = "sha256:abc"
                someFutureAnnotation = "whatever"
                """);

        assertEquals(5, lockfile.version());
    }

    @Test
    void staysOutOfTheCanonicalLockfileThatLockedVerificationCompares() {
        String withFingerprint = writer.write(lockfile(Optional.of(FINGERPRINT)));
        String withoutFingerprint = writer.write(lockfile(Optional.empty()));

        assertEquals(
                LockfileSidecars.canonicalDependencyLockfile(withoutFingerprint),
                LockfileSidecars.canonicalDependencyLockfile(withFingerprint));
    }

    @Test
    void keepsJavaToolchainSidecarsOutOfTheCanonicalLockfileToo() {
        String content = writer.write(lockfile(Optional.of(FINGERPRINT)))
                + "\n[[toolchain.java]]\nversion = \"21\"\n";

        assertFalse(LockfileSidecars.canonicalDependencyLockfile(content).contains("toolchain.java"));
        assertFalse(LockfileSidecars.canonicalDependencyLockfile(content)
                .contains("workspaceResolutionInputFingerprint"));
    }

    private static ZoltLockfile lockfile(Optional<String> workspaceFingerprint) {
        return new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                Optional.empty(),
                Optional.of("sha256:abc"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                workspaceFingerprint);
    }
}

package sh.zolt.lockfile.toml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockConflict;
import sh.zolt.lockfile.ZoltLockfile;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ZoltLockfileReaderTest {
    private final ZoltLockfileReader reader = new ZoltLockfileReader();

    @Test
    void readsCurrentLockfileVersion() throws IOException {
        ZoltLockfile lockfile = reader.read(ZoltLockfileReaderTestSupport.golden());

        assertEquals(ZoltLockfile.CURRENT_VERSION, lockfile.version());
        assertEquals("sha256:project-inputs", lockfile.projectResolutionFingerprint().orElseThrow());
        assertEquals(
                List.of("dependencies.compile=sha256:compile-inputs", "repositories=sha256:repo-inputs"),
                lockfile.projectResolutionInputFingerprints());
        assertEquals(3, lockfile.packages().size());
        assertEquals(1, lockfile.conflicts().size());
    }

    @Test
    void readsToolAttributedConflictAndDefaultsAbsentToolToEmpty() {
        ZoltLockfile lockfile = reader.read("""
                version = 1

                [[conflict]]
                id = "com.example:shared"
                tool = "codegen"
                selected = "2.0.0"
                requested = ["1.0.0", "2.0.0"]
                reason = "newest version wins"

                [[conflict]]
                id = "org.slf4j:slf4j-api"
                selected = "2.0.16"
                requested = ["1.7.36", "2.0.16"]
                reason = "direct dependency wins"
                """);

        assertEquals(2, lockfile.conflicts().size());
        LockConflict tooled = lockfile.conflicts().stream()
                .filter(conflict -> conflict.packageId().equals(new PackageId("com.example", "shared")))
                .findFirst()
                .orElseThrow();
        assertEquals(Optional.of("codegen"), tooled.toolGroup());
        LockConflict main = lockfile.conflicts().stream()
                .filter(conflict -> conflict.packageId().equals(new PackageId("org.slf4j", "slf4j-api")))
                .findFirst()
                .orElseThrow();
        assertTrue(main.toolGroup().isEmpty());
    }

    @Test
    void readsLegacyCurrentVersionWithoutResolutionMetadata() {
        ZoltLockfile lockfile = reader.read("version = 1\n");

        assertTrue(lockfile.projectResolutionFingerprint().isEmpty());
        assertEquals(List.of(), lockfile.projectResolutionInputFingerprints());
    }

    @Test
    void wrapsTomlTypeErrorsAsLockfileReadExceptions() {
        LockfileReadException exception = assertThrows(LockfileReadException.class, () -> reader.read("""
                version = 1

                [[package]]
                id = 42
                """));

        assertTrue(exception.getMessage().contains("Invalid value type in zolt.lock"));
        assertTrue(exception.getMessage().contains("Run `zolt resolve` to regenerate the lockfile."));
    }

    @Test
    void readsAliasFingerprint() {
        ZoltLockfile lockfile = reader.read("""
                version = 1
                aliasFingerprint = "sha256:alias-inputs"
                """);

        assertEquals("sha256:alias-inputs", lockfile.aliasFingerprint().orElseThrow());
    }

    @Test
    void readsProjectResolutionFingerprint() {
        ZoltLockfile lockfile = reader.read("""
                version = 1
                projectResolutionFingerprint = "sha256:project-inputs"
                projectResolutionInputFingerprints = ["repositories=sha256:repo-inputs", "dependencies.compile=sha256:compile-inputs"]
                """);

        assertEquals("sha256:project-inputs", lockfile.projectResolutionFingerprint().orElseThrow());
        assertEquals(
                List.of("repositories=sha256:repo-inputs", "dependencies.compile=sha256:compile-inputs"),
                lockfile.projectResolutionInputFingerprints());
    }

    @Test
    void rejectsNewerLockfileVersionWithUpgradeHint() {
        LockfileReadException exception = assertThrows(
                LockfileReadException.class,
                () -> reader.read("version = 99\n"));

        assertEquals(
                "zolt.lock version 99 is newer than this Zolt supports (current 4). Upgrade Zolt, then run `zolt resolve --locked` to verify the lockfile.",
                exception.getMessage());
    }

    @Test
    void rejectsOlderLockfileVersionWithRegenerateHint() {
        LockfileReadException exception = assertThrows(
                LockfileReadException.class,
                () -> reader.read("version = 0\n"));

        assertEquals(
                "zolt.lock version 0 is older than this Zolt supports (current 4). Run `zolt resolve` with this Zolt version to regenerate the lockfile.",
                exception.getMessage());
    }

    @Test
    void rejectsCorruptTomlWithActionableError() {
        LockfileReadException exception = assertThrows(
                LockfileReadException.class,
                () -> reader.read("version =\n"));

        assertTrue(exception.getMessage().contains("Could not parse zolt.lock"));
        assertTrue(exception.getMessage().contains("Fix the TOML syntax"));
    }

    @Test
    void rejectsMissingRequiredPackageField() {
        LockfileReadException exception = assertThrows(
                LockfileReadException.class,
                () -> reader.read("""
                        version = 1

                        [[package]]
                        id = "com.example:demo"
                        version = "1.0.0"
                        scope = "compile"
                        direct = true
                        dependencies = []
                        """));

        assertEquals("Missing required string field `source` in zolt.lock.", exception.getMessage());
    }

}

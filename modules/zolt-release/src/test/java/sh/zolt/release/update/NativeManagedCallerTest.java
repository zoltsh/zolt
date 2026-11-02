package sh.zolt.release.update;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import sh.zolt.release.ReleaseTarget;

final class NativeManagedCallerTest extends NativeUpdateServiceTestCase {
    @Test
    void canonicalLeaseAcceptsRetainedLegacyExecutableWithoutReleaseMetadata() throws Exception {
        InstalledFixture installed = install("1.0.0");
        Path captured = installed.binLink().toRealPath();
        writeFakeZolt(installed.installRoot().resolve("versions/1.0.1/bin/zolt"), "1.0.1");
        new NativeVersionService().use(new NativeVersionSwitchRequest(installed.installRoot(), captured, "1.0.1"));
        Path alias = Files.createSymbolicLink(tempDir.resolve("installation alias"), installed.installRoot());
        try (NativeInstallLease lease = NativeInstallLease.acquireManaged(alias, captured)) {
            assertEquals(installed.installRoot().toRealPath(), lease.root());
            assertEquals("1.0.1", NativeInstalledLayout.detect(alias, captured).version());
            assertFalse(Files.exists(captured.getParent().getParent().resolve("BUILD.json")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"list", "exec", "notice"})
    void retainedCallerUsesCurrentLauncherSnapshotForReadOperations(String operation) throws Exception {
        InstalledFixture installed = install("1.0.0");
        Path captured = installed.binLink().toRealPath();
        writeFakeZolt(installed.installRoot().resolve("versions/1.0.1/bin/zolt"), "1.0.1");
        new NativeVersionService().use(new NativeVersionSwitchRequest(installed.installRoot(), captured, "1.0.1"));
        switch (operation) {
            case "list" -> assertEquals("1.0.1", new NativeVersionService()
                    .list(new NativeVersionListRequest(installed.installRoot(), captured)).currentVersion());
            case "exec" -> assertEquals(captured, new NativeVersionExecService().plan(new NativeVersionExecRequest(
                    installed.installRoot(), captured, "1.0.0", List.of("--version"))).executable().toRealPath());
            case "notice" -> {
                Path archive = archive("1.0.2", "linux-x64", "1.0.2");
                Path channel = writeChannel("stable", "1.0.2", "linux-x64", archive, archive.getFileName().toString(), "sidecar");
                var notice = new NativeUpdateNoticeService().check(new NativeUpdateNoticeRequest(
                        installed.installRoot(), captured, channel.toUri(), ReleaseTarget.LINUX_X64,
                        tempDir.resolve("notice-state"), Instant.parse("2026-11-02T00:00:00Z"), Duration.ZERO,
                        false, false, false, true)).orElseThrow();
                assertEquals("1.0.1", notice.currentVersion());
                assertEquals("1.0.2", notice.availableVersion());
            }
            default -> fail(operation);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"external/bin/zolt", "versions/.install-attempt-staged/bin/zolt",
            "versions/1.0.1.tmp/bin/zolt", "versions/1.0.1/nested/bin/zolt", "versions/1.0.1/bin/other"})
    void rejectsExternalStagedAndMalformedCallersBeforeAcquiringLease(String relative) throws Exception {
        InstalledFixture installed = install("1.0.0");
        Path caller = installed.installRoot().resolve(relative);
        writeFakeZolt(caller, "1.0.1");
        assertThrows(NativeUpdateException.class, () -> NativeInstallLease.acquireManaged(installed.installRoot(), caller));
        assertFalse(Files.exists(installed.installRoot().resolve(".install-lock")));
        assertEquals("1.0.0", NativeInstalledLayout.detect(installed.installRoot(), installed.binLink()).version());
    }

    @Test
    void missingRootFailsActionablyWithoutCreatingInstallationState() {
        Path missing = tempDir.resolve("missing-installation");
        NativeUpdateException failure = assertThrows(NativeUpdateException.class,
                () -> NativeInstallLease.acquireManaged(missing, tempDir.resolve("unmanaged-executable")));
        assertTrue(failure.getMessage().startsWith("Installer-managed native Zolt layouts"));
        assertFalse(Files.exists(missing));
    }

    @Test
    void rejectsVersionDirectoryThatResolvesOutsideInstallation() throws Exception {
        InstalledFixture installed = install("1.0.0");
        Path external = tempDir.resolve("external-version");
        writeFakeZolt(external.resolve("bin/zolt"), "1.0.1");
        Path alias = Files.createSymbolicLink(installed.installRoot().resolve("versions/1.0.1"), external);
        assertThrows(NativeUpdateException.class,
                () -> NativeInstallLease.acquireManaged(installed.installRoot(), alias.resolve("bin/zolt")));
        assertFalse(Files.exists(installed.installRoot().resolve(".install-lock")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../external/bin/zolt", "../versions/.install-attempt-staged/bin/zolt",
            "../versions/1.0.1.tmp/bin/zolt", "../versions/1.0.1/not-bin/zolt", "../versions/1.0.1/bin/other"})
    void retainedCallerCannotOperateThroughTamperedActiveLauncher(String target) throws Exception {
        InstalledFixture installed = install("1.0.0");
        Path captured = installed.binLink().toRealPath();
        Path unsafe = installed.binLink().getParent().resolve(target).normalize();
        writeFakeZolt(unsafe, "1.0.1");
        String before = Files.readString(unsafe);
        Files.delete(installed.binLink());
        Files.createSymbolicLink(installed.binLink(), Path.of(target));
        assertThrows(NativeUpdateException.class, () -> new NativeVersionService().use(
                new NativeVersionSwitchRequest(installed.installRoot(), captured, "1.0.0")));
        assertEquals(Path.of(target), Files.readSymbolicLink(installed.binLink()));
        assertEquals(before, Files.readString(unsafe));
        assertFalse(Files.exists(installed.installRoot().resolve(".install-lock")));
    }

    @Test
    void retainedCallerCannotOperateThroughNonSymlinkLauncher() throws Exception {
        InstalledFixture installed = install("1.0.0");
        Path captured = installed.binLink().toRealPath();
        Files.delete(installed.binLink());
        Files.writeString(installed.binLink(), "unmanaged launcher");
        assertThrows(NativeUpdateException.class, () -> new NativeVersionService().prune(
                new NativeVersionPruneRequest(installed.installRoot(), captured, 1, false)));
        assertEquals("unmanaged launcher", Files.readString(installed.binLink()));
        assertFalse(Files.exists(installed.installRoot().resolve(".install-lock")));
    }
}

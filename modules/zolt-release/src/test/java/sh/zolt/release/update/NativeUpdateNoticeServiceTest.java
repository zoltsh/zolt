package sh.zolt.release.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.release.ReleaseTarget;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class NativeUpdateNoticeServiceTest extends NativeUpdateNoticeServiceTestCase {
    @Test
    void reportsNewerNativeVersionForInstallerManagedLayout() throws IOException {
        InstalledFixture installed = install("0.1.0");
        Path channel = writeChannel("stable", "0.1.1");

        Optional<NativeUpdateNotice> notice = service.check(request(installed, channel.toUri(), now));

        assertTrue(notice.isPresent());
        assertEquals("stable", notice.orElseThrow().channel());
        assertEquals("0.1.0", notice.orElseThrow().currentVersion());
        assertEquals("0.1.1", notice.orElseThrow().availableVersion());
        assertEquals(
                "A newer Zolt is available on stable: 0.1.0 -> 0.1.1. Run `zolt self update` to download and verify it.",
                notice.orElseThrow().message());
    }

    @Test
    void suppressesWhenInstalledVersionMatchesChannel() throws IOException {
        InstalledFixture installed = install("0.1.0");
        Path channel = writeChannel("stable", "0.1.0");

        Optional<NativeUpdateNotice> notice = service.check(request(installed, channel.toUri(), now));

        assertTrue(notice.isEmpty());
    }

    @Test
    void suppressesUnsupportedLayoutsWithoutFailingOriginalCommand() throws IOException {
        InstalledFixture installed = install("0.1.0");
        Path channel = writeChannel("stable", "0.1.1");
        Path developmentExecutable = tempDir.resolve("dev/zolt");
        Files.createDirectories(developmentExecutable.getParent());
        Files.writeString(developmentExecutable, "dev");

        Optional<NativeUpdateNotice> notice = service.check(new NativeUpdateNoticeRequest(
                installed.installRoot(),
                developmentExecutable,
                channel.toUri(),
                ReleaseTarget.LINUX_X64,
                tempDir.resolve("state"),
                now,
                Duration.ZERO,
                false,
                false,
                false,
                true));

        assertTrue(notice.isEmpty());
    }

    @Test
    void suppressesDisabledOfflineCiAndNonInteractiveChecks() throws IOException {
        InstalledFixture installed = install("0.1.0");
        URI channel = writeChannel("stable", "0.1.1").toUri();

        assertTrue(service.check(request(installed, channel, now, true, false, false, true)).isEmpty());
        assertTrue(service.check(request(installed, channel, now, false, true, false, true)).isEmpty());
        assertTrue(service.check(request(installed, channel, now, false, false, true, true)).isEmpty());
        assertTrue(service.check(request(installed, channel, now, false, false, false, false)).isEmpty());
    }

    @Test
    void comparesMissingVersionPartsAndTextQualifiers() throws IOException {
        InstalledFixture shortVersion = install("0.1");
        Path numericChannel = writeChannel("stable", "0.1.1");
        Optional<NativeUpdateNotice> numericNotice = service.check(request(
                shortVersion,
                numericChannel.toUri(),
                now,
                tempDir.resolve("numeric-state")));

        InstalledFixture qualifiedVersion = install("0.1.0-beta.1");
        Path qualifiedChannel = writeChannel("preview", "0.1.0-rc.1");
        Optional<NativeUpdateNotice> qualifiedNotice = service.check(request(
                qualifiedVersion,
                qualifiedChannel.toUri(),
                now,
                tempDir.resolve("qualified-state")));

        assertTrue(numericNotice.isPresent());
        assertEquals("0.1.1", numericNotice.orElseThrow().availableVersion());
        assertTrue(qualifiedNotice.isPresent());
        assertEquals("0.1.0-rc.1", qualifiedNotice.orElseThrow().availableVersion());
    }
}

package sh.zolt.release.verification;

import static sh.zolt.release.verification.ReleaseVerificationServiceTestSupport.sha256;
import static sh.zolt.release.verification.ReleaseVerificationServiceTestSupport.writeZip;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.release.verification.ReleaseVerificationServiceTestSupport.ZipFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every release archive must carry LICENSE, NOTICE, and THIRD_PARTY_NOTICES so the distribution stays
 * within its own license terms. These checks run before any smoke command, so a legally incomplete
 * archive fails without executing the bundled binary.
 */
final class ReleaseVerificationLegalDocumentsTest {
    @TempDir
    private Path projectDir;

    @Test
    void missingNoticeFailsBeforeSmokeCommands() throws IOException {
        Path archive = projectDir.resolve("zolt-0.1.0-windows-x64.zip");
        writeZip(
                archive,
                new ZipFile("zolt-0.1.0-windows-x64/bin/zolt.exe", "native"),
                new ZipFile("zolt-0.1.0-windows-x64/libexec/zolt-junit-worker.jar", "worker"),
                new ZipFile("zolt-0.1.0-windows-x64/libexec/zolt-javac-worker.jar", "worker"),
                new ZipFile("zolt-0.1.0-windows-x64/VERSION", "0.1.0\n"),
                new ZipFile("zolt-0.1.0-windows-x64/LICENSE", "license\n"),
                new ZipFile("zolt-0.1.0-windows-x64/THIRD_PARTY_NOTICES", "third party notices\n"));
        Files.writeString(archive.resolveSibling(archive.getFileName() + ".sha256"),
                sha256(archive) + "  " + archive.getFileName() + "\n");
        List<List<String>> commands = new ArrayList<>();
        ReleaseVerificationService service = new ReleaseVerificationService((command, directory) -> {
            commands.add(command);
            return new ReleaseVerificationService.ProcessResult(0, "0.1.0\n");
        });

        ReleaseVerificationException exception = assertThrows(
                ReleaseVerificationException.class,
                () -> service.verify(List.of(archive), projectDir.resolve("verify-no-notice"), "0.1.0"));

        assertTrue(exception.getMessage().contains("expected NOTICE"), exception.getMessage());
        assertTrue(
                exception.getMessage().contains("LICENSE, NOTICE, THIRD_PARTY_NOTICES"),
                exception.getMessage());
        assertEquals(List.of(), commands);
    }

    @Test
    void emptyThirdPartyNoticesFailsBeforeSmokeCommands() throws IOException {
        Path archive = projectDir.resolve("zolt-0.1.0-windows-x64.zip");
        writeZip(
                archive,
                new ZipFile("zolt-0.1.0-windows-x64/bin/zolt.exe", "native"),
                new ZipFile("zolt-0.1.0-windows-x64/libexec/zolt-junit-worker.jar", "worker"),
                new ZipFile("zolt-0.1.0-windows-x64/libexec/zolt-javac-worker.jar", "worker"),
                new ZipFile("zolt-0.1.0-windows-x64/VERSION", "0.1.0\n"),
                new ZipFile("zolt-0.1.0-windows-x64/LICENSE", "license\n"),
                new ZipFile("zolt-0.1.0-windows-x64/NOTICE", "notice\n"),
                new ZipFile("zolt-0.1.0-windows-x64/THIRD_PARTY_NOTICES", "   \n"));
        Files.writeString(archive.resolveSibling(archive.getFileName() + ".sha256"),
                sha256(archive) + "  " + archive.getFileName() + "\n");
        List<List<String>> commands = new ArrayList<>();
        ReleaseVerificationService service = new ReleaseVerificationService((command, directory) -> {
            commands.add(command);
            return new ReleaseVerificationService.ProcessResult(0, "0.1.0\n");
        });

        ReleaseVerificationException exception = assertThrows(
                ReleaseVerificationException.class,
                () -> service.verify(List.of(archive), projectDir.resolve("verify-empty-notices"), "0.1.0"));

        assertTrue(exception.getMessage().contains("THIRD_PARTY_NOTICES"), exception.getMessage());
        assertTrue(exception.getMessage().contains("is empty"), exception.getMessage());
        assertEquals(List.of(), commands);
    }
}

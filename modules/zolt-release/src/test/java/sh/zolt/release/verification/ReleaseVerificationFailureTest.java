package sh.zolt.release.verification;

import static sh.zolt.release.verification.ReleaseVerificationServiceTestSupport.config;
import static sh.zolt.release.verification.ReleaseVerificationServiceTestSupport.passingService;
import static sh.zolt.release.verification.ReleaseVerificationServiceTestSupport.sha256;
import static sh.zolt.release.verification.ReleaseVerificationServiceTestSupport.smokeAwareService;
import static sh.zolt.release.verification.ReleaseVerificationServiceTestSupport.writeBinary;
import static sh.zolt.release.verification.ReleaseVerificationServiceTestSupport.writeProjectFiles;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.release.ReleaseTarget;
import sh.zolt.release.archive.ReleaseArchiveResult;
import sh.zolt.release.archive.ReleaseArchiveService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ReleaseVerificationFailureTest {
    @TempDir
    private Path projectDir;

    @Test
    void emptyArchiveListFailsBeforeCreatingWorkDirectory() {
        Path workDirectory = projectDir.resolve("verify-empty");

        ReleaseVerificationException exception = assertThrows(
                ReleaseVerificationException.class,
                () -> passingService().verify(List.of(), workDirectory, "0.1.0"));

        assertEquals("Release verification needs at least one archive. Pass an archive path.", exception.getMessage());
        assertTrue(Files.notExists(workDirectory));
    }

    @Test
    void workDirectoryThatIsAFileFailsClearly() throws IOException {
        Path workDirectory = projectDir.resolve("verify-file");
        Files.writeString(workDirectory, "not a directory");

        ReleaseVerificationException exception = assertThrows(
                ReleaseVerificationException.class,
                () -> passingService().verify(
                        List.of(projectDir.resolve("zolt-0.1.0-linux-x64.tar.gz")),
                        workDirectory,
                        "0.1.0"));

        assertTrue(exception.getMessage().contains("Could not create release verification work directory"));
        assertTrue(exception.getMessage().contains("Check that it is writable."));
    }

    @Test
    void missingArchiveFileFailsWithActionablePathDiagnostic() {
        Path archive = projectDir.resolve("zolt-0.1.0-linux-x64.tar.gz");

        ReleaseVerificationException exception = assertThrows(
                ReleaseVerificationException.class,
                () -> passingService().verify(List.of(archive), projectDir.resolve("verify-missing"), "0.1.0"));

        assertTrue(exception.getMessage().contains("Release archive verification failed for " + archive));
        assertTrue(exception.getMessage().contains("archive does not exist. Pass a valid release archive path."));
    }

    @Test
    void unsupportedArchiveSuffixFailsWithSupportedTargets() throws IOException {
        Path archive = projectDir.resolve("zolt-0.1.0-plan9-x64.tar.gz");
        Files.writeString(archive, "not inspected because target inference fails first");

        ReleaseVerificationException exception = assertThrows(
                ReleaseVerificationException.class,
                () -> passingService().verify(List.of(archive), projectDir.resolve("verify-target"), "0.1.0"));

        assertTrue(exception.getMessage().contains("Could not infer release target"));
        assertTrue(exception.getMessage().contains("zolt-0.1.0-plan9-x64.tar.gz"));
        assertTrue(exception.getMessage().contains(ReleaseTarget.supportedTargets()));
    }

    @Test
    void missingChecksumFailsWithArchiveName() throws IOException {
        writeProjectFiles(projectDir);
        Path binary = writeBinary(projectDir, "target/native/zolt");
        ReleaseArchiveResult archive = new ReleaseArchiveService().assemble(
                projectDir,
                config(),
                ReleaseTarget.LINUX_X64,
                binary,
                Path.of("dist"));
        Files.delete(archive.checksumPath());

        ReleaseVerificationException exception = assertThrows(
                ReleaseVerificationException.class,
                () -> passingService().verify(List.of(archive.archivePath()), projectDir.resolve("verify"), "0.1.0"));

        assertTrue(exception.getMessage().contains("Release archive verification failed for " + archive.archivePath()));
        assertTrue(exception.getMessage().contains("missing checksum sidecar"));
    }

    @Test
    void checksumMismatchFailsWithExpectedAndActualChecksums() throws IOException {
        writeProjectFiles(projectDir);
        Path binary = writeBinary(projectDir, "target/native/zolt");
        ReleaseArchiveResult archive = new ReleaseArchiveService().assemble(
                projectDir,
                config(),
                ReleaseTarget.LINUX_X64,
                binary,
                Path.of("dist"));
        Files.writeString(archive.checksumPath(), "0".repeat(64) + "  " + archive.archivePath().getFileName() + "\n");

        ReleaseVerificationException exception = assertThrows(
                ReleaseVerificationException.class,
                () -> passingService().verify(List.of(archive.archivePath()), projectDir.resolve("verify"), "0.1.0"));

        assertTrue(exception.getMessage().contains("SHA-256 mismatch"));
        assertTrue(exception.getMessage().contains("Expected " + "0".repeat(64)));
        assertTrue(exception.getMessage().contains("but found "));
    }

    @Test
    void corruptArchiveFailsWithUnreadableArchiveDiagnostic() throws IOException {
        Path archive = projectDir.resolve("zolt-0.1.0-linux-x64.tar.gz");
        Files.writeString(archive, "not a gzip stream");
        Files.writeString(archive.resolveSibling(archive.getFileName() + ".sha256"),
                sha256(archive) + "  " + archive.getFileName() + "\n");

        ReleaseVerificationException exception = assertThrows(
                ReleaseVerificationException.class,
                () -> passingService().verify(List.of(archive), projectDir.resolve("verify-corrupt"), "0.1.0"));

        assertTrue(exception.getMessage().contains("could not unpack or read archive"));
        assertTrue(exception.getMessage().contains("archive is readable and not corrupt"));
    }

    @Test
    void missingBinaryAfterUnpackFailsBeforeSmokeCommands() throws IOException {
        Path archive = projectDir.resolve("zolt-0.1.0-windows-x64.zip");
        writeZip(archive, new ZipFile("zolt-0.1.0-windows-x64/VERSION", "0.1.0\n"));
        Files.writeString(archive.resolveSibling(archive.getFileName() + ".sha256"),
                sha256(archive) + "  " + archive.getFileName() + "\n");
        List<List<String>> commands = new ArrayList<>();
        ReleaseVerificationService service = new ReleaseVerificationService((command, directory) -> {
            commands.add(command);
            return new ReleaseVerificationService.ProcessResult(0, "0.1.0\n");
        });

        ReleaseVerificationException exception = assertThrows(
                ReleaseVerificationException.class,
                () -> service.verify(List.of(archive), projectDir.resolve("verify-no-binary"), "0.1.0"));

        assertTrue(exception.getMessage().contains("expected binary at"));
        assertTrue(exception.getMessage().contains("after unpacking"));
        assertEquals(List.of(), commands);
    }

    @Test
    void missingVersionMetadataFailsBeforeSmokeCommands() throws IOException {
        Path archive = projectDir.resolve("zolt-0.1.0-windows-x64.zip");
        writeZip(archive, new ZipFile("zolt-0.1.0-windows-x64/bin/zolt.exe", "native"));
        Files.writeString(archive.resolveSibling(archive.getFileName() + ".sha256"),
                sha256(archive) + "  " + archive.getFileName() + "\n");
        List<List<String>> commands = new ArrayList<>();
        ReleaseVerificationService service = new ReleaseVerificationService((command, directory) -> {
            commands.add(command);
            return new ReleaseVerificationService.ProcessResult(0, "0.1.0\n");
        });

        ReleaseVerificationException exception = assertThrows(
                ReleaseVerificationException.class,
                () -> service.verify(List.of(archive), projectDir.resolve("verify-no-version"), "0.1.0"));

        assertTrue(exception.getMessage().contains("expected VERSION metadata"));
        assertEquals(List.of(), commands);
    }

    @Test
    void versionMetadataMismatchFailsBeforeSmokeCommands() throws IOException {
        writeProjectFiles(projectDir);
        Path binary = writeBinary(projectDir, "target/native/zolt");
        ReleaseArchiveResult archive = new ReleaseArchiveService().assemble(
                projectDir,
                config(),
                ReleaseTarget.LINUX_X64,
                binary,
                Path.of("dist"));
        List<List<String>> commands = new ArrayList<>();
        ReleaseVerificationService service = new ReleaseVerificationService((command, directory) -> {
            commands.add(command);
            return new ReleaseVerificationService.ProcessResult(0, "0.2.0\n");
        });

        ReleaseVerificationException exception = assertThrows(
                ReleaseVerificationException.class,
                () -> service.verify(List.of(archive.archivePath()), projectDir.resolve("verify-version"), "0.2.0"));

        assertTrue(exception.getMessage().contains("VERSION metadata did not match expected version 0.2.0"));
        assertTrue(exception.getMessage().contains("Found 0.1.0"));
        assertEquals(List.of(), commands);
    }

    @Test
    void missingBundledJunitWorkerFailsBeforeSmokeCommands() throws IOException {
        Path archive = projectDir.resolve("zolt-0.1.0-windows-x64.zip");
        writeZip(
                archive,
                new ZipFile("zolt-0.1.0-windows-x64/bin/zolt.exe", "native"),
                new ZipFile("zolt-0.1.0-windows-x64/VERSION", "0.1.0\n"));
        Files.writeString(archive.resolveSibling(archive.getFileName() + ".sha256"),
                sha256(archive) + "  " + archive.getFileName() + "\n");
        List<List<String>> commands = new ArrayList<>();
        ReleaseVerificationService service = new ReleaseVerificationService((command, directory) -> {
            commands.add(command);
            return new ReleaseVerificationService.ProcessResult(0, "0.1.0\n");
        });

        ReleaseVerificationException exception = assertThrows(
                ReleaseVerificationException.class,
                () -> service.verify(List.of(archive), projectDir.resolve("verify-no-worker"), "0.1.0"));

        assertTrue(exception.getMessage().contains("expected bundled JUnit worker"));
        assertTrue(exception.getMessage().contains("libexec/zolt-junit-worker.jar"));
        assertEquals(List.of(), commands);
    }

    @Test
    void missingBundledJavacWorkerFailsBeforeSmokeCommands() throws IOException {
        Path archive = projectDir.resolve("zolt-0.1.0-windows-x64.zip");
        writeZip(
                archive,
                new ZipFile("zolt-0.1.0-windows-x64/bin/zolt.exe", "native"),
                new ZipFile("zolt-0.1.0-windows-x64/libexec/zolt-junit-worker.jar", "worker"),
                new ZipFile("zolt-0.1.0-windows-x64/VERSION", "0.1.0\n"));
        Files.writeString(archive.resolveSibling(archive.getFileName() + ".sha256"),
                sha256(archive) + "  " + archive.getFileName() + "\n");
        List<List<String>> commands = new ArrayList<>();
        ReleaseVerificationService service = new ReleaseVerificationService((command, directory) -> {
            commands.add(command);
            return new ReleaseVerificationService.ProcessResult(0, "0.1.0\n");
        });

        ReleaseVerificationException exception = assertThrows(
                ReleaseVerificationException.class,
                () -> service.verify(List.of(archive), projectDir.resolve("verify-no-javac-worker"), "0.1.0"));

        assertTrue(exception.getMessage().contains("expected bundled javac worker"));
        assertTrue(exception.getMessage().contains("libexec/zolt-javac-worker.jar"));
        assertEquals(List.of(), commands);
    }

    @Test
    void versionSmokeFailureReportsArchiveAndOutput() throws IOException {
        writeProjectFiles(projectDir);
        Path binary = writeBinary(projectDir, "target/native/zolt");
        ReleaseArchiveResult archive = new ReleaseArchiveService().assemble(
                projectDir,
                config(),
                ReleaseTarget.MACOS_ARM64,
                binary,
                Path.of("dist"));
        ReleaseVerificationService service = new ReleaseVerificationService((command, directory) ->
                new ReleaseVerificationService.ProcessResult(7, "cannot run\n"));

        ReleaseVerificationException exception = assertThrows(
                ReleaseVerificationException.class,
                () -> service.verify(List.of(archive.archivePath()), projectDir.resolve("verify"), "0.1.0"));

        assertTrue(exception.getMessage().contains(archive.archivePath().toString()));
        assertTrue(exception.getMessage().contains("`zolt --version` failed with exit code 7"));
        assertTrue(exception.getMessage().contains("cannot run"));
    }

    @Test
    void versionSmokeUnexpectedOutputReportsExpectedVersion() throws IOException {
        writeProjectFiles(projectDir);
        Path binary = writeBinary(projectDir, "target/native/zolt");
        ReleaseArchiveResult archive = new ReleaseArchiveService().assemble(
                projectDir,
                config(),
                ReleaseTarget.LINUX_X64,
                binary,
                Path.of("dist"));
        ReleaseVerificationService service = new ReleaseVerificationService((command, directory) ->
                new ReleaseVerificationService.ProcessResult(0, "0.1.0 extra\n"));

        ReleaseVerificationException exception = assertThrows(
                ReleaseVerificationException.class,
                () -> service.verify(List.of(archive.archivePath()), projectDir.resolve("verify-output"), "0.1.0"));

        assertTrue(exception.getMessage().contains("did not print only expected version 0.1.0"));
        assertTrue(exception.getMessage().contains("0.1.0 extra"));
    }

    @Test
    void initSmokeFailureReportsOutput() throws IOException {
        writeProjectFiles(projectDir);
        Path binary = writeBinary(projectDir, "target/native/zolt");
        ReleaseArchiveResult archive = new ReleaseArchiveService().assemble(
                projectDir,
                config(),
                ReleaseTarget.LINUX_X64,
                binary,
                Path.of("dist"));
        ReleaseVerificationService service = new ReleaseVerificationService((command, directory) -> {
            if (command.contains("init")) {
                return new ReleaseVerificationService.ProcessResult(2, "cannot init\n");
            }
            return new ReleaseVerificationService.ProcessResult(0, "0.1.0\n");
        });

        ReleaseVerificationException exception = assertThrows(
                ReleaseVerificationException.class,
                () -> service.verify(List.of(archive.archivePath()), projectDir.resolve("verify-init"), "0.1.0"));

        assertTrue(exception.getMessage().contains("`zolt init smoke` failed with exit code 2"));
        assertTrue(exception.getMessage().contains("cannot init"));
    }

    @Test
    void initSmokeSuccessMustCreateProjectConfig() throws IOException {
        writeProjectFiles(projectDir);
        Path binary = writeBinary(projectDir, "target/native/zolt");
        ReleaseArchiveResult archive = new ReleaseArchiveService().assemble(
                projectDir,
                config(),
                ReleaseTarget.LINUX_X64,
                binary,
                Path.of("dist"));
        ReleaseVerificationService service = new ReleaseVerificationService((command, directory) ->
                new ReleaseVerificationService.ProcessResult(0, "0.1.0\n"));

        ReleaseVerificationException exception = assertThrows(
                ReleaseVerificationException.class,
                () -> service.verify(List.of(archive.archivePath()), projectDir.resolve("verify-missing-init"), "0.1.0"));

        assertTrue(exception.getMessage().contains("`zolt init smoke` did not create smoke/zolt.toml"));
    }

    @Test
    void buildSmokeFailureReportsOutput() throws IOException {
        writeProjectFiles(projectDir);
        Path binary = writeBinary(projectDir, "target/native/zolt");
        ReleaseArchiveResult archive = new ReleaseArchiveService().assemble(
                projectDir,
                config(),
                ReleaseTarget.LINUX_X64,
                binary,
                Path.of("dist"));
        ReleaseVerificationService service = new ReleaseVerificationService((command, directory) -> {
            if (command.contains("init")) {
                Path cwd = Path.of(command.get(command.indexOf("--cwd") + 1));
                try {
                    Files.createDirectories(cwd.resolve("smoke"));
                    Files.writeString(cwd.resolve("smoke/zolt.toml"), "[project]\n");
                } catch (IOException exception) {
                    throw new AssertionError(exception);
                }
            }
            if (command.contains("build")) {
                return new ReleaseVerificationService.ProcessResult(3, "cannot build\n");
            }
            return new ReleaseVerificationService.ProcessResult(0, "0.1.0\n");
        });

        ReleaseVerificationException exception = assertThrows(
                ReleaseVerificationException.class,
                () -> service.verify(List.of(archive.archivePath()), projectDir.resolve("verify-build"), "0.1.0"));

        assertTrue(exception.getMessage().contains("`zolt build` on the initialized smoke project failed with exit code 3"));
        assertTrue(exception.getMessage().contains("cannot build"));
    }

    @Test
    void unsafeZipEntryFailsBeforeSmokeCommands() throws IOException {
        Path archive = projectDir.resolve("zolt-0.1.0-windows-x64.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("../evil.txt"));
            zip.write("oops".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Files.writeString(archive.resolveSibling(archive.getFileName() + ".sha256"),
                sha256(archive) + "  " + archive.getFileName() + "\n");

        ReleaseVerificationException exception = assertThrows(
                ReleaseVerificationException.class,
                () -> passingService().verify(List.of(archive), projectDir.resolve("verify"), "0.1.0"));

        assertTrue(exception.getMessage().contains("unsafe entry path"));
    }

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

    @Test
    void defaultProcessRunnerReportsUnexecutableArchiveBinary() throws IOException {
        Path archive = projectDir.resolve("zolt-0.1.0-windows-x64.zip");
        writeZip(
                archive,
                new ZipFile("zolt-0.1.0-windows-x64/bin/zolt.exe", "#!/definitely/missing/zolt\n"),
                new ZipFile("zolt-0.1.0-windows-x64/libexec/zolt-junit-worker.jar", "worker"),
                new ZipFile("zolt-0.1.0-windows-x64/libexec/zolt-javac-worker.jar", "worker"),
                new ZipFile("zolt-0.1.0-windows-x64/VERSION", "0.1.0\n"),
                new ZipFile("zolt-0.1.0-windows-x64/LICENSE", "license\n"),
                new ZipFile("zolt-0.1.0-windows-x64/NOTICE", "notice\n"),
                new ZipFile("zolt-0.1.0-windows-x64/THIRD_PARTY_NOTICES", "third party notices\n"));
        Files.writeString(archive.resolveSibling(archive.getFileName() + ".sha256"),
                sha256(archive) + "  " + archive.getFileName() + "\n");

        ReleaseVerificationException exception = assertThrows(
                ReleaseVerificationException.class,
                () -> new ReleaseVerificationService().verify(
                        List.of(archive),
                        projectDir.resolve("verify-unexecutable"),
                        "0.1.0"));

        assertTrue(exception.getMessage().contains("Could not run release verification command `"), exception.getMessage());
        assertTrue(exception.getMessage().contains("Check that the archive binary can be executed."), exception.getMessage());
    }

    private static void writeZip(Path archive, ZipFile... entries) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (ZipFile entry : entries) {
                zip.putNextEntry(new ZipEntry(entry.name()));
                zip.write(entry.content().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }

    private record ZipFile(String name, String content) {
    }
}

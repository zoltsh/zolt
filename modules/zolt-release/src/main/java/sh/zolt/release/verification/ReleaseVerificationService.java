package sh.zolt.release.verification;

import sh.zolt.release.ReleaseTarget;
import sh.zolt.release.archive.ReleaseArchiveUnpacker;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public final class ReleaseVerificationService {
    private static final String JUNIT_WORKER_ARCHIVE_NAME = "zolt-junit-worker.jar";
    private static final String JAVAC_WORKER_ARCHIVE_NAME = "zolt-javac-worker.jar";
    private static final List<String> LEGAL_DOCUMENT_NAMES = List.of("LICENSE", "NOTICE", "THIRD_PARTY_NOTICES");

    private final ProcessRunner processRunner;

    public ReleaseVerificationService() {
        this(ReleaseVerificationService::runProcess);
    }

    ReleaseVerificationService(ProcessRunner processRunner) {
        this.processRunner = processRunner;
    }

    public ReleaseVerificationResult verify(
            List<Path> archives,
            Path workDirectory,
            String expectedVersion) {
        if (archives.isEmpty()) {
            throw new ReleaseVerificationException(
                    "Release verification needs at least one archive. Pass an archive path.");
        }
        try {
            Files.createDirectories(workDirectory);
        } catch (IOException exception) {
            throw new ReleaseVerificationException(
                    "Could not create release verification work directory " + workDirectory + ". Check that it is writable.",
                    exception);
        }
        List<ReleaseVerificationResult.VerifiedArchive> verified = new ArrayList<>();
        for (Path archive : archives) {
            Path normalizedArchive = archive.normalize();
            try {
                verified.add(verifyArchive(normalizedArchive, workDirectory, expectedVersion));
            } catch (IOException exception) {
                throw archiveFailure(
                        normalizedArchive,
                        "could not unpack or read archive. Check that the archive is readable and not corrupt.");
            }
        }
        return new ReleaseVerificationResult(verified);
    }

    private ReleaseVerificationResult.VerifiedArchive verifyArchive(
            Path archive,
            Path workDirectory,
            String expectedVersion) throws IOException {
        if (!Files.isRegularFile(archive)) {
            throw archiveFailure(archive, "archive does not exist. Pass a valid release archive path.");
        }
        ReleaseTarget target = targetFromArchiveName(archive.getFileName().toString());
        verifyChecksum(archive);
        String rootName = rootName(archive.getFileName().toString(), target);
        Path unpackDirectory = Files.createTempDirectory(workDirectory, rootName + "-");
        ReleaseArchiveUnpacker.unpack(
                archive,
                unpackDirectory,
                target.archiveExtension().substring(1),
                message -> archiveFailure(archive, message));
        Path binary = unpackDirectory.resolve(rootName).resolve("bin").resolve(target.binaryName());
        if (!Files.isRegularFile(binary)) {
            throw archiveFailure(archive, "expected binary at " + binary + " after unpacking.");
        }
        binary.toFile().setExecutable(true);
        Path rootDirectory = unpackDirectory.resolve(rootName);
        verifyVersionMetadata(archive, rootDirectory, expectedVersion);
        verifyJunitWorker(archive, rootDirectory);
        verifyJavacWorker(archive, rootDirectory);
        verifyLegalDocuments(archive, rootDirectory);
        verifyVersion(archive, binary, expectedVersion);
        Path smokeProject = verifyInit(archive, binary, unpackDirectory);
        verifyBuild(archive, binary, unpackDirectory, smokeProject);
        return new ReleaseVerificationResult.VerifiedArchive(archive, unpackDirectory, binary);
    }

    private static ReleaseTarget targetFromArchiveName(String archiveName) {
        for (ReleaseTarget target : ReleaseTarget.values()) {
            if (archiveName.endsWith("-" + target.id() + target.archiveExtension())) {
                return target;
            }
        }
        throw new ReleaseVerificationException(
                "Could not infer release target from archive `" + archiveName
                        + "`. Expected suffixes for: " + ReleaseTarget.supportedTargets() + ".");
    }

    private static String rootName(String archiveName, ReleaseTarget target) {
        return archiveName.substring(0, archiveName.length() - target.archiveExtension().length());
    }

    private static void verifyChecksum(Path archive) throws IOException {
        Path checksumPath = archive.resolveSibling(archive.getFileName() + ".sha256");
        if (!Files.isRegularFile(checksumPath)) {
            throw archiveFailure(archive, "missing checksum sidecar " + checksumPath.getFileName() + ".");
        }
        String expected = Files.readString(checksumPath, StandardCharsets.UTF_8).trim().split("\\s+", 2)[0];
        String actual = sha256(archive);
        if (!actual.equals(expected)) {
            throw archiveFailure(archive, "SHA-256 mismatch. Expected " + expected + " but found " + actual + ".");
        }
    }

    private void verifyVersion(Path archive, Path binary, String expectedVersion) {
        ProcessResult result = processRunner.run(List.of(binary.toString(), "--version"), binary.getParent());
        if (result.exitCode() != 0) {
            throw archiveFailure(archive, "`zolt --version` failed with exit code "
                    + result.exitCode() + ". Output:\n" + result.output());
        }
        if (!result.output().trim().equals(expectedVersion)) {
            throw archiveFailure(archive, "`zolt --version` did not print only expected version "
                    + expectedVersion + ". Output:\n" + result.output());
        }
    }

    private static void verifyVersionMetadata(Path archive, Path rootDirectory, String expectedVersion) {
        Path versionFile = rootDirectory.resolve("VERSION");
        if (!Files.isRegularFile(versionFile)) {
            throw archiveFailure(archive, "expected VERSION metadata at " + versionFile + " after unpacking.");
        }
        try {
            String version = Files.readString(versionFile, StandardCharsets.UTF_8).trim();
            if (!version.equals(expectedVersion)) {
                throw archiveFailure(archive, "VERSION metadata did not match expected version "
                        + expectedVersion + ". Found " + version + ".");
            }
        } catch (IOException exception) {
            throw archiveFailure(archive, "could not read VERSION metadata at " + versionFile + ".");
        }
    }

    private static void verifyLegalDocuments(Path archive, Path rootDirectory) {
        for (String name : LEGAL_DOCUMENT_NAMES) {
            Path document = rootDirectory.resolve(name);
            if (!Files.isRegularFile(document)) {
                throw archiveFailure(archive, "expected " + name + " at " + document
                        + " after unpacking. Every release archive must carry "
                        + String.join(", ", LEGAL_DOCUMENT_NAMES)
                        + " so the distribution stays within its own license terms.");
            }
            String content;
            try {
                content = Files.readString(document, StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw archiveFailure(archive, "could not read " + name + " at " + document + ".");
            }
            if (content.isBlank()) {
                throw archiveFailure(archive, name + " at " + document
                        + " is empty. Ship the real notice text, not a placeholder.");
            }
        }
    }

    private static void verifyJunitWorker(Path archive, Path rootDirectory) {
        Path workerJar = rootDirectory.resolve("libexec").resolve(JUNIT_WORKER_ARCHIVE_NAME);
        if (!Files.isRegularFile(workerJar)) {
            throw archiveFailure(archive, "expected bundled JUnit worker at " + workerJar + " after unpacking.");
        }
    }

    private static void verifyJavacWorker(Path archive, Path rootDirectory) {
        Path workerJar = rootDirectory.resolve("libexec").resolve(JAVAC_WORKER_ARCHIVE_NAME);
        if (!Files.isRegularFile(workerJar)) {
            throw archiveFailure(archive, "expected bundled javac worker at " + workerJar + " after unpacking.");
        }
    }

    private Path verifyInit(Path archive, Path binary, Path unpackDirectory) {
        Path initDirectory = unpackDirectory.resolve("smoke-work");
        try {
            Files.createDirectories(initDirectory);
        } catch (IOException exception) {
            throw archiveFailure(archive, "could not create smoke work directory " + initDirectory + ".");
        }
        ProcessResult result = processRunner.run(
                List.of(binary.toString(), "init", "--cwd", initDirectory.toString(), "smoke"),
                unpackDirectory);
        if (result.exitCode() != 0) {
            throw archiveFailure(archive, "`zolt init smoke` failed with exit code "
                    + result.exitCode() + ". Output:\n" + result.output());
        }
        if (!Files.isRegularFile(initDirectory.resolve("smoke/zolt.toml"))) {
            throw archiveFailure(archive, "`zolt init smoke` did not create smoke/zolt.toml.");
        }
        return initDirectory.resolve("smoke");
    }

    private void verifyBuild(Path archive, Path binary, Path unpackDirectory, Path smokeProject) {
        ProcessResult result = processRunner.run(
                List.of(binary.toString(), "build", "--cwd", smokeProject.toString()),
                unpackDirectory);
        if (result.exitCode() != 0) {
            throw archiveFailure(archive, "`zolt build` on the initialized smoke project failed with exit code "
                    + result.exitCode() + ". Output:\n" + result.output());
        }
    }

    private static String sha256(Path archivePath) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(archivePath)) {
                byte[] buffer = new byte[8192];
                int read = input.read(buffer);
                while (read >= 0) {
                    digest.update(buffer, 0, read);
                    read = input.read(buffer);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new ReleaseVerificationException(
                    "Could not verify SHA-256 checksum. SHA-256 is missing from this JDK.",
                    exception);
        }
    }

    private static ReleaseVerificationException archiveFailure(Path archive, String message) {
        return new ReleaseVerificationException("Release archive verification failed for " + archive + ": " + message);
    }

    private static ProcessResult runProcess(List<String> command, Path directory) {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(directory.toFile());
        processBuilder.redirectErrorStream(true);
        try {
            Process process = processBuilder.start();
            String output;
            try (InputStream input = process.getInputStream()) {
                output = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            return new ProcessResult(process.waitFor(), output);
        } catch (IOException exception) {
            throw new ReleaseVerificationException(
                    "Could not run release verification command `"
                            + String.join(" ", command)
                            + "`. Check that the archive binary can be executed.",
                    exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ReleaseVerificationException("Release verification was interrupted.", exception);
        }
    }

    @FunctionalInterface
    interface ProcessRunner {
        ProcessResult run(List<String> command, Path directory);
    }

    record ProcessResult(int exitCode, String output) {
    }
}

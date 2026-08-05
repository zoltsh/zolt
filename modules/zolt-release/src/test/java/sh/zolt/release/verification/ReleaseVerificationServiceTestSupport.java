package sh.zolt.release.verification;

import sh.zolt.project.BuildSettings;
import sh.zolt.project.NativeSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class ReleaseVerificationServiceTestSupport {
    private ReleaseVerificationServiceTestSupport() {
    }

    static void writeProjectFiles(Path projectDir) throws IOException {
        Files.writeString(projectDir.resolve("README.md"), "# Demo\n");
        writeLegalDocuments(projectDir);
        Path workerJar = projectDir.resolve("target/libexec/zolt-junit-worker.jar");
        Files.createDirectories(workerJar.getParent());
        Files.writeString(workerJar, "worker");
        Files.writeString(projectDir.resolve("target/libexec/zolt-javac-worker.jar"), "worker");
    }

    static void writeLegalDocuments(Path projectDir) throws IOException {
        Files.writeString(projectDir.resolve("LICENSE"), "license\n");
        Files.writeString(projectDir.resolve("NOTICE"), "notice\n");
        Files.writeString(projectDir.resolve("THIRD_PARTY_NOTICES"), "third party notices\n");
    }

    static Path writeBinary(Path projectDir, String path) throws IOException {
        Path binary = projectDir.resolve(path);
        Files.createDirectories(binary.getParent());
        Files.writeString(binary, "native");
        return Path.of(path);
    }

    static ReleaseVerificationService passingService() {
        return new ReleaseVerificationService((command, directory) -> {
            if (command.contains("init")) {
                Path cwd = Path.of(command.get(command.indexOf("--cwd") + 1));
                try {
                    Files.createDirectories(cwd.resolve("smoke"));
                    Files.writeString(cwd.resolve("smoke/zolt.toml"), "[project]\n");
                } catch (IOException exception) {
                    throw new AssertionError(exception);
                }
            }
            return new ReleaseVerificationService.ProcessResult(0, "0.1.0\n");
        });
    }

    static ReleaseVerificationService smokeAwareService() {
        return new ReleaseVerificationService((command, directory) -> {
            if (command.contains("init")) {
                Path cwd = Path.of(command.get(command.indexOf("--cwd") + 1));
                try {
                    Files.createDirectories(cwd.resolve("smoke"));
                    Files.writeString(cwd.resolve("smoke/zolt.toml"), "[project]\n");
                } catch (IOException exception) {
                    throw new AssertionError(exception);
                }
                return new ReleaseVerificationService.ProcessResult(0, "Created Zolt project at smoke\n");
            }
            return new ReleaseVerificationService.ProcessResult(0, "0.1.0\n");
        });
    }

    static ProjectConfig config() {
        return ProjectConfigs.withDirectDependencies(
                new ProjectMetadata("zolt", "0.1.0", "sh.zolt", currentJavaMajorVersion(), Optional.of("sh.zolt.Main")),
                Map.of("central", "https://repo.maven.apache.org/maven2"),
                Map.of(),
                Map.of(),
                BuildSettings.defaults(),
                new NativeSettings("zolt", "target/native", List.of("--no-fallback")));
    }

    static String sha256(Path archivePath) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(archivePath)));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static String currentJavaMajorVersion() {
        String version = System.getProperty("java.version");
        String[] parts = version.split("[._+-]", -1);
        if (parts.length >= 2 && "1".equals(parts[0])) {
            return parts[1];
        }
        return parts[0];
    }
}

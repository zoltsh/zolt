package sh.zolt.build.generatedsource;

import sh.zolt.build.BuildException;
import sh.zolt.project.GeneratedSourceStep;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

final class OpenApiGeneratedSourceCache {
    private static final String FINGERPRINT_VERSION = "1";

    GenerationCacheState state(
            Path projectRoot,
            Path output,
            List<Path> toolClasspath,
            String scope,
            GeneratedSourceStep step) {
        return new GenerationCacheState(
                output.resolve(".zolt-openapi-" + scope + "-" + step.id() + ".fingerprint"),
                output.resolve(".zolt-openapi-" + scope + "-" + step.id() + ".log"),
                producerFingerprint(projectRoot, toolClasspath, scope, step));
    }

    boolean isCurrent(Path output, GenerationCacheState state) {
        return Files.isDirectory(output)
                && Files.isRegularFile(state.fingerprint())
                && readFingerprint(state.fingerprint()).equals(state.fingerprintSha256());
    }

    void writeFingerprint(GenerationCacheState state) {
        try {
            Files.writeString(state.fingerprint(), state.fingerprintSha256(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new BuildException(
                    "Could not write OpenAPI generation fingerprint at "
                            + state.fingerprint()
                            + ". Check filesystem permissions.",
                    exception);
        }
    }

    void writeLog(GenerationCacheState state, String output) {
        try {
            Files.writeString(state.log(), output, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new BuildException(
                    "Could not write OpenAPI generation log at "
                            + state.log()
                            + ". Check filesystem permissions.",
                    exception);
        }
    }

    static String producerFingerprint(
            Path projectRoot,
            List<Path> toolClasspath,
            String scope,
            GeneratedSourceStep step) {
        StringBuilder content = new StringBuilder();
        content.append("version=").append(FINGERPRINT_VERSION).append('\n');
        content.append("scope=").append(scope).append('\n');
        content.append("step=").append(step).append('\n');
        content.append("[toolClasspath]\n");
        toolClasspath.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .sorted()
                .forEach(path -> content
                        .append(relative(projectRoot, path))
                        .append('|')
                        .append(fileHash(path))
                        .append('\n'));
        content.append("[inputs]\n");
        step.inputs().stream()
                .map(input -> projectRoot.resolve(input).normalize())
                .sorted()
                .forEach(path -> content
                        .append(relative(projectRoot, path))
                        .append('|')
                        .append(fileHash(path))
                        .append('\n'));
        step.openApi().config().ifPresent(value -> content
                .append("config=")
                .append(value)
                .append('|')
                .append(fileHash(projectRoot.resolve(value).normalize()))
                .append('\n'));
        step.openApi().templateDir().ifPresent(value -> content
                .append("templateDir=")
                .append(value)
                .append('|')
                .append(fileHash(projectRoot.resolve(value).normalize()))
                .append('\n'));
        return sha256(content.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String readFingerprint(Path fingerprint) {
        try {
            return Files.readString(fingerprint);
        } catch (IOException exception) {
            return "";
        }
    }

    private static String fileHash(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (Files.isDirectory(normalized)) {
            return directoryHash(normalized);
        }
        if (!Files.isRegularFile(normalized)) {
            return "missing";
        }
        try {
            return sha256(Files.readAllBytes(normalized));
        } catch (IOException exception) {
            throw new BuildException(
                    "Could not fingerprint OpenAPI input "
                            + normalized
                            + ". Check that it is readable.",
                    exception);
        }
    }

    private static String directoryHash(Path directory) {
        try (Stream<Path> paths = Files.walk(directory)) {
            StringBuilder content = new StringBuilder();
            paths.filter(Files::isRegularFile)
                    .map(Path::normalize)
                    .sorted()
                    .forEach(path -> content
                            .append(directory.relativize(path).toString().replace('\\', '/'))
                            .append('|')
                            .append(fileHash(path))
                            .append('\n'));
            return sha256(content.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new BuildException(
                    "Could not fingerprint OpenAPI directory "
                            + directory
                            + ". Check that it is readable.",
                    exception);
        }
    }

    private static String relative(Path projectRoot, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.startsWith(projectRoot)) {
            return projectRoot.relativize(normalized).toString().replace('\\', '/');
        }
        return normalized.toString().replace('\\', '/');
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new BuildException("Could not compute OpenAPI fingerprint because SHA-256 is unavailable.", exception);
        }
    }

    record GenerationCacheState(Path fingerprint, Path log, String fingerprintSha256) {
    }
}

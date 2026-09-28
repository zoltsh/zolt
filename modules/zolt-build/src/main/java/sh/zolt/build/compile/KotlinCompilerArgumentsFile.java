package sh.zolt.build.compile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** A short-lived UTF-8 response file containing arguments for the Kotlin compiler. */
final class KotlinCompilerArgumentsFile implements AutoCloseable {
    private static final String ARGUMENT_FILE_PREFIX = "zolt-kotlinc-";
    private static final String ARGUMENT_FILE_SUFFIX = ".args";
    private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY_PERMISSIONS =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));

    private final Path path;

    private KotlinCompilerArgumentsFile(Path path) {
        this.path = path;
    }

    static KotlinCompilerArgumentsFile create(List<String> arguments) throws IOException {
        Objects.requireNonNull(arguments, "Kotlin compiler arguments are required.");
        Path temporaryPath = createTemporaryFile();
        try {
            Files.writeString(temporaryPath, encode(arguments), StandardCharsets.UTF_8);
            return new KotlinCompilerArgumentsFile(temporaryPath);
        } catch (IOException | RuntimeException | Error failure) {
            deleteAfterFailedWrite(temporaryPath, failure);
            throw failure;
        }
    }

    static String encode(List<String> arguments) {
        Objects.requireNonNull(arguments, "Kotlin compiler arguments are required.");
        StringBuilder encoded = new StringBuilder();
        for (String argument : arguments) {
            String value = Objects.requireNonNull(
                    argument,
                    "Kotlin compiler arguments must not contain null values.");
            encoded.append('"');
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                if (character == '\\' || character == '"') {
                    encoded.append('\\');
                }
                encoded.append(character);
            }
            encoded.append('"').append('\n');
        }
        return encoded.toString();
    }

    String commandArgument() {
        return "@" + path;
    }

    @Override
    public void close() throws IOException {
        Files.deleteIfExists(path);
    }

    private static void deleteAfterFailedWrite(Path path, Throwable failure) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException | RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private static Path createTemporaryFile() throws IOException {
        Path temporaryPath;
        try {
            temporaryPath = Files.createTempFile(
                    ARGUMENT_FILE_PREFIX,
                    ARGUMENT_FILE_SUFFIX,
                    OWNER_ONLY_PERMISSIONS);
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX providers use their native temporary-file access controls.
            temporaryPath = Files.createTempFile(ARGUMENT_FILE_PREFIX, ARGUMENT_FILE_SUFFIX);
        }
        return temporaryPath.toAbsolutePath().normalize();
    }
}

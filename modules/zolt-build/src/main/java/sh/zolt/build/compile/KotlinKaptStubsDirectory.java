package sh.zolt.build.compile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Stream;
import sh.zolt.build.KotlinCompileException;

/** Owner-private, short-lived Java stubs used only during one KAPT invocation. */
final class KotlinKaptStubsDirectory implements AutoCloseable {
    private static final String PREFIX = "zolt-kapt-stubs-";
    private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY_PERMISSIONS =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));

    private final Path path;

    private KotlinKaptStubsDirectory(Path path) {
        this.path = path;
    }

    static KotlinKaptStubsDirectory create() {
        try {
            Path path;
            try {
                path = Files.createTempDirectory(PREFIX, OWNER_ONLY_PERMISSIONS);
            } catch (UnsupportedOperationException ignored) {
                path = Files.createTempDirectory(PREFIX);
            }
            return new KotlinKaptStubsDirectory(path.toAbsolutePath().normalize());
        } catch (IOException exception) {
            throw new KotlinCompileException(
                    "Could not create the temporary KAPT stub directory. Check that the system temporary "
                            + "directory is writable and try again.",
                    exception);
        }
    }

    Path path() {
        return path;
    }

    @Override
    public void close() {
        try (Stream<Path> paths = Files.walk(path)) {
            for (Path candidate : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(candidate);
            }
        } catch (IOException exception) {
            throw new KotlinCompileException(
                    "Could not remove the temporary KAPT stub directory " + path
                            + ". Remove it manually and check temporary-directory permissions.",
                    exception);
        }
    }
}

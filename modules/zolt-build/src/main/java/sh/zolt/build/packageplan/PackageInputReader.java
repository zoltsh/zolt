package sh.zolt.build.packageplan;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The single seam through which package inputs are opened, so reads per file can be counted.
 */
@FunctionalInterface
public interface PackageInputReader {
    InputStream open(Path path) throws IOException;

    static PackageInputReader files() {
        return Files::newInputStream;
    }
}

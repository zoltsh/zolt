package sh.zolt.build.packaging;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Bytes of one archive entry, written straight into the archive from wherever they already live.
 */
@FunctionalInterface
public interface PackageEntryContent {
    void writeTo(OutputStream output) throws IOException;
}

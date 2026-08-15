package sh.zolt.build.nativeimage;

import sh.zolt.build.NativeImageException;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** Same-filesystem staging and atomic publication for one native binary. */
final class NativeBinaryPublication {
    private NativeBinaryPublication() {
    }

    static Path stagingPath(Path outputBinary) {
        Path name = outputBinary.getFileName();
        String prefix = name == null ? "native" : name.toString();
        return outputBinary.resolveSibling(
                "." + prefix + ".zolt-staging-" + UUID.randomUUID());
    }

    static void requireCandidate(Path stagingBinary, Path finalBinary) {
        if (!Files.isRegularFile(stagingBinary)) {
            throw new NativeImageException(
                    "Native Image completed but did not create expected binary at "
                            + finalBinary
                            + ". Review the native-image output and retry.");
        }
        if (!Files.isExecutable(stagingBinary)) {
            throw new NativeImageException(
                    "Native Image created a non-executable candidate for "
                            + finalBinary
                            + ". Review native-image permissions and retry.");
        }
    }

    static void publish(Path stagingBinary, Path finalBinary) {
        try {
            Files.move(
                    stagingBinary,
                    finalBinary,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new NativeImageException(
                    "Could not atomically publish Native Image binary at "
                            + finalBinary
                            + ". Keep the native output on one filesystem and retry.",
                    exception);
        } catch (IOException exception) {
            throw new NativeImageException(
                    "Could not publish Native Image binary at "
                            + finalBinary
                            + ". The previous binary was preserved; check filesystem permissions and retry.",
                    exception);
        }
    }

    static void removeStaging(Path stagingBinary) {
        try {
            Files.deleteIfExists(stagingBinary);
        } catch (IOException exception) {
            throw new NativeImageException(
                    "Could not remove failed Native Image staging binary at "
                            + stagingBinary
                            + ". Check filesystem permissions and remove it before retrying.",
                    exception);
        }
    }
}

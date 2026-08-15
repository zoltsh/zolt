package sh.zolt.build.nativeimage;

import sh.zolt.build.NativeImageException;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Incremental durable output sink for native-image. */
final class NativeImageLog implements AutoCloseable {
    private final Path path;
    private final BufferedWriter writer;

    private NativeImageLog(Path path, BufferedWriter writer) {
        this.path = path;
        this.writer = writer;
    }

    static NativeImageLog open(Path path) {
        try {
            return new NativeImageLog(path, Files.newBufferedWriter(path, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw failure(path, exception);
        }
    }

    void append(String chunk) {
        try {
            writer.write(chunk);
            writer.flush();
        } catch (IOException exception) {
            throw failure(path, exception);
        }
    }

    @Override
    public void close() {
        try {
            writer.close();
        } catch (IOException exception) {
            throw failure(path, exception);
        }
    }

    private static NativeImageException failure(Path path, IOException exception) {
        return new NativeImageException(
                "Could not write Native Image log at "
                        + path
                        + ". Check that the output directory is writable.",
                exception);
    }
}

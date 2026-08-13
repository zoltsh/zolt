package sh.zolt.maven.repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** One bounded HTTP response body stored in a unique temporary file. */
record DownloadedRepositoryBody(Path path, long size, String sha256) implements AutoCloseable {
    DownloadedRepositoryBody {
        Objects.requireNonNull(path, "path");
        if (size < 0 || sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Downloaded repository body requires a size and SHA-256 digest.");
        }
    }

    @Override
    public void close() {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best-effort cleanup; the cache move consumes successful bodies.
        }
    }
}

package sh.zolt.cache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

record StagedLocalArtifact(Path path, String sha256, long length) {
    void deleteIfPresent() {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }
}

package sh.zolt.maven.repository;

import java.io.IOException;
import java.nio.file.Files;

final class RepositoryArtifactTestSupport {
    private RepositoryArtifactTestSupport() {
    }

    static byte[] artifactBytes(RepositoryArtifact artifact) {
        try (artifact) {
            return Files.readAllBytes(artifact.temporaryPath());
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }
}

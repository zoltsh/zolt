package sh.zolt.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RepositoryCacheScopeTest {
    @TempDir
    private Path tempDir;

    @Test
    void overlayScopeIncludesKindIdAndCanonicalRoot() throws IOException {
        Path firstRoot = Files.createDirectories(tempDir.resolve("first"));
        Path secondRoot = Files.createDirectories(tempDir.resolve("second"));
        RepositoryCacheScope first = RepositoryCacheScope.forOverlay(
                "MAVEN_LOCAL", "maven-local", firstRoot);

        assertEquals(
                first,
                RepositoryCacheScope.forOverlay(
                        "MAVEN_LOCAL", "maven-local", firstRoot.resolve(".")));
        assertNotEquals(
                first,
                RepositoryCacheScope.forOverlay(
                        "MAVEN_LOCAL", "maven-local", secondRoot));
        assertNotEquals(
                first,
                RepositoryCacheScope.forOverlay(
                        "OTHER", "maven-local", firstRoot));
        assertNotEquals(
                first,
                RepositoryCacheScope.forOverlay(
                        "MAVEN_LOCAL", "other", firstRoot));
    }

    @Test
    void missingOverlayRootCannotProduceAStableScope() {
        assertThrows(
                ArtifactCacheException.class,
                () -> RepositoryCacheScope.forOverlay(
                        "MAVEN_LOCAL", "maven-local", tempDir.resolve("missing")));
    }
}

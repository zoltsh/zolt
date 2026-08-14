package sh.zolt.lockfile;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import sh.zolt.error.ActionableException;

final class ContentAddressedLockCapabilityTest {
    @Test
    void refusesReadableLocksThatPredateContentAddressedCachePaths() {
        for (int version = 1; version < ContentAddressedLockCapability.MINIMUM_VERSION; version++) {
            ZoltLockfile lockfile = new ZoltLockfile(version, List.of(), List.of());

            ActionableException exception = assertThrows(
                    ActionableException.class,
                    () -> ContentAddressedLockCapability.requireArtifactCachePaths(
                            lockfile, "zolt resolve"),
                    "version " + version);

            assertTrue(exception.getMessage().contains("version " + version));
            assertTrue(exception.getMessage().contains("content-addressed artifact cache path"));
            assertTrue(exception.getMessage().contains("zolt resolve"));
        }
    }

    @Test
    void acceptsTheFirstCapableSchemaAndNewerSchemas() {
        assertDoesNotThrow(() -> ContentAddressedLockCapability.requireArtifactCachePaths(
                new ZoltLockfile(ContentAddressedLockCapability.MINIMUM_VERSION, List.of(), List.of()),
                "zolt resolve"));
        assertDoesNotThrow(() -> ContentAddressedLockCapability.requireArtifactCachePaths(
                new ZoltLockfile(ContentAddressedLockCapability.MINIMUM_VERSION + 1, List.of(), List.of()),
                "zolt resolve"));
    }
}

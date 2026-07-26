package sh.zolt.lockfile;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.error.ActionableException;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class WorkspaceGraphLockCapabilityTest {
    @Test
    void refusesReadableLocksThatPredateOptionalBoundaryEvidence() {
        for (int version = 1; version < WorkspaceGraphLockCapability.MINIMUM_VERSION; version++) {
            ZoltLockfile lockfile = new ZoltLockfile(version, List.of(), List.of());

            ActionableException exception = assertThrows(
                    ActionableException.class,
                    () -> WorkspaceGraphLockCapability.requireMemberGraphEvidence(lockfile),
                    "version " + version);

            assertTrue(exception.getMessage().contains("version " + version));
            assertTrue(exception.getMessage().contains("optional-boundary"));
            assertTrue(exception.getMessage().contains("zolt resolve --workspace"));
        }
    }

    @Test
    void acceptsTheFirstCapableSchemaAndNewerSchemas() {
        assertDoesNotThrow(() -> WorkspaceGraphLockCapability.requireMemberGraphEvidence(
                new ZoltLockfile(WorkspaceGraphLockCapability.MINIMUM_VERSION, List.of(), List.of())));
        assertDoesNotThrow(() -> WorkspaceGraphLockCapability.requireMemberGraphEvidence(
                new ZoltLockfile(WorkspaceGraphLockCapability.MINIMUM_VERSION + 1, List.of(), List.of())));
    }

    @Test
    void refusesVersionFiveExternalPackagesWithoutMemberAttribution() {
        ZoltLockfile lockfile = new ZoltLockfile(
                WorkspaceGraphLockCapability.MINIMUM_VERSION,
                List.of(new LockPackage(
                        new PackageId("org.example", "library"),
                        "1.0.0",
                        "central",
                        DependencyScope.COMPILE,
                        true,
                        Optional.of("library.jar"),
                        Optional.of("library.pom"),
                        Optional.empty(),
                        Optional.empty(),
                        List.of())),
                List.of());

        ActionableException exception = assertThrows(
                ActionableException.class,
                () -> WorkspaceGraphLockCapability
                        .requireMemberGraphEvidence(lockfile));

        assertTrue(exception.getMessage().contains("without member attribution"));
        assertTrue(exception.getMessage().contains("zolt resolve --workspace"));
    }
}

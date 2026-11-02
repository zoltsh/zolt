package sh.zolt.toolchain.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.error.ActionableException;
import sh.zolt.net.NetworkTransport;
import sh.zolt.toolchain.store.ToolchainStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class JavaToolchainInstallRecoveryTest {
    @TempDir
    private Path tempDir;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedPublicationRestoresOrRetainsThePreviousInstallation(boolean failRestore) throws IOException {
        Path archive = ToolchainInstallFixtures.archive(tempDir.resolve("mirror"), ".", true);
        var locked = ToolchainInstallFixtures.locked(archive, "21", ".");
        var store = new ToolchainStore(tempDir.resolve("store"));
        Path installation = store.installRoot(locked);
        Files.createDirectories(installation);
        Files.writeString(installation.resolve("sentinel"), "recoverable prior state");
        var moves = new AtomicInteger();
        var installer = new JavaToolchainInstaller(new JavaToolchainDownloader(NetworkTransport.direct(),
                ToolchainDownloadMirror.of(archive.getParent().toUri().toString())), new JavaToolchainArchiveExtractor(),
                (source, target) -> {
                    int operation = moves.incrementAndGet();
                    if (operation == 2 || (operation == 3 && failRestore)) {
                        throw new IOException("injected rename failure");
                    }
                    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
                });

        ActionableException failure = assertThrows(ActionableException.class,
                () -> installer.install(locked, ToolchainInstallFixtures.artifact(locked), store));

        assertEquals(3, moves.get());
        try (var paths = Files.list(installation.getParent())) {
            var attempts = paths.filter(path -> path.getFileName().toString().startsWith(".jdk-attempt-")).toList();
            if (failRestore) {
                assertFalse(Files.exists(installation));
                assertEquals(1, attempts.size());
                Path saved = attempts.getFirst().resolve("previous");
                assertEquals("recoverable prior state", Files.readString(saved.resolve("sentinel")));
                assertTrue(failure.getMessage().contains(saved.toString()), failure.getMessage());
                assertTrue(failure.getMessage().contains("restore"), failure.getMessage());
            } else {
                assertTrue(attempts.isEmpty());
                assertEquals("recoverable prior state", Files.readString(installation.resolve("sentinel")));
            }
        }
    }
}

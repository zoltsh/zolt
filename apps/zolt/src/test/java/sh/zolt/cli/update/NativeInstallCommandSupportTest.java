package sh.zolt.cli.update;

import static org.junit.jupiter.api.Assertions.assertEquals;

import sh.zolt.cli.command.update.NativeInstallCommandSupport;
import sh.zolt.home.UserGlobalDirectory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class NativeInstallCommandSupportTest {
    @TempDir
    private Path tempDir;

    @Test
    void infersCustomInstallRootFromActiveVersionedExecutable() throws IOException {
        Path installRoot = tempDir.resolve("custom-zolt");
        Path executable = installRoot.resolve("versions/0.1.0/bin/zolt");
        Files.createDirectories(executable.getParent());
        Files.writeString(executable, "native zolt");
        Path binLink = installRoot.resolve("bin/zolt");
        Files.createDirectories(binLink.getParent());
        Files.createSymbolicLink(binLink, Path.of("../versions/0.1.0/bin/zolt"));

        assertEquals(
                installRoot.toRealPath(),
                NativeInstallCommandSupport.effectiveInstallRoot(null, binLink));
        assertEquals(
                installRoot.toRealPath(),
                NativeInstallCommandSupport.effectiveInstallRoot(null, executable));
    }

    @Test
    void capturedExecutableKeepsCustomRootAfterAnotherTransactionSwitchesLauncher() throws IOException {
        Path root = tempDir.resolve("custom install with spaces");
        Path first = root.resolve("versions/0.1.0/bin/zolt");
        Path second = root.resolve("versions/0.1.1/bin/zolt");
        for (Path executable : java.util.List.of(first, second)) {
            Files.createDirectories(executable.getParent());
            Files.writeString(executable, "native zolt");
        }
        Path launcher = root.resolve("bin/zolt");
        Files.createDirectories(launcher.getParent());
        Files.createSymbolicLink(launcher, Path.of("../versions/0.1.0/bin/zolt"));
        Path alias = Files.createSymbolicLink(tempDir.resolve("root alias"), root);
        Path captured = alias.resolve("bin/zolt").toRealPath();
        Files.delete(launcher);
        Files.createSymbolicLink(launcher, Path.of("../versions/0.1.1/bin/zolt"));

        assertEquals(root.toRealPath(), NativeInstallCommandSupport.effectiveInstallRoot(null, captured));
        assertEquals(root.toRealPath(), NativeInstallCommandSupport.effectiveInstallRoot(null, alias.resolve("bin/zolt")));
    }

    @Test
    void externalAndAttemptExecutablesDoNotInferAnInstallationRoot() throws IOException {
        Path root = tempDir.resolve("managed");
        Path active = root.resolve("versions/1.0.0/bin/zolt");
        Files.createDirectories(active.getParent());
        Files.writeString(active, "native zolt");
        Files.createDirectories(root.resolve("bin"));
        Files.createSymbolicLink(root.resolve("bin/zolt"), Path.of("../versions/1.0.0/bin/zolt"));
        for (Path caller : java.util.List.of(tempDir.resolve("outside/zolt"),
                root.resolve("versions/.install-attempt-staged/bin/zolt"), root.resolve("versions/1.0.1.tmp/bin/zolt"))) {
            Files.createDirectories(caller.getParent());
            Files.writeString(caller, "unmanaged zolt");
            assertEquals(UserGlobalDirectory.root(), NativeInstallCommandSupport.effectiveInstallRoot(null, caller));
        }
    }
}

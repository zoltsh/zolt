package sh.zolt.cli.update;

import static org.junit.jupiter.api.Assertions.assertEquals;

import sh.zolt.cli.command.update.NativeInstallCommandSupport;
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
}

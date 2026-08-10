package sh.zolt.cli.command.dependency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import sh.zolt.toml.ZoltConfigException;
import java.io.File;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CanonicalUpdatePathTest {
    @TempDir
    private Path tempDir;

    @Test
    void joinsNativePathElementsWithCanonicalPosixSeparators() {
        assertEquals(
                "apps/api/zolt.toml",
                CanonicalUpdatePath.relative(tempDir, tempDir.resolve("apps").resolve("api").resolve("zolt.toml")));
    }

    @Test
    void rejectsLiteralBackslashesOnUnix() {
        assumeTrue(File.separatorChar != '\\');
        Path literalBackslash = tempDir.resolve("member\\name").resolve("zolt.toml");

        assertThrows(
                ZoltConfigException.class,
                () -> CanonicalUpdatePath.relative(tempDir, literalBackslash));
    }
}

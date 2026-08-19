package sh.zolt.arch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class ZoltRuntimeClosureScriptTest {
    @Test
    void capturedRuntimeIsExternalVerifiedAndRunnable() throws IOException, InterruptedException {
        Path root = RepositoryPaths.root();
        Process process = new ProcessBuilder(
                        "bash",
                        root.resolve("scripts/capture-zolt-runtime-closure-test").toString())
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start();

        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "runtime closure script test timed out");
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("Zolt runtime closure script test passed"), output);
    }
}

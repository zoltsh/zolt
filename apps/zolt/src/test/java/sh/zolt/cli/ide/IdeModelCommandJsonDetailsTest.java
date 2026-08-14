package sh.zolt.cli.ide;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.ContentAddressedLockTestSupport.cachedJar;
import static sh.zolt.cli.ide.IdeModelCommandJsonTestSupport.cacheRoot;
import static sh.zolt.cli.ide.IdeModelCommandJsonTestSupport.jsonPathValue;
import static sh.zolt.cli.ide.IdeModelCommandJsonTestSupport.root;
import static sh.zolt.cli.ide.IdeModelCommandJsonTestSupport.writeLockfile;
import static sh.zolt.cli.ide.IdeModelCommandJsonTestSupport.writeProject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IdeModelCommandJsonDetailsTest {
    @TempDir
    private Path tempDir;

    @Test
    void ideModelPrintsClasspathFrameworkAndDiagnosticsDetailsInJson() throws IOException {
        Path projectDir = writeProject(tempDir);
        Path cacheRoot = cacheRoot(tempDir);
        writeLockfile(projectDir, cacheRoot);

        CommandResult result = execute(
                "ide",
                "model",
                "--cwd", projectDir.toString(),
                "--cache-root", cacheRoot.toString(),
                "--format", "json");

        Path projectRoot = root(projectDir);
        Path appJar = cachedJar(projectDir.resolve("zolt.lock"), cacheRoot, "com.example:app");
        Path testJar = cachedJar(projectDir.resolve("zolt.lock"), cacheRoot, "com.example:test-lib");
        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        String json = result.stdout();
        assertTrue(json.contains("\"classpaths\": {\n    \"compile\": ["));
        assertTrue(json.contains(jsonPathValue(appJar)));
        assertTrue(json.contains(jsonPathValue(testJar)), json);
        assertTrue(json.contains("\"frameworks\": {\n    \"quarkus\": {"));
        assertTrue(json.contains("\"augmentationStatus\": \"disabled\""));
        assertTrue(json.contains("\"diagnostics\": [\n    {\n      \"severity\": \"error\""));
        assertTrue(json.contains("\"code\": \"LOCKFILE_STALE\""));
        assertTrue(json.contains("\"nextStep\": \"Run zolt resolve.\""));
    }
}

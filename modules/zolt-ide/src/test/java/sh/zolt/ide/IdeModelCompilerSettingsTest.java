package sh.zolt.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IdeModelCompilerSettingsTest {
    private final IdeModelService service = new IdeModelService();

    @TempDir
    private Path tempDir;

    @Test
    void exportsCompilerSettingsForEditors() throws IOException {
        Path projectDir = tempDir.resolve("compiler-settings");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "compiler-settings"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [compiler]
                generatedSources = "build/generated/main"
                generatedTestSources = "build/generated/test"
                release = "17"
                encoding = "UTF-8"
                args = ["-Xlint:deprecation", "-parameters"]
                testArgs = ["-Xlint:unchecked"]
                """);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        IdeModel model = service.export(projectDir, tempDir.resolve("cache"));

        Path root = projectDir.toAbsolutePath().normalize();
        assertEquals(new IdeModel.CompilerInfo(
                "17",
                "17",
                "UTF-8",
                List.of("-Xlint:deprecation", "-parameters"),
                List.of("-Xlint:unchecked"),
                root.resolve("build/generated/main"),
                root.resolve("build/generated/test")),
                model.compiler());
        String json = new IdeModelJsonWriter().write(model);
        assertTrue(json.contains("\"compiler\": {"));
        assertTrue(json.contains("\"effectiveRelease\": \"17\""));
        assertTrue(json.contains("\"args\": ["));
        assertTrue(json.contains("\"testArgs\": ["));
    }
}

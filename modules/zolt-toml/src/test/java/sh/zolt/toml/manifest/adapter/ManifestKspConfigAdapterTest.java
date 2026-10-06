package sh.zolt.toml.manifest.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.ProjectConfig;

final class ManifestKspConfigAdapterTest {
    @Test
    void projectsAPublicMainKspDeclarationIntoTheBuildContract() {
        ProjectConfig config = FinalManifests.load("""
                [project]
                name = "ksp-demo"
                version = "1.0.0"
                group = "com.example"
                java = 21

                [generated.tools.ksp]
                version = "2.2.0-2.0.2"
                coordinates = [
                    { coordinate = "com.example:symbol-processor", version = "1.4.0" },
                ]

                [generated.main.symbols]
                kind = "ksp"
                options = { "room.schemaLocation" = "schemas" }
                """);

        GeneratedSourceStep step = config.build().generatedMainSources().getFirst();

        assertEquals(GeneratedSourceKind.KSP, step.kind());
        assertEquals("kotlin", step.language());
        assertEquals("target/generated/ksp/main/symbols", step.output());
        assertEquals(List.of(), step.inputs());
        assertTrue(step.required());
        assertTrue(step.clean());
        assertEquals("ksp", step.ksp().toolName());
        assertEquals("2.2.0-2.0.2", step.ksp().version().orElseThrow());
        assertEquals(
                "com.example:symbol-processor",
                step.ksp().processors().getFirst().coordinate());
        assertEquals(Map.of("room.schemaLocation", "schemas"), step.ksp().options());
    }

    @Test
    void projectsAPublicTestKspDeclarationIntoItsOwnedTestContract() {
        ProjectConfig config = FinalManifests.load("""
                [project]
                name = "ksp-demo"
                version = "1.0.0"
                group = "com.example"
                java = 21

                [generated.tools.ksp]
                version = "2.2.0-2.0.2"
                coordinates = [
                    { coordinate = "com.example:test-processor", version = "1.4.0" },
                ]

                [generated.test.fixtures]
                kind = "ksp"
                options = { mode = "test" }
                """);

        GeneratedSourceStep step = config.build().generatedTestSources().getFirst();

        assertEquals(GeneratedSourceKind.KSP, step.kind());
        assertEquals("kotlin", step.language());
        assertEquals("target/generated/ksp/test/fixtures", step.output());
        assertEquals(List.of(), step.inputs());
        assertTrue(step.required());
        assertTrue(step.clean());
        assertEquals("ksp", step.ksp().toolName());
        assertEquals(Map.of("mode", "test"), step.ksp().options());
    }
}

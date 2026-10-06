package sh.zolt.toml.manifest.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static sh.zolt.toml.manifest.ManifestSemanticTestSupport.decodeAuthoredManifest;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.GeneratedStepSettings;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.authored.AuthoredGeneratedSources;
import sh.zolt.manifest.authored.AuthoredGeneratedStep;
import sh.zolt.manifest.authored.AuthoredKspStep;

final class ManifestGeneratedKspStepWriterTest {
    @Test
    void emitsCanonicalMainKspFieldsAndOmitsTheDerivedOutput() {
        AuthoredKspStep step = new AuthoredKspStep(
                GeneratedStepSettings.defaultsOmitted(),
                Optional.of(new LocalId("symbols-tool")),
                Optional.of(new ManifestRelativePath("target/generated/ksp/main/symbols")),
                Map.of("room.schemaLocation", "schemas", "alpha", ""));

        String output = write(Map.of(new LocalId("symbols"), step), Map.of());
        assertEquals(
                """
                [generated.main.symbols]
                kind = "ksp"
                tool = "symbols-tool"
                options = { alpha = "", "room.schemaLocation" = "schemas" }
                """,
                output);
        AuthoredGeneratedSources decoded = decodeAuthoredManifest("""
                [project]
                name = "round-trip"

                [generated.tools.symbols-tool]
                kind = "ksp"
                version = "2.2.0-2.0.2"
                coordinates = [
                    { coordinate = "com.example:processor", version = "1.0.0" },
                ]

                """ + output).generated().orElseThrow();
        assertEquals(output, write(decoded.main(), Map.of()));
    }

    @Test
    void emitsCanonicalTestKspFieldsAndOmitsTheDerivedOutput() {
        AuthoredKspStep step = new AuthoredKspStep(
                GeneratedStepSettings.defaultsOmitted(),
                Optional.empty(),
                Optional.of(new ManifestRelativePath("target/generated/ksp/test/symbols")),
                Map.of("fixture", "enabled"));

        String output = write(Map.of(), Map.of(new LocalId("symbols"), step));

        assertEquals(
                """
                [generated.test.symbols]
                kind = "ksp"
                options = { fixture = "enabled" }
                """,
                output);
    }

    private static String write(
            Map<LocalId, AuthoredGeneratedStep> main,
            Map<LocalId, AuthoredGeneratedStep> test) {
        ManifestTomlEmitter emitter = new ManifestTomlEmitter();
        new ManifestGeneratedStepsWriter().write(
                emitter, main, test, new ManifestRelativePath("target"));
        return emitter.finish();
    }
}

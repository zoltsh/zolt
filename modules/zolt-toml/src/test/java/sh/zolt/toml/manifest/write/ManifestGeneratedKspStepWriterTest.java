package sh.zolt.toml.manifest.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.GeneratedStepSettings;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestRelativePath;
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

        assertEquals(
                """
                [generated.main.symbols]
                kind = "ksp"
                tool = "symbols-tool"
                options = { alpha = "", "room.schemaLocation" = "schemas" }
                """,
                write(Map.of(new LocalId("symbols"), step), Map.of()));
    }

    @Test
    void rejectsKspStepsInTheUnimplementedTestLane() {
        AuthoredKspStep step = new AuthoredKspStep(
                GeneratedStepSettings.defaultsOmitted(),
                Optional.empty(),
                Optional.empty(),
                Map.of());

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> write(Map.of(), Map.of(new LocalId("symbols"), step)));

        assertEquals(
                "KSP generated steps are currently supported only in [generated.main].",
                failure.getMessage());
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

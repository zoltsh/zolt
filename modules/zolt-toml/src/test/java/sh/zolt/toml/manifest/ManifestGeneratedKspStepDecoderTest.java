package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.authored.AuthoredKspStep;
import sh.zolt.toml.ZoltConfigException;

final class ManifestGeneratedKspStepDecoderTest {
    @Test
    void decodesTheBoundedMainKspSurface() {
        AuthoredKspStep step = assertInstanceOf(
                AuthoredKspStep.class,
                decode("""
                        [generated.main.symbols]
                        kind = "ksp"
                        tool = "symbols-tool"
                        output = "target/generated/ksp/main/custom"
                        options = { "room.schemaLocation" = "schemas", alpha = "" }
                        required = true
                        clean = true
                        """).main().orElseThrow().get(new LocalId("symbols")));

        assertEquals(Optional.of(new LocalId("symbols-tool")), step.tool());
        assertEquals(
                Optional.of(new ManifestRelativePath("target/generated/ksp/main/custom")),
                step.output());
        assertEquals(Map.of("alpha", "", "room.schemaLocation", "schemas"), step.options());
        assertEquals(Optional.of(true), step.settings().required());
        assertEquals(Optional.of(true), step.settings().clean());
        assertTrue(step.settings().language().isEmpty());
    }

    @Test
    void rejectsKspOutsideMainAtTheKindField() {
        assertFailure(
                """
                [generated.test.symbols]
                kind = "ksp"
                """,
                "generated.test.symbols.kind",
                "supported only in [generated.main]");
    }

    @Test
    void rejectsOptionalOrRetainedKspExecutionPromisesAtTheirFields() {
        assertFailure(
                """
                [generated.main.symbols]
                kind = "ksp"
                required = false
                """,
                "generated.main.symbols.required",
                "must be required");
        assertFailure(
                """
                [generated.main.symbols]
                kind = "ksp"
                clean = false
                """,
                "generated.main.symbols.clean",
                "must clean its owned output");
    }

    private static ManifestGeneratedStepsDecoder.Decoded decode(String source) {
        return new ManifestGeneratedStepsDecoder().decode(
                ManifestSemanticTestSupport.index(source));
    }

    private static void assertFailure(String source, String... details) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class, () -> decode(source));
        for (String detail : details) {
            assertTrue(failure.getMessage().contains(detail), failure.getMessage());
        }
    }
}

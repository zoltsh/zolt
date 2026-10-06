package sh.zolt.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class KspGenerationSettingsTest {
    @Test
    void generatedSourceStepsDefaultKspSettingsWithoutBreakingLegacyConstruction() {
        GeneratedSourceStep step = new GeneratedSourceStep(
                "generated",
                GeneratedSourceKind.DECLARED_ROOT,
                "java",
                "target/generated",
                List.of("src/generated"),
                true,
                false,
                OpenApiGenerationSettings.empty(),
                ProtobufGenerationSettings.empty(),
                ExecGenerationSettings.empty());

        assertEquals(KspGenerationSettings.empty(), step.ksp());
    }

    @Test
    void modelsSeparateDeterministicToolGroupsAndProtectsInputs() {
        ArrayList<KspProcessorSettings> processors = new ArrayList<>(List.of(
                new KspProcessorSettings(
                        "com.example:processor", "1.2.3", Optional.of(" processor.version "))));
        Map<String, String> options = new LinkedHashMap<>();
        options.put("zeta", "last");
        options.put("alpha", "first");

        KspGenerationSettings settings = new KspGenerationSettings(
                " symbols ",
                Optional.of(" 2.2.0-2.0.2 "),
                Optional.of(" ksp.version "),
                processors,
                options);
        processors.clear();
        options.put("later", "ignored");

        assertTrue(settings.configured());
        assertEquals("symbols", settings.toolName());
        assertEquals(Optional.of("2.2.0-2.0.2"), settings.version());
        assertEquals(Optional.of("ksp.version"), settings.versionRef());
        assertEquals(Optional.of("processor.version"), settings.processors().getFirst().versionRef());
        assertEquals(List.of("alpha", "zeta"), settings.options().keySet().stream().toList());
        assertEquals("ksp:symbols:engine", settings.engineGroup());
        assertEquals("ksp:symbols:processors", settings.processorGroup());
        assertThrows(UnsupportedOperationException.class, () -> settings.processors().clear());
        assertThrows(UnsupportedOperationException.class, () -> settings.options().clear());
    }

    @Test
    void emptySettingsAreExplicitlyUnconfigured() {
        KspGenerationSettings settings = KspGenerationSettings.empty();

        assertFalse(settings.configured());
        assertThrows(IllegalStateException.class, settings::engineGroup);
        assertThrows(IllegalStateException.class, settings::processorGroup);
    }

    @Test
    void rejectsIncompleteAndAmbiguousToolContracts() {
        IllegalArgumentException missingVersion = assertThrows(
                IllegalArgumentException.class,
                () -> new KspGenerationSettings(
                        "ksp",
                        Optional.empty(),
                        Optional.empty(),
                        List.of(processor("com.example:first")),
                        Map.of()));
        assertEquals("KSP tool version is required.", missingVersion.getMessage());

        IllegalArgumentException duplicate = assertThrows(
                IllegalArgumentException.class,
                () -> new KspGenerationSettings(
                        "ksp",
                        Optional.of("2.2.0-2.0.2"),
                        Optional.empty(),
                        List.of(processor("com.example:first"), processor("com.example:first")),
                        Map.of()));
        assertEquals("Duplicate KSP processor coordinate `com.example:first`.", duplicate.getMessage());
    }

    @Test
    void validatesProcessorCoordinatesAndOptionsAtTheBoundary() {
        IllegalArgumentException blankCoordinate = assertThrows(
                IllegalArgumentException.class,
                () -> new KspProcessorSettings(" ", "1.0.0", Optional.empty()));
        assertEquals("KSP processor coordinate is required.", blankCoordinate.getMessage());

        Map<String, String> options = new LinkedHashMap<>();
        options.put("option", "value");
        options.put("invalid", null);
        IllegalArgumentException nullOption = assertThrows(
                IllegalArgumentException.class,
                () -> new KspGenerationSettings(
                        "ksp",
                        Optional.of("2.2.0-2.0.2"),
                        Optional.empty(),
                        List.of(processor("com.example:first")),
                        options));
        assertEquals("KSP processor option `invalid` must have a value.", nullOption.getMessage());
    }

    private static KspProcessorSettings processor(String coordinate) {
        return new KspProcessorSettings(coordinate, "1.0.0", Optional.empty());
    }
}

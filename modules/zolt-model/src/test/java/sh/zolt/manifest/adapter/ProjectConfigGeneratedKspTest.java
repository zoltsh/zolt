package sh.zolt.manifest.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.DependencySelector;
import sh.zolt.manifest.GeneratedArtifactRequest;
import sh.zolt.manifest.GeneratedStepSettings;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.VersionAliasValue;
import sh.zolt.manifest.authored.AuthoredGeneratedPresets;
import sh.zolt.manifest.authored.AuthoredGeneratedSources;
import sh.zolt.manifest.authored.AuthoredGeneratedTool;
import sh.zolt.manifest.authored.AuthoredGeneratedTools;
import sh.zolt.manifest.authored.AuthoredKspStep;
import sh.zolt.manifest.effective.EffectiveValue;
import sh.zolt.project.KspGenerationSettings;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;

final class ProjectConfigGeneratedKspTest {
    private static final LocalId KSP = new LocalId("ksp");
    private static final LocalId KSP_VERSION = new LocalId("ksp-version");
    private static final LocalId PROCESSOR_VERSION = new LocalId("processor-version");

    @Test
    void resolvesEngineAndProcessorAliasesWithoutLosingTheirReferences() {
        AuthoredGeneratedSources sources = sources(
                KSP,
                new DependencySelector.VersionReference(KSP_VERSION),
                new DependencySelector.VersionReference(PROCESSOR_VERSION));

        KspGenerationSettings settings = ProjectConfigGeneratedKsp.settings(
                Optional.empty(),
                sources,
                Map.of(
                        KSP_VERSION, EffectiveValue.builtIn(new VersionAliasValue("2.2.0-2.0.2")),
                        PROCESSOR_VERSION, EffectiveValue.builtIn(new VersionAliasValue("1.4.0"))),
                Map.of("room.schemaLocation", "schemas"));

        assertEquals("ksp", settings.toolName());
        assertEquals(Optional.of("2.2.0-2.0.2"), settings.version());
        assertEquals(Optional.of("ksp-version"), settings.versionRef());
        assertEquals("com.example:symbol-processor", settings.processors().getFirst().coordinate());
        assertEquals("1.4.0", settings.processors().getFirst().version());
        assertEquals(Optional.of("processor-version"), settings.processors().getFirst().versionRef());
        assertEquals(Map.of("room.schemaLocation", "schemas"), settings.options());
    }

    @Test
    void resolvesASelectedCustomToolAndFixedVersions() {
        LocalId custom = new LocalId("symbols");
        AuthoredGeneratedSources sources = sources(
                custom,
                new DependencySelector.FixedVersion("2.2.0-2.0.2"),
                new DependencySelector.FixedVersion("1.4.0"));

        KspGenerationSettings settings = ProjectConfigGeneratedKsp.settings(
                Optional.of(custom), sources, Map.of(), Map.of());

        assertEquals("symbols", settings.toolName());
        assertEquals(Optional.empty(), settings.versionRef());
        assertEquals(Optional.empty(), settings.processors().getFirst().versionRef());
        assertEquals("ksp:symbols:engine", settings.engineGroup());
        assertEquals("ksp:symbols:processors", settings.processorGroup());
    }

    @Test
    void projectsAMainKspStepWithOwnedDerivedOutput() {
        AuthoredGeneratedTool.Ksp tool = new AuthoredGeneratedTool.Ksp(
                new DependencySelector.FixedVersion("2.2.0-2.0.2"),
                List.of(new GeneratedArtifactRequest(
                        new DependencyCoordinate("com.example:symbol-processor"),
                        new DependencySelector.FixedVersion("1.4.0"))));
        AuthoredGeneratedSources sources = new AuthoredGeneratedSources(
                new AuthoredGeneratedTools(Map.of(KSP, tool)),
                AuthoredGeneratedPresets.empty(),
                Map.of(new LocalId("symbols"), new AuthoredKspStep(
                        GeneratedStepSettings.defaultsOmitted(),
                        Optional.empty(),
                        Optional.empty(),
                        Map.of("room.schemaLocation", "schemas"))),
                Map.of());

        GeneratedSourceStep step = ProjectConfigGenerated.main(
                Optional.of(sources), "target", Map.of()).getFirst();

        assertEquals(GeneratedSourceKind.KSP, step.kind());
        assertEquals("kotlin", step.language());
        assertEquals("target/generated/ksp/main/symbols", step.output());
        assertEquals(List.of(), step.inputs());
        assertEquals(Map.of("room.schemaLocation", "schemas"), step.ksp().options());
    }

    @Test
    void projectsATestKspStepWithOwnedDerivedOutput() {
        AuthoredGeneratedTool.Ksp tool = new AuthoredGeneratedTool.Ksp(
                new DependencySelector.FixedVersion("2.2.0-2.0.2"),
                List.of(new GeneratedArtifactRequest(
                        new DependencyCoordinate("com.example:test-processor"),
                        new DependencySelector.FixedVersion("1.4.0"))));
        AuthoredGeneratedSources sources = new AuthoredGeneratedSources(
                new AuthoredGeneratedTools(Map.of(KSP, tool)),
                AuthoredGeneratedPresets.empty(),
                Map.of(),
                Map.of(new LocalId("fixtures"), new AuthoredKspStep(
                        GeneratedStepSettings.defaultsOmitted(),
                        Optional.empty(),
                        Optional.empty(),
                        Map.of("mode", "test"))));

        GeneratedSourceStep step = ProjectConfigGenerated.test(
                Optional.of(sources), "target", Map.of()).getFirst();

        assertEquals(GeneratedSourceKind.KSP, step.kind());
        assertEquals("kotlin", step.language());
        assertEquals("target/generated/ksp/test/fixtures", step.output());
        assertEquals(List.of(), step.inputs());
        assertEquals(Map.of("mode", "test"), step.ksp().options());
    }

    @Test
    void rejectsMissingOrWrongKindToolReferences() {
        AuthoredGeneratedSources sources = new AuthoredGeneratedSources(
                new AuthoredGeneratedTools(Map.of(
                        new LocalId("openapi"),
                        new AuthoredGeneratedTool.OpenApi(Optional.empty(), Optional.empty()))),
                AuthoredGeneratedPresets.empty(),
                Map.of(),
                Map.of());

        IllegalArgumentException missing = assertThrows(
                IllegalArgumentException.class,
                () -> ProjectConfigGeneratedKsp.settings(
                        Optional.empty(), sources, Map.of(), Map.of()));
        assertEquals(
                "Generated KSP step requires `ksp` to be a declared KSP tool.",
                missing.getMessage());

        IllegalArgumentException wrongKind = assertThrows(
                IllegalArgumentException.class,
                () -> ProjectConfigGeneratedKsp.settings(
                        Optional.of(new LocalId("openapi")), sources, Map.of(), Map.of()));
        assertEquals(
                "Generated KSP step requires `openapi` to be a declared KSP tool.",
                wrongKind.getMessage());
    }

    private static AuthoredGeneratedSources sources(
            LocalId id,
            DependencySelector kspVersion,
            DependencySelector processorVersion) {
        AuthoredGeneratedTool.Ksp tool = new AuthoredGeneratedTool.Ksp(
                kspVersion,
                List.of(new GeneratedArtifactRequest(
                        new DependencyCoordinate("com.example:symbol-processor"),
                        processorVersion)));
        return new AuthoredGeneratedSources(
                new AuthoredGeneratedTools(Map.of(id, tool)),
                AuthoredGeneratedPresets.empty(),
                Map.of(),
                Map.of());
    }
}

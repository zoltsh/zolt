package sh.zolt.toml.manifest;

import static sh.zolt.toml.schema.FinalManifestGeneratedPresetFields.GENERATED_PRESET_ADDITIONAL_PROPERTIES;
import static sh.zolt.toml.schema.FinalManifestGeneratedPresetFields.GENERATED_PRESET_API_PACKAGE;
import static sh.zolt.toml.schema.FinalManifestGeneratedPresetFields.GENERATED_PRESET_CONFIG;
import static sh.zolt.toml.schema.FinalManifestGeneratedPresetFields.GENERATED_PRESET_CONFIG_OPTIONS;
import static sh.zolt.toml.schema.FinalManifestGeneratedPresetFields.GENERATED_PRESET_GENERATOR;
import static sh.zolt.toml.schema.FinalManifestGeneratedPresetFields.GENERATED_PRESET_GLOBAL_PROPERTIES;
import static sh.zolt.toml.schema.FinalManifestGeneratedPresetFields.GENERATED_PRESET_IMPORT_MAPPINGS;
import static sh.zolt.toml.schema.FinalManifestGeneratedPresetFields.GENERATED_PRESET_INVOKER_PACKAGE;
import static sh.zolt.toml.schema.FinalManifestGeneratedPresetFields.GENERATED_PRESET_KIND;
import static sh.zolt.toml.schema.FinalManifestGeneratedPresetFields.GENERATED_PRESET_LIBRARY;
import static sh.zolt.toml.schema.FinalManifestGeneratedPresetFields.GENERATED_PRESET_MODEL_PACKAGE;
import static sh.zolt.toml.schema.FinalManifestGeneratedPresetFields.GENERATED_PRESET_OPTIONS;
import static sh.zolt.toml.schema.FinalManifestGeneratedPresetFields.GENERATED_PRESET_TEMPLATE_DIR;
import static sh.zolt.toml.schema.FinalManifestGeneratedPresetFields.GENERATED_PRESET_TYPE_MAPPINGS;
import static sh.zolt.toml.schema.FinalManifestGeneratedPresetFields.GENERATED_PRESET_VALIDATE_SPEC;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.authored.AuthoredDeclaredRootStep;
import sh.zolt.manifest.authored.AuthoredExecStep;
import sh.zolt.manifest.authored.AuthoredGeneratedPresets;
import sh.zolt.manifest.authored.AuthoredGeneratedSources;
import sh.zolt.manifest.authored.AuthoredGeneratedStep;
import sh.zolt.manifest.authored.AuthoredGeneratedTools;
import sh.zolt.manifest.authored.AuthoredOpenApiOptions;
import sh.zolt.manifest.authored.AuthoredOpenApiStep;
import sh.zolt.manifest.authored.AuthoredProtobufStep;
import sh.zolt.toml.schema.FinalManifestPaths;
import sh.zolt.toml.schema.ManifestField;

/** Composes authored generated tools, presets, and steps without applying effective defaults. */
final class ManifestGeneratedSourcesDecoder {
    private final ManifestGeneratedToolsDecoder tools = new ManifestGeneratedToolsDecoder();
    private final ManifestGeneratedPresetsDecoder presets = new ManifestGeneratedPresetsDecoder();
    private final ManifestGeneratedStepsDecoder steps = new ManifestGeneratedStepsDecoder();

    Optional<AuthoredGeneratedSources> decode(
            ManifestDecodeIndex index,
            GeneratedSourcesPresenceObserver observer) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        Objects.requireNonNull(
                observer, "Authored generated sources presence observer is required.");
        firstPresentCollection(index).ifPresent(section ->
                ManifestSemanticDiagnostics.construct(section, () -> {
                    AuthoredGeneratedSources empty = AuthoredGeneratedSources.empty();
                    observer.present(empty);
                    return empty;
                }));
        Optional<AuthoredGeneratedTools> decodedTools = tools.decode(index);
        Optional<AuthoredGeneratedPresets> decodedPresets = presets.decode(index);
        AuthoredGeneratedTools declarations = decodedTools.orElseGet(AuthoredGeneratedTools::empty);
        AuthoredGeneratedPresets options = decodedPresets.orElseGet(AuthoredGeneratedPresets::empty);
        Composition composition = new Composition(index, declarations, options);
        ManifestGeneratedStepsDecoder.Decoded decodedSteps =
                steps.decode(index, composition::decoded);
        if (decodedTools.isEmpty()
                && decodedPresets.isEmpty()
                && decodedSteps.main().isEmpty()
                && decodedSteps.test().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(composition.generated());
    }

    private static Optional<ValidatedManifestSection> firstPresentCollection(
            ManifestDecodeIndex index) {
        return index.section(FinalManifestPaths.GENERATED_TOOLS)
                .or(() -> index.section(FinalManifestPaths.GENERATED_PRESETS))
                .or(() -> index.section(FinalManifestPaths.GENERATED_MAIN_STEPS))
                .or(() -> index.section(FinalManifestPaths.GENERATED_TEST_STEPS));
    }

    private static AuthoredGeneratedSources appendStep(
            Composition composition,
            ManifestGeneratedStepFields fields,
            ManifestDecodeIndex.SectionEntry entry,
            LocalId id,
            AuthoredGeneratedStep step) {
        Map<LocalId, AuthoredGeneratedStep> lane = composition.lane(fields);
        if (step instanceof AuthoredOpenApiStep openApi) {
            return appendOpenApi(
                    composition, entry, fields, id, openApi, lane);
        }
        lane.put(id, step);
        Supplier<AuthoredGeneratedSources> aggregate = composition::aggregate;
        if (step instanceof AuthoredProtobufStep protobuf) {
            return constructAtOptionalTool(
                    composition.index, entry, fields, protobuf.tool().isPresent(), aggregate);
        }
        if (step instanceof AuthoredExecStep) {
            return constructAtField(
                    composition.index,
                    entry,
                    fields.field(ManifestGeneratedStepFields.Slot.TOOL),
                    aggregate);
        }
        if (step instanceof AuthoredDeclaredRootStep) {
            return ManifestSemanticDiagnostics.construct(entry.section(), aggregate);
        }
        throw new IllegalStateException(
                "Decoded manifest contains an unsupported generated-step model type.");
    }

    private static AuthoredGeneratedSources appendOpenApi(
            Composition composition,
            ManifestDecodeIndex.SectionEntry entry,
            ManifestGeneratedStepFields fields,
            LocalId id,
            AuthoredOpenApiStep step,
            Map<LocalId, AuthoredGeneratedStep> lane) {
        AuthoredOpenApiStep toolProbe = step.preset().isEmpty()
                ? step
                : new AuthoredOpenApiStep(
                        step.settings(),
                        step.tool(),
                        step.input(),
                        step.output(),
                        Optional.empty(),
                        step.overrides());
        lane.put(id, toolProbe);
        Supplier<AuthoredGeneratedSources> aggregate = composition::aggregate;
        AuthoredGeneratedSources generated = constructAtOptionalTool(
                composition.index, entry, fields, step.tool().isPresent(), aggregate);
        if (step.preset().isEmpty()) {
            return generated;
        }
        lane.put(id, step);
        return constructAtField(
                composition.index,
                entry,
                fields.field(ManifestGeneratedStepFields.Slot.PRESET),
                aggregate);
    }

    private static AuthoredGeneratedSources constructAtOptionalTool(
            ManifestDecodeIndex index,
            ManifestDecodeIndex.SectionEntry entry,
            ManifestGeneratedStepFields fields,
            boolean explicit,
            Supplier<AuthoredGeneratedSources> factory) {
        if (!explicit) {
            return ManifestSemanticDiagnostics.construct(entry.section(), factory);
        }
        return constructAtField(
                index, entry, fields.field(ManifestGeneratedStepFields.Slot.TOOL), factory);
    }

    private static AuthoredGeneratedSources constructAtField(
            ManifestDecodeIndex index,
            ManifestDecodeIndex.SectionEntry entry,
            ManifestField handle,
            Supplier<AuthoredGeneratedSources> factory) {
        ValidatedManifestField field = index.field(entry, handle).orElseThrow(() ->
                new IllegalStateException(
                        "Decoded generated step is missing retained field `" + handle.path() + "`."));
        return ManifestSemanticDiagnostics.construct(field, factory);
    }

    @FunctionalInterface
    interface GeneratedSourcesPresenceObserver {
        void present(AuthoredGeneratedSources generated);
    }

    private static final class Composition {
        private final ManifestDecodeIndex index;
        private final AuthoredGeneratedTools tools;
        private final AuthoredGeneratedPresets presets;
        private final LinkedHashMap<LocalId, AuthoredGeneratedStep> main = new LinkedHashMap<>();
        private final LinkedHashMap<LocalId, AuthoredGeneratedStep> test = new LinkedHashMap<>();
        private AuthoredGeneratedSources generated;

        private Composition(
                ManifestDecodeIndex index,
                AuthoredGeneratedTools tools,
                AuthoredGeneratedPresets presets) {
            this.index = index;
            this.tools = tools;
            this.presets = presets;
            this.generated = aggregate();
        }

        private void decoded(
                ManifestGeneratedStepFields fields,
                ManifestDecodeIndex.SectionEntry entry,
                LocalId id,
                AuthoredGeneratedStep step) {
            generated = appendStep(this, fields, entry, id, step);
        }

        private Map<LocalId, AuthoredGeneratedStep> lane(ManifestGeneratedStepFields fields) {
            if (fields == ManifestGeneratedStepFields.MAIN) {
                return main;
            }
            if (fields == ManifestGeneratedStepFields.TEST) {
                return test;
            }
            throw new IllegalStateException("Generated-step lane is not registered.");
        }

        private AuthoredGeneratedSources aggregate() {
            return new AuthoredGeneratedSources(tools, presets, main, test);
        }

        private AuthoredGeneratedSources generated() {
            return generated;
        }
    }
}

/** Decodes authored generated-source presets without composing them into steps. */
final class ManifestGeneratedPresetsDecoder {
    private static final ManifestOpenApiOptionsDecoder.Fields OPEN_API_FIELDS =
            new ManifestOpenApiOptionsDecoder.Fields(
                    GENERATED_PRESET_GENERATOR,
                    GENERATED_PRESET_LIBRARY,
                    GENERATED_PRESET_API_PACKAGE,
                    GENERATED_PRESET_MODEL_PACKAGE,
                    GENERATED_PRESET_INVOKER_PACKAGE,
                    GENERATED_PRESET_CONFIG,
                    GENERATED_PRESET_TEMPLATE_DIR,
                    GENERATED_PRESET_VALIDATE_SPEC,
                    GENERATED_PRESET_OPTIONS,
                    GENERATED_PRESET_ADDITIONAL_PROPERTIES,
                    GENERATED_PRESET_CONFIG_OPTIONS,
                    GENERATED_PRESET_GLOBAL_PROPERTIES,
                    GENERATED_PRESET_TYPE_MAPPINGS,
                    GENERATED_PRESET_IMPORT_MAPPINGS);

    Optional<AuthoredGeneratedPresets> decode(ManifestDecodeIndex index) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        return index.section(FinalManifestPaths.GENERATED_PRESETS).map(ignored -> {
            LinkedHashMap<LocalId, AuthoredOpenApiOptions> declarations = new LinkedHashMap<>();
            AuthoredGeneratedPresets presets = AuthoredGeneratedPresets.empty();
            for (ManifestDecodeIndex.SectionEntry entry :
                    index.sectionEntries(FinalManifestPaths.GENERATED_PRESET)) {
                Row row = new Row(index, entry);
                LocalId id = ManifestSemanticDiagnostics.construct(
                        entry.section(), () -> new LocalId(entry.key()));
                requireOpenApiKind(row);
                AuthoredOpenApiOptions options = ManifestOpenApiOptionsDecoder.decode(
                        index, entry, OPEN_API_FIELDS);
                if (declarations.put(id, options) != null) {
                    throw new IllegalStateException(
                            "Validated manifest contains duplicate generated preset `" + id + "`.");
                }
                presets = ManifestSemanticDiagnostics.construct(
                        entry.section(), () -> new AuthoredGeneratedPresets(declarations));
            }
            return presets;
        });
    }

    private static void requireOpenApiKind(Row row) {
        ValidatedManifestField field = row.required(GENERATED_PRESET_KIND);
        String value = ManifestTomlValues.string(field);
        if (!value.equals("openapi")) {
            throw new IllegalStateException(
                    "Final manifest schema accepted generated-preset kind `" + value
                            + "` at `" + field.path() + "` but the decoder does not recognize it.");
        }
    }

    private record Row(
            ManifestDecodeIndex index,
            ManifestDecodeIndex.SectionEntry entry) {
        private Row {
            Objects.requireNonNull(index, "Manifest decode index is required.");
            Objects.requireNonNull(entry, "Generated preset section entry is required.");
        }

        private ValidatedManifestField required(ManifestField handle) {
            return ManifestSemanticDiagnostics.requiredField(index, entry, handle);
        }
    }
}

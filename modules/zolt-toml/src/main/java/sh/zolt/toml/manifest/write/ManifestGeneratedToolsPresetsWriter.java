package sh.zolt.toml.manifest.write;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.DependencySelector;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.authored.AuthoredGeneratedPresets;
import sh.zolt.manifest.authored.AuthoredGeneratedTool;
import sh.zolt.manifest.authored.AuthoredGeneratedTools;
import sh.zolt.manifest.authored.AuthoredOpenApiOptions;
import sh.zolt.toml.schema.FinalManifestGeneratedPresetFields;
import sh.zolt.toml.schema.FinalManifestGeneratedToolFields;
import sh.zolt.toml.schema.FinalManifestPaths;
import sh.zolt.toml.schema.FinalManifestSchema;
import sh.zolt.toml.schema.ManifestField;
import sh.zolt.toml.schema.ManifestPath;
import sh.zolt.toml.schema.ManifestSection;

/** Emits canonical generated-tool declarations and OpenAPI presets. */
final class ManifestGeneratedToolsPresetsWriter {
    private static final LocalId OPENAPI = new LocalId("openapi");
    private static final LocalId PROTOBUF = new LocalId("protobuf");
    private static final ManifestSection TOOL = section(FinalManifestPaths.GENERATED_TOOL);
    private static final ManifestSection PRESET = section(FinalManifestPaths.GENERATED_PRESET);
    private static final ManifestGeneratedOpenApiOptionsWriter.Fields PRESET_FIELDS =
            new ManifestGeneratedOpenApiOptionsWriter.Fields(
                    FinalManifestGeneratedPresetFields.GENERATED_PRESET_GENERATOR,
                    FinalManifestGeneratedPresetFields.GENERATED_PRESET_LIBRARY,
                    FinalManifestGeneratedPresetFields.GENERATED_PRESET_API_PACKAGE,
                    FinalManifestGeneratedPresetFields.GENERATED_PRESET_MODEL_PACKAGE,
                    FinalManifestGeneratedPresetFields.GENERATED_PRESET_INVOKER_PACKAGE,
                    FinalManifestGeneratedPresetFields.GENERATED_PRESET_CONFIG,
                    FinalManifestGeneratedPresetFields.GENERATED_PRESET_TEMPLATE_DIR,
                    FinalManifestGeneratedPresetFields.GENERATED_PRESET_VALIDATE_SPEC,
                    FinalManifestGeneratedPresetFields.GENERATED_PRESET_OPTIONS,
                    FinalManifestGeneratedPresetFields.GENERATED_PRESET_ADDITIONAL_PROPERTIES,
                    FinalManifestGeneratedPresetFields.GENERATED_PRESET_CONFIG_OPTIONS,
                    FinalManifestGeneratedPresetFields.GENERATED_PRESET_GLOBAL_PROPERTIES,
                    FinalManifestGeneratedPresetFields.GENERATED_PRESET_TYPE_MAPPINGS,
                    FinalManifestGeneratedPresetFields.GENERATED_PRESET_IMPORT_MAPPINGS);

    void write(
            ManifestTomlEmitter emitter,
            AuthoredGeneratedTools tools,
            AuthoredGeneratedPresets presets) {
        Objects.requireNonNull(emitter, "Manifest TOML emitter is required.");
        Objects.requireNonNull(tools, "Authored generated tools are required.");
        Objects.requireNonNull(presets, "Authored generated presets are required.");
        tools.declarations().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> writeTool(
                        emitter, entry.getKey(), entry.getValue()));
        presets.openApi().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> writePreset(
                        emitter, entry.getKey(), entry.getValue()));
    }

    private static void writeTool(
            ManifestTomlEmitter emitter, LocalId id, AuthoredGeneratedTool tool) {
        emitter.namedSection(TOOL, id.value());
        switch (tool) {
            case AuthoredGeneratedTool.OpenApi openApi -> writeOpenApiTool(
                    emitter, id, openApi);
            case AuthoredGeneratedTool.Protobuf protobuf -> writeProtobufTool(
                    emitter, id, protobuf);
            case AuthoredGeneratedTool.Jvm jvm -> writeJvmTool(emitter, jvm);
            case AuthoredGeneratedTool.Process process -> writeProcessTool(emitter, process);
        }
    }

    private static void writeOpenApiTool(
            ManifestTomlEmitter emitter,
            LocalId id,
            AuthoredGeneratedTool.OpenApi tool) {
        if (!id.equals(OPENAPI)) {
            emitter.field(FinalManifestGeneratedToolFields.GENERATED_TOOL_KIND, string("openapi"));
        }
        tool.coordinate().ifPresent(value -> emitter.field(
                FinalManifestGeneratedToolFields.GENERATED_TOOL_COORDINATE,
                string(value.value())));
        writeSelector(
                emitter,
                tool.version(),
                FinalManifestGeneratedToolFields.GENERATED_TOOL_VERSION,
                FinalManifestGeneratedToolFields.GENERATED_TOOL_VERSION_REF);
    }

    private static void writeProtobufTool(
            ManifestTomlEmitter emitter,
            LocalId id,
            AuthoredGeneratedTool.Protobuf tool) {
        if (!id.equals(PROTOBUF)) {
            emitter.field(FinalManifestGeneratedToolFields.GENERATED_TOOL_KIND, string("protobuf"));
        }
        tool.protocCoordinate().ifPresent(value -> emitter.field(
                FinalManifestGeneratedToolFields.GENERATED_TOOL_PROTOC_COORDINATE,
                string(value.value())));
        writeSelector(
                emitter,
                tool.protocVersion(),
                FinalManifestGeneratedToolFields.GENERATED_TOOL_PROTOC_VERSION,
                FinalManifestGeneratedToolFields.GENERATED_TOOL_PROTOC_VERSION_REF);
        tool.grpcCoordinate().ifPresent(value -> emitter.field(
                FinalManifestGeneratedToolFields.GENERATED_TOOL_GRPC_COORDINATE,
                string(value.value())));
        writeSelector(
                emitter,
                tool.grpcVersion(),
                FinalManifestGeneratedToolFields.GENERATED_TOOL_GRPC_VERSION,
                FinalManifestGeneratedToolFields.GENERATED_TOOL_GRPC_VERSION_REF);
    }

    private static void writeJvmTool(
            ManifestTomlEmitter emitter, AuthoredGeneratedTool.Jvm tool) {
        emitter.field(FinalManifestGeneratedToolFields.GENERATED_TOOL_KIND, string("jvm"));
        emitter.field(
                FinalManifestGeneratedToolFields.GENERATED_TOOL_COORDINATES,
                ManifestGeneratedWriterValues.artifactRequests(
                        FinalManifestGeneratedToolFields.GENERATED_TOOL_COORDINATES,
                        tool.coordinates()));
        emitter.field(
                FinalManifestGeneratedToolFields.GENERATED_TOOL_MAIN_CLASS,
                string(tool.mainClass().value()));
    }

    private static void writeProcessTool(
            ManifestTomlEmitter emitter, AuthoredGeneratedTool.Process tool) {
        emitter.field(FinalManifestGeneratedToolFields.GENERATED_TOOL_KIND, string("process"));
        emitter.field(
                FinalManifestGeneratedToolFields.GENERATED_TOOL_BINARY,
                string(tool.binary().value()));
        emitter.field(
                FinalManifestGeneratedToolFields.GENERATED_TOOL_VERSION_COMMAND,
                ManifestGeneratedWriterValues.strings(
                        FinalManifestGeneratedToolFields.GENERATED_TOOL_VERSION_COMMAND,
                        tool.versionCommand(),
                        value -> value));
        tool.versionExpect().ifPresent(value -> emitter.field(
                FinalManifestGeneratedToolFields.GENERATED_TOOL_VERSION_EXPECT,
                string(value.value())));
        emitter.field(
                FinalManifestGeneratedToolFields.GENERATED_TOOL_ALLOW_UNPINNED_TOOL,
                ManifestTomlValueEncoder.booleanValue(tool.allowUnpinnedTool()));
    }

    private static void writeSelector(
            ManifestTomlEmitter emitter,
            Optional<DependencySelector> selector,
            ManifestField fixedField,
            ManifestField referenceField) {
        selector.ifPresent(value -> {
            switch (value) {
                case DependencySelector.FixedVersion fixed ->
                    emitter.field(fixedField, string(fixed.value()));
                case DependencySelector.VersionReference reference ->
                    emitter.field(referenceField, string(reference.alias().value()));
                default -> throw new IllegalStateException(
                        "Authored generated tool has an unsupported selector.");
            }
        });
    }

    private static void writePreset(
            ManifestTomlEmitter emitter, LocalId id, AuthoredOpenApiOptions options) {
        emitter.namedSection(PRESET, id.value());
        emitter.field(FinalManifestGeneratedPresetFields.GENERATED_PRESET_KIND, string("openapi"));
        ManifestGeneratedOpenApiOptionsWriter.write(emitter, options, PRESET_FIELDS);
    }

    private static String string(String value) {
        return ManifestGeneratedWriterValues.string(value);
    }

    private static ManifestSection section(ManifestPath path) {
        return FinalManifestSchema.registry().section(path).orElseThrow();
    }
}

package sh.zolt.toml.manifest;

import static sh.zolt.toml.schema.FinalManifestGeneratedToolFields.GENERATED_TOOL_ALLOW_UNPINNED_TOOL;
import static sh.zolt.toml.schema.FinalManifestGeneratedToolFields.GENERATED_TOOL_BINARY;
import static sh.zolt.toml.schema.FinalManifestGeneratedToolFields.GENERATED_TOOL_COORDINATE;
import static sh.zolt.toml.schema.FinalManifestGeneratedToolFields.GENERATED_TOOL_COORDINATES;
import static sh.zolt.toml.schema.FinalManifestGeneratedToolFields.GENERATED_TOOL_GRPC_COORDINATE;
import static sh.zolt.toml.schema.FinalManifestGeneratedToolFields.GENERATED_TOOL_GRPC_VERSION;
import static sh.zolt.toml.schema.FinalManifestGeneratedToolFields.GENERATED_TOOL_GRPC_VERSION_REF;
import static sh.zolt.toml.schema.FinalManifestGeneratedToolFields.GENERATED_TOOL_KIND;
import static sh.zolt.toml.schema.FinalManifestGeneratedToolFields.GENERATED_TOOL_MAIN_CLASS;
import static sh.zolt.toml.schema.FinalManifestGeneratedToolFields.GENERATED_TOOL_PROTOC_COORDINATE;
import static sh.zolt.toml.schema.FinalManifestGeneratedToolFields.GENERATED_TOOL_PROTOC_VERSION;
import static sh.zolt.toml.schema.FinalManifestGeneratedToolFields.GENERATED_TOOL_PROTOC_VERSION_REF;
import static sh.zolt.toml.schema.FinalManifestGeneratedToolFields.GENERATED_TOOL_VERSION;
import static sh.zolt.toml.schema.FinalManifestGeneratedToolFields.GENERATED_TOOL_VERSION_COMMAND;
import static sh.zolt.toml.schema.FinalManifestGeneratedToolFields.GENERATED_TOOL_VERSION_EXPECT;
import static sh.zolt.toml.schema.FinalManifestGeneratedToolFields.GENERATED_TOOL_VERSION_REF;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.DependencySelector;
import sh.zolt.manifest.GeneratedArtifactRequest;
import sh.zolt.manifest.GeneratedProcessBinary;
import sh.zolt.manifest.GeneratedVersionExpectation;
import sh.zolt.manifest.JavaBinaryClassName;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.authored.AuthoredGeneratedTool;
import sh.zolt.manifest.authored.AuthoredGeneratedTools;
import sh.zolt.toml.schema.FinalManifestGeneratedToolFields;
import sh.zolt.toml.schema.FinalManifestObjectShapes;
import sh.zolt.toml.schema.FinalManifestPaths;
import sh.zolt.toml.schema.ManifestField;

/** Decodes built-in overrides and custom generated-tool declarations without resolving them. */
final class ManifestGeneratedToolsDecoder {
    private static final LocalId OPENAPI = new LocalId("openapi");
    private static final LocalId PROTOBUF = new LocalId("protobuf");
    private static final JavaBinaryClassName VALIDATION_MAIN_CLASS =
            new JavaBinaryClassName("zolt.validation.GeneratedTool");

    Optional<AuthoredGeneratedTools> decode(ManifestDecodeIndex index) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        return index.section(FinalManifestPaths.GENERATED_TOOLS).map(ignored -> {
            LinkedHashMap<LocalId, AuthoredGeneratedTool> declarations = new LinkedHashMap<>();
            AuthoredGeneratedTools tools = AuthoredGeneratedTools.empty();
            for (ManifestDecodeIndex.SectionEntry entry :
                    index.sectionEntries(FinalManifestPaths.GENERATED_TOOL)) {
                Row row = new Row(index, entry);
                LocalId id = ManifestSemanticDiagnostics.construct(
                        entry.section(), () -> new LocalId(entry.key()));
                AuthoredGeneratedTool tool = tool(row, id);
                if (declarations.put(id, tool) != null) {
                    throw new IllegalStateException(
                            "Validated manifest contains duplicate generated tool `" + id + "`.");
                }
                tools = ManifestSemanticDiagnostics.construct(
                        entry.section(), () -> new AuthoredGeneratedTools(declarations));
            }
            return tools;
        });
    }

    private static AuthoredGeneratedTool tool(Row row, LocalId id) {
        ToolKind kind;
        if (id.equals(OPENAPI) || id.equals(PROTOBUF)) {
            row.reject(GENERATED_TOOL_KIND, "reserved built-in tool overrides derive their kind");
            kind = id.equals(OPENAPI) ? ToolKind.OPENAPI : ToolKind.PROTOBUF;
        } else {
            ValidatedManifestField field = row.required(GENERATED_TOOL_KIND);
            kind = ToolKind.from(field, ManifestTomlValues.string(field));
        }
        return switch (kind) {
            case OPENAPI -> openApi(row);
            case PROTOBUF -> protobuf(row);
            case JVM -> jvm(row);
            case PROCESS -> process(row);
        };
    }

    private static AuthoredGeneratedTool.OpenApi openApi(Row row) {
        Optional<DependencyCoordinate> coordinate = row.field(GENERATED_TOOL_COORDINATE)
                .map(ManifestGeneratedToolsDecoder::coordinate);
        Optional<DependencySelector> version = selector(
                row, GENERATED_TOOL_VERSION, GENERATED_TOOL_VERSION_REF);
        row.reject(
                GENERATED_TOOL_PROTOC_COORDINATE,
                GENERATED_TOOL_PROTOC_VERSION,
                GENERATED_TOOL_PROTOC_VERSION_REF,
                GENERATED_TOOL_GRPC_COORDINATE,
                GENERATED_TOOL_GRPC_VERSION,
                GENERATED_TOOL_GRPC_VERSION_REF,
                GENERATED_TOOL_COORDINATES,
                GENERATED_TOOL_MAIN_CLASS,
                GENERATED_TOOL_BINARY,
                GENERATED_TOOL_VERSION_COMMAND,
                GENERATED_TOOL_VERSION_EXPECT,
                GENERATED_TOOL_ALLOW_UNPINNED_TOOL);
        return ManifestSemanticDiagnostics.construct(
                row.entry().section(), () -> new AuthoredGeneratedTool.OpenApi(coordinate, version));
    }

    private static AuthoredGeneratedTool.Protobuf protobuf(Row row) {
        row.reject(GENERATED_TOOL_COORDINATE, GENERATED_TOOL_VERSION, GENERATED_TOOL_VERSION_REF);
        Optional<DependencyCoordinate> protocCoordinate = row.field(GENERATED_TOOL_PROTOC_COORDINATE)
                .map(ManifestGeneratedToolsDecoder::coordinate);
        Optional<DependencySelector> protocVersion = selector(
                row, GENERATED_TOOL_PROTOC_VERSION, GENERATED_TOOL_PROTOC_VERSION_REF);
        Optional<DependencyCoordinate> grpcCoordinate = row.field(GENERATED_TOOL_GRPC_COORDINATE)
                .map(ManifestGeneratedToolsDecoder::coordinate);
        Optional<DependencySelector> grpcVersion = selector(
                row, GENERATED_TOOL_GRPC_VERSION, GENERATED_TOOL_GRPC_VERSION_REF);
        row.reject(
                GENERATED_TOOL_COORDINATES,
                GENERATED_TOOL_MAIN_CLASS,
                GENERATED_TOOL_BINARY,
                GENERATED_TOOL_VERSION_COMMAND,
                GENERATED_TOOL_VERSION_EXPECT,
                GENERATED_TOOL_ALLOW_UNPINNED_TOOL);
        return ManifestSemanticDiagnostics.construct(
                row.entry().section(),
                () -> new AuthoredGeneratedTool.Protobuf(
                        protocCoordinate, protocVersion, grpcCoordinate, grpcVersion));
    }

    private static AuthoredGeneratedTool.Jvm jvm(Row row) {
        row.reject(
                GENERATED_TOOL_COORDINATE,
                GENERATED_TOOL_VERSION,
                GENERATED_TOOL_VERSION_REF,
                GENERATED_TOOL_PROTOC_COORDINATE,
                GENERATED_TOOL_PROTOC_VERSION,
                GENERATED_TOOL_PROTOC_VERSION_REF,
                GENERATED_TOOL_GRPC_COORDINATE,
                GENERATED_TOOL_GRPC_VERSION,
                GENERATED_TOOL_GRPC_VERSION_REF);
        ValidatedManifestField coordinatesField = row.required(GENERATED_TOOL_COORDINATES);
        List<GeneratedArtifactRequest> coordinates = artifactRequests(coordinatesField);
        ValidatedManifestField mainClassField = row.required(GENERATED_TOOL_MAIN_CLASS);
        JavaBinaryClassName mainClass = ManifestSemanticDiagnostics.construct(
                mainClassField,
                () -> new JavaBinaryClassName(ManifestTomlValues.string(mainClassField)));
        row.reject(
                GENERATED_TOOL_BINARY,
                GENERATED_TOOL_VERSION_COMMAND,
                GENERATED_TOOL_VERSION_EXPECT,
                GENERATED_TOOL_ALLOW_UNPINNED_TOOL);
        return ManifestSemanticDiagnostics.construct(
                mainClassField, () -> new AuthoredGeneratedTool.Jvm(coordinates, mainClass));
    }

    private static List<GeneratedArtifactRequest> artifactRequests(
            ValidatedManifestField field) {
        List<ManifestInlineTable> tables = ManifestTomlValues.inlineObjectArray(field);
        ArrayList<GeneratedArtifactRequest> requests = new ArrayList<>(tables.size());
        for (int index = 0; index < tables.size(); index++) {
            ManifestInlineTable table = tables.get(index);
            String coordinate = table.requiredString(
                    FinalManifestObjectShapes.GENERATED_ARTIFACT_COORDINATE);
            DependencyCoordinate parsedCoordinate = ManifestSemanticDiagnostics.construct(
                    table,
                    FinalManifestObjectShapes.GENERATED_ARTIFACT_COORDINATE,
                    () -> new DependencyCoordinate(coordinate));
            DependencySelector selector = artifactSelector(table);
            requests.add(new GeneratedArtifactRequest(parsedCoordinate, selector));
            ManifestSemanticDiagnostics.construct(
                    table,
                    FinalManifestObjectShapes.GENERATED_ARTIFACT_COORDINATE,
                    () -> new AuthoredGeneratedTool.Jvm(requests, VALIDATION_MAIN_CLASS));
        }
        if (requests.isEmpty()) {
            ManifestSemanticDiagnostics.construct(
                    field, () -> new AuthoredGeneratedTool.Jvm(List.of(), VALIDATION_MAIN_CLASS));
        }
        return List.copyOf(requests);
    }

    private static DependencySelector artifactSelector(ManifestInlineTable table) {
        Optional<String> fixed = table.optionalString(
                FinalManifestObjectShapes.GENERATED_ARTIFACT_VERSION);
        if (fixed.isPresent()) {
            return ManifestSemanticDiagnostics.construct(
                    table,
                    FinalManifestObjectShapes.GENERATED_ARTIFACT_VERSION,
                    () -> new DependencySelector.FixedVersion(fixed.orElseThrow()));
        }
        String reference = table.requiredString(
                FinalManifestObjectShapes.GENERATED_ARTIFACT_VERSION_REF);
        return ManifestSemanticDiagnostics.construct(
                table,
                FinalManifestObjectShapes.GENERATED_ARTIFACT_VERSION_REF,
                () -> new DependencySelector.VersionReference(new LocalId(reference)));
    }

    private static AuthoredGeneratedTool.Process process(Row row) {
        row.reject(
                GENERATED_TOOL_COORDINATE,
                GENERATED_TOOL_VERSION,
                GENERATED_TOOL_VERSION_REF,
                GENERATED_TOOL_PROTOC_COORDINATE,
                GENERATED_TOOL_PROTOC_VERSION,
                GENERATED_TOOL_PROTOC_VERSION_REF,
                GENERATED_TOOL_GRPC_COORDINATE,
                GENERATED_TOOL_GRPC_VERSION,
                GENERATED_TOOL_GRPC_VERSION_REF,
                GENERATED_TOOL_COORDINATES,
                GENERATED_TOOL_MAIN_CLASS);
        ValidatedManifestField binaryField = row.required(GENERATED_TOOL_BINARY);
        GeneratedProcessBinary binary = ManifestSemanticDiagnostics.construct(
                binaryField,
                () -> new GeneratedProcessBinary(ManifestTomlValues.string(binaryField)));
        ValidatedManifestField commandField = row.required(GENERATED_TOOL_VERSION_COMMAND);
        List<String> command = versionCommand(commandField, binary);
        Optional<GeneratedVersionExpectation> expect = row.field(GENERATED_TOOL_VERSION_EXPECT)
                .map(ManifestGeneratedToolsDecoder::versionExpectation);
        ValidatedManifestField acknowledgementField = row.required(
                GENERATED_TOOL_ALLOW_UNPINNED_TOOL);
        boolean acknowledgement = ManifestTomlValues.booleanValue(acknowledgementField);
        return ManifestSemanticDiagnostics.construct(
                acknowledgementField,
                () -> new AuthoredGeneratedTool.Process(
                        binary, command, expect, acknowledgement));
    }

    private static List<String> versionCommand(
            ValidatedManifestField field,
            GeneratedProcessBinary binary) {
        List<String> command = ManifestTomlValues.strings(field);
        for (int index = 0; index < command.size(); index++) {
            int diagnosticIndex = index;
            List<String> prefix = command.subList(0, index + 1);
            ManifestSemanticDiagnostics.construct(
                    field,
                    diagnosticIndex,
                    () -> new AuthoredGeneratedTool.Process(
                            binary, prefix, Optional.empty(), true));
        }
        if (command.isEmpty()) {
            ManifestSemanticDiagnostics.construct(
                    field,
                    () -> new AuthoredGeneratedTool.Process(
                            binary, List.of(), Optional.empty(), true));
        }
        return command;
    }

    private static GeneratedVersionExpectation versionExpectation(
            ValidatedManifestField field) {
        return ManifestSemanticDiagnostics.construct(
                field,
                () -> new GeneratedVersionExpectation(ManifestTomlValues.string(field)));
    }

    private static DependencyCoordinate coordinate(ValidatedManifestField field) {
        return ManifestSemanticDiagnostics.construct(
                field, () -> new DependencyCoordinate(ManifestTomlValues.string(field)));
    }

    private static Optional<DependencySelector> selector(
            Row row,
            ManifestField versionHandle,
            ManifestField referenceHandle) {
        Optional<ValidatedManifestField> versionField = row.field(versionHandle);
        Optional<ValidatedManifestField> referenceField = row.field(referenceHandle);
        if (versionField.isPresent() && referenceField.isPresent()) {
            return invalid(
                    referenceField.orElseThrow(),
                    "Generated tool version selectors must declare only one of `"
                            + versionHandle.path().segments().getLast() + "` or `"
                            + referenceHandle.path().segments().getLast() + "`.");
        }
        if (versionField.isPresent()) {
            ValidatedManifestField field = versionField.orElseThrow();
            return Optional.of(ManifestSemanticDiagnostics.construct(
                    field,
                    () -> new DependencySelector.FixedVersion(ManifestTomlValues.string(field))));
        }
        return referenceField.map(field -> ManifestSemanticDiagnostics.construct(
                field,
                () -> new DependencySelector.VersionReference(
                        new LocalId(ManifestTomlValues.string(field)))));
    }

    private static <T> T invalid(ValidatedManifestField field, String message) {
        return ManifestSemanticDiagnostics.construct(field, () -> {
            throw new IllegalArgumentException(message);
        });
    }

    private enum ToolKind {
        OPENAPI("openapi"),
        PROTOBUF("protobuf"),
        JVM("jvm"),
        PROCESS("process");

        private final String configValue;

        ToolKind(String configValue) {
            this.configValue = configValue;
        }

        private static ToolKind from(ValidatedManifestField field, String value) {
            for (ToolKind kind : values()) {
                if (kind.configValue.equals(value)) {
                    return kind;
                }
            }
            throw new IllegalStateException(
                    "Final manifest schema accepted generated-tool kind `" + value
                            + "` at `" + field.path() + "` but the decoder does not recognize it.");
        }
    }

    private record Row(
            ManifestDecodeIndex index,
            ManifestDecodeIndex.SectionEntry entry) {
        private Row {
            Objects.requireNonNull(index, "Manifest decode index is required.");
            Objects.requireNonNull(entry, "Generated tool section entry is required.");
        }

        private Optional<ValidatedManifestField> field(ManifestField handle) {
            return index.field(entry, handle);
        }

        private ValidatedManifestField required(ManifestField handle) {
            return ManifestSemanticDiagnostics.requiredField(index, entry, handle);
        }

        private void reject(ManifestField... handles) {
            for (ManifestField handle : handles) {
                reject(handle, "the selected generated-tool kind does not allow this field");
            }
        }

        private void reject(ManifestField handle, String reason) {
            field(handle).ifPresent(field -> invalid(field, reason + "."));
        }
    }
}

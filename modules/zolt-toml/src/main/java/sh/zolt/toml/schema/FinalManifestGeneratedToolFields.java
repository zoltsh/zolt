package sh.zolt.toml.schema;

import java.util.List;

/** Registered field handles for authored generated-tool declarations and overrides. */
public final class FinalManifestGeneratedToolFields {
    public static final ManifestField GENERATED_TOOL_KIND = field(
            FinalManifestPaths.GENERATED_TOOL, "kind", ManifestValueKind.STRING, 6_301);
    public static final ManifestField GENERATED_TOOL_COORDINATE = field(
            FinalManifestPaths.GENERATED_TOOL, "coordinate", ManifestValueKind.STRING, 6_302);
    public static final ManifestField GENERATED_TOOL_VERSION = field(
            FinalManifestPaths.GENERATED_TOOL, "version", ManifestValueKind.STRING, 6_303);
    public static final ManifestField GENERATED_TOOL_VERSION_REF = field(
            FinalManifestPaths.GENERATED_TOOL, "versionRef", ManifestValueKind.STRING, 6_304);
    public static final ManifestField GENERATED_TOOL_PROTOC_COORDINATE = field(
            FinalManifestPaths.GENERATED_TOOL, "protocCoordinate", ManifestValueKind.STRING, 6_305);
    public static final ManifestField GENERATED_TOOL_PROTOC_VERSION = field(
            FinalManifestPaths.GENERATED_TOOL, "protocVersion", ManifestValueKind.STRING, 6_306);
    public static final ManifestField GENERATED_TOOL_PROTOC_VERSION_REF = field(
            FinalManifestPaths.GENERATED_TOOL, "protocVersionRef", ManifestValueKind.STRING, 6_307);
    public static final ManifestField GENERATED_TOOL_GRPC_COORDINATE = field(
            FinalManifestPaths.GENERATED_TOOL, "grpcCoordinate", ManifestValueKind.STRING, 6_308);
    public static final ManifestField GENERATED_TOOL_GRPC_VERSION = field(
            FinalManifestPaths.GENERATED_TOOL, "grpcVersion", ManifestValueKind.STRING, 6_309);
    public static final ManifestField GENERATED_TOOL_GRPC_VERSION_REF = field(
            FinalManifestPaths.GENERATED_TOOL, "grpcVersionRef", ManifestValueKind.STRING, 6_310);
    public static final ManifestField GENERATED_TOOL_COORDINATES = objectField(
            FinalManifestPaths.GENERATED_TOOL,
            "coordinates",
            ManifestValueKind.INLINE_TABLE_ARRAY,
            6_311,
            FinalManifestObjectShapes.GENERATED_ARTIFACT_REQUEST);
    public static final ManifestField GENERATED_TOOL_MAIN_CLASS = field(
            FinalManifestPaths.GENERATED_TOOL, "mainClass", ManifestValueKind.STRING, 6_312);
    public static final ManifestField GENERATED_TOOL_BINARY = field(
            FinalManifestPaths.GENERATED_TOOL, "binary", ManifestValueKind.STRING, 6_313);
    public static final ManifestField GENERATED_TOOL_VERSION_COMMAND = field(
            FinalManifestPaths.GENERATED_TOOL, "versionCommand", ManifestValueKind.STRING_ARRAY, 6_314);
    public static final ManifestField GENERATED_TOOL_VERSION_EXPECT = field(
            FinalManifestPaths.GENERATED_TOOL, "versionExpect", ManifestValueKind.STRING, 6_315);
    public static final ManifestField GENERATED_TOOL_ALLOW_UNPINNED_TOOL = field(
            FinalManifestPaths.GENERATED_TOOL, "allowUnpinnedTool", ManifestValueKind.BOOLEAN, 6_316);

    private FinalManifestGeneratedToolFields() {
    }

    static List<ManifestField> fields() {
        return List.of(
                GENERATED_TOOL_KIND,
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
                GENERATED_TOOL_MAIN_CLASS,
                GENERATED_TOOL_BINARY,
                GENERATED_TOOL_VERSION_COMMAND,
                GENERATED_TOOL_VERSION_EXPECT,
                GENERATED_TOOL_ALLOW_UNPINNED_TOOL);
    }

    private static ManifestField field(
            ManifestPath section,
            String name,
            ManifestValueKind kind,
            int canonicalOrder) {
        return FinalManifestFieldFactory.field(section, name, kind, canonicalOrder);
    }

    private static ManifestField objectField(
            ManifestPath section,
            String name,
            ManifestValueKind kind,
            int canonicalOrder,
            ManifestObjectShape objectShape) {
        return FinalManifestFieldFactory.objectField(
                section, name, kind, canonicalOrder, objectShape);
    }
}

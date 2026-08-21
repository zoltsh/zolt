package sh.zolt.update;

/** The kind of zolt.toml surface a reportable version originates from. */
public enum OutdatedSurface {
    VERSION_ALIAS("versionAlias"),
    DEPENDENCY("dependency"),
    PLATFORM("platform"),
    ANNOTATION_PROCESSOR("annotationProcessor"),
    DEPENDENCY_CONSTRAINT("dependencyConstraint"),
    BOM_VERSION("bomVersion"),
    BOM_IMPORT("bomImport"),
    EXEC_TOOL_COORDINATE("execToolCoordinate"),
    PROTOBUF_TOOL("protobufTool"),
    OPENAPI_TOOL("openapiTool");

    private final String jsonName;

    OutdatedSurface(String jsonName) {
        this.jsonName = jsonName;
    }

    public String jsonName() {
        return jsonName;
    }
}

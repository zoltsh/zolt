package sh.zolt.update;

/**
 * Which surfaces {@code zolt update} can write through the source-safe manifest editor. Every
 * schema-declared mutable table is applicable: aliases, dependencies, annotation processors,
 * platforms, constraints, and the two BOM maps (design §18.5). Literal generated-tool coordinates
 * (exec/protobuf/openapi) live in tables the editor does not declare mutable, so they are reported
 * as skipped and can only move through a {@code [versions]} alias.
 */
final class UpdateApplicability {
    private UpdateApplicability() {
    }

    static boolean isApplicable(OutdatedSurface surface) {
        return switch (surface) {
            case VERSION_ALIAS, DEPENDENCY, ANNOTATION_PROCESSOR, PLATFORM, DEPENDENCY_CONSTRAINT,
                    BOM_VERSION, BOM_IMPORT -> true;
            case EXEC_TOOL_COORDINATE, PROTOBUF_TOOL, OPENAPI_TOOL -> false;
        };
    }

    static String reason(OutdatedSurface surface) {
        return switch (surface) {
            case EXEC_TOOL_COORDINATE ->
                "Literal exec-tool coordinate mutation is not yet supported; route the version through a "
                        + "[versions] alias or edit zolt.toml manually.";
            case PROTOBUF_TOOL, OPENAPI_TOOL ->
                "Literal generated-tool coordinate mutation is not supported; route the version through a "
                        + "[versions] alias.";
            default -> "";
        };
    }
}

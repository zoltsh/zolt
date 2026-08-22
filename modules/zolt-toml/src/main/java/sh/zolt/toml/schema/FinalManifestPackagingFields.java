package sh.zolt.toml.schema;

import java.util.List;

/** Registered field handles for package, BOM, framework, and native-image settings. */
public final class FinalManifestPackagingFields {
    public static final ManifestField PACKAGE_MODE = field(
            FinalManifestPaths.PACKAGE, "mode", ManifestValueKind.STRING, 7_001);
    public static final ManifestField PACKAGE_SOURCES = field(
            FinalManifestPaths.PACKAGE, "sources", ManifestValueKind.BOOLEAN, 7_002);
    public static final ManifestField PACKAGE_JAVADOC = field(
            FinalManifestPaths.PACKAGE, "javadoc", ManifestValueKind.BOOLEAN, 7_003);
    public static final ManifestField PACKAGE_TEST_JAR = field(
            FinalManifestPaths.PACKAGE, "testJar", ManifestValueKind.BOOLEAN, 7_004);
    public static final ManifestField PACKAGE_DUPLICATES = field(
            FinalManifestPaths.PACKAGE, "duplicates", ManifestValueKind.STRING, 7_005);
    public static final ManifestField PACKAGE_MANIFEST_ENTRY = field(
            FinalManifestPaths.PACKAGE_MANIFEST,
            "<attribute>",
            ManifestValueKind.STRING,
            7_011);
    public static final ManifestField BOM_MEMBERS = field(
            FinalManifestPaths.BOM,
            "members",
            ManifestValueKind.BOOLEAN_OR_STRING_ARRAY,
            7_101);
    public static final ManifestField BOM_EXCLUDE = field(
            FinalManifestPaths.BOM, "exclude", ManifestValueKind.STRING_ARRAY, 7_102);
    public static final ManifestField BOM_VERSIONS_ENTRY = mutableObjectMapEntry(
            FinalManifestPaths.BOM_VERSIONS,
            "<coordinate>",
            ManifestValueKind.STRING_OR_INLINE_TABLE,
            7_111,
            FinalManifestObjectShapes.BOM_VERSION_SELECTOR);
    public static final ManifestField BOM_IMPORTS_ENTRY = mutableObjectMapEntry(
            FinalManifestPaths.BOM_IMPORTS,
            "<coordinate>",
            ManifestValueKind.STRING_OR_INLINE_TABLE,
            7_121,
            FinalManifestObjectShapes.PLATFORM_SELECTOR);
    public static final ManifestField FRAMEWORK_SPRING_BOOT_NATIVE = field(
            FinalManifestPaths.FRAMEWORK_SPRING_BOOT,
            "native",
            ManifestValueKind.BOOLEAN,
            7_201);
    public static final ManifestField NATIVE_NAME = field(
            FinalManifestPaths.NATIVE, "name", ManifestValueKind.STRING, 7_301);
    public static final ManifestField NATIVE_OUTPUT = field(
            FinalManifestPaths.NATIVE, "output", ManifestValueKind.STRING, 7_302);
    public static final ManifestField NATIVE_ARGS = field(
            FinalManifestPaths.NATIVE, "args", ManifestValueKind.STRING_ARRAY, 7_303);

    private FinalManifestPackagingFields() {
    }

    static List<ManifestField> fields() {
        return List.of(
                PACKAGE_MODE,
                PACKAGE_SOURCES,
                PACKAGE_JAVADOC,
                PACKAGE_TEST_JAR,
                PACKAGE_DUPLICATES,
                PACKAGE_MANIFEST_ENTRY,
                BOM_MEMBERS,
                BOM_EXCLUDE,
                BOM_VERSIONS_ENTRY,
                BOM_IMPORTS_ENTRY,
                FRAMEWORK_SPRING_BOOT_NATIVE,
                NATIVE_NAME,
                NATIVE_OUTPUT,
                NATIVE_ARGS);
    }

    private static ManifestField field(
            ManifestPath section,
            String name,
            ManifestValueKind kind,
            int canonicalOrder) {
        return FinalManifestFieldFactory.field(section, name, kind, canonicalOrder);
    }

    private static ManifestField mutableObjectMapEntry(
            ManifestPath section,
            String name,
            ManifestValueKind kind,
            int canonicalOrder,
            ManifestObjectShape objectShape) {
        return FinalManifestFieldFactory.mutableObjectMapEntry(
                section, name, kind, canonicalOrder, objectShape);
    }
}

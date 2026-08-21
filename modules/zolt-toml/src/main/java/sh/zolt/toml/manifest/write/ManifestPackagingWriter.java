package sh.zolt.toml.manifest.write;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.PlatformSelector;
import sh.zolt.manifest.authored.AuthoredBom;
import sh.zolt.manifest.authored.AuthoredNativeImage;
import sh.zolt.manifest.authored.AuthoredPackage;
import sh.zolt.manifest.authored.AuthoredPackageManifest;
import sh.zolt.manifest.authored.AuthoredPackaging;
import sh.zolt.manifest.authored.AuthoredSpringBoot;
import sh.zolt.manifest.authored.DependencyVariant;
import sh.zolt.toml.schema.FinalManifestObjectShapes;
import sh.zolt.toml.schema.FinalManifestPackagingFields;
import sh.zolt.toml.schema.FinalManifestPaths;
import sh.zolt.toml.schema.FinalManifestSchema;
import sh.zolt.toml.schema.ManifestField;
import sh.zolt.toml.schema.ManifestPath;
import sh.zolt.toml.schema.ManifestSection;

/** Emits canonical package, BOM, framework, and native-image authored settings. */
final class ManifestPackagingWriter {
    private static final ManifestSection PACKAGE = section(FinalManifestPaths.PACKAGE);
    private static final ManifestSection PACKAGE_MANIFEST =
            section(FinalManifestPaths.PACKAGE_MANIFEST);
    private static final ManifestSection BOM = section(FinalManifestPaths.BOM);
    private static final ManifestSection BOM_VERSIONS = section(FinalManifestPaths.BOM_VERSIONS);
    private static final ManifestSection BOM_IMPORTS = section(FinalManifestPaths.BOM_IMPORTS);
    private static final ManifestSection SPRING_BOOT =
            section(FinalManifestPaths.FRAMEWORK_SPRING_BOOT);
    private static final ManifestSection NATIVE = section(FinalManifestPaths.NATIVE);

    void write(ManifestTomlEmitter emitter, AuthoredPackaging packaging) {
        Objects.requireNonNull(emitter, "Manifest TOML emitter is required.");
        AuthoredPackaging authored = Objects.requireNonNull(
                packaging, "Authored packaging is required.");
        authored.packageSettings().ifPresent(value -> writePackage(emitter, value));
        authored.manifest()
                .filter(value -> !value.attributes().isEmpty())
                .ifPresent(value -> writeManifest(emitter, value));
        authored.bom().ifPresent(value -> writeBom(emitter, value));
        authored.springBoot().ifPresent(value -> writeSpringBoot(emitter, value));
        authored.nativeImage().ifPresent(value -> writeNative(emitter, value));
    }

    private static void writePackage(ManifestTomlEmitter emitter, AuthoredPackage settings) {
        emitter.section(PACKAGE);
        settings.mode()
                .filter(value -> value != AuthoredPackage.Mode.JAR)
                .ifPresent(value -> emitter.field(
                        FinalManifestPackagingFields.PACKAGE_MODE,
                        string(value.configValue())));
        writeTrue(emitter, FinalManifestPackagingFields.PACKAGE_SOURCES, settings.sources());
        writeTrue(emitter, FinalManifestPackagingFields.PACKAGE_JAVADOC, settings.javadoc());
        writeTrue(emitter, FinalManifestPackagingFields.PACKAGE_TEST_JAR, settings.testJar());
        settings.duplicates()
                .filter(value -> value != AuthoredPackage.DuplicatePolicy.FAIL)
                .ifPresent(value -> emitter.field(
                        FinalManifestPackagingFields.PACKAGE_DUPLICATES,
                        string(value.configValue())));
    }

    private static void writeManifest(
            ManifestTomlEmitter emitter, AuthoredPackageManifest manifest) {
        emitter.section(PACKAGE_MANIFEST);
        for (Map.Entry<String, String> entry : manifest.attributes().entrySet()) {
            emitter.dynamicField(
                    FinalManifestPackagingFields.PACKAGE_MANIFEST_ENTRY,
                    entry.getKey(),
                    string(entry.getValue()));
        }
    }

    private static void writeBom(ManifestTomlEmitter emitter, AuthoredBom bom) {
        bom.members().ifPresent(value -> writeBomMembers(emitter, value));
        bom.versions()
                .filter(value -> !value.isEmpty())
                .ifPresent(value -> writeBomVersions(emitter, value));
        bom.imports()
                .filter(value -> !value.isEmpty())
                .ifPresent(value -> writeBomImports(emitter, value));
    }

    private static void writeBomMembers(
            ManifestTomlEmitter emitter, AuthoredBom.Members members) {
        emitter.section(BOM);
        String selection = switch (members.selection()) {
            case AuthoredBom.AllMembers ignored -> ManifestTomlValueEncoder.booleanValue(true);
            case AuthoredBom.ExplicitMembers explicit -> strings(
                    FinalManifestPackagingFields.BOM_MEMBERS,
                    explicit.paths().stream().map(value -> value.value()).toList());
        };
        emitter.field(FinalManifestPackagingFields.BOM_MEMBERS, selection);
        if (!members.exclude().isEmpty()) {
            emitter.field(
                    FinalManifestPackagingFields.BOM_EXCLUDE,
                    strings(
                            FinalManifestPackagingFields.BOM_EXCLUDE,
                            members.exclude().stream().map(value -> value.value()).toList()));
        }
    }

    private static void writeBomVersions(
            ManifestTomlEmitter emitter, Map<DependencyCoordinate, AuthoredBom.Version> versions) {
        emitter.section(BOM_VERSIONS);
        for (Map.Entry<DependencyCoordinate, AuthoredBom.Version> entry : versions.entrySet()) {
            emitter.dynamicField(
                    FinalManifestPackagingFields.BOM_VERSIONS_ENTRY,
                    entry.getKey().value(),
                    bomVersion(entry.getValue()));
        }
    }

    private static String bomVersion(AuthoredBom.Version version) {
        boolean metadata = version.classifier().isPresent()
                || version.type()
                        .filter(value -> !DependencyVariant.DEFAULT_TYPE.equals(value))
                        .isPresent();
        if (version.selector() instanceof PlatformSelector.FixedVersion fixed && !metadata) {
            return string(fixed.value());
        }
        ArrayList<ManifestTomlValueEncoder.InlineMember> members = new ArrayList<>();
        addSelector(members, version.selector());
        version.classifier().ifPresent(value -> members.add(member(
                FinalManifestObjectShapes.BOM_VERSION_CLASSIFIER.name(), value)));
        version.type()
                .filter(value -> !DependencyVariant.DEFAULT_TYPE.equals(value))
                .ifPresent(value -> members.add(member(
                        FinalManifestObjectShapes.BOM_VERSION_TYPE.name(), value)));
        return ManifestTomlValueEncoder.inlineObject(members);
    }

    private static void writeBomImports(
            ManifestTomlEmitter emitter, Map<DependencyCoordinate, PlatformSelector> imports) {
        emitter.section(BOM_IMPORTS);
        for (Map.Entry<DependencyCoordinate, PlatformSelector> entry : imports.entrySet()) {
            emitter.dynamicField(
                    FinalManifestPackagingFields.BOM_IMPORTS_ENTRY,
                    entry.getKey().value(),
                    selector(entry.getValue()));
        }
    }

    private static String selector(PlatformSelector selector) {
        if (selector instanceof PlatformSelector.FixedVersion fixed) {
            return string(fixed.value());
        }
        ArrayList<ManifestTomlValueEncoder.InlineMember> members = new ArrayList<>();
        addSelector(members, selector);
        return ManifestTomlValueEncoder.inlineObject(members);
    }

    private static void addSelector(
            List<ManifestTomlValueEncoder.InlineMember> members,
            PlatformSelector selector) {
        switch (selector) {
            case PlatformSelector.FixedVersion fixed -> members.add(member(
                    FinalManifestObjectShapes.PLATFORM_VERSION.name(), fixed.value()));
            case PlatformSelector.VersionReference reference -> members.add(member(
                    FinalManifestObjectShapes.PLATFORM_VERSION_REF.name(),
                    reference.alias().value()));
        }
    }

    private static void writeSpringBoot(
            ManifestTomlEmitter emitter, AuthoredSpringBoot springBoot) {
        springBoot.nativeImage()
                .filter(Boolean::booleanValue)
                .ifPresent(value -> {
                    emitter.section(SPRING_BOOT);
                    emitter.field(
                            FinalManifestPackagingFields.FRAMEWORK_SPRING_BOOT_NATIVE,
                            ManifestTomlValueEncoder.booleanValue(value));
                });
    }

    private static void writeNative(
            ManifestTomlEmitter emitter, AuthoredNativeImage nativeImage) {
        emitter.section(NATIVE);
        nativeImage.name().ifPresent(value -> emitter.field(
                FinalManifestPackagingFields.NATIVE_NAME, string(value)));
        nativeImage.output()
                .filter(value -> !value.value().equals("native"))
                .ifPresent(value -> emitter.field(
                        FinalManifestPackagingFields.NATIVE_OUTPUT, string(value.value())));
        nativeImage.args()
                .filter(value -> !value.isEmpty())
                .ifPresent(value -> emitter.field(
                        FinalManifestPackagingFields.NATIVE_ARGS,
                        strings(FinalManifestPackagingFields.NATIVE_ARGS, value)));
    }

    private static void writeTrue(
            ManifestTomlEmitter emitter,
            ManifestField field,
            Optional<Boolean> value) {
        value.filter(Boolean::booleanValue).ifPresent(present -> emitter.field(
                field, ManifestTomlValueEncoder.booleanValue(present)));
    }

    private static ManifestTomlValueEncoder.InlineMember member(String name, String value) {
        return ManifestTomlValueEncoder.member(name, string(value));
    }

    private static String strings(ManifestField field, List<String> values) {
        return ManifestTomlValueEncoder.fieldArray(field, values.stream()
                .map(ManifestPackagingWriter::string)
                .toList());
    }

    private static String string(String value) {
        return ManifestTomlValueEncoder.basicString(value);
    }

    private static ManifestSection section(ManifestPath path) {
        return FinalManifestSchema.registry().section(path).orElseThrow();
    }
}

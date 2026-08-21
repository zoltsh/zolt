package sh.zolt.toml.manifest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.ManifestModelValues;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.authored.AuthoredBom;
import sh.zolt.manifest.authored.AuthoredNativeImage;
import sh.zolt.manifest.authored.AuthoredPackage;
import sh.zolt.manifest.authored.AuthoredPackageManifest;
import sh.zolt.manifest.authored.AuthoredPackaging;
import sh.zolt.manifest.authored.AuthoredSpringBoot;
import sh.zolt.toml.schema.FinalManifestPackagingFields;
import sh.zolt.toml.schema.FinalManifestPaths;

/** Composes the complete authored packaging domain in canonical schema order. */
final class ManifestPackagingDecoder {
    private final ManifestPackageDecoder packageDecoder = new ManifestPackageDecoder();
    private final ManifestPackageManifestDecoder manifestDecoder =
            new ManifestPackageManifestDecoder();
    private final ManifestBomDecoder bomDecoder = new ManifestBomDecoder();
    private final ManifestSpringBootDecoder springBootDecoder =
            new ManifestSpringBootDecoder();
    private final ManifestNativeImageDecoder nativeImageDecoder =
            new ManifestNativeImageDecoder();

    AuthoredPackaging decode(
            ManifestDecodeIndex index,
            ManifestBomDecoder.BomPresenceObserver observer) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        Objects.requireNonNull(observer, "Authored BOM presence observer is required.");
        Optional<AuthoredPackage> packageSettings = packageDecoder.decode(index);
        Optional<AuthoredPackageManifest> manifest = manifestDecoder.decode(index);
        Optional<AuthoredBom> bom = bomDecoder.decode(
                index,
                partial -> {
                    observer.present(partial);
                    validateBomPresence(packageSettings, manifest, partial);
                });
        AuthoredPackaging packaging = new AuthoredPackaging(
                packageSettings,
                manifest,
                Optional.empty(),
                Optional.empty(),
                bom);

        Optional<AuthoredSpringBoot> springBoot = springBootDecoder.decode(index);
        if (springBoot.isPresent()) {
            ValidatedManifestField field = index
                    .field(FinalManifestPackagingFields.FRAMEWORK_SPRING_BOOT_NATIVE)
                    .orElseThrow(() -> new IllegalStateException(
                            "Decoded Spring Boot settings are missing their retained field."));
            packaging = ManifestSemanticDiagnostics.construct(
                    field,
                    () -> new AuthoredPackaging(
                            packageSettings,
                            manifest,
                            springBoot,
                            Optional.empty(),
                            bom));
        }

        Optional<AuthoredNativeImage> nativeImage = nativeImageDecoder.decode(index);
        if (nativeImage.isPresent()) {
            ValidatedManifestField anchor = index
                    .field(FinalManifestPackagingFields.NATIVE_NAME)
                    .or(() -> index.field(FinalManifestPackagingFields.NATIVE_OUTPUT))
                    .or(() -> index.field(FinalManifestPackagingFields.NATIVE_ARGS))
                    .orElseThrow(() -> new IllegalStateException(
                            "Decoded native-image settings have no retained field."));
            packaging = ManifestSemanticDiagnostics.construct(
                    anchor,
                    () -> new AuthoredPackaging(
                            packageSettings,
                            manifest,
                            springBoot,
                            nativeImage,
                            bom));
        }
        return packaging;
    }

    private static void validateBomPresence(
            Optional<AuthoredPackage> packageSettings,
            Optional<AuthoredPackageManifest> manifest,
            AuthoredBom partial) {
        Optional<AuthoredBom> bom = Optional.of(partial);
        new AuthoredPackaging(
                packageSettings,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                bom);
        new AuthoredPackaging(
                packageSettings,
                manifest,
                Optional.empty(),
                Optional.empty(),
                bom);
    }
}

/** Decodes authored package settings without applying artifact defaults. */
final class ManifestPackageDecoder {
    Optional<AuthoredPackage> decode(ManifestDecodeIndex index) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        Optional<ValidatedManifestField> modeField =
                index.field(FinalManifestPackagingFields.PACKAGE_MODE);
        Optional<ValidatedManifestField> sourcesField =
                index.field(FinalManifestPackagingFields.PACKAGE_SOURCES);
        Optional<ValidatedManifestField> javadocField =
                index.field(FinalManifestPackagingFields.PACKAGE_JAVADOC);
        Optional<ValidatedManifestField> testJarField =
                index.field(FinalManifestPackagingFields.PACKAGE_TEST_JAR);
        Optional<ValidatedManifestField> duplicatesField =
                index.field(FinalManifestPackagingFields.PACKAGE_DUPLICATES);
        if (modeField.isEmpty()
                && sourcesField.isEmpty()
                && javadocField.isEmpty()
                && testJarField.isEmpty()
                && duplicatesField.isEmpty()) {
            return Optional.empty();
        }

        Optional<AuthoredPackage.Mode> mode = modeField.map(ManifestPackageDecoder::mode);
        Optional<Boolean> sources = sourcesField.map(ManifestTomlValues::booleanValue);
        Optional<Boolean> javadoc = javadocField.map(ManifestTomlValues::booleanValue);
        Optional<Boolean> testJar = testJarField.map(ManifestTomlValues::booleanValue);
        Optional<AuthoredPackage.DuplicatePolicy> duplicates = duplicatesField.map(
                field -> duplicatePolicy(field, mode, sources, javadoc, testJar));
        ValidatedManifestField anchor = modeField
                .or(() -> sourcesField)
                .or(() -> javadocField)
                .or(() -> testJarField)
                .or(() -> duplicatesField)
                .orElseThrow();
        return Optional.of(ManifestSemanticDiagnostics.construct(
                anchor,
                () -> new AuthoredPackage(mode, sources, javadoc, testJar, duplicates)));
    }

    private static AuthoredPackage.Mode mode(ValidatedManifestField field) {
        return ManifestAuthoredSymbols.model(
                field,
                ManifestTomlValues.string(field),
                AuthoredPackage.Mode.values(),
                AuthoredPackage.Mode::configValue,
                "package mode");
    }

    private static AuthoredPackage.DuplicatePolicy duplicatePolicy(
            ValidatedManifestField field,
            Optional<AuthoredPackage.Mode> mode,
            Optional<Boolean> sources,
            Optional<Boolean> javadoc,
            Optional<Boolean> testJar) {
        AuthoredPackage.DuplicatePolicy policy = duplicatePolicy(field);
        ManifestSemanticDiagnostics.construct(
                field,
                () -> new AuthoredPackage(
                        mode,
                        sources,
                        javadoc,
                        testJar,
                        Optional.of(policy)));
        return policy;
    }

    private static AuthoredPackage.DuplicatePolicy duplicatePolicy(
            ValidatedManifestField field) {
        return ManifestAuthoredSymbols.model(
                field,
                ManifestTomlValues.string(field),
                AuthoredPackage.DuplicatePolicy.values(),
                AuthoredPackage.DuplicatePolicy::configValue,
                "package duplicate policy");
    }
}

/** Decodes the optional authored JAR manifest attribute collection. */
final class ManifestPackageManifestDecoder {
    Optional<AuthoredPackageManifest> decode(ManifestDecodeIndex index) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        List<ManifestDecodeIndex.Entry> entries =
                index.entries(FinalManifestPackagingFields.PACKAGE_MANIFEST_ENTRY);
        if (index.section(FinalManifestPaths.PACKAGE_MANIFEST).isEmpty()
                && entries.isEmpty()) {
            return Optional.empty();
        }

        LinkedHashMap<String, String> attributes = new LinkedHashMap<>();
        AuthoredPackageManifest manifest = new AuthoredPackageManifest(Map.of());
        for (ManifestDecodeIndex.Entry entry : entries) {
            ValidatedManifestField field = entry.field();
            String prior = attributes.put(
                    entry.key(), ManifestTomlValues.string(field));
            if (prior != null) {
                throw new IllegalStateException(
                        "Validated manifest contains duplicate package manifest attribute `"
                                + entry.key() + "`.");
            }
            manifest = ManifestSemanticDiagnostics.construct(
                    field,
                    () -> new AuthoredPackageManifest(attributes));
        }
        return Optional.of(manifest);
    }
}

/** Decodes authored Spring Boot options without inferring the package mode. */
final class ManifestSpringBootDecoder {
    Optional<AuthoredSpringBoot> decode(ManifestDecodeIndex index) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        return index.field(FinalManifestPackagingFields.FRAMEWORK_SPRING_BOOT_NATIVE)
                .map(field -> ManifestSemanticDiagnostics.construct(
                        field,
                        () -> new AuthoredSpringBoot(
                                Optional.of(ManifestTomlValues.booleanValue(field)))));
    }
}

/** Decodes authored native-image settings without applying project or output defaults. */
final class ManifestNativeImageDecoder {
    Optional<AuthoredNativeImage> decode(ManifestDecodeIndex index) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        Optional<ValidatedManifestField> nameField =
                index.field(FinalManifestPackagingFields.NATIVE_NAME);
        Optional<ValidatedManifestField> outputField =
                index.field(FinalManifestPackagingFields.NATIVE_OUTPUT);
        Optional<ValidatedManifestField> argsField =
                index.field(FinalManifestPackagingFields.NATIVE_ARGS);
        if (nameField.isEmpty()
                && outputField.isEmpty()
                && argsField.isEmpty()) {
            return Optional.empty();
        }

        Optional<String> name = nameField.map(ManifestNativeImageDecoder::name);
        Optional<ManifestRelativePath> output = outputField.map(field ->
                ManifestSemanticDiagnostics.construct(
                        field,
                        () -> new ManifestRelativePath(ManifestTomlValues.string(field))));
        Optional<List<String>> args = argsField.map(ManifestTomlValues::strings);
        ValidatedManifestField anchor = nameField
                .or(() -> outputField)
                .or(() -> argsField)
                .orElseThrow();
        return Optional.of(ManifestSemanticDiagnostics.construct(
                anchor, () -> new AuthoredNativeImage(name, output, args)));
    }

    private static String name(ValidatedManifestField field) {
        String value = ManifestTomlValues.string(field);
        return ManifestSemanticDiagnostics.construct(field, () -> {
            ManifestModelValues.requireNonBlank(value, "Native image name");
            return value;
        });
    }
}

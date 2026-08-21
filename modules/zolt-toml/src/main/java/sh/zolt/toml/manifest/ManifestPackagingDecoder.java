package sh.zolt.toml.manifest;

import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.authored.AuthoredBom;
import sh.zolt.manifest.authored.AuthoredNativeImage;
import sh.zolt.manifest.authored.AuthoredPackage;
import sh.zolt.manifest.authored.AuthoredPackageManifest;
import sh.zolt.manifest.authored.AuthoredPackaging;
import sh.zolt.manifest.authored.AuthoredSpringBoot;
import sh.zolt.toml.schema.FinalManifestPackagingFields;

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

    AuthoredPackaging decode(ManifestDecodeIndex index) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        Optional<AuthoredPackage> packageSettings = packageDecoder.decode(index);
        Optional<AuthoredPackageManifest> manifest = manifestDecoder.decode(index);
        Optional<AuthoredBom> bom = bomDecoder.decode(
                index,
                partial -> validateBomPresence(packageSettings, manifest, partial));
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

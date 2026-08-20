package sh.zolt.manifest.authored;

import java.util.Objects;
import java.util.Optional;

/** Complete authored §12 domain without effective defaults or workspace membership resolution. */
public record AuthoredPackaging(
        Optional<AuthoredPackage> packageSettings,
        Optional<AuthoredPackageManifest> manifest,
        Optional<AuthoredSpringBoot> springBoot,
        Optional<AuthoredNativeImage> nativeImage,
        Optional<AuthoredBom> bom) {
    public AuthoredPackaging {
        packageSettings = Objects.requireNonNull(
                packageSettings, "Authored package settings must not be null.");
        manifest = Objects.requireNonNull(manifest, "Authored package manifest must not be null.");
        springBoot = Objects.requireNonNull(
                springBoot, "Authored Spring Boot settings must not be null.");
        nativeImage = Objects.requireNonNull(
                nativeImage, "Authored native image settings must not be null.");
        bom = Objects.requireNonNull(bom, "Authored BOM settings must not be null.");
        if (bom.isPresent()
                && manifest.filter(value -> !value.attributes().isEmpty()).isPresent()) {
            throw new IllegalArgumentException("A BOM cannot author a JAR manifest.");
        }
        if (bom.isPresent() && nativeImage.isPresent()) {
            throw new IllegalArgumentException("A BOM cannot author native-image settings.");
        }
        if (bom.isPresent() && springBoot.isPresent()) {
            throw new IllegalArgumentException(
                    "A BOM cannot author Spring Boot framework settings.");
        }
        if (bom.isPresent()
                && packageSettings.flatMap(AuthoredPackage::mode).isPresent()) {
            throw new IllegalArgumentException(
                    "A BOM cannot author package mode; a [bom] domain implies BOM packaging.");
        }
        if (bom.isPresent()
                && packageSettings.filter(AuthoredPackaging::producesAttachedArtifact).isPresent()) {
            throw new IllegalArgumentException(
                    "A BOM cannot enable sources, javadoc, or test JAR artifacts.");
        }
    }

    public static AuthoredPackaging empty() {
        return new AuthoredPackaging(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static boolean producesAttachedArtifact(AuthoredPackage settings) {
        return settings.sources().orElse(false)
                || settings.javadoc().orElse(false)
                || settings.testJar().orElse(false);
    }
}

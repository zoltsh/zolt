package sh.zolt.toml.manifest;

import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.authored.AuthoredSpringBoot;
import sh.zolt.toml.schema.FinalManifestPackagingFields;

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

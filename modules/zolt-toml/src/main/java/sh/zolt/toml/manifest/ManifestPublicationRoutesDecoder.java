package sh.zolt.toml.manifest;

import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.authored.AuthoredPublicationRoutes;
import sh.zolt.toml.schema.FinalManifestPublishingFields;

/** Decodes authored release and snapshot publication repository selections. */
final class ManifestPublicationRoutesDecoder {
    Optional<AuthoredPublicationRoutes> decode(ManifestDecodeIndex index) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        Optional<ValidatedManifestField> releaseField =
                index.field(FinalManifestPublishingFields.PUBLISH_RELEASE);
        Optional<ValidatedManifestField> snapshotField =
                index.field(FinalManifestPublishingFields.PUBLISH_SNAPSHOT);
        if (releaseField.isEmpty() && snapshotField.isEmpty()) {
            return Optional.empty();
        }

        Optional<LocalId> release = route(releaseField);
        Optional<LocalId> snapshot = route(snapshotField);
        ValidatedManifestField anchor = releaseField
                .or(() -> snapshotField)
                .orElseThrow();
        return Optional.of(ManifestSemanticDiagnostics.construct(
                anchor,
                () -> new AuthoredPublicationRoutes(release, snapshot)));
    }

    private static Optional<LocalId> route(
            Optional<ValidatedManifestField> field) {
        return field.map(value -> ManifestSemanticDiagnostics.construct(
                value,
                () -> new LocalId(ManifestTomlValues.string(value))));
    }
}

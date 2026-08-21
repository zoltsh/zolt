package sh.zolt.toml.manifest;

import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.RepositoryUrl;
import sh.zolt.manifest.authored.AuthoredPublicationRepository;
import sh.zolt.toml.schema.FinalManifestPublishingFields;

/** Decodes one named authored publication repository. */
final class ManifestPublicationRepositoryDecoder {
    Decoded decode(
            ManifestDecodeIndex index,
            ManifestDecodeIndex.SectionEntry entry) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        Objects.requireNonNull(entry, "Publication repository section entry is required.");

        LocalId id = ManifestSemanticDiagnostics.construct(
                entry.section(), () -> new LocalId(entry.key()));
        ValidatedManifestField urlField = ManifestSemanticDiagnostics.requiredField(
                index,
                entry,
                FinalManifestPublishingFields.PUBLISH_REPOSITORY_URL);
        RepositoryUrl url = ManifestSemanticDiagnostics.construct(
                urlField,
                () -> new RepositoryUrl(ManifestTomlValues.string(urlField)));
        AuthoredPublicationRepository repository = ManifestSemanticDiagnostics.construct(
                urlField,
                () -> AuthoredPublicationRepository.unauthenticated(url));

        Optional<ValidatedManifestField> credentialsField = index.field(
                entry,
                FinalManifestPublishingFields.PUBLISH_REPOSITORY_CREDENTIALS);
        if (credentialsField.isPresent()) {
            ValidatedManifestField field = credentialsField.orElseThrow();
            LocalId credentials = ManifestSemanticDiagnostics.construct(
                    field,
                    () -> new LocalId(ManifestTomlValues.string(field)));
            repository = ManifestSemanticDiagnostics.construct(
                    field,
                    () -> new AuthoredPublicationRepository(
                            url, Optional.of(credentials)));
        }
        return new Decoded(id, repository);
    }

    record Decoded(LocalId id, AuthoredPublicationRepository repository) {
        Decoded {
            Objects.requireNonNull(id, "Publication repository ID is required.");
            Objects.requireNonNull(repository, "Authored publication repository is required.");
        }
    }
}

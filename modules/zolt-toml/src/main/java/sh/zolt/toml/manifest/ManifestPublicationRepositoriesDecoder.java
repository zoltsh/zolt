package sh.zolt.toml.manifest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestModelValues;
import sh.zolt.manifest.RepositoryUrl;
import sh.zolt.manifest.authored.AuthoredPublicationRepository;
import sh.zolt.toml.schema.FinalManifestPaths;
import sh.zolt.toml.schema.FinalManifestPublishingFields;

/** Collects named publication repositories with authored presence and source-order validation. */
final class ManifestPublicationRepositoriesDecoder {
    private final ManifestPublicationRepositoryDecoder repositoryDecoder =
            new ManifestPublicationRepositoryDecoder();

    Optional<Map<LocalId, AuthoredPublicationRepository>> decode(ManifestDecodeIndex index) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        List<ManifestDecodeIndex.SectionEntry> entries =
                index.sectionEntries(FinalManifestPaths.PUBLISH_REPOSITORY);
        if (index.section(FinalManifestPaths.PUBLISH_REPOSITORIES).isEmpty()
                && entries.isEmpty()) {
            return Optional.empty();
        }

        LinkedHashMap<LocalId, AuthoredPublicationRepository> repositories =
                new LinkedHashMap<>();
        for (ManifestDecodeIndex.SectionEntry entry : entries) {
            ManifestPublicationRepositoryDecoder.Decoded decoded =
                    repositoryDecoder.decode(index, entry);
            if (repositories.put(decoded.id(), decoded.repository()) != null) {
                throw new IllegalStateException(
                        "Validated manifest contains duplicate publication repository `"
                                + decoded.id() + "`.");
            }
        }
        return Optional.of(ManifestModelValues.immutableSortedMap(
                repositories,
                LocalId::compareTo,
                "Publication repository ID",
                "Publication repository"));
    }
}

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

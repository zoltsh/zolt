package sh.zolt.toml.manifest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestModelValues;
import sh.zolt.manifest.authored.AuthoredPublicationRepository;
import sh.zolt.toml.schema.FinalManifestPaths;

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

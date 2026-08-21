package sh.zolt.toml.manifest;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.PlatformSelector;
import sh.zolt.manifest.authored.AuthoredBom;
import sh.zolt.toml.schema.FinalManifestPaths;
import sh.zolt.toml.schema.ManifestPath;

/** Composes the independently optional authored BOM domains. */
final class ManifestBomDecoder {
    private final ManifestBomMembersDecoder members = new ManifestBomMembersDecoder();
    private final ManifestBomVersionsDecoder versions = new ManifestBomVersionsDecoder();
    private final ManifestBomImportsDecoder imports = new ManifestBomImportsDecoder();

    Optional<AuthoredBom> decode(
            ManifestDecodeIndex index,
            BomPresenceObserver observer) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        Objects.requireNonNull(observer, "Authored BOM presence observer is required.");
        Optional<AuthoredBom.Members> decodedMembers = members.decode(
                index,
                decoded -> observer.present(new AuthoredBom(
                        Optional.of(decoded), Optional.empty(), Optional.empty())));
        observeCollectionPresence(
                index,
                FinalManifestPaths.BOM_VERSIONS,
                new AuthoredBom(
                        decodedMembers, Optional.of(Map.of()), Optional.empty()),
                observer);
        Optional<Map<DependencyCoordinate, AuthoredBom.Version>> decodedVersions =
                versions.decode(index);
        observeCollectionPresence(
                index,
                FinalManifestPaths.BOM_IMPORTS,
                new AuthoredBom(
                        decodedMembers, decodedVersions, Optional.of(Map.of())),
                observer);
        Optional<Map<DependencyCoordinate, PlatformSelector>> decodedImports =
                imports.decode(index);
        if (decodedMembers.isEmpty()
                && decodedVersions.isEmpty()
                && decodedImports.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new AuthoredBom(
                decodedMembers, decodedVersions, decodedImports));
    }

    private static void observeCollectionPresence(
            ManifestDecodeIndex index,
            ManifestPath path,
            AuthoredBom partial,
            BomPresenceObserver observer) {
        index.section(path)
                .filter(section -> section.source().authoredTable())
                .ifPresent(section -> ManifestSemanticDiagnostics.construct(section, () -> {
                    observer.present(partial);
                    return partial;
                }));
    }

    @FunctionalInterface
    interface BomPresenceObserver {
        void present(AuthoredBom bom);
    }
}

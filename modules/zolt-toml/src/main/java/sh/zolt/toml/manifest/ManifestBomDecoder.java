package sh.zolt.toml.manifest;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.PlatformSelector;
import sh.zolt.manifest.authored.AuthoredBom;

/** Composes the independently optional authored BOM domains. */
final class ManifestBomDecoder {
    private final ManifestBomMembersDecoder members = new ManifestBomMembersDecoder();
    private final ManifestBomVersionsDecoder versions = new ManifestBomVersionsDecoder();
    private final ManifestBomImportsDecoder imports = new ManifestBomImportsDecoder();

    Optional<AuthoredBom> decode(ManifestDecodeIndex index) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        Optional<AuthoredBom.Members> decodedMembers = members.decode(index);
        Optional<Map<DependencyCoordinate, AuthoredBom.Version>> decodedVersions =
                versions.decode(index);
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
}

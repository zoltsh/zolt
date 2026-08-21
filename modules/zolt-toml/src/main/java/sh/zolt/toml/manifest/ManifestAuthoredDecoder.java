package sh.zolt.toml.manifest;

import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.authored.AuthoredBuildConfiguration;
import sh.zolt.manifest.authored.AuthoredCommands;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.manifest.authored.AuthoredPackaging;
import sh.zolt.manifest.authored.AuthoredPublishing;
import sh.zolt.manifest.authored.AuthoredToolchains;

/** Decodes the complete authored manifest in canonical cross-domain order. */
final class ManifestAuthoredDecoder {
    private static final ManifestSharedDecoder.Decoded EMPTY_SHARED =
            new ManifestSharedDecoder.Decoded(
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    private static final ManifestDependencyDecoder.Decoded EMPTY_DEPENDENCIES =
            new ManifestDependencyDecoder.Decoded(
                    Optional.empty(), Optional.empty(), Optional.empty());
    private static final ManifestBuildConfigurationDecoder.Decoded EMPTY_BUILD =
            new ManifestBuildConfigurationDecoder.Decoded(
                    AuthoredBuildConfiguration.empty(), Optional.empty());
    private static final AuthoredPackaging EMPTY_PACKAGING = AuthoredPackaging.empty();

    private final ManifestIdentityDecoder identityDecoder = new ManifestIdentityDecoder();
    private final ManifestToolchainDecoder toolchainDecoder = new ManifestToolchainDecoder();
    private final ManifestSharedDecoder sharedDecoder = new ManifestSharedDecoder();
    private final ManifestDependencyDecoder dependencyDecoder = new ManifestDependencyDecoder();
    private final ManifestBuildConfigurationDecoder buildDecoder =
            new ManifestBuildConfigurationDecoder();
    private final ManifestPackagingDecoder packagingDecoder = new ManifestPackagingDecoder();
    private final ManifestPublishingDecoder publishingDecoder = new ManifestPublishingDecoder();
    private final ManifestCommandsDecoder commandsDecoder = new ManifestCommandsDecoder();

    AuthoredManifest decode(ManifestDecodeIndex index) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        ManifestIdentityDecoder.Decoded identity = identityDecoder.decode(index);
        Prefix identityPrefix = new Prefix(identity, AuthoredToolchains.empty(), EMPTY_SHARED);
        ManifestSemanticDiagnostics.constructDocument(() -> identityPrefix.manifest(
                EMPTY_DEPENDENCIES,
                EMPTY_BUILD,
                EMPTY_PACKAGING,
                Optional.empty(),
                Optional.empty()));
        AuthoredToolchains toolchains = toolchainDecoder.decode(index);
        ManifestSharedDecoder.Decoded shared = sharedDecoder.decode(index);
        Prefix prefix = new Prefix(identity, toolchains, shared);
        ManifestDependencyDecoder.Decoded dependencies = dependencyDecoder.decode(
                index,
                partial -> prefix.manifest(
                        partial, EMPTY_BUILD, EMPTY_PACKAGING, Optional.empty(), Optional.empty()));
        ManifestBuildConfigurationDecoder.Decoded build = buildDecoder.decode(
                index,
                partial -> prefix.manifest(
                        dependencies, partial, EMPTY_PACKAGING, Optional.empty(), Optional.empty()));
        AuthoredPackaging packaging = packagingDecoder.decode(
                index,
                partial -> prefix.manifest(
                        dependencies, build, partial, Optional.empty(), Optional.empty()));
        Optional<AuthoredPublishing> publishing = publishingDecoder.decode(
                index,
                partial -> prefix.manifest(
                        dependencies, build, packaging, Optional.of(partial), Optional.empty()));
        Optional<AuthoredCommands> commands = commandsDecoder.decode(index);
        return ManifestSemanticDiagnostics.constructDocument(() -> prefix.manifest(
                dependencies, build, packaging, publishing, commands));
    }

    private record Prefix(
            ManifestIdentityDecoder.Decoded identity,
            AuthoredToolchains toolchains,
            ManifestSharedDecoder.Decoded shared) {
        private AuthoredManifest manifest(
                ManifestDependencyDecoder.Decoded dependencies,
                ManifestBuildConfigurationDecoder.Decoded build,
                AuthoredPackaging packaging,
                Optional<AuthoredPublishing> publishing,
                Optional<AuthoredCommands> commands) {
            return new AuthoredManifest(
                    identity.workspace(), identity.project(), toolchains,
                    shared.versions(), shared.repositories(), shared.credentials(), shared.platforms(),
                    dependencies.dependencies(), dependencies.constraints(), dependencies.policy(),
                    build.build(), build.generated(), packaging, publishing, commands);
        }
    }
}

package sh.zolt.toml.manifest.write;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import sh.zolt.manifest.CentralRepositoryControl;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.DependencyRepository;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestModelValues;
import sh.zolt.manifest.PlatformSelector;
import sh.zolt.manifest.RepositoryCredential;
import sh.zolt.manifest.authored.AuthoredCredentials;
import sh.zolt.manifest.authored.AuthoredDependencyRepositories;
import sh.zolt.manifest.authored.AuthoredPlatforms;
import sh.zolt.manifest.authored.AuthoredRepositoryControl;
import sh.zolt.manifest.authored.AuthoredVersionAliases;
import sh.zolt.toml.schema.FinalManifestObjectShapes;
import sh.zolt.toml.schema.FinalManifestPaths;
import sh.zolt.toml.schema.FinalManifestSchema;
import sh.zolt.toml.schema.FinalManifestSharedFields;
import sh.zolt.toml.schema.ManifestPath;
import sh.zolt.toml.schema.ManifestSection;

/** Emits canonical authored versions, repositories, credentials, and platforms. */
final class ManifestSharedWriter {
    private static final LocalId CENTRAL = new LocalId("central");
    private static final ManifestSection VERSIONS = section(FinalManifestPaths.VERSIONS);
    private static final ManifestSection REPOSITORIES =
            section(FinalManifestPaths.REPOSITORIES);
    private static final ManifestSection REPOSITORY = section(FinalManifestPaths.REPOSITORY);
    private static final ManifestSection CREDENTIAL = section(FinalManifestPaths.CREDENTIAL);
    private static final ManifestSection PLATFORMS = section(FinalManifestPaths.PLATFORMS);

    void write(
            ManifestTomlEmitter emitter,
            Optional<AuthoredVersionAliases> versions,
            Optional<AuthoredDependencyRepositories> repositories,
            Optional<AuthoredCredentials> credentials,
            Optional<AuthoredPlatforms> platforms) {
        Objects.requireNonNull(emitter, "Manifest TOML emitter is required.");
        Objects.requireNonNull(versions, "Authored versions are required.")
                .filter(value -> !value.entries().isEmpty())
                .ifPresent(value -> writeVersions(emitter, value));
        Objects.requireNonNull(repositories, "Authored repositories are required.")
                .ifPresent(value -> writeRepositories(emitter, value));
        Objects.requireNonNull(credentials, "Authored credentials are required.")
                .filter(value -> !value.entries().isEmpty())
                .ifPresent(value -> writeCredentials(emitter, value));
        Objects.requireNonNull(platforms, "Authored platforms are required.")
                .filter(value -> !value.entries().isEmpty())
                .ifPresent(value -> writePlatforms(emitter, value));
    }

    private static void writeVersions(
            ManifestTomlEmitter emitter, AuthoredVersionAliases versions) {
        emitter.section(VERSIONS);
        for (Map.Entry<LocalId, sh.zolt.manifest.VersionAliasValue> entry
                : sorted(versions.entries(), LocalId::value)) {
            emitter.dynamicField(
                    FinalManifestSharedFields.VERSIONS_ENTRY,
                    entry.getKey().value(),
                    string(entry.getValue().value()));
        }
    }

    private static void writeRepositories(
            ManifestTomlEmitter emitter, AuthoredDependencyRepositories repositories) {
        repositories.control().ifPresent(control -> writeRepositoryControl(
                emitter, repositories, control));
        for (Map.Entry<LocalId, DependencyRepository> entry
                : sorted(repositories.named(), LocalId::value)) {
            emitter.namedSection(REPOSITORY, entry.getKey().value());
            DependencyRepository repository = entry.getValue();
            emitter.field(
                    FinalManifestSharedFields.REPOSITORY_URL,
                    string(repository.url().value()));
            repository.credentials().ifPresent(value -> emitter.field(
                    FinalManifestSharedFields.REPOSITORY_CREDENTIALS,
                    string(value.value())));
        }
    }

    private static void writeRepositoryControl(
            ManifestTomlEmitter emitter,
            AuthoredDependencyRepositories repositories,
            AuthoredRepositoryControl control) {
        emitter.section(REPOSITORIES);
        control.central().ifPresent(value -> writeCentral(emitter, value));
        control.order()
                .filter(value -> !value.equals(defaultOrder(repositories)))
                .ifPresent(value -> emitter.field(
                        FinalManifestSharedFields.REPOSITORIES_ORDER,
                        stringArray(value.stream().map(LocalId::value).toList())));
    }

    private static void writeCentral(
            ManifestTomlEmitter emitter, CentralRepositoryControl central) {
        switch (central) {
            case CentralRepositoryControl.Enabled ignored -> {
                // Explicit true is the canonical default and is therefore omitted.
            }
            case CentralRepositoryControl.Disabled ignored -> emitter.field(
                    FinalManifestSharedFields.REPOSITORIES_CENTRAL,
                    ManifestTomlValueEncoder.booleanValue(false));
            case CentralRepositoryControl.Replacement replacement -> emitter.field(
                    FinalManifestSharedFields.REPOSITORIES_CENTRAL,
                    centralReplacement(replacement));
        }
    }

    private static String centralReplacement(
            CentralRepositoryControl.Replacement replacement) {
        if (replacement.credentials().isEmpty()) {
            return string(replacement.url().value());
        }
        return ManifestTomlValueEncoder.inlineObject(List.of(
                ManifestTomlValueEncoder.member(
                        FinalManifestObjectShapes.CENTRAL_URL.name(),
                        string(replacement.url().value())),
                ManifestTomlValueEncoder.member(
                        FinalManifestObjectShapes.CENTRAL_CREDENTIALS.name(),
                        string(replacement.credentials().orElseThrow().value()))));
    }

    private static List<LocalId> defaultOrder(
            AuthoredDependencyRepositories repositories) {
        ArrayList<LocalId> order = new ArrayList<>(repositories.named().keySet());
        order.sort(Comparator.comparing(LocalId::value, ManifestModelValues.CODE_POINT_ORDER));
        if (repositories.centralEnabled()) {
            order.add(CENTRAL);
        }
        return List.copyOf(order);
    }

    private static void writeCredentials(
            ManifestTomlEmitter emitter, AuthoredCredentials credentials) {
        for (Map.Entry<LocalId, RepositoryCredential> entry
                : sorted(credentials.entries(), LocalId::value)) {
            emitter.namedSection(CREDENTIAL, entry.getKey().value());
            switch (entry.getValue()) {
                case RepositoryCredential.BearerToken bearer -> emitter.field(
                        FinalManifestSharedFields.CREDENTIAL_TOKEN_ENV,
                        string(bearer.tokenEnvironment().value()));
                case RepositoryCredential.Basic basic -> {
                    emitter.field(
                            FinalManifestSharedFields.CREDENTIAL_USERNAME_ENV,
                            string(basic.usernameEnvironment().value()));
                    emitter.field(
                            FinalManifestSharedFields.CREDENTIAL_PASSWORD_ENV,
                            string(basic.passwordEnvironment().value()));
                }
            }
        }
    }

    private static void writePlatforms(
            ManifestTomlEmitter emitter, AuthoredPlatforms platforms) {
        emitter.section(PLATFORMS);
        for (Map.Entry<DependencyCoordinate, PlatformSelector> entry
                : sorted(platforms.entries(), DependencyCoordinate::value)) {
            emitter.dynamicField(
                    FinalManifestSharedFields.PLATFORMS_ENTRY,
                    entry.getKey().value(),
                    platformSelector(entry.getValue()));
        }
    }

    private static String platformSelector(PlatformSelector selector) {
        return switch (selector) {
            case PlatformSelector.FixedVersion fixed -> string(fixed.value());
            case PlatformSelector.VersionReference reference ->
                ManifestTomlValueEncoder.inlineObject(List.of(
                        ManifestTomlValueEncoder.member(
                                FinalManifestObjectShapes.PLATFORM_VERSION_REF.name(),
                                string(reference.alias().value()))));
        };
    }

    private static String string(String value) {
        return ManifestTomlValueEncoder.basicString(value);
    }

    private static String stringArray(List<String> values) {
        return ManifestTomlValueEncoder.array(values.stream()
                .map(ManifestSharedWriter::string)
                .toList());
    }

    private static <K, V> List<Map.Entry<K, V>> sorted(
            Map<K, V> values, Function<K, String> key) {
        return values.entrySet().stream()
                .sorted(Comparator.comparing(
                        entry -> key.apply(entry.getKey()),
                        ManifestModelValues.CODE_POINT_ORDER))
                .toList();
    }

    private static ManifestSection section(ManifestPath path) {
        return FinalManifestSchema.registry().section(path).orElseThrow();
    }
}

package sh.zolt.toml.manifest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.CentralRepositoryControl;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.DependencyRepository;
import sh.zolt.manifest.EnvironmentVariableName;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.PlatformSelector;
import sh.zolt.manifest.RepositoryCredential;
import sh.zolt.manifest.RepositoryUrl;
import sh.zolt.manifest.VersionAliasValue;
import sh.zolt.manifest.authored.AuthoredCredentials;
import sh.zolt.manifest.authored.AuthoredDependencyRepositories;
import sh.zolt.manifest.authored.AuthoredPlatforms;
import sh.zolt.manifest.authored.AuthoredRepositoryControl;
import sh.zolt.manifest.authored.AuthoredVersionAliases;
import sh.zolt.toml.schema.FinalManifestObjectShapes;
import sh.zolt.toml.schema.FinalManifestPaths;
import sh.zolt.toml.schema.FinalManifestSharedFields;
import sh.zolt.toml.schema.ManifestField;

/** Coordinates shared authored domains without applying workspace composition. */
final class ManifestSharedDecoder {
    private final ManifestVersionAliasesDecoder versions = new ManifestVersionAliasesDecoder();
    private final ManifestDependencyRepositoriesDecoder repositories =
            new ManifestDependencyRepositoriesDecoder();
    private final ManifestCredentialsDecoder credentials = new ManifestCredentialsDecoder();
    private final ManifestPlatformsDecoder platforms = new ManifestPlatformsDecoder();

    Decoded decode(ManifestDecodeIndex index) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        return new Decoded(
                versions.decode(index),
                repositories.decode(index),
                credentials.decode(index),
                platforms.decode(index));
    }

    record Decoded(
            Optional<AuthoredVersionAliases> versions,
            Optional<AuthoredDependencyRepositories> repositories,
            Optional<AuthoredCredentials> credentials,
            Optional<AuthoredPlatforms> platforms) {
        Decoded {
            versions = Objects.requireNonNull(versions, "Decoded versions must not be null.");
            repositories = Objects.requireNonNull(
                    repositories, "Decoded repositories must not be null.");
            credentials = Objects.requireNonNull(
                    credentials, "Decoded credentials must not be null.");
            platforms = Objects.requireNonNull(platforms, "Decoded platforms must not be null.");
        }
    }
}

/** Decodes the optional authored fixed-version alias collection. */
final class ManifestVersionAliasesDecoder {
    Optional<AuthoredVersionAliases> decode(ManifestDecodeIndex index) {
        return index.section(FinalManifestPaths.VERSIONS).map(section -> {
            Map<LocalId, VersionAliasValue> entries = entries(index);
            return ManifestSemanticDiagnostics.construct(
                    section, () -> new AuthoredVersionAliases(entries));
        });
    }

    private static Map<LocalId, VersionAliasValue> entries(ManifestDecodeIndex index) {
        LinkedHashMap<LocalId, VersionAliasValue> entries = new LinkedHashMap<>();
        for (ManifestDecodeIndex.Entry entry :
                index.entries(FinalManifestSharedFields.VERSIONS_ENTRY)) {
            ValidatedManifestField field = entry.field();
            LocalId id = ManifestSemanticDiagnostics.construct(
                    field, () -> new LocalId(entry.key()));
            VersionAliasValue value = ManifestSemanticDiagnostics.construct(
                    field,
                    () -> new VersionAliasValue(ManifestTomlValues.string(field)));
            if (entries.put(id, value) != null) {
                throw new IllegalStateException(
                        "Validated manifest contains duplicate version alias `" + id + "`.");
            }
        }
        return entries;
    }
}

/** Decodes the optional dependency-repository universe and exact lookup controls. */
final class ManifestDependencyRepositoriesDecoder {
    Optional<AuthoredDependencyRepositories> decode(ManifestDecodeIndex index) {
        return index.section(FinalManifestPaths.REPOSITORIES).map(section -> {
            Optional<AuthoredRepositoryControl> control = decodeControl(index, section);
            Map<LocalId, DependencyRepository> named = decodeNamed(index);
            return ManifestSemanticDiagnostics.construct(
                    section,
                    () -> new AuthoredDependencyRepositories(control, named));
        });
    }

    private static Optional<AuthoredRepositoryControl> decodeControl(
            ManifestDecodeIndex index,
            ValidatedManifestSection section) {
        Optional<CentralRepositoryControl> central = index
                .field(FinalManifestSharedFields.REPOSITORIES_CENTRAL)
                .map(ManifestDependencyRepositoriesDecoder::decodeCentral);
        Optional<List<LocalId>> order = index
                .field(FinalManifestSharedFields.REPOSITORIES_ORDER)
                .map(ManifestDependencyRepositoriesDecoder::decodeOrder);
        if (central.isEmpty() && order.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(ManifestSemanticDiagnostics.construct(
                section, () -> new AuthoredRepositoryControl(central, order)));
    }

    private static CentralRepositoryControl decodeCentral(ValidatedManifestField field) {
        if (ManifestTomlValues.isBoolean(field)) {
            return ManifestTomlValues.booleanValue(field)
                    ? new CentralRepositoryControl.Enabled()
                    : new CentralRepositoryControl.Disabled();
        }
        if (ManifestTomlValues.isString(field)) {
            RepositoryUrl url = ManifestSemanticDiagnostics.construct(
                    field, () -> new RepositoryUrl(ManifestTomlValues.string(field)));
            return new CentralRepositoryControl.Replacement(url, Optional.empty());
        }
        ManifestInlineTable table = ManifestTomlValues.inlineObject(field);
        RepositoryUrl url = ManifestSemanticDiagnostics.construct(
                table,
                FinalManifestObjectShapes.CENTRAL_URL,
                () -> new RepositoryUrl(
                        table.requiredString(FinalManifestObjectShapes.CENTRAL_URL)));
        Optional<LocalId> credentials = table
                .optionalString(FinalManifestObjectShapes.CENTRAL_CREDENTIALS)
                .map(value -> ManifestSemanticDiagnostics.construct(
                        table,
                        FinalManifestObjectShapes.CENTRAL_CREDENTIALS,
                        () -> new LocalId(value)));
        return new CentralRepositoryControl.Replacement(url, credentials);
    }

    private static List<LocalId> decodeOrder(ValidatedManifestField field) {
        return ManifestSemanticDiagnostics.construct(
                field,
                () -> ManifestTomlValues.strings(field).stream()
                        .map(LocalId::new)
                        .toList());
    }

    private static Map<LocalId, DependencyRepository> decodeNamed(
            ManifestDecodeIndex index) {
        LinkedHashMap<LocalId, DependencyRepository> named = new LinkedHashMap<>();
        for (ManifestDecodeIndex.SectionEntry entry :
                index.sectionEntries(FinalManifestPaths.REPOSITORY)) {
            LocalId id = ManifestSemanticDiagnostics.construct(
                    entry.section(), () -> new LocalId(entry.key()));
            DependencyRepository repository = decodeNamed(index, entry);
            if (named.put(id, repository) != null) {
                throw new IllegalStateException(
                        "Validated manifest contains duplicate repository `" + id + "`.");
            }
        }
        return named;
    }

    private static DependencyRepository decodeNamed(
            ManifestDecodeIndex index,
            ManifestDecodeIndex.SectionEntry entry) {
        ValidatedManifestField urlField = ManifestSemanticDiagnostics.requiredField(
                index, entry, FinalManifestSharedFields.REPOSITORY_URL);
        RepositoryUrl url = ManifestSemanticDiagnostics.construct(
                urlField, () -> new RepositoryUrl(ManifestTomlValues.string(urlField)));
        Optional<LocalId> credentials = index
                .field(entry, FinalManifestSharedFields.REPOSITORY_CREDENTIALS)
                .map(field -> ManifestSemanticDiagnostics.construct(
                        field, () -> new LocalId(ManifestTomlValues.string(field))));
        return ManifestSemanticDiagnostics.construct(
                entry.section(), () -> new DependencyRepository(url, credentials));
    }
}

/** Decodes exact environment-backed repository credential forms. */
final class ManifestCredentialsDecoder {
    Optional<AuthoredCredentials> decode(ManifestDecodeIndex index) {
        return index.section(FinalManifestPaths.CREDENTIALS).map(section -> {
            Map<LocalId, RepositoryCredential> entries = decodeEntries(index);
            return ManifestSemanticDiagnostics.construct(
                    section, () -> new AuthoredCredentials(entries));
        });
    }

    private static Map<LocalId, RepositoryCredential> decodeEntries(
            ManifestDecodeIndex index) {
        LinkedHashMap<LocalId, RepositoryCredential> credentials = new LinkedHashMap<>();
        for (ManifestDecodeIndex.SectionEntry entry :
                index.sectionEntries(FinalManifestPaths.CREDENTIAL)) {
            LocalId id = ManifestSemanticDiagnostics.construct(
                    entry.section(), () -> new LocalId(entry.key()));
            RepositoryCredential credential = decodeEntry(index, entry);
            if (credentials.put(id, credential) != null) {
                throw new IllegalStateException(
                        "Validated manifest contains duplicate credential `" + id + "`.");
            }
        }
        return credentials;
    }

    private static RepositoryCredential decodeEntry(
            ManifestDecodeIndex index,
            ManifestDecodeIndex.SectionEntry entry) {
        Optional<EnvironmentVariableName> token = environment(
                index, entry, FinalManifestSharedFields.CREDENTIAL_TOKEN_ENV);
        Optional<EnvironmentVariableName> username = environment(
                index, entry, FinalManifestSharedFields.CREDENTIAL_USERNAME_ENV);
        Optional<EnvironmentVariableName> password = environment(
                index, entry, FinalManifestSharedFields.CREDENTIAL_PASSWORD_ENV);
        return ManifestSemanticDiagnostics.construct(entry.section(), () -> {
            if (token.isPresent() && username.isEmpty() && password.isEmpty()) {
                return new RepositoryCredential.BearerToken(token.orElseThrow());
            }
            if (token.isEmpty() && username.isPresent() && password.isPresent()) {
                return new RepositoryCredential.Basic(
                        username.orElseThrow(), password.orElseThrow());
            }
            throw new IllegalArgumentException(
                    "Credential must declare exactly `tokenEnv` or both `usernameEnv` and `passwordEnv`.");
        });
    }

    private static Optional<EnvironmentVariableName> environment(
            ManifestDecodeIndex index,
            ManifestDecodeIndex.SectionEntry entry,
            ManifestField handle) {
        return index.field(entry, handle).map(field -> ManifestSemanticDiagnostics.construct(
                field,
                () -> new EnvironmentVariableName(ManifestTomlValues.string(field))));
    }
}

/** Decodes fixed and alias-referenced imported platform selectors. */
final class ManifestPlatformsDecoder {
    Optional<AuthoredPlatforms> decode(ManifestDecodeIndex index) {
        return index.section(FinalManifestPaths.PLATFORMS).map(section -> {
            Map<DependencyCoordinate, PlatformSelector> entries = decodeEntries(index);
            return ManifestSemanticDiagnostics.construct(
                    section, () -> new AuthoredPlatforms(entries));
        });
    }

    private static Map<DependencyCoordinate, PlatformSelector> decodeEntries(
            ManifestDecodeIndex index) {
        LinkedHashMap<DependencyCoordinate, PlatformSelector> platforms = new LinkedHashMap<>();
        for (ManifestDecodeIndex.Entry entry :
                index.entries(FinalManifestSharedFields.PLATFORMS_ENTRY)) {
            ValidatedManifestField field = entry.field();
            DependencyCoordinate coordinate = ManifestSemanticDiagnostics.construct(
                    field, () -> new DependencyCoordinate(entry.key()));
            PlatformSelector selector = ManifestPlatformSelectorDecoder.decode(field);
            if (platforms.put(coordinate, selector) != null) {
                throw new IllegalStateException(
                        "Validated manifest contains duplicate platform `" + coordinate + "`.");
            }
        }
        return platforms;
    }

}

/** Decodes the shared fixed-version or version-alias selector union. */
final class ManifestPlatformSelectorDecoder {
    private ManifestPlatformSelectorDecoder() {
    }

    static PlatformSelector decode(ValidatedManifestField field) {
        Objects.requireNonNull(field, "Validated platform-selector field is required.");
        if (ManifestTomlValues.isString(field)) {
            return ManifestSemanticDiagnostics.construct(
                    field,
                    () -> new PlatformSelector.FixedVersion(
                            ManifestTomlValues.string(field)));
        }
        ManifestInlineTable table = ManifestTomlValues.inlineObject(field);
        Optional<String> version = table.optionalString(
                FinalManifestObjectShapes.PLATFORM_VERSION);
        if (version.isPresent()) {
            return ManifestSemanticDiagnostics.construct(
                    table,
                    FinalManifestObjectShapes.PLATFORM_VERSION,
                    () -> new PlatformSelector.FixedVersion(version.orElseThrow()));
        }
        String versionRef = table.requiredString(
                FinalManifestObjectShapes.PLATFORM_VERSION_REF);
        return ManifestSemanticDiagnostics.construct(
                table,
                FinalManifestObjectShapes.PLATFORM_VERSION_REF,
                () -> new PlatformSelector.VersionReference(new LocalId(versionRef)));
    }
}

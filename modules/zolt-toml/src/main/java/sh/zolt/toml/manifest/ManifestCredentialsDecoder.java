package sh.zolt.toml.manifest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import sh.zolt.manifest.EnvironmentVariableName;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.RepositoryCredential;
import sh.zolt.manifest.authored.AuthoredCredentials;
import sh.zolt.toml.schema.FinalManifestPaths;
import sh.zolt.toml.schema.FinalManifestSharedFields;
import sh.zolt.toml.schema.ManifestField;

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

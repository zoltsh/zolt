package sh.zolt.toml.manifest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import sh.zolt.manifest.JavaBinaryClassName;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestModelValues;
import sh.zolt.manifest.authored.AuthoredProjectDeveloper;
import sh.zolt.manifest.authored.AuthoredProjectMetadata;
import sh.zolt.manifest.authored.AuthoredProjectScm;
import sh.zolt.toml.schema.FinalManifestIdentityFields;
import sh.zolt.toml.schema.FinalManifestPaths;
import sh.zolt.toml.schema.ManifestField;

/** Decodes project-local publication metadata and named developer rows. */
final class ManifestProjectMetadataDecoder {
    AuthoredProjectMetadata decode(
            ManifestDecodeIndex index,
            ValidatedManifestSection projectSection) {
        Optional<JavaBinaryClassName> main = optional(
                index,
                FinalManifestIdentityFields.PROJECT_MAIN,
                JavaBinaryClassName::new);
        Optional<String> description = optionalNonBlank(
                index,
                FinalManifestIdentityFields.PROJECT_DESCRIPTION,
                "Project description");
        Optional<String> url = optionalNonBlank(
                index, FinalManifestIdentityFields.PROJECT_URL, "Project URL");
        Optional<String> issues = optionalNonBlank(
                index, FinalManifestIdentityFields.PROJECT_ISSUES, "Project issues URL");
        Optional<AuthoredProjectScm> scm = index.section(FinalManifestPaths.PROJECT_SCM)
                .map(section -> decodeScm(index, section));
        Map<LocalId, AuthoredProjectDeveloper> developers = decodeDevelopers(index);
        return ManifestSemanticDiagnostics.construct(
                projectSection,
                () -> new AuthoredProjectMetadata(
                        main, description, url, issues, scm, developers));
    }

    private static AuthoredProjectScm decodeScm(
            ManifestDecodeIndex index,
            ValidatedManifestSection section) {
        Optional<String> url = optionalNonBlank(
                index, FinalManifestIdentityFields.PROJECT_SCM_URL, "Project SCM URL");
        Optional<String> connection = optionalNonBlank(
                index,
                FinalManifestIdentityFields.PROJECT_SCM_CONNECTION,
                "Project SCM connection");
        Optional<String> developerConnection = optionalNonBlank(
                index,
                FinalManifestIdentityFields.PROJECT_SCM_DEVELOPER_CONNECTION,
                "Project SCM developer connection");
        Optional<String> tag = optionalNonBlank(
                index, FinalManifestIdentityFields.PROJECT_SCM_TAG, "Project SCM tag");
        return ManifestSemanticDiagnostics.construct(
                section,
                () -> new AuthoredProjectScm(url, connection, developerConnection, tag));
    }

    private static Map<LocalId, AuthoredProjectDeveloper> decodeDevelopers(
            ManifestDecodeIndex index) {
        LinkedHashMap<LocalId, AuthoredProjectDeveloper> developers = new LinkedHashMap<>();
        for (ManifestDecodeIndex.SectionEntry entry :
                index.sectionEntries(FinalManifestPaths.PROJECT_DEVELOPER)) {
            LocalId id = ManifestSemanticDiagnostics.construct(
                    entry.section(), () -> new LocalId(entry.key()));
            AuthoredProjectDeveloper developer = decodeDeveloper(index, entry);
            if (developers.put(id, developer) != null) {
                throw new IllegalStateException(
                        "Validated manifest contains duplicate developer `" + id + "`.");
            }
        }
        return developers;
    }

    private static AuthoredProjectDeveloper decodeDeveloper(
            ManifestDecodeIndex index,
            ManifestDecodeIndex.SectionEntry entry) {
        Optional<String> name = optionalNonBlank(
                index, entry, FinalManifestIdentityFields.PROJECT_DEVELOPER_NAME,
                "Project developer name");
        Optional<String> email = optionalNonBlank(
                index, entry, FinalManifestIdentityFields.PROJECT_DEVELOPER_EMAIL,
                "Project developer email");
        Optional<String> organization = optionalNonBlank(
                index, entry, FinalManifestIdentityFields.PROJECT_DEVELOPER_ORGANIZATION,
                "Project developer organization");
        Optional<String> url = optionalNonBlank(
                index, entry, FinalManifestIdentityFields.PROJECT_DEVELOPER_URL,
                "Project developer URL");
        return ManifestSemanticDiagnostics.construct(
                entry.section(),
                () -> new AuthoredProjectDeveloper(name, email, organization, url));
    }

    private static Optional<String> optionalNonBlank(
            ManifestDecodeIndex index,
            ManifestField handle,
            String label) {
        return optional(index, handle, value -> nonBlank(value, label));
    }

    private static Optional<String> optionalNonBlank(
            ManifestDecodeIndex index,
            ManifestDecodeIndex.SectionEntry entry,
            ManifestField handle,
            String label) {
        return index.field(entry, handle).map(field -> ManifestSemanticDiagnostics.construct(
                field,
                () -> nonBlank(ManifestTomlValues.string(field), label)));
    }

    private static <T> Optional<T> optional(
            ManifestDecodeIndex index,
            ManifestField handle,
            Function<String, T> factory) {
        return index.field(handle).map(field -> ManifestSemanticDiagnostics.construct(
                field,
                () -> factory.apply(ManifestTomlValues.string(field))));
    }

    private static String nonBlank(String value, String label) {
        ManifestModelValues.requireNonBlank(value, label);
        return value;
    }
}

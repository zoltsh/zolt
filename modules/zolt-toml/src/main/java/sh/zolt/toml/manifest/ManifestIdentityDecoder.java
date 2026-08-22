package sh.zolt.toml.manifest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import sh.zolt.manifest.JavaBinaryClassName;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestModelValues;
import sh.zolt.manifest.ProjectGroup;
import sh.zolt.manifest.ProjectLicense;
import sh.zolt.manifest.ProjectName;
import sh.zolt.manifest.ProjectVersion;
import sh.zolt.manifest.WorkspaceMemberPath;
import sh.zolt.manifest.WorkspaceMemberPattern;
import sh.zolt.manifest.authored.AuthoredProject;
import sh.zolt.manifest.authored.AuthoredProjectDeveloper;
import sh.zolt.manifest.authored.AuthoredProjectIdentity;
import sh.zolt.manifest.authored.AuthoredProjectMetadata;
import sh.zolt.manifest.authored.AuthoredProjectScm;
import sh.zolt.manifest.authored.AuthoredWorkspace;
import sh.zolt.manifest.authored.AuthoredWorkspaceMembers;
import sh.zolt.manifest.authored.AuthoredWorkspaceProjectDefaults;
import sh.zolt.project.toolchain.JavaFeatureRelease;
import sh.zolt.toml.schema.FinalManifestIdentityFields;
import sh.zolt.toml.schema.FinalManifestPaths;
import sh.zolt.toml.schema.ManifestField;

/** Decodes final workspace and project identity without applying effective-value rules. */
final class ManifestIdentityDecoder {
    private final ManifestWorkspaceMembersDecoder workspaceMembers =
            new ManifestWorkspaceMembersDecoder();
    private final ManifestProjectMetadataDecoder projectMetadata =
            new ManifestProjectMetadataDecoder();
    private final ManifestLicenseDecoder licenses = new ManifestLicenseDecoder();

    Decoded decode(ManifestDecodeIndex index) {
        return new Decoded(decodeWorkspace(index), decodeProject(index));
    }

    /** Authored workspace and project domains decoded without applying workspace context. */
    record Decoded(
            Optional<AuthoredWorkspace> workspace,
            Optional<AuthoredProject> project) {
        Decoded {
            workspace = Objects.requireNonNull(workspace, "Decoded workspace must not be null.");
            project = Objects.requireNonNull(project, "Decoded project must not be null.");
        }
    }

    private Optional<AuthoredWorkspace> decodeWorkspace(ManifestDecodeIndex index) {
        return index.section(FinalManifestPaths.WORKSPACE).map(section -> {
            ValidatedManifestField nameField = ManifestSemanticDiagnostics.requiredField(
                    index, FinalManifestIdentityFields.WORKSPACE_NAME);
            LocalId name = ManifestSemanticDiagnostics.construct(
                    nameField,
                    () -> new LocalId(ManifestTomlValues.string(nameField)));
            AuthoredWorkspaceMembers members = workspaceMembers.decode(index);
            Optional<AuthoredWorkspaceProjectDefaults> defaults =
                    decodeWorkspaceProjectDefaults(index);
            return ManifestSemanticDiagnostics.construct(
                    section, () -> new AuthoredWorkspace(name, members, defaults));
        });
    }

    private Optional<AuthoredWorkspaceProjectDefaults> decodeWorkspaceProjectDefaults(
            ManifestDecodeIndex index) {
        return index.section(FinalManifestPaths.WORKSPACE_PROJECT).map(section -> {
            Optional<ProjectGroup> group = optionalString(
                    index,
                    FinalManifestIdentityFields.WORKSPACE_PROJECT_GROUP,
                    ProjectGroup::new);
            Optional<ProjectVersion> version = optionalString(
                    index,
                    FinalManifestIdentityFields.WORKSPACE_PROJECT_VERSION,
                    ProjectVersion::new);
            Optional<JavaFeatureRelease> javaRelease = optionalJavaRelease(
                    index, FinalManifestIdentityFields.WORKSPACE_PROJECT_JAVA);
            Optional<ProjectLicense> license = index
                    .field(FinalManifestIdentityFields.WORKSPACE_PROJECT_LICENSE)
                    .map(licenses::decode);
            return ManifestSemanticDiagnostics.construct(
                    section,
                    () -> new AuthoredWorkspaceProjectDefaults(
                            group, version, javaRelease, license));
        });
    }

    private Optional<AuthoredProject> decodeProject(ManifestDecodeIndex index) {
        return index.section(FinalManifestPaths.PROJECT).map(section -> {
            ValidatedManifestField nameField = ManifestSemanticDiagnostics.requiredField(
                    index, FinalManifestIdentityFields.PROJECT_NAME);
            ProjectName name = ManifestSemanticDiagnostics.construct(
                    nameField,
                    () -> new ProjectName(ManifestTomlValues.string(nameField)));
            Optional<ProjectVersion> version = optionalString(
                    index, FinalManifestIdentityFields.PROJECT_VERSION, ProjectVersion::new);
            Optional<ProjectGroup> group = optionalString(
                    index, FinalManifestIdentityFields.PROJECT_GROUP, ProjectGroup::new);
            Optional<JavaFeatureRelease> javaRelease = optionalJavaRelease(
                    index, FinalManifestIdentityFields.PROJECT_JAVA);
            Optional<ProjectLicense> license = index
                    .field(FinalManifestIdentityFields.PROJECT_LICENSE)
                    .map(licenses::decode);
            AuthoredProjectIdentity identity = ManifestSemanticDiagnostics.construct(
                    section,
                    () -> new AuthoredProjectIdentity(
                            name, version, group, javaRelease, license));
            AuthoredProjectMetadata metadata = projectMetadata.decode(index, section);
            return ManifestSemanticDiagnostics.construct(
                    section, () -> new AuthoredProject(identity, metadata));
        });
    }

    private static Optional<JavaFeatureRelease> optionalJavaRelease(
            ManifestDecodeIndex index,
            ManifestField handle) {
        return index.field(handle).map(field -> ManifestSemanticDiagnostics.construct(
                field,
                () -> new JavaFeatureRelease(checkedInteger(field))));
    }

    private static int checkedInteger(ValidatedManifestField field) {
        long value = ManifestTomlValues.integer(field);
        try {
            return Math.toIntExact(value);
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException(
                    "Java feature release is outside the supported integer range.", failure);
        }
    }

    private static <T> Optional<T> optionalString(
            ManifestDecodeIndex index,
            ManifestField handle,
            Function<String, T> factory) {
        return index.field(handle).map(field -> ManifestSemanticDiagnostics.construct(
                field,
                () -> factory.apply(ManifestTomlValues.string(field))));
    }
}

/** Decodes workspace membership while retaining field-specific aggregate diagnostics. */
final class ManifestWorkspaceMembersDecoder {
    AuthoredWorkspaceMembers decode(ManifestDecodeIndex index) {
        ManifestSemanticDiagnostics.requiredSection(index, FinalManifestPaths.WORKSPACE_MEMBERS);
        ValidatedManifestField includeField = ManifestSemanticDiagnostics.requiredField(
                index, FinalManifestIdentityFields.WORKSPACE_MEMBERS_INCLUDE);
        AuthoredWorkspaceMembers members = ManifestSemanticDiagnostics.construct(
                includeField,
                () -> new AuthoredWorkspaceMembers(
                        patterns(includeField), List.of(), Optional.empty()));

        Optional<ValidatedManifestField> excludeField =
                index.field(FinalManifestIdentityFields.WORKSPACE_MEMBERS_EXCLUDE);
        if (excludeField.isPresent()) {
            ValidatedManifestField field = excludeField.orElseThrow();
            AuthoredWorkspaceMembers prior = members;
            members = ManifestSemanticDiagnostics.construct(
                    field,
                    () -> new AuthoredWorkspaceMembers(
                            prior.include(), patterns(field), prior.defaultMembers()));
        }

        Optional<ValidatedManifestField> defaultField =
                index.field(FinalManifestIdentityFields.WORKSPACE_MEMBERS_DEFAULT);
        if (defaultField.isPresent()) {
            ValidatedManifestField field = defaultField.orElseThrow();
            AuthoredWorkspaceMembers prior = members;
            members = ManifestSemanticDiagnostics.construct(
                    field,
                    () -> new AuthoredWorkspaceMembers(
                            prior.include(), prior.exclude(), Optional.of(paths(field))));
        }
        return members;
    }

    private static List<WorkspaceMemberPattern> patterns(ValidatedManifestField field) {
        return ManifestTomlValues.strings(field).stream()
                .map(WorkspaceMemberPattern::new)
                .toList();
    }

    private static List<WorkspaceMemberPath> paths(ValidatedManifestField field) {
        return ManifestTomlValues.strings(field).stream()
                .map(WorkspaceMemberPath::new)
                .toList();
    }
}

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

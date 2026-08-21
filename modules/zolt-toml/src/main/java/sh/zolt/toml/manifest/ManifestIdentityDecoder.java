package sh.zolt.toml.manifest;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ProjectGroup;
import sh.zolt.manifest.ProjectLicense;
import sh.zolt.manifest.ProjectName;
import sh.zolt.manifest.ProjectVersion;
import sh.zolt.manifest.authored.AuthoredProject;
import sh.zolt.manifest.authored.AuthoredProjectIdentity;
import sh.zolt.manifest.authored.AuthoredProjectMetadata;
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

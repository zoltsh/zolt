package sh.zolt.toml.manifest.write;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ProjectLicense;
import sh.zolt.manifest.authored.AuthoredProject;
import sh.zolt.manifest.authored.AuthoredProjectDeveloper;
import sh.zolt.manifest.authored.AuthoredProjectIdentity;
import sh.zolt.manifest.authored.AuthoredProjectMetadata;
import sh.zolt.manifest.authored.AuthoredProjectScm;
import sh.zolt.manifest.authored.AuthoredWorkspace;
import sh.zolt.manifest.authored.AuthoredWorkspaceMembers;
import sh.zolt.manifest.authored.AuthoredWorkspaceProjectDefaults;
import sh.zolt.toml.schema.FinalManifestIdentityFields;
import sh.zolt.toml.schema.FinalManifestObjectShapes;
import sh.zolt.toml.schema.FinalManifestPaths;
import sh.zolt.toml.schema.FinalManifestSchema;
import sh.zolt.toml.schema.ManifestPath;
import sh.zolt.toml.schema.ManifestSection;

/** Emits authored workspace and project identity without materializing effective defaults. */
final class ManifestIdentityWriter {
    private static final ManifestSection WORKSPACE = section(FinalManifestPaths.WORKSPACE);
    private static final ManifestSection WORKSPACE_MEMBERS =
            section(FinalManifestPaths.WORKSPACE_MEMBERS);
    private static final ManifestSection WORKSPACE_PROJECT =
            section(FinalManifestPaths.WORKSPACE_PROJECT);
    private static final ManifestSection PROJECT = section(FinalManifestPaths.PROJECT);
    private static final ManifestSection PROJECT_SCM = section(FinalManifestPaths.PROJECT_SCM);
    private static final ManifestSection PROJECT_DEVELOPER =
            section(FinalManifestPaths.PROJECT_DEVELOPER);

    void write(
            ManifestTomlEmitter emitter,
            Optional<AuthoredWorkspace> workspace,
            Optional<AuthoredProject> project) {
        Objects.requireNonNull(emitter, "Manifest TOML emitter is required.");
        Objects.requireNonNull(workspace, "Authored workspace is required.")
                .ifPresent(value -> writeWorkspace(emitter, value));
        Objects.requireNonNull(project, "Authored project is required.")
                .ifPresent(value -> writeProject(emitter, value));
    }

    private static void writeWorkspace(ManifestTomlEmitter emitter, AuthoredWorkspace workspace) {
        emitter.section(WORKSPACE);
        emitter.field(
                FinalManifestIdentityFields.WORKSPACE_NAME,
                string(workspace.name().value()));
        writeMembers(emitter, workspace.members());
        workspace.projectDefaults().ifPresent(defaults -> writeProjectDefaults(emitter, defaults));
    }

    private static void writeMembers(
            ManifestTomlEmitter emitter, AuthoredWorkspaceMembers members) {
        emitter.section(WORKSPACE_MEMBERS);
        members.defaultMembers().ifPresent(values -> emitter.field(
                FinalManifestIdentityFields.WORKSPACE_MEMBERS_DEFAULT,
                stringArray(values.stream().map(value -> value.value()).toList())));
        emitter.field(
                FinalManifestIdentityFields.WORKSPACE_MEMBERS_INCLUDE,
                stringArray(members.include().stream().map(value -> value.value()).toList()));
        if (!members.exclude().isEmpty()) {
            emitter.field(
                    FinalManifestIdentityFields.WORKSPACE_MEMBERS_EXCLUDE,
                    stringArray(members.exclude().stream().map(value -> value.value()).toList()));
        }
    }

    private static void writeProjectDefaults(
            ManifestTomlEmitter emitter, AuthoredWorkspaceProjectDefaults defaults) {
        emitter.section(WORKSPACE_PROJECT);
        defaults.group().ifPresent(value -> emitter.field(
                FinalManifestIdentityFields.WORKSPACE_PROJECT_GROUP, string(value.value())));
        defaults.version().ifPresent(value -> emitter.field(
                FinalManifestIdentityFields.WORKSPACE_PROJECT_VERSION, string(value.value())));
        defaults.javaRelease().ifPresent(value -> emitter.field(
                FinalManifestIdentityFields.WORKSPACE_PROJECT_JAVA,
                ManifestTomlValueEncoder.integer(value.value())));
        defaults.license().ifPresent(value -> emitter.field(
                FinalManifestIdentityFields.WORKSPACE_PROJECT_LICENSE, license(value)));
    }

    private static void writeProject(ManifestTomlEmitter emitter, AuthoredProject project) {
        AuthoredProjectIdentity identity = project.identity();
        AuthoredProjectMetadata metadata = project.metadata();
        emitter.section(PROJECT);
        emitter.field(FinalManifestIdentityFields.PROJECT_NAME, string(identity.name().value()));
        identity.version().ifPresent(value -> emitter.field(
                FinalManifestIdentityFields.PROJECT_VERSION, string(value.value())));
        identity.group().ifPresent(value -> emitter.field(
                FinalManifestIdentityFields.PROJECT_GROUP, string(value.value())));
        identity.javaRelease().ifPresent(value -> emitter.field(
                FinalManifestIdentityFields.PROJECT_JAVA,
                ManifestTomlValueEncoder.integer(value.value())));
        metadata.main().ifPresent(value -> emitter.field(
                FinalManifestIdentityFields.PROJECT_MAIN, string(value.value())));
        metadata.description().ifPresent(value -> emitter.field(
                FinalManifestIdentityFields.PROJECT_DESCRIPTION, string(value)));
        metadata.url().ifPresent(value ->
                emitter.field(FinalManifestIdentityFields.PROJECT_URL, string(value)));
        metadata.issues().ifPresent(value ->
                emitter.field(FinalManifestIdentityFields.PROJECT_ISSUES, string(value)));
        identity.license().ifPresent(value -> emitter.field(
                FinalManifestIdentityFields.PROJECT_LICENSE, license(value)));
        metadata.scm().ifPresent(value -> writeScm(emitter, value));
        for (Map.Entry<LocalId, AuthoredProjectDeveloper> entry
                : metadata.developers().entrySet()) {
            writeDeveloper(emitter, entry.getKey(), entry.getValue());
        }
    }

    private static void writeScm(ManifestTomlEmitter emitter, AuthoredProjectScm scm) {
        emitter.section(PROJECT_SCM);
        scm.url().ifPresent(value ->
                emitter.field(FinalManifestIdentityFields.PROJECT_SCM_URL, string(value)));
        scm.connection().ifPresent(value -> emitter.field(
                FinalManifestIdentityFields.PROJECT_SCM_CONNECTION, string(value)));
        scm.developerConnection().ifPresent(value -> emitter.field(
                FinalManifestIdentityFields.PROJECT_SCM_DEVELOPER_CONNECTION, string(value)));
        scm.tag().ifPresent(value ->
                emitter.field(FinalManifestIdentityFields.PROJECT_SCM_TAG, string(value)));
    }

    private static void writeDeveloper(
            ManifestTomlEmitter emitter,
            LocalId id,
            AuthoredProjectDeveloper developer) {
        emitter.namedSection(PROJECT_DEVELOPER, id.value());
        developer.name().ifPresent(value -> emitter.field(
                FinalManifestIdentityFields.PROJECT_DEVELOPER_NAME, string(value)));
        developer.email().ifPresent(value -> emitter.field(
                FinalManifestIdentityFields.PROJECT_DEVELOPER_EMAIL, string(value)));
        developer.organization().ifPresent(value -> emitter.field(
                FinalManifestIdentityFields.PROJECT_DEVELOPER_ORGANIZATION, string(value)));
        developer.url().ifPresent(value -> emitter.field(
                FinalManifestIdentityFields.PROJECT_DEVELOPER_URL, string(value)));
    }

    private static String license(ProjectLicense license) {
        if (license instanceof ProjectLicense.Identifier identifier) {
            return string(identifier.id());
        }
        ProjectLicense.Metadata metadata = (ProjectLicense.Metadata) license;
        List<ManifestTomlValueEncoder.InlineMember> members = new ArrayList<>(3);
        metadata.id().ifPresent(value -> members.add(ManifestTomlValueEncoder.member(
                FinalManifestObjectShapes.LICENSE_ID.name(), string(value))));
        metadata.name().ifPresent(value -> members.add(ManifestTomlValueEncoder.member(
                FinalManifestObjectShapes.LICENSE_NAME.name(), string(value))));
        metadata.url().ifPresent(value -> members.add(ManifestTomlValueEncoder.member(
                FinalManifestObjectShapes.LICENSE_URL.name(), string(value))));
        return ManifestTomlValueEncoder.inlineObject(members);
    }

    private static String string(String value) {
        return ManifestTomlValueEncoder.basicString(value);
    }

    private static String stringArray(List<String> values) {
        return ManifestTomlValueEncoder.array(values.stream()
                .map(ManifestIdentityWriter::string)
                .toList());
    }

    private static ManifestSection section(ManifestPath path) {
        return FinalManifestSchema.registry().section(path).orElseThrow();
    }
}

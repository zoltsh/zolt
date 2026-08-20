package sh.zolt.toml.manifest;

import java.util.List;
import java.util.Optional;
import sh.zolt.manifest.WorkspaceMemberPath;
import sh.zolt.manifest.WorkspaceMemberPattern;
import sh.zolt.manifest.authored.AuthoredWorkspaceMembers;
import sh.zolt.toml.schema.FinalManifestIdentityFields;
import sh.zolt.toml.schema.FinalManifestPaths;

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

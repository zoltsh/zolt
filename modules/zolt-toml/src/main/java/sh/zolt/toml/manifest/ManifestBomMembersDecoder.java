package sh.zolt.toml.manifest;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.WorkspaceMemberPath;
import sh.zolt.manifest.authored.AuthoredBom;
import sh.zolt.toml.schema.FinalManifestPackagingFields;

/** Decodes an explicitly authored BOM member selection and exact exclusions. */
final class ManifestBomMembersDecoder {
    Optional<AuthoredBom.Members> decode(
            ManifestDecodeIndex index,
            DecodedMembersObserver observer) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        Objects.requireNonNull(observer, "Decoded BOM members observer is required.");
        Optional<ValidatedManifestField> membersField =
                index.field(FinalManifestPackagingFields.BOM_MEMBERS);
        Optional<ValidatedManifestField> excludeField =
                index.field(FinalManifestPackagingFields.BOM_EXCLUDE);
        if (membersField.isEmpty() && excludeField.isEmpty()) {
            return Optional.empty();
        }

        ValidatedManifestField requiredMembers = ManifestSemanticDiagnostics.requiredField(
                index, FinalManifestPackagingFields.BOM_MEMBERS);
        AuthoredBom.MemberSelection selection = selection(requiredMembers);
        AuthoredBom.Members selected = ManifestSemanticDiagnostics.construct(
                requiredMembers,
                () -> {
                    AuthoredBom.Members value =
                            new AuthoredBom.Members(selection, List.of());
                    observer.decoded(value);
                    return value;
                });
        if (excludeField.isEmpty()) {
            return Optional.of(selected);
        }

        ValidatedManifestField exclusions = excludeField.orElseThrow();
        if (!(selection instanceof AuthoredBom.AllMembers)) {
            return Optional.of(ManifestSemanticDiagnostics.construct(exclusions, () -> {
                throw new IllegalArgumentException(
                        "BOM member exclusions are valid only with `members = true`.");
            }));
        }
        return Optional.of(exclusions(selection, exclusions));
    }

    private static AuthoredBom.MemberSelection selection(ValidatedManifestField field) {
        if (ManifestTomlValues.isBoolean(field)) {
            return ManifestSemanticDiagnostics.construct(field, () -> {
                if (!ManifestTomlValues.booleanValue(field)) {
                    throw new IllegalArgumentException(
                            "BOM members must be `true` or a nonempty array of exact workspace member paths.");
                }
                return new AuthoredBom.AllMembers();
            });
        }

        List<String> authored = ManifestTomlValues.strings(field);
        ArrayList<WorkspaceMemberPath> paths = new ArrayList<>(authored.size());
        for (int item = 0; item < authored.size(); item++) {
            int index = item;
            paths.add(ManifestSemanticDiagnostics.construct(
                    field,
                    index,
                    () -> new WorkspaceMemberPath(authored.get(index))));
            ManifestSemanticDiagnostics.construct(
                    field, index, () -> new AuthoredBom.ExplicitMembers(paths));
        }
        return ManifestSemanticDiagnostics.construct(
                field, () -> new AuthoredBom.ExplicitMembers(paths));
    }

    private static AuthoredBom.Members exclusions(
            AuthoredBom.MemberSelection selection,
            ValidatedManifestField field) {
        List<String> authored = ManifestTomlValues.strings(field);
        ArrayList<WorkspaceMemberPath> paths = new ArrayList<>(authored.size());
        for (int item = 0; item < authored.size(); item++) {
            int index = item;
            paths.add(ManifestSemanticDiagnostics.construct(
                    field,
                    index,
                    () -> new WorkspaceMemberPath(authored.get(index))));
            ManifestSemanticDiagnostics.construct(
                    field, index, () -> new AuthoredBom.Members(selection, paths));
        }
        return ManifestSemanticDiagnostics.construct(
                field, () -> new AuthoredBom.Members(selection, paths));
    }

    @FunctionalInterface
    interface DecodedMembersObserver {
        void decoded(AuthoredBom.Members members);
    }
}

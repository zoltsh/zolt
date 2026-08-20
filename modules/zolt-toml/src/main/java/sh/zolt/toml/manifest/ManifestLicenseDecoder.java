package sh.zolt.toml.manifest;

import java.util.Optional;
import sh.zolt.manifest.ManifestModelValues;
import sh.zolt.manifest.ProjectLicense;
import sh.zolt.toml.schema.FinalManifestObjectShapes;
import sh.zolt.toml.schema.ManifestObjectMember;

/** Decodes the project-license shorthand and closed inline metadata forms. */
final class ManifestLicenseDecoder {
    ProjectLicense decode(ValidatedManifestField field) {
        if (ManifestTomlValues.isString(field)) {
            return ManifestSemanticDiagnostics.construct(
                    field,
                    () -> new ProjectLicense.Identifier(ManifestTomlValues.string(field)));
        }

        ManifestInlineTable table = ManifestTomlValues.inlineObject(field);
        Optional<String> id = nonBlank(
                table,
                FinalManifestObjectShapes.LICENSE_ID,
                "Project license metadata id");
        Optional<String> name = nonBlank(
                table,
                FinalManifestObjectShapes.LICENSE_NAME,
                "Project license metadata name");
        Optional<String> url = nonBlank(
                table,
                FinalManifestObjectShapes.LICENSE_URL,
                "Project license metadata URL");
        try {
            return new ProjectLicense.Metadata(id, name, url);
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException(
                    "Validated manifest license `" + field.path()
                            + "` violates its closed object shape.",
                    failure);
        }
    }

    private static Optional<String> nonBlank(
            ManifestInlineTable table,
            ManifestObjectMember member,
            String label) {
        return table.optionalString(member).map(value -> ManifestSemanticDiagnostics.construct(
                table,
                member,
                () -> {
                    ManifestModelValues.requireNonBlank(value, label);
                    return value;
                }));
    }
}

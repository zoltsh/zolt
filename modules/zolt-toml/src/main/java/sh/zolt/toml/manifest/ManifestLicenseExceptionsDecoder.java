package sh.zolt.toml.manifest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.SpdxLicenseTerm;
import sh.zolt.manifest.authored.AuthoredLicenseException;
import sh.zolt.toml.schema.FinalManifestDependencyFields;
import sh.zolt.toml.schema.FinalManifestPaths;

/** Decodes exact-coordinate license exceptions without evaluating resolved evidence. */
final class ManifestLicenseExceptionsDecoder {
    Map<DependencyCoordinate, AuthoredLicenseException> decode(
            ManifestDecodeIndex index) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        LinkedHashMap<DependencyCoordinate, AuthoredLicenseException> exceptions =
                new LinkedHashMap<>();
        for (ManifestDecodeIndex.SectionEntry entry :
                index.sectionEntries(FinalManifestPaths.DEPENDENCY_LICENSE_EXCEPTION)) {
            DependencyCoordinate coordinate = ManifestSemanticDiagnostics.construct(
                    entry.section(), () -> new DependencyCoordinate(entry.key()));
            AuthoredLicenseException exception = exception(index, entry);
            if (exceptions.put(coordinate, exception) != null) {
                throw new IllegalStateException(
                        "Validated manifest contains duplicate license exception `"
                                + coordinate + "`.");
            }
        }
        return Collections.unmodifiableMap(exceptions);
    }

    private static AuthoredLicenseException exception(
            ManifestDecodeIndex index,
            ManifestDecodeIndex.SectionEntry entry) {
        ValidatedManifestField allowField = ManifestSemanticDiagnostics.requiredField(
                index,
                entry,
                FinalManifestDependencyFields.DEPENDENCY_LICENSE_EXCEPTION_ALLOW);
        List<SpdxLicenseTerm> allow = terms(allowField);
        ManifestSemanticDiagnostics.construct(allowField, () -> requireNonEmpty(allow));

        ValidatedManifestField reasonField = ManifestSemanticDiagnostics.requiredField(
                index,
                entry,
                FinalManifestDependencyFields.DEPENDENCY_LICENSE_EXCEPTION_REASON);
        String reason = ManifestTomlValues.string(reasonField);
        AuthoredLicenseException exception = ManifestSemanticDiagnostics.construct(
                reasonField,
                () -> new AuthoredLicenseException(allow, Optional.empty(), reason));
        return index.field(
                        entry,
                        FinalManifestDependencyFields.DEPENDENCY_LICENSE_EXCEPTION_VERSION)
                .map(field -> ManifestSemanticDiagnostics.construct(
                        field,
                        () -> new AuthoredLicenseException(
                                allow,
                                Optional.of(ManifestTomlValues.string(field)),
                                reason)))
                .orElse(exception);
    }

    private static List<SpdxLicenseTerm> terms(ValidatedManifestField field) {
        List<String> values = ManifestTomlValues.strings(field);
        ArrayList<SpdxLicenseTerm> terms = new ArrayList<>(values.size());
        Set<SpdxLicenseTerm> seen = new LinkedHashSet<>();
        for (int item = 0; item < values.size(); item++) {
            String value = values.get(item);
            SpdxLicenseTerm term = ManifestSemanticDiagnostics.construct(
                    field, item, () -> new SpdxLicenseTerm(value));
            ManifestSemanticDiagnostics.construct(
                    field, item, () -> requireUnique(seen, term));
            terms.add(term);
        }
        return List.copyOf(terms);
    }

    private static List<SpdxLicenseTerm> requireNonEmpty(
            List<SpdxLicenseTerm> terms) {
        if (terms.isEmpty()) {
            throw new IllegalArgumentException(
                    "License exception allow terms must not be empty.");
        }
        return terms;
    }

    private static SpdxLicenseTerm requireUnique(
            Set<SpdxLicenseTerm> seen,
            SpdxLicenseTerm term) {
        if (!seen.add(term)) {
            throw new IllegalArgumentException(
                    "License exception allow term `" + term
                            + "` is declared more than once.");
        }
        return term;
    }
}

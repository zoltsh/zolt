package sh.zolt.toml.manifest;

import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.CoveragePercentage;
import sh.zolt.manifest.authored.AuthoredCoverage;
import sh.zolt.toml.schema.FinalManifestCoverageFields;

/** Decodes authored coverage floors without applying workspace minimums. */
final class ManifestCoverageDecoder {
    Optional<AuthoredCoverage> decode(ManifestDecodeIndex index) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        Optional<ValidatedManifestField> lineField =
                index.field(FinalManifestCoverageFields.COVERAGE_LINE);
        Optional<ValidatedManifestField> branchField =
                index.field(FinalManifestCoverageFields.COVERAGE_BRANCH);
        Optional<ValidatedManifestField> instructionField =
                index.field(FinalManifestCoverageFields.COVERAGE_INSTRUCTION);
        Optional<ValidatedManifestField> methodField =
                index.field(FinalManifestCoverageFields.COVERAGE_METHOD);
        if (lineField.isEmpty()
                && branchField.isEmpty()
                && instructionField.isEmpty()
                && methodField.isEmpty()) {
            return Optional.empty();
        }

        Optional<CoveragePercentage> line = floor(lineField);
        Optional<CoveragePercentage> branch = floor(branchField);
        Optional<CoveragePercentage> instruction = floor(instructionField);
        Optional<CoveragePercentage> method = floor(methodField);
        ValidatedManifestField anchor = lineField
                .or(() -> branchField)
                .or(() -> instructionField)
                .or(() -> methodField)
                .orElseThrow();
        return Optional.of(ManifestSemanticDiagnostics.construct(
                anchor,
                () -> new AuthoredCoverage(line, branch, instruction, method)));
    }

    private static Optional<CoveragePercentage> floor(
            Optional<ValidatedManifestField> field) {
        return field.map(value -> ManifestSemanticDiagnostics.construct(
                value,
                () -> new CoveragePercentage(ManifestTomlValues.number(value))));
    }
}

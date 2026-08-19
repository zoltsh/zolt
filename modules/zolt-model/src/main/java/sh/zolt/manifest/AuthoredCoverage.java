package sh.zolt.manifest;

import java.util.Objects;
import java.util.Optional;

/** Authored coverage floors before workspace minimums are applied. */
public record AuthoredCoverage(
        Optional<CoveragePercentage> line,
        Optional<CoveragePercentage> branch,
        Optional<CoveragePercentage> instruction,
        Optional<CoveragePercentage> method) {
    public AuthoredCoverage {
        line = Objects.requireNonNull(line, "Authored line coverage floor must not be null.");
        branch = Objects.requireNonNull(branch, "Authored branch coverage floor must not be null.");
        instruction = Objects.requireNonNull(
                instruction, "Authored instruction coverage floor must not be null.");
        method = Objects.requireNonNull(method, "Authored method coverage floor must not be null.");
        if (line.isEmpty() && branch.isEmpty() && instruction.isEmpty() && method.isEmpty()) {
            throw new IllegalArgumentException("Authored coverage floors must not be empty.");
        }
    }
}

package sh.zolt.manifest;

import java.util.Objects;
import java.util.Optional;

/** Effective per-metric coverage floors after workspace minimums are applied. */
public record EffectiveCoverage(
        Optional<EffectiveValue<CoveragePercentage>> line,
        Optional<EffectiveValue<CoveragePercentage>> branch,
        Optional<EffectiveValue<CoveragePercentage>> instruction,
        Optional<EffectiveValue<CoveragePercentage>> method) {
    public EffectiveCoverage {
        line = Objects.requireNonNull(line, "Effective line coverage floor must not be null.");
        branch = Objects.requireNonNull(branch, "Effective branch coverage floor must not be null.");
        instruction = Objects.requireNonNull(
                instruction, "Effective instruction coverage floor must not be null.");
        method = Objects.requireNonNull(method, "Effective method coverage floor must not be null.");
        rejectBuiltIn(line, "Effective line coverage floor");
        rejectBuiltIn(branch, "Effective branch coverage floor");
        rejectBuiltIn(instruction, "Effective instruction coverage floor");
        rejectBuiltIn(method, "Effective method coverage floor");
    }

    public static EffectiveCoverage empty() {
        return new EffectiveCoverage(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static void rejectBuiltIn(
            Optional<? extends EffectiveValue<?>> value, String label) {
        value.ifPresent(item -> {
            if (item.origin() == ValueOrigin.BUILT_IN) {
                throw new IllegalArgumentException(label + " must be authored or inherited.");
            }
        });
    }
}

package sh.zolt.project;

import java.util.Optional;

/**
 * Project-owned coverage floors: the minimum acceptable Jacoco coverage percentages a project (or
 * workspace root) is willing to accept. Each floor is optional; an empty {@link Optional} means the
 * metric is not gated. Percentages are validated to the inclusive {@code 0..100} range.
 *
 * <p>Floors are read on demand by the {@code coverage} and {@code check} commands rather than folded
 * into {@link ProjectConfig}, since they are a specialized quality policy consulted only at
 * enforcement time.
 */
public record CoverageSettings(
        Optional<Double> minLine,
        Optional<Double> minBranch,
        Optional<Double> minInstruction,
        Optional<Double> minMethod) {

    private static final CoverageSettings NONE =
            new CoverageSettings(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

    public CoverageSettings {
        minLine = validated("line", minLine);
        minBranch = validated("branch", minBranch);
        minInstruction = validated("instruction", minInstruction);
        minMethod = validated("method", minMethod);
    }

    /** The empty policy: no floors configured, so coverage enforcement is a no-op. */
    public static CoverageSettings none() {
        return NONE;
    }

    /** True when at least one metric floor is configured. */
    public boolean hasAnyFloor() {
        return minLine.isPresent()
                || minBranch.isPresent()
                || minInstruction.isPresent()
                || minMethod.isPresent();
    }

    private static Optional<Double> validated(String field, Optional<Double> value) {
        if (value == null || value.isEmpty()) {
            return Optional.empty();
        }
        double percent = value.get();
        if (Double.isNaN(percent) || percent < 0.0 || percent > 100.0) {
            throw new IllegalArgumentException(
                    "Invalid value for [coverage]." + field
                            + " in zolt.toml. Use a percentage between 0 and 100.");
        }
        return value;
    }
}

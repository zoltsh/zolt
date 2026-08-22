package sh.zolt.manifest;

/** A finite inclusive {@code 0..100} minimum coverage percentage. */
public record CoveragePercentage(double value) implements Comparable<CoveragePercentage> {
    public CoveragePercentage {
        if (!Double.isFinite(value) || value < 0.0 || value > 100.0) {
            throw new IllegalArgumentException(
                    "Coverage percentage must be finite and between 0 and 100 inclusive.");
        }
        if (value == 0.0) {
            value = 0.0;
        }
    }

    @Override
    public int compareTo(CoveragePercentage other) {
        return Double.compare(value, other.value);
    }
}

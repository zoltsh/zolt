package sh.zolt.project.toolchain;

/** A validated Java feature release used by project and toolchain configuration. */
public record JavaFeatureRelease(int value) {
    public JavaFeatureRelease {
        if (value <= 0) {
            throw new IllegalArgumentException("Java feature release must be a positive integer.");
        }
    }

    @Override
    public String toString() {
        return Integer.toString(value);
    }
}

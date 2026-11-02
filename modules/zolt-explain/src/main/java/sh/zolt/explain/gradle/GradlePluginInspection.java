package sh.zolt.explain.gradle;

/** A plugin declaration and whether it is actually applied to the inspected project. */
public record GradlePluginInspection(String id, String version, boolean applied) {
    public GradlePluginInspection(String id, String version) {
        this(id, version, true);
    }

    GradlePluginInspection withApplied(boolean value) {
        return new GradlePluginInspection(id, version, value);
    }
}

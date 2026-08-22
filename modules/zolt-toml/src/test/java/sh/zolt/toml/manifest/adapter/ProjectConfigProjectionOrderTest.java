package sh.zolt.toml.manifest.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import sh.zolt.project.ProjectConfig;

/**
 * The projections package evidence hashes must render identically for identical authored input.
 *
 * <p>Package evidence hashes {@code ProjectConfig} projections through their {@code toString()}, so
 * their iteration order is part of the fingerprint. A projection published through
 * {@code Map.copyOf} carries a per-JVM salted order, which made {@code zolt check} report the
 * artifact it had just packaged as stale and report a different "current" hash on every run. These
 * fixtures are authored in reverse-alphabetical order and the effective model publishes them in
 * canonical sorted order, so a salted copy matches neither by coincidence.
 */
final class ProjectConfigProjectionOrderTest {
    private static final String MANIFEST = """
            [project]
            name = "order"
            version = "1.0.0"
            group = "com.example"
            java = 21

            [dependencies.provided]
            "com.example:zeta" = "1.0.0"
            "com.example:yankee" = "1.0.0"
            "com.example:xray" = "1.0.0"
            "com.example:whiskey" = "1.0.0"
            "com.example:victor" = "1.0.0"
            "com.example:uniform" = "1.0.0"
            "com.example:tango" = "1.0.0"
            "com.example:sierra" = "1.0.0"

            [dependencies.constraints]
            "com.example:zeta" = { version = "1.0.0", reason = "z" }
            "com.example:yankee" = { version = "1.0.0", reason = "y" }
            "com.example:xray" = { version = "1.0.0", reason = "x" }
            "com.example:whiskey" = { version = "1.0.0", reason = "w" }
            "com.example:victor" = { version = "1.0.0", reason = "v" }
            "com.example:uniform" = { version = "1.0.0", reason = "u" }
            """;

    @Test
    void providedDependenciesKeepTheEffectiveCanonicalOrder() {
        ProjectConfig config = FinalManifests.load(MANIFEST);

        assertEquals(
                List.of(
                        "com.example:sierra",
                        "com.example:tango",
                        "com.example:uniform",
                        "com.example:victor",
                        "com.example:whiskey",
                        "com.example:xray",
                        "com.example:yankee",
                        "com.example:zeta"),
                List.copyOf(config.providedDependencies().keySet()));
    }

    @Test
    void dependencyConstraintsKeepTheEffectiveCanonicalOrder() {
        ProjectConfig config = FinalManifests.load(MANIFEST);

        assertEquals(
                List.of(
                        "com.example:uniform",
                        "com.example:victor",
                        "com.example:whiskey",
                        "com.example:xray",
                        "com.example:yankee",
                        "com.example:zeta"),
                List.copyOf(config.dependencyPolicy().constraints().keySet()));
    }

    /** Two adapts of identical source must render identically, field by field. */
    @Test
    void repeatedAdaptsRenderIdenticalProjections() {
        ProjectConfig first = FinalManifests.load(MANIFEST);
        ProjectConfig second = FinalManifests.load(MANIFEST);

        assertEquals(
                first.providedDependencies().toString(), second.providedDependencies().toString());
        assertEquals(first.dependencyPolicy().toString(), second.dependencyPolicy().toString());
        assertEquals(first.dependencies().toString(), second.dependencies().toString());
        assertEquals(first.apiDependencies().toString(), second.apiDependencies().toString());
        assertEquals(first.runtimeDependencies().toString(), second.runtimeDependencies().toString());
        assertEquals(first.dependencyMetadata().toString(), second.dependencyMetadata().toString());
    }
}

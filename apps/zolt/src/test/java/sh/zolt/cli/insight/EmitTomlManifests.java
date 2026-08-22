package sh.zolt.cli.insight;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import sh.zolt.dependency.DependencyLane;
import sh.zolt.manifest.DependencyConstraintSelector;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.DependencySelector;
import sh.zolt.manifest.PlatformSelector;
import sh.zolt.manifest.authored.AuthoredDependencies;
import sh.zolt.manifest.authored.AuthoredDependency;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.util.List;

/**
 * Reads an emitted {@code zolt explain --emit-toml} draft back through the final loader so a suite
 * asserts on the parsed manifest rather than on rendered text.
 *
 * <p>Parsing the draft is also the assertion that matters most: a draft that the final parser cannot
 * read is not a draft a user can adopt.
 */
final class EmitTomlManifests {
    private static final ManifestProjectConfigLoader LOADER = new ManifestProjectConfigLoader();

    private EmitTomlManifests() {
    }

    static AuthoredManifest parse(String emitted) {
        return LOADER.document(emitted).authored();
    }

    static List<AuthoredDependency> declarations(AuthoredManifest manifest) {
        return manifest.dependencies().map(AuthoredDependencies::declarations).orElse(List.of());
    }

    static boolean has(AuthoredManifest manifest, DependencyLane lane, String coordinate) {
        return declarations(manifest).stream()
                .anyMatch(entry -> entry.lane() == lane && entry.coordinate().value().equals(coordinate));
    }

    static AuthoredDependency dependency(
            AuthoredManifest manifest, DependencyLane lane, String coordinate) {
        return declarations(manifest).stream()
                .filter(entry -> entry.lane() == lane && entry.coordinate().value().equals(coordinate))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No " + lane + " dependency on `" + coordinate + "` in " + declarations(manifest)));
    }

    static String fixedVersion(
            AuthoredManifest manifest, DependencyLane lane, String coordinate) {
        return assertInstanceOf(
                        DependencySelector.FixedVersion.class,
                        dependency(manifest, lane, coordinate).selector())
                .value();
    }

    static String platformVersion(AuthoredManifest manifest, String coordinate) {
        PlatformSelector selector = manifest.platforms()
                .orElseThrow()
                .entries()
                .get(new DependencyCoordinate(coordinate));
        return assertInstanceOf(PlatformSelector.FixedVersion.class, selector).value();
    }

    static String constraintVersion(AuthoredManifest manifest, String coordinate) {
        DependencyConstraintSelector selector = manifest.dependencyConstraints()
                .orElseThrow()
                .entries()
                .get(new DependencyCoordinate(coordinate))
                .selector();
        return assertInstanceOf(DependencyConstraintSelector.FixedVersion.class, selector).value();
    }
}

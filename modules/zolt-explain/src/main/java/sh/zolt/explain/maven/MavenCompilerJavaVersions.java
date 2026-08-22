package sh.zolt.explain.maven;

import static sh.zolt.explain.maven.MavenXml.child;
import static sh.zolt.explain.maven.MavenXml.children;
import static sh.zolt.explain.maven.MavenXml.text;

import java.util.List;
import java.util.Optional;
import org.w3c.dom.Element;

/**
 * The statically resolved Maven main/test Java levels plus the {@link MavenJavaVersionProvenance} of
 * the main level.
 *
 * <p>Provenance is load-bearing for : a {@code <release>} (or {@code maven.compiler.release})
 * POM targeted the reproducible {@code --release} surface, so Zolt keeps the strict default; a POM that
 * used only {@code source}/{@code target} compiled against the host JDK's platform API, so the emit path
 * offers a commented {@code jdkApi = "host"} escape hatch and the audit fires a host-candidate
 * signal. The two must be distinguishable, which is why the old collapsing of release/target/source into
 * one anonymous string is no longer sufficient.
 */
record MavenCompilerJavaVersions(String mainVersion, String testVersion, MavenJavaVersionProvenance mainProvenance) {
    static MavenCompilerJavaVersions inspect(Element project, MavenPomProperties properties) {
        Optional<Element> configuration = compilerPluginConfiguration(project);
        ResolvedVersion main = javaVersion(properties, configuration);
        String testVersion = testJavaVersion(properties, configuration);
        if (testVersion.equals(main.version())) {
            testVersion = "";
        }
        return new MavenCompilerJavaVersions(main.version(), testVersion, main.provenance());
    }

    private record ResolvedVersion(String version, MavenJavaVersionProvenance provenance) {
    }

    private static ResolvedVersion javaVersion(MavenPomProperties properties, Optional<Element> configuration) {
        Optional<ResolvedVersion> propertyVersion = compilerProperty(properties, List.of(
                new KeyedProvenance("maven.compiler.release", MavenJavaVersionProvenance.RELEASE),
                new KeyedProvenance("maven.compiler.target", MavenJavaVersionProvenance.SOURCE_TARGET),
                new KeyedProvenance("maven.compiler.source", MavenJavaVersionProvenance.SOURCE_TARGET),
                new KeyedProvenance("java.version", MavenJavaVersionProvenance.PROPERTY)));
        if (propertyVersion.isPresent()) {
            return propertyVersion.orElseThrow();
        }
        if (configuration.isPresent()) {
            for (KeyedProvenance keyed : List.of(
                    new KeyedProvenance("release", MavenJavaVersionProvenance.RELEASE),
                    new KeyedProvenance("target", MavenJavaVersionProvenance.SOURCE_TARGET),
                    new KeyedProvenance("source", MavenJavaVersionProvenance.SOURCE_TARGET))) {
                Optional<String> value = text(configuration.orElseThrow(), keyed.key());
                if (value.isPresent()) {
                    return new ResolvedVersion(properties.interpolate(value.orElseThrow()), keyed.provenance());
                }
            }
        }
        return new ResolvedVersion("unknown", MavenJavaVersionProvenance.UNKNOWN);
    }

    private static String testJavaVersion(MavenPomProperties properties, Optional<Element> configuration) {
        Optional<ResolvedVersion> propertyVersion = compilerProperty(properties, List.of(
                new KeyedProvenance("maven.compiler.testRelease", MavenJavaVersionProvenance.RELEASE),
                new KeyedProvenance("maven.compiler.testTarget", MavenJavaVersionProvenance.SOURCE_TARGET),
                new KeyedProvenance("maven.compiler.testSource", MavenJavaVersionProvenance.SOURCE_TARGET)));
        if (propertyVersion.isPresent()) {
            return propertyVersion.orElseThrow().version();
        }
        if (configuration.isPresent()) {
            for (String key : List.of("testRelease", "testTarget", "testSource")) {
                Optional<String> value = text(configuration.orElseThrow(), key);
                if (value.isPresent()) {
                    return properties.interpolate(value.orElseThrow());
                }
            }
        }
        return "";
    }

    private record KeyedProvenance(String key, MavenJavaVersionProvenance provenance) {
    }

    private static Optional<ResolvedVersion> compilerProperty(
            MavenPomProperties properties, List<KeyedProvenance> keys) {
        for (KeyedProvenance keyed : keys) {
            String value = properties.values().get(keyed.key());
            if (value != null && !value.isBlank()) {
                return Optional.of(new ResolvedVersion(properties.interpolate(value), keyed.provenance()));
            }
        }
        return Optional.empty();
    }

    private static Optional<Element> compilerPluginConfiguration(Element project) {
        Optional<Element> build = child(project, "build");
        if (build.isEmpty()) {
            return Optional.empty();
        }
        Optional<Element> compilerPlugin = child(build.orElseThrow(), "plugins").stream()
                .flatMap(pluginsElement -> children(pluginsElement, "plugin").stream())
                .filter(plugin -> "maven-compiler-plugin".equals(text(plugin, "artifactId").orElse("")))
                .findFirst();
        if (compilerPlugin.isEmpty()) {
            return Optional.empty();
        }
        return child(compilerPlugin.orElseThrow(), "configuration");
    }
}

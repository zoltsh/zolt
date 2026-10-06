package sh.zolt.build.compile.kotlin;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import sh.zolt.classpath.Classpath;

/** The verified launcher and plugin paths consumed by one Kotlin compiler invocation lane. */
public final class KotlinCompilerInvocationToolchain {
    private final Classpath launcherClasspath;
    private final Path kaptPluginJar;
    private final List<Path> compilerPluginJars;

    public KotlinCompilerInvocationToolchain(
            Classpath launcherClasspath,
            Path kaptPluginJar,
            List<Path> compilerPluginJars) {
        this.launcherClasspath = Objects.requireNonNull(
                launcherClasspath,
                "Kotlin compiler launcher classpath is required.");
        this.kaptPluginJar = normalize(kaptPluginJar);
        this.compilerPluginJars = Objects.requireNonNull(
                        compilerPluginJars,
                        "Kotlin compiler plugin JARs are required.")
                .stream()
                .map(path -> Objects.requireNonNull(
                        path,
                        "Kotlin compiler plugin JAR paths are required."))
                .map(KotlinCompilerInvocationToolchain::normalize)
                .toList();
        List<Path> launcherJars = launcherClasspath.entries().stream()
                .map(KotlinCompilerInvocationToolchain::normalize)
                .toList();
        if (this.kaptPluginJar != null && !launcherJars.contains(this.kaptPluginJar)) {
            throw new IllegalArgumentException(
                    "KAPT plugin JAR must be part of the Kotlin compiler launcher classpath.");
        }
        if (!launcherJars.containsAll(this.compilerPluginJars)) {
            throw new IllegalArgumentException(
                    "Kotlin compiler plugin JARs must be part of the compiler launcher classpath.");
        }
        if (this.compilerPluginJars.stream().distinct().count()
                != this.compilerPluginJars.size()) {
            throw new IllegalArgumentException(
                    "Kotlin compiler plugin JARs must not contain duplicates.");
        }
    }

    public Classpath launcherClasspath() {
        return launcherClasspath;
    }

    public Optional<Path> kaptPluginJar() {
        return Optional.ofNullable(kaptPluginJar);
    }

    public List<Path> compilerPluginJars() {
        return compilerPluginJars;
    }

    private static Path normalize(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }
}

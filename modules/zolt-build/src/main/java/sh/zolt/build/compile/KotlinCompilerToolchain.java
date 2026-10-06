package sh.zolt.build.compile;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import sh.zolt.build.compile.kotlin.KotlinCompilerInvocationToolchain;
import sh.zolt.classpath.Classpath;

/** The checksum-verified, relocatably identified Kotlin compiler launcher closure. */
public final class KotlinCompilerToolchain {
    public static final String COORDINATE =
            "org.jetbrains.kotlin:kotlin-compiler-embeddable";

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final String version;
    private final String sha256;
    private final String identity;
    private final Classpath launcherClasspath;
    private final Path kaptPluginJar;
    private final List<Path> compilerPluginJars;

    KotlinCompilerToolchain(
            String version,
            String sha256,
            List<Path> launcherJars,
            String launcherClosureIdentity) {
        this(version, sha256, launcherJars, launcherClosureIdentity, null, List.of());
    }

    KotlinCompilerToolchain(
            String version,
            String sha256,
            List<Path> launcherJars,
            String launcherClosureIdentity,
            Path kaptPluginJar) {
        this(version, sha256, launcherJars, launcherClosureIdentity, kaptPluginJar, List.of());
    }

    KotlinCompilerToolchain(
            String version,
            String sha256,
            List<Path> launcherJars,
            String launcherClosureIdentity,
            Path kaptPluginJar,
            List<Path> compilerPluginJars) {
        this.version = require(version, "version");
        this.sha256 = require(sha256, "SHA-256");
        if (!SHA256.matcher(this.sha256).matches()) {
            throw new IllegalArgumentException(
                    "Kotlin compiler SHA-256 must be 64 lowercase hexadecimal characters.");
        }
        List<Path> normalizedJars = Objects.requireNonNull(
                        launcherJars,
                        "Kotlin compiler launcher classpath is required.")
                .stream()
                .map(path -> Objects.requireNonNull(
                                path,
                                "Kotlin compiler launcher classpath entries are required.")
                        .toAbsolutePath()
                        .normalize())
                .toList();
        if (normalizedJars.isEmpty()) {
            throw new IllegalArgumentException(
                    "Kotlin compiler launcher classpath must not be empty.");
        }
        String closureIdentity = require(
                launcherClosureIdentity,
                "launcher closure identity");
        if (!closureIdentity.startsWith("sha256:")
                || !SHA256.matcher(closureIdentity.substring("sha256:".length())).matches()) {
            throw new IllegalArgumentException(
                    "Kotlin compiler launcher closure identity must be a SHA-256 identity.");
        }
        this.identity = COORDINATE + ":" + this.version + "@sha256:" + this.sha256
                + "|launcher=" + closureIdentity;
        this.launcherClasspath = new Classpath(normalizedJars);
        Path normalizedKapt = kaptPluginJar == null
                ? null
                : kaptPluginJar.toAbsolutePath().normalize();
        if (normalizedKapt != null && !normalizedJars.contains(normalizedKapt)) {
            throw new IllegalArgumentException(
                    "KAPT plugin JAR must be part of the Kotlin compiler launcher closure.");
        }
        this.kaptPluginJar = normalizedKapt;
        List<Path> normalizedPlugins = Objects.requireNonNull(
                        compilerPluginJars,
                        "Kotlin compiler plugin JARs are required.")
                .stream()
                .map(path -> Objects.requireNonNull(
                                path,
                                "Kotlin compiler plugin JAR paths are required.")
                        .toAbsolutePath()
                        .normalize())
                .toList();
        if (!normalizedJars.containsAll(normalizedPlugins)) {
            throw new IllegalArgumentException(
                    "Kotlin compiler plugin JARs must be part of the compiler launcher closure.");
        }
        if (normalizedPlugins.stream().distinct().count() != normalizedPlugins.size()) {
            throw new IllegalArgumentException(
                    "Kotlin compiler plugin JARs must not contain duplicates.");
        }
        this.compilerPluginJars = normalizedPlugins;
    }

    public String coordinate() {
        return COORDINATE;
    }

    public String version() {
        return version;
    }

    public String sha256() {
        return sha256;
    }

    /** A path-independent identity suitable for compiler fingerprints and cache keys. */
    public String identity() {
        return identity;
    }

    public Classpath launcherClasspath() {
        return launcherClasspath;
    }

    /** The verified KAPT compiler plugin when processor lanes require it. */
    public Optional<Path> kaptPluginJar() {
        return Optional.ofNullable(kaptPluginJar);
    }

    /** The ordered checksum-verified compiler plugins selected by the project. */
    public List<Path> compilerPluginJars() {
        return compilerPluginJars;
    }

    /** Verified invocation paths for compilation lanes that do not own resolution. */
    public KotlinCompilerInvocationToolchain invocationToolchain() {
        return new KotlinCompilerInvocationToolchain(
                launcherClasspath,
                kaptPluginJar,
                compilerPluginJars);
    }

    private static String require(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Kotlin compiler " + label + " is required.");
        }
        return value.strip();
    }
}

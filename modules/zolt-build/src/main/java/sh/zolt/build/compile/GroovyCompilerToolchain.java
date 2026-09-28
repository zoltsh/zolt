package sh.zolt.build.compile;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import sh.zolt.classpath.Classpath;

/**
 * The verified, relocatably identified Groovy compiler bootstrap selected for one compile scope.
 *
 * <p>Instances are created only by {@link GroovyCompilerToolchainResolver}. The launcher classpath is
 * intentionally distinct from the application compilation classpath: it contains only the artifact
 * whose locked identity was validated as the Groovy compiler.
 */
public final class GroovyCompilerToolchain {
    public static final String COORDINATE = "org.apache.groovy:groovy";

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final String version;
    private final String sha256;
    private final Classpath launcherClasspath;

    GroovyCompilerToolchain(String version, String sha256, Path launcherJar) {
        this.version = require(version, "version");
        this.sha256 = require(sha256, "SHA-256");
        if (!SHA256.matcher(this.sha256).matches()) {
            throw new IllegalArgumentException("Groovy compiler SHA-256 must be 64 lowercase hexadecimal characters.");
        }
        Path normalizedJar = Objects.requireNonNull(launcherJar, "Groovy compiler launcher jar is required.")
                .toAbsolutePath()
                .normalize();
        this.launcherClasspath = new Classpath(List.of(normalizedJar));
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
        return COORDINATE + ":" + version + "@sha256:" + sha256;
    }

    public Classpath launcherClasspath() {
        return launcherClasspath;
    }

    private static String require(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Groovy compiler " + label + " is required.");
        }
        return value.strip();
    }
}

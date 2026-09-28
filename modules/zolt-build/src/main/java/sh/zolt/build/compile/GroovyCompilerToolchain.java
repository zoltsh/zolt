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
 * intentionally distinct from the application compilation classpath: explicit toolchains contain
 * the complete checksum-verified compiler closure, while the compatibility path contains its single
 * validated compiler artifact.
 */
public final class GroovyCompilerToolchain {
    public static final String COORDINATE = "org.apache.groovy:groovy";

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final String version;
    private final String sha256;
    private final String identity;
    private final Classpath launcherClasspath;

    GroovyCompilerToolchain(String version, String sha256, Path launcherJar) {
        this(version, sha256, List.of(launcherJar), "");
    }

    GroovyCompilerToolchain(
            String version,
            String sha256,
            List<Path> launcherJars,
            String launcherClosureIdentity) {
        this.version = require(version, "version");
        this.sha256 = require(sha256, "SHA-256");
        if (!SHA256.matcher(this.sha256).matches()) {
            throw new IllegalArgumentException("Groovy compiler SHA-256 must be 64 lowercase hexadecimal characters.");
        }
        List<Path> normalizedJars = Objects.requireNonNull(
                        launcherJars,
                        "Groovy compiler launcher classpath is required.")
                .stream()
                .map(path -> Objects.requireNonNull(
                                path,
                                "Groovy compiler launcher classpath entries are required.")
                        .toAbsolutePath()
                        .normalize())
                .toList();
        if (normalizedJars.isEmpty()) {
            throw new IllegalArgumentException("Groovy compiler launcher classpath must not be empty.");
        }
        String coreIdentity = COORDINATE + ":" + this.version + "@sha256:" + this.sha256;
        String closureIdentity = launcherClosureIdentity == null ? "" : launcherClosureIdentity.strip();
        if (!closureIdentity.isEmpty()
                && (!closureIdentity.startsWith("sha256:")
                        || !SHA256.matcher(closureIdentity.substring("sha256:".length())).matches())) {
            throw new IllegalArgumentException(
                    "Groovy compiler launcher closure identity must be a SHA-256 identity.");
        }
        this.identity = closureIdentity.isEmpty()
                ? coreIdentity
                : coreIdentity + "|launcher=" + closureIdentity;
        this.launcherClasspath = new Classpath(normalizedJars);
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

    private static String require(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Groovy compiler " + label + " is required.");
        }
        return value.strip();
    }
}

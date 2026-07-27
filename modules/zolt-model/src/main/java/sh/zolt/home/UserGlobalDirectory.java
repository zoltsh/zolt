package sh.zolt.home;

import sh.zolt.error.ActionableException;
import java.nio.file.Path;
import java.util.function.UnaryOperator;

/**
 * Single resolution point for Zolt's user-global directory: the machine-level {@code ~/.zolt} tree
 * holding {@code config.toml}, the artifact cache, the build-cache CAS, managed toolchains, shims,
 * and daemon runtime state.
 *
 * <p>{@code ZOLT_USER_HOME} redirects the whole tree at once, modeled on {@code GRADLE_USER_HOME}, so
 * hermetic and CI runs can relocate every user-global path without resorting to {@code -Duser.home}.
 * The value must be an absolute path; blank or whitespace-only values are ignored and resolution falls
 * back to {@code user.home}/.zolt. A relative value is a configuration error rather than a silent
 * fallback, because a relative user home would follow the working directory from command to command.
 *
 * <p>Precedence: narrower overrides still win for their own slice. {@code ZOLT_CA_BUNDLE} (CA trust)
 * and {@code ZOLT_TOOLCHAIN_MIRROR} (toolchain download base) configure transport, not location, and
 * are unaffected by this variable. An explicit CLI path option such as {@code --config},
 * {@code --shims-dir}, or {@code --install-root} beats both, since it names one concrete path.
 */
public final class UserGlobalDirectory {
    /** Environment variable that redirects the entire user-global directory. */
    public static final String USER_HOME_ENV = "ZOLT_USER_HOME";

    private static final String DIRECTORY_NAME = ".zolt";

    private UserGlobalDirectory() {
    }

    /** The user-global directory root, honoring {@code ZOLT_USER_HOME}. */
    public static Path root() {
        return root(System::getenv, System::getProperty);
    }

    /** Resolution against injected environment and system-property lookups, for tests and embedding. */
    public static Path root(UnaryOperator<String> environment, UnaryOperator<String> systemProperties) {
        String override = environment.apply(USER_HOME_ENV);
        if (override != null && !override.isBlank()) {
            return absolute(override.trim());
        }
        String home = systemProperties.apply("user.home");
        if (home == null || home.isBlank()) {
            throw new ActionableException(
                    "Could not resolve the user-global Zolt directory: the JVM reports no `user.home`.",
                    "Set " + USER_HOME_ENV + " to an absolute directory path and run the command again.");
        }
        return Path.of(home, DIRECTORY_NAME).toAbsolutePath().normalize();
    }

    /** The user-global config file, {@code <root>/config.toml}. */
    public static Path configFile() {
        return root().resolve("config.toml").normalize();
    }

    /** The shared Maven artifact cache, {@code <root>/cache}. */
    public static Path artifactCache() {
        return root().resolve("cache").normalize();
    }

    /** The default build-cache directory, {@code <root>/build-cache}. */
    public static Path buildCache() {
        return root().resolve("build-cache").normalize();
    }

    /** The managed Java toolchain store, {@code <root>/toolchains}. */
    public static Path toolchains() {
        return root().resolve("toolchains").normalize();
    }

    /** The shim directory, {@code <root>/shims}. */
    public static Path shims() {
        return root().resolve("shims").normalize();
    }

    /** Runtime state for a long-lived helper process, {@code <root>/run/<component>}. */
    public static Path runtime(String component) {
        return root().resolve("run").resolve(component).normalize();
    }

    private static Path absolute(String override) {
        Path path = Path.of(override);
        if (!path.isAbsolute()) {
            throw new ActionableException(
                    USER_HOME_ENV + " is set to the relative path `" + override
                            + "`, so user-global paths would move with the working directory.",
                    "Set " + USER_HOME_ENV + " to an absolute directory path, or unset it to use "
                            + "the default user home, and run the command again.");
        }
        return path.toAbsolutePath().normalize();
    }
}

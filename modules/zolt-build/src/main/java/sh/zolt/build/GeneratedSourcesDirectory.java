package sh.zolt.build;

import java.nio.file.Path;

/**
 * Resolves a configured generated-source output path against the project it belongs to, rejecting
 * anything that would write outside it. Shared by the main and test compile lanes, which apply the
 * identical rule and differ only in which configuration key they can name in the error.
 */
public final class GeneratedSourcesDirectory {
    private GeneratedSourcesDirectory() {
    }

    /** The main compile scope's generated-source directory. */
    public static Path main(Path projectDirectory, String configuredPath) {
        return resolve(projectDirectory, configuredPath, "source");
    }

    /** The test compile scope's generated-source directory. */
    public static Path test(Path projectDirectory, String configuredPath) {
        return resolve(projectDirectory, configuredPath, "test source");
    }

    private static Path resolve(Path projectDirectory, String configuredPath, String scope) {
        Path configured = Path.of(configuredPath);
        Path projectRoot = projectDirectory.toAbsolutePath().normalize();
        Path path = projectRoot.resolve(configured).normalize();
        if (configured.isAbsolute() || !path.startsWith(projectRoot) || path.equals(projectRoot)) {
            throw new BuildException(
                    "Invalid generated " + scope + " output path `"
                            + configuredPath
                            + "`. Use a project-relative path under the project directory.");
        }
        return path;
    }
}

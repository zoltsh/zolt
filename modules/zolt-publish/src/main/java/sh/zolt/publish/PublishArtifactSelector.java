package sh.zolt.publish;

import sh.zolt.project.PackageMode;
import java.util.List;
import java.util.Set;

/**
 * Resolves and validates the single package-artifact selector for a publish: {@code main} (the
 * configured package output) or one of the single-archive package-mode selectors, which must match
 * the project's actual {@code [package].mode}.
 */
final class PublishArtifactSelector {
    private static final Set<PackageMode> SINGLE_FILE_PACKAGE_ARTIFACTS = Set.of(
            PackageMode.THIN,
            PackageMode.SPRING_BOOT,
            PackageMode.WAR,
            PackageMode.SPRING_BOOT_WAR);

    private PublishArtifactSelector() {
    }

    static String select(List<String> artifacts, PackageMode packageMode) {
        if (packageMode == PackageMode.QUARKUS) {
            throw new PublishException(
                    "Quarkus fast-JAR mode produces a multi-file runtime layout and cannot be published as a single Maven main artifact. Publish an explicitly configured uber-JAR instead.");
        }
        if (artifacts.size() != 1) {
            throw new PublishException("zolt publish --dry-run currently supports one package artifact selector. Use [publish].artifacts = [\"main\"] for the configured package output, or [\""
                    + packageMode.configValue()
                    + "\"] to select it explicitly.");
        }
        String artifact = artifacts.getFirst();
        if (artifact.equals("main")) {
            return artifact;
        }
        PackageMode selectedMode = PackageMode.fromConfigValue(artifact)
                .orElseThrow(() -> new PublishException("Unsupported publish artifact selector `"
                        + artifact
                        + "`. Use `main` or one of the package mode selectors: thin, spring-boot, war, spring-boot-war."));
        if (!SINGLE_FILE_PACKAGE_ARTIFACTS.contains(selectedMode)) {
            throw new PublishException("Publish artifact selector `"
                    + artifact
                    + "` does not describe a single package archive yet. Use `main`, `thin`, `spring-boot`, `war`, or `spring-boot-war`.");
        }
        if (selectedMode != packageMode) {
            throw new PublishException("Publish artifact selector `"
                    + artifact
                    + "` requires [package].mode = \""
                    + artifact
                    + "\", but the current package mode is `"
                    + packageMode.configValue()
                    + "`.");
        }
        return artifact;
    }
}

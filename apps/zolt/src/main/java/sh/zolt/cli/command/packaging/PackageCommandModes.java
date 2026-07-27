package sh.zolt.cli.command.packaging;

import sh.zolt.build.PackageException;
import sh.zolt.build.packaging.PackageResult;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;
import java.util.Optional;

final class PackageCommandModes {
    private PackageCommandModes() {
    }

    static Optional<PackageMode> packageModeOverride(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(PackageMode.fromConfigValue(value).orElseThrow(() -> new PackageException(
                "Unsupported package mode `"
                        + value
                        + "`. Supported package modes are: "
                        + PackageMode.supportedValues()
                        + ".")));
    }

    static ProjectConfig withPackageModeOverride(
            ProjectConfig config,
            Optional<PackageMode> packageModeOverride) {
        return packageModeOverride
                .map(mode -> config.withPackageSettings(config.packageSettings().withMode(mode)))
                .orElse(config);
    }

    static void rejectWorkspaceModeOverride(
            String command,
            Optional<PackageMode> packageModeOverride) {
        if (packageModeOverride.isEmpty()) {
            return;
        }
        throw PackageException.actionable(
                "`zolt "
                        + command
                        + " --workspace --mode` is not supported because the override would be applied after workspace dependency resolution and build planning.",
                "Set [package].mode on the selected workspace members, run `zolt resolve --workspace`, then retry without `--mode`.");
    }

    static PlanOutputFormat planOutputFormat(String value) {
        String normalized = value == null || value.isBlank() ? "text" : value.trim().toLowerCase();
        return switch (normalized) {
            case "text" -> PlanOutputFormat.TEXT;
            case "json" -> PlanOutputFormat.JSON;
            default -> throw new PackageException("Unsupported package plan format `" + value + "`. Use text or json.");
        };
    }

    static String packageSummary(PackageResult result) {
        if (result.mode() == PackageMode.QUARKUS) {
            return "Packaged Quarkus fast-jar layout with " + result.entryCount() + " files";
        }
        if (result.mode() == PackageMode.BOM) {
            return "Packaged dependencyManagement BOM POM with "
                    + result.entryCount()
                    + " managed entries";
        }
        String extension = (result.mode() == PackageMode.WAR || result.mode() == PackageMode.SPRING_BOOT_WAR)
                ? "war"
                : "jar";
        return "Packaged "
                + result.entryCount()
                + " compiled files as "
                + result.mode().configValue()
                + " "
                + extension;
    }

    static Optional<String> runInstruction(PackageResult result) {
        return switch (result.mode()) {
            case SPRING_BOOT -> Optional.of("Run with Zolt: zolt run-package --mode spring-boot -- [args]");
            case SPRING_BOOT_WAR -> Optional.of("Run with Zolt: zolt run-package --mode spring-boot-war -- [args]");
            case QUARKUS -> Optional.of("Run with Zolt: zolt run");
            case THIN -> Optional.of("Run with dependencies: zolt run-package -- [args]");
            case UBER -> Optional.of("Run as a self-contained jar: java -jar " + result.jarPath() + " [args]");
            case WAR, BOM -> Optional.empty();
        };
    }

    static Optional<String> noMainClassDetail(PackageResult result) {
        return switch (result.mode()) {
            case WAR -> Optional.of("WAR is a servlet container deployment artifact; use `spring-boot-war` for java -jar.");
            case BOM -> Optional.of(
                    "BOM is dependency-management metadata and is not runnable.");
            default -> Optional.empty();
        };
    }

    static PackageModeDetail packageModeDetail(PackageResult result) {
        return switch (result.mode()) {
            case SPRING_BOOT -> new PackageModeDetail(
                    "Spring Boot jar: dependencies are nested under BOOT-INF/lib.",
                    Optional.empty());
            case WAR -> new PackageModeDetail(
                    "WAR: application classes are under WEB-INF/classes and runtime dependencies are under WEB-INF/lib.",
                    Optional.empty());
            case SPRING_BOOT_WAR -> new PackageModeDetail(
                    "Spring Boot WAR: runtime dependencies are under WEB-INF/lib and provided dependencies are under WEB-INF/lib-provided.",
                    Optional.empty());
            case QUARKUS -> new PackageModeDetail(
                    "Quarkus fast-jar: deploy the whole directory containing the generated quarkus-run.jar.",
                    Optional.empty());
            case UBER -> new PackageModeDetail(
                    "Uber jar: runtime dependency classes and resources are merged into the archive root.",
                    Optional.empty());
            case BOM -> new PackageModeDetail(
                    "BOM POM: publishes curated dependencyManagement versions and produces no application archive.",
                    Optional.empty());
            case THIN -> new PackageModeDetail(
                    "Thin jar: dependencies are not bundled.",
                    result.runtimeClasspathPath().map(path -> "Wrote runtime classpath to " + path));
        };
    }

    record PackageModeDetail(String message, Optional<String> secondaryMessage) {
        PackageModeDetail {
            if (message == null || message.isBlank()) {
                throw new PackageException("Package mode detail message is required.");
            }
            secondaryMessage = secondaryMessage == null ? Optional.empty() : secondaryMessage;
        }
    }

    enum PlanOutputFormat {
        TEXT,
        JSON
    }
}

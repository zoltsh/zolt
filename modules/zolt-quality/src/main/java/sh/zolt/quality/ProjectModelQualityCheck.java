package sh.zolt.quality;

import static sh.zolt.quality.QualityCheckService.PROJECT_MODEL;

import sh.zolt.project.BuildSettings;
import sh.zolt.project.DependencyMetadata;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.ProjectConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

final class ProjectModelQualityCheck {
    List<QualityCheckResult> check(
            Optional<String> member,
            Path projectRoot,
            ProjectConfig config) {
        Optional<QualityCheckResult> invalidPath = firstInvalidPath(member, config);
        if (invalidPath.isPresent()) {
            return List.of(invalidPath.orElseThrow());
        }

        Optional<QualityCheckResult> invalidCompilerRelease = invalidCompilerRelease(member, config);
        if (invalidCompilerRelease.isPresent()) {
            return List.of(invalidCompilerRelease.orElseThrow());
        }

        List<QualityCheckResult> results = new ArrayList<>();
        results.add(QualityCheckResult.passed(
                PROJECT_MODEL,
                member,
                config.project().name(),
                "Project model is valid for Zolt-owned checks at " + projectRoot.toAbsolutePath().normalize() + "."));
        migrationOutputRootDiagnostic(member, projectRoot, config).ifPresent(results::add);
        results.addAll(unusedVersionAliasDiagnostics(member, config));
        return List.copyOf(results);
    }

    private static Optional<QualityCheckResult> migrationOutputRootDiagnostic(
            Optional<String> member,
            Path projectRoot,
            ProjectConfig config) {
        if (!Path.of(config.build().outputRoot()).normalize().equals(Path.of("target"))) {
            return Optional.empty();
        }
        List<String> legacyFiles = legacyBuildFiles(projectRoot);
        if (legacyFiles.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(QualityCheckResult.warning(
                PROJECT_MODEL,
                member,
                "[build.output].root",
                "Maven or Gradle project files are present ("
                        + String.join(", ", legacyFiles)
                        + ") while the Zolt build output root is `target`, so tools may write into the same output tree.",
                "For side-by-side migration, set [build.output].root = \".zolt/build\" in zolt.toml so Zolt-owned outputs stay separate."));
    }

    private static List<String> legacyBuildFiles(Path projectRoot) {
        List<String> names = List.of(
                "pom.xml",
                "settings.gradle",
                "settings.gradle.kts",
                "build.gradle",
                "build.gradle.kts");
        return names.stream()
                .filter(name -> Files.isRegularFile(projectRoot.resolve(name)))
                .toList();
    }

    private static List<QualityCheckResult> unusedVersionAliasDiagnostics(
            Optional<String> member,
            ProjectConfig config) {
        if (config.versionAliases().isEmpty()) {
            return List.of();
        }
        Set<String> referencedAliases = referencedVersionAliases(config);
        return config.versionAliases().keySet().stream()
                .filter(alias -> !referencedAliases.contains(alias))
                .sorted()
                .map(alias -> QualityCheckResult.skipped(
                        PROJECT_MODEL,
                        member,
                        "[versions]." + alias,
                        "Version alias `" + alias + "` is declared but not referenced by any versionRef.",
                        "Remove [versions]." + alias + " or update a dependency, platform, processor, constraint, or OpenAPI tool to use versionRef = \"" + alias + "\"."))
                .toList();
    }

    private static Set<String> referencedVersionAliases(ProjectConfig config) {
        Set<String> aliases = new LinkedHashSet<>();
        for (DependencyMetadata metadata : config.dependencyMetadata().values()) {
            if (metadata.versionRef() != null) {
                aliases.add(metadata.versionRef());
            }
        }
        config.dependencyPolicy().constraints().values().stream()
                .flatMap(constraint -> constraint.versionRef().stream())
                .forEach(aliases::add);
        openApiSteps(config).stream()
                .flatMap(step -> step.openApi().toolVersionRef().stream())
                .forEach(aliases::add);
        return Set.copyOf(aliases);
    }

    private static List<GeneratedSourceStep> openApiSteps(ProjectConfig config) {
        List<GeneratedSourceStep> steps = new ArrayList<>();
        config.build().generatedMainSources().stream()
                .filter(step -> step.kind() == GeneratedSourceKind.OPENAPI)
                .forEach(steps::add);
        config.build().generatedTestSources().stream()
                .filter(step -> step.kind() == GeneratedSourceKind.OPENAPI)
                .forEach(steps::add);
        return List.copyOf(steps);
    }

    private static Optional<QualityCheckResult> firstInvalidPath(
            Optional<String> member,
            ProjectConfig config) {
        List<PathField> fields = new ArrayList<>();
        BuildSettings build = config.build();
        fields.add(new PathField("[build].source", build.source()));
        addPathFields(fields, "[build].sources", build.sourceRoots());
        fields.add(new PathField("[build].test", build.test()));
        fields.add(new PathField("[build].output", build.output()));
        fields.add(new PathField("[build].testOutput", build.testOutput()));
        addPathFields(fields, "[test.sources].java", build.testSources());
        addPathFields(fields, "[test.sources].groovy", build.groovyTestSources());
        addPathFields(fields, "[resources].main", build.resourceRoots());
        addPathFields(fields, "[resources].test", build.testResourceRoots());
        fields.add(new PathField("[compiler].generatedSources", config.compilerSettings().generatedSources()));
        fields.add(new PathField("[compiler].generatedTestSources", config.compilerSettings().generatedTestSources()));
        addGeneratedPathFields(fields, "[generated.main]", build.generatedMainSources());
        addGeneratedPathFields(fields, "[generated.test]", build.generatedTestSources());

        for (PathField field : fields) {
            if (!isProjectRelative(field.value())) {
                return Optional.of(QualityCheckResult.failed(
                        PROJECT_MODEL,
                        member,
                        field.name(),
                        "Path `" + field.value() + "` must be project-relative and stay inside the project.",
                        "Edit zolt.toml to use a relative path such as `src/main/java` or `target/classes`."));
            }
        }
        return Optional.empty();
    }

    private static Optional<QualityCheckResult> invalidCompilerRelease(
            Optional<String> member,
            ProjectConfig config) {
        String release = config.compilerSettings().release();
        if (release.isBlank()) {
            return Optional.empty();
        }
        Optional<Integer> releaseVersion = javaFeatureVersion(release);
        Optional<Integer> projectVersion = javaFeatureVersion(config.project().java());
        if (releaseVersion.isEmpty()) {
            return Optional.of(QualityCheckResult.failed(
                    PROJECT_MODEL,
                    member,
                    "[compiler].release",
                    "Compiler release `" + release + "` must be a Java feature version.",
                    "Use a numeric release such as `8`, `11`, `17`, or `21`."));
        }
        if (projectVersion.isPresent() && releaseVersion.orElseThrow() > projectVersion.orElseThrow()) {
            return Optional.of(QualityCheckResult.failed(
                    PROJECT_MODEL,
                    member,
                    "[compiler].release",
                    "Compiler release `" + release + "` is newer than [project].java `" + config.project().java() + "`.",
                    "Lower [compiler].release or raise [project].java in zolt.toml."));
        }
        return Optional.empty();
    }

    private static Optional<Integer> javaFeatureVersion(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Integer.parseInt(value));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    private static void addPathFields(List<PathField> fields, String name, List<String> values) {
        for (int index = 0; index < values.size(); index++) {
            fields.add(new PathField(name + "[" + index + "]", values.get(index)));
        }
    }

    private static void addGeneratedPathFields(
            List<PathField> fields,
            String section,
            List<GeneratedSourceStep> steps) {
        for (GeneratedSourceStep step : steps) {
            fields.add(new PathField(section + "." + step.id() + ".output", step.output()));
            addPathFields(fields, section + "." + step.id() + ".inputs", step.inputs());
        }
    }

    private static boolean isProjectRelative(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        Path path = Path.of(value);
        Path normalized = path.normalize();
        return !path.isAbsolute() && !normalized.startsWith("..");
    }

    private record PathField(String name, String value) {
    }
}

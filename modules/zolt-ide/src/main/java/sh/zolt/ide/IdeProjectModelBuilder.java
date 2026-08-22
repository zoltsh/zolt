package sh.zolt.ide;

import sh.zolt.project.CompilerSettings;
import sh.zolt.project.DeveloperEntry;
import sh.zolt.project.PackageSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectMetadata;
import sh.zolt.project.ProjectPathException;
import sh.zolt.project.ProjectPaths;
import sh.zolt.project.PublicationMetadata;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

final class IdeProjectModelBuilder {
    IdeModel.ProjectInfo projectInfo(ProjectConfig config) {
        if (config == null) {
            return new IdeModel.ProjectInfo(null, null, null, null);
        }
        ProjectMetadata project = config.project();
        return new IdeModel.ProjectInfo(
                project.name(),
                project.group(),
                project.version(),
                project.main().orElse(null));
    }

    IdeModel.JavaInfo javaInfo(ProjectConfig config) {
        return new IdeModel.JavaInfo(config == null ? null : config.project().java(), null, null);
    }

    IdeModel.CompilerInfo compilerInfo(
            Path root,
            ProjectConfig config,
            List<IdeModel.Diagnostic> diagnostics) {
        if (config == null) {
            return new IdeModel.CompilerInfo(null, null, null, List.of(), List.of(), null, null);
        }
        CompilerSettings compiler = config.compilerSettings();
        String release = compiler.release().isBlank() ? null : compiler.release();
        String encoding = compiler.encoding().isBlank() ? null : compiler.encoding();
        return new IdeModel.CompilerInfo(
                release,
                release == null ? config.project().java() : release,
                encoding,
                compiler.args(),
                compiler.testArgs(),
                outputPath(root, "[compiler.generated].main", compiler.generatedSources(), diagnostics),
                outputPath(root, "[compiler.generated].test", compiler.generatedTestSources(), diagnostics));
    }

    IdeModel.TestRuntimeInfo testRuntimeInfo(ProjectConfig config) {
        if (config == null) {
            return new IdeModel.TestRuntimeInfo(List.of(), Map.of(), Map.of(), List.of());
        }
        return new IdeModel.TestRuntimeInfo(
                config.build().testRuntime().jvmArgs(),
                config.build().testRuntime().systemProperties(),
                config.build().testRuntime().redactedEnvironment(),
                config.build().testRuntime().events());
    }

    IdeModel.PackageInfo packageInfo(
            Path root,
            ProjectConfig config,
            List<IdeModel.Diagnostic> diagnostics) {
        if (config == null) {
            return new IdeModel.PackageInfo(
                    null,
                    false,
                    false,
                    false,
                    null,
                    null,
                    null,
                    null,
                    new IdeModel.PublicationInfo(null, null, null, null, List.of(), null, null),
                    Map.of());
        }
        PackageSettings settings = config.packageSettings();
        String artifactBaseName = artifactBaseName(root, config, diagnostics);
        Path mainJar = artifactPath(root, config, artifactBaseName, "", diagnostics);
        return new IdeModel.PackageInfo(
                settings.mode().configValue(),
                settings.sources(),
                settings.javadoc(),
                settings.tests(),
                mainJar,
                settings.sources() ? artifactPath(root, config, artifactBaseName, "sources", diagnostics) : null,
                settings.javadoc() ? artifactPath(root, config, artifactBaseName, "javadoc", diagnostics) : null,
                settings.tests() ? artifactPath(root, config, artifactBaseName, "tests", diagnostics) : null,
                publicationInfo(settings.metadata()),
                settings.manifestAttributes());
    }

    IdeModel.OutputInfo outputInfo(
            Path root,
            ProjectConfig config,
            List<IdeModel.Diagnostic> diagnostics) {
        if (config == null) {
            return new IdeModel.OutputInfo(null, null, null);
        }
        String artifactBaseName = artifactBaseName(root, config, diagnostics);
        return new IdeModel.OutputInfo(
                outputPath(root, "[build.output].main", config.build().output(), diagnostics),
                outputPath(root, "[build.output].test", config.build().testOutput(), diagnostics),
                artifactPath(root, config, artifactBaseName, "", diagnostics));
    }

    private static IdeModel.PublicationInfo publicationInfo(PublicationMetadata metadata) {
        return new IdeModel.PublicationInfo(
                blankToNull(metadata.name()),
                blankToNull(metadata.description()),
                blankToNull(metadata.url()),
                blankToNull(metadata.license()),
                developers(metadata),
                blankToNull(metadata.scm()),
                blankToNull(metadata.issues()));
    }

    /**
     * Editors show developer names. The manifest language carries them as structured
     * {@code [project.developers.<id>]} entries, so project the entry names when present.
     */
    private static List<String> developers(PublicationMetadata metadata) {
        if (metadata.developerEntries().isEmpty()) {
            return metadata.developers();
        }
        return metadata.developerEntries().stream()
                .map(DeveloperEntry::name)
                .toList();
    }

    private static String artifactBaseName(
            Path root,
            ProjectConfig config,
            List<IdeModel.Diagnostic> diagnostics) {
        String name = filenameComponent(root, "[project].name", config.project().name(), diagnostics);
        String version = filenameComponent(root, "[project].version", config.project().version(), diagnostics);
        if (name == null || version == null) {
            return null;
        }
        return name + "-" + version;
    }

    private static Path artifactPath(
            Path root,
            ProjectConfig config,
            String artifactBaseName,
            String classifier,
            List<IdeModel.Diagnostic> diagnostics) {
        if (artifactBaseName == null) {
            return null;
        }
        String suffix = classifier == null || classifier.isBlank() ? "" : "-" + classifier;
        return outputPath(
                root,
                "package artifact",
                config.build().outputRoot() + "/" + artifactBaseName + suffix + ".jar",
                diagnostics);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Path outputPath(
            Path root,
            String key,
            String configuredPath,
            List<IdeModel.Diagnostic> diagnostics) {
        try {
            return ProjectPaths.output(root, key, configuredPath);
        } catch (ProjectPathException exception) {
            pathDiagnostic(root, exception, diagnostics);
            return null;
        }
    }

    private static String filenameComponent(
            Path root,
            String key,
            String value,
            List<IdeModel.Diagnostic> diagnostics) {
        try {
            return ProjectPaths.filenameComponent(key, value);
        } catch (ProjectPathException exception) {
            pathDiagnostic(root, exception, diagnostics);
            return null;
        }
    }

    private static void pathDiagnostic(
            Path root,
            ProjectPathException exception,
            List<IdeModel.Diagnostic> diagnostics) {
        if (diagnostics.stream().anyMatch(diagnostic ->
                "PROJECT_PATH_INVALID".equals(diagnostic.code())
                        && exception.getMessage().equals(diagnostic.message()))) {
            return;
        }
        diagnostics.add(new IdeModel.Diagnostic(
                "error",
                "PROJECT_PATH_INVALID",
                exception.getMessage(),
                root.resolve("zolt.toml").normalize(),
                "Fix the unsafe path in zolt.toml and run zolt ide model --format json again."));
    }
}

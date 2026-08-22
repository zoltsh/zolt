package sh.zolt.ide;

import sh.zolt.generated.GeneratedSourceEvidence;
import sh.zolt.generated.GeneratedSourceEvidenceService;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectPathException;
import sh.zolt.project.ProjectPaths;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class IdeRootModelBuilder {
    private final GeneratedSourceEvidenceService generatedSourceEvidenceService;

    IdeRootModelBuilder(GeneratedSourceEvidenceService generatedSourceEvidenceService) {
        this.generatedSourceEvidenceService = generatedSourceEvidenceService;
    }

    List<IdeModel.SourceRoot> sourceRoots(
            Path root,
            ProjectConfig config,
            List<IdeModel.Diagnostic> diagnostics) {
        if (config == null) {
            return List.of();
        }
        BuildSettings settings = config.build();
        List<IdeModel.SourceRoot> roots = new ArrayList<>();
        for (int index = 0; index < settings.sourceRoots().size(); index++) {
            addSourceRoot(
                    roots,
                    index == 0 ? "main-java" : "main-java-" + (index + 1),
                    "main",
                    "java",
                    inputRoot(root, "[build].sources", settings.sourceRoots().get(index), diagnostics),
                    false);
        }
        addSourceRoot(
                roots,
                "main-generated-java",
                "main",
                "java",
                outputPath(root, "[compiler.generated].main", config.compilerSettings().generatedSources(), diagnostics),
                true);
        for (GeneratedSourceRoot generatedRoot : generatedRoots(root, settings.generatedMainSources(), "main", diagnostics)) {
            roots.add(new IdeModel.SourceRoot(
                    generatedRoot.id(),
                    "main",
                    "java",
                    generatedRoot.path(),
                    true));
        }
        for (int index = 0; index < settings.testSources().size(); index++) {
            addSourceRoot(
                    roots,
                    "test-java-" + (index + 1),
                    "test",
                    "java",
                    inputRoot(root, "[test.sources].java", settings.testSources().get(index), diagnostics),
                    false);
        }
        for (int index = 0; index < settings.groovyTestSources().size(); index++) {
            addSourceRoot(
                    roots,
                    "test-groovy-" + (index + 1),
                    "test",
                    "groovy",
                    inputRoot(root, "[test.sources].groovy", settings.groovyTestSources().get(index), diagnostics),
                    false);
        }
        addSourceRoot(
                roots,
                "test-generated-java",
                "test",
                "java",
                outputPath(root, "[compiler.generated].test", config.compilerSettings().generatedTestSources(), diagnostics),
                true);
        for (GeneratedSourceRoot generatedRoot : generatedRoots(root, settings.generatedTestSources(), "test", diagnostics)) {
            roots.add(new IdeModel.SourceRoot(
                    generatedRoot.id(),
                    "test",
                    "java",
                    generatedRoot.path(),
                    true));
        }
        return roots;
    }

    List<IdeModel.GeneratedSourceInfo> generatedSources(
            Path root,
            ProjectConfig config,
            List<IdeModel.Diagnostic> diagnostics) {
        if (config == null) {
            return List.of();
        }
        BuildSettings build = config.build();
        BuildSettings safeBuild = build.withGeneratedSources(
                safeGeneratedSteps(root, build.generatedMainSources(), "main", diagnostics),
                safeGeneratedSteps(root, build.generatedTestSources(), "test", diagnostics));
        return generatedSourceEvidenceService.evidence(root, safeBuild).stream()
                .map(IdeRootModelBuilder::generatedSourceInfo)
                .toList();
    }

    List<IdeModel.ResourceRoot> resourceRoots(
            Path root,
            ProjectConfig config,
            List<IdeModel.Diagnostic> diagnostics) {
        if (config == null) {
            return List.of();
        }
        BuildSettings settings = config.build();
        List<IdeModel.ResourceRoot> roots = new ArrayList<>();
        addResourceRoots(roots, root, "main", settings.resourceRoots(), diagnostics);
        addResourceRoots(roots, root, "test", settings.testResourceRoots(), diagnostics);
        return List.copyOf(roots);
    }

    private static void addSourceRoot(
            List<IdeModel.SourceRoot> roots,
            String id,
            String kind,
            String language,
            Path path,
            boolean generated) {
        if (path != null) {
            roots.add(new IdeModel.SourceRoot(id, kind, language, path, generated));
        }
    }

    private static List<GeneratedSourceRoot> generatedRoots(
            Path root,
            List<GeneratedSourceStep> steps,
            String kind,
            List<IdeModel.Diagnostic> diagnostics) {
        return steps.stream()
                .map(step -> generatedRoot(root, kind, step, diagnostics))
                .filter(generatedRoot -> generatedRoot != null)
                .toList();
    }

    private static GeneratedSourceRoot generatedRoot(
            Path root,
            String kind,
            GeneratedSourceStep step,
            List<IdeModel.Diagnostic> diagnostics) {
        Path output = outputPath(
                root,
                "[generated." + kind + "." + step.id() + "].output",
                step.output(),
                diagnostics);
        if (output == null) {
            return null;
        }
        return new GeneratedSourceRoot("generated-" + kind + "-" + step.id(), output);
    }

    private record GeneratedSourceRoot(String id, Path path) {}

    private static List<GeneratedSourceStep> safeGeneratedSteps(
            Path root,
            List<GeneratedSourceStep> steps,
            String scope,
            List<IdeModel.Diagnostic> diagnostics) {
        return steps.stream()
                .filter(step -> generatedStepPathsAreSafe(root, step, scope, diagnostics))
                .toList();
    }

    private static boolean generatedStepPathsAreSafe(
            Path root,
            GeneratedSourceStep step,
            String scope,
            List<IdeModel.Diagnostic> diagnostics) {
        boolean safe = outputPath(
                root,
                "[generated." + scope + "." + step.id() + "].output",
                step.output(),
                diagnostics) != null;
        for (String input : step.inputs()) {
            safe = inputPath(
                    root,
                    "[generated." + scope + "." + step.id() + "].inputs",
                    input,
                    diagnostics) != null && safe;
        }
        return safe;
    }

    private static IdeModel.GeneratedSourceInfo generatedSourceInfo(GeneratedSourceEvidence evidence) {
        return new IdeModel.GeneratedSourceInfo(
                evidence.id(),
                evidence.sourceRootId(),
                evidence.scope(),
                evidence.step().kind().configValue(),
                evidence.step().language(),
                evidence.output(),
                evidence.inputs(),
                evidence.step().required(),
                evidence.step().clean(),
                evidence.ownership(),
                evidence.compileLane(),
                evidence.freshness(),
                evidence.outputExists(),
                evidence.inputsPresent(),
                evidence.toolArtifact(),
                evidence.step().openApi().toolVersionRef().orElse(null),
                evidence.toolFingerprint(),
                evidence.optionsFingerprint());
    }

    private static void addResourceRoots(
            List<IdeModel.ResourceRoot> roots,
            Path root,
            String kind,
            List<String> configuredRoots,
            List<IdeModel.Diagnostic> diagnostics) {
        String idPrefix = kind + "-resources";
        for (int index = 0; index < configuredRoots.size(); index++) {
            String id = index == 0 ? idPrefix : idPrefix + "-" + (index + 1);
            Path path = inputRoot(root, "[resources]." + kind, configuredRoots.get(index), diagnostics);
            if (path != null) {
                roots.add(new IdeModel.ResourceRoot(id, kind, path));
            }
        }
    }

    private static Path inputPath(
            Path root,
            String key,
            String configuredPath,
            List<IdeModel.Diagnostic> diagnostics) {
        try {
            return ProjectPaths.input(root, key, configuredPath);
        } catch (ProjectPathException exception) {
            pathDiagnostic(root, exception, diagnostics);
            return null;
        }
    }

    private static Path inputRoot(
            Path root,
            String key,
            String configuredPath,
            List<IdeModel.Diagnostic> diagnostics) {
        try {
            return ProjectPaths.existingRoot(root, key, configuredPath);
        } catch (ProjectPathException exception) {
            pathDiagnostic(root, exception, diagnostics);
            return null;
        }
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

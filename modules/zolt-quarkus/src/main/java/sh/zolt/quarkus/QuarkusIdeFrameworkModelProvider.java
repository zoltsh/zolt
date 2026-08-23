package sh.zolt.quarkus;

import sh.zolt.ide.IdeFrameworkModelProvider;
import sh.zolt.ide.IdeModel;
import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.project.ProjectConfig;
import sh.zolt.quarkus.production.QuarkusAugmentationState;
import sh.zolt.quarkus.production.QuarkusOutputLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class QuarkusIdeFrameworkModelProvider implements IdeFrameworkModelProvider {
    private final QuarkusPlanService quarkusPlanService;

    public QuarkusIdeFrameworkModelProvider() {
        this(new QuarkusPlanService());
    }

    QuarkusIdeFrameworkModelProvider(QuarkusPlanService quarkusPlanService) {
        this.quarkusPlanService = quarkusPlanService;
    }

    @Override
    public IdeModel.FrameworkInfo build(
            Path root,
            Path lockfilePath,
            Path cacheRoot,
            ProjectConfig config,
            List<IdeModel.Diagnostic> diagnostics) {
        return new IdeModel.FrameworkInfo(quarkusInfo(root, lockfilePath, cacheRoot, config, diagnostics));
    }

    private IdeModel.QuarkusInfo quarkusInfo(
            Path root,
            Path lockfilePath,
            Path cacheRoot,
            ProjectConfig config,
            List<IdeModel.Diagnostic> diagnostics) {
        if (config == null || !config.frameworkSettings().quarkus().enabled()) {
            return new IdeModel.QuarkusInfo(
                    false,
                    null,
                    "disabled",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    List.of());
        }

        QuarkusOutputLayout outputLayout = QuarkusOutputLayout.forProject(root, config.build().outputRoot());
        if (cacheRoot == null) {
            return quarkusInfoWithoutPlan(root, config, outputLayout, "unknown");
        }

        try {
            QuarkusPlan plan = quarkusPlanService.planFrom(root, config, lockfilePath, cacheRoot);
            quarkusDiagnostics(plan, diagnostics);
            return new IdeModel.QuarkusInfo(
                    true,
                    plan.packageMode().configValue(),
                    plan.augmentationState().status().label(),
                    plan.inputFingerprint(),
                    plan.augmentationState().recordedInputFingerprint().orElse(null),
                    plan.augmentationState().metadataPath(),
                    plan.outputLayout().augmentationDirectory(),
                    plan.outputLayout().packageDirectory(),
                    plan.outputLayout().packageDirectory().resolve("quarkus-run.jar").normalize(),
                    plan.outputLayout().packageDirectory().resolve("quarkus/generated-bytecode.jar").normalize(),
                    plan.outputLayout().packageDirectory().resolve("quarkus/transformed-bytecode.jar").normalize(),
                    generatedOutputs(plan.outputLayout()),
                    absolutePaths(plan.deploymentClasspath()));
        } catch (LockfileReadException | QuarkusPlanException exception) {
            diagnostics.add(new IdeModel.Diagnostic(
                    "warning",
                    "QUARKUS_MODEL_UNAVAILABLE",
                    exception.getMessage(),
                    lockfilePath.normalize(),
                    "Run zolt resolve, then run zolt build."));
            return quarkusInfoWithoutPlan(root, config, outputLayout, "unknown");
        }
    }

    private static IdeModel.QuarkusInfo quarkusInfoWithoutPlan(
            Path root,
            ProjectConfig config,
            QuarkusOutputLayout outputLayout,
            String status) {
        Path metadataPath = outputLayout.augmentationDirectory().resolve("zolt-augmentation.properties").normalize();
        return new IdeModel.QuarkusInfo(
                true,
                config.frameworkSettings().quarkus().packageMode().configValue(),
                status,
                null,
                null,
                metadataPath,
                outputLayout.augmentationDirectory(),
                outputLayout.packageDirectory(),
                outputLayout.packageDirectory().resolve("quarkus-run.jar").normalize(),
                outputLayout.packageDirectory().resolve("quarkus/generated-bytecode.jar").normalize(),
                outputLayout.packageDirectory().resolve("quarkus/transformed-bytecode.jar").normalize(),
                generatedOutputs(outputLayout),
                List.of());
    }

    private static List<IdeModel.QuarkusGeneratedOutput> generatedOutputs(QuarkusOutputLayout outputLayout) {
        Path packageDirectory = outputLayout.packageDirectory();
        Path runnerJar = packageDirectory.resolve("quarkus-run.jar").normalize();
        Path generatedBytecodeJar = packageDirectory.resolve("quarkus/generated-bytecode.jar").normalize();
        Path transformedBytecodeJar = packageDirectory.resolve("quarkus/transformed-bytecode.jar").normalize();
        return List.of(
                generatedOutput("runner-jar", "runner-jar", runnerJar),
                generatedOutput("generated-bytecode", "generated-bytecode-jar", generatedBytecodeJar),
                generatedOutput("transformed-bytecode", "transformed-bytecode-jar", transformedBytecodeJar));
    }

    private static IdeModel.QuarkusGeneratedOutput generatedOutput(String id, String kind, Path path) {
        return new IdeModel.QuarkusGeneratedOutput(id, kind, path, Files.exists(path));
    }

    private static void quarkusDiagnostics(
            QuarkusPlan plan,
            List<IdeModel.Diagnostic> diagnostics) {
        QuarkusAugmentationState state = plan.augmentationState();
        if (state.status() == QuarkusAugmentationState.Status.CURRENT) {
            return;
        }
        String code = state.status() == QuarkusAugmentationState.Status.MISSING
                ? "QUARKUS_AUGMENTATION_MISSING"
                : "QUARKUS_AUGMENTATION_STALE";
        String message = state.status() == QuarkusAugmentationState.Status.MISSING
                ? "Quarkus augmentation output is missing."
                : "Quarkus augmentation output is stale.";
        diagnostics.add(new IdeModel.Diagnostic(
                "warning",
                code,
                message,
                state.metadataPath(),
                "Run zolt build."));
    }

    private static List<Path> absolutePaths(List<Path> paths) {
        return paths.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .toList();
    }
}

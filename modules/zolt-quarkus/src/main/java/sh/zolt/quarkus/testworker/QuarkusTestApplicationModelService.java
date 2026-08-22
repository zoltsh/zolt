package sh.zolt.quarkus.testworker;

import sh.zolt.doctor.JdkDetector;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectPathException;
import sh.zolt.project.ProjectPaths;
import sh.zolt.quarkus.QuarkusAugmentationException;
import sh.zolt.quarkus.bootstrap.descriptor.QuarkusBootstrapDescriptor;
import sh.zolt.quarkus.bootstrap.descriptor.QuarkusBootstrapDescriptorReader;
import sh.zolt.quarkus.bootstrap.QuarkusWorkspaceModuleInputs;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

public final class QuarkusTestApplicationModelService {
    public static final String SERIALIZED_TEST_MODEL_PROPERTY =
            "quarkus-internal-test.serialized-app-model.path";

    private final QuarkusBootstrapDescriptorReader descriptorReader;
    private final JdkDetector jdkDetector;
    private final Supplier<List<Path>> workerClasspath;
    private final LauncherFactory launcherFactory;

    public QuarkusTestApplicationModelService() {
        this(
                new QuarkusBootstrapDescriptorReader(),
                new JdkDetector(),
                QuarkusTestApplicationModelService::currentWorkerClasspath,
                QuarkusTestApplicationModelWorkerLauncher::new);
    }

    QuarkusTestApplicationModelService(
            QuarkusBootstrapDescriptorReader descriptorReader,
            JdkDetector jdkDetector,
            Supplier<List<Path>> workerClasspath,
            LauncherFactory launcherFactory) {
        if (descriptorReader == null) {
            throw new QuarkusAugmentationException("Quarkus bootstrap descriptor reader is required.");
        }
        if (jdkDetector == null) {
            throw new QuarkusAugmentationException("JDK detector is required for Quarkus test application models.");
        }
        if (workerClasspath == null) {
            throw new QuarkusAugmentationException("Quarkus test application model worker classpath supplier is required.");
        }
        if (launcherFactory == null) {
            throw new QuarkusAugmentationException("Quarkus test application model launcher factory is required.");
        }
        this.descriptorReader = descriptorReader;
        this.jdkDetector = jdkDetector;
        this.workerClasspath = workerClasspath;
        this.launcherFactory = launcherFactory;
    }

    public Optional<Path> writeIfEnabled(Path projectDirectory, ProjectConfig config) {
        if (projectDirectory == null) {
            throw new QuarkusAugmentationException("Quarkus test application model requires a project directory.");
        }
        if (config == null) {
            throw new QuarkusAugmentationException("Quarkus test application model requires a project config.");
        }
        if (!config.frameworkSettings().quarkus().enabled()) {
            return Optional.empty();
        }

        JdkStatus status = jdkDetector.detect(config.project().java());
        if (!status.ok()) {
            throw new QuarkusAugmentationException(
                    "JDK check failed for Quarkus test application model. " + String.join(" ", status.problems()));
        }
        Path root = ProjectPaths.root(projectDirectory);
        Path quarkusDirectory = outputPath(
                root,
                "Quarkus test application model output",
                config.build().outputRoot() + "/quarkus");
        QuarkusBootstrapDescriptor descriptor = descriptorReader.read(
                quarkusDirectory.resolve("zolt-bootstrap.properties"));
        Path outputPath = quarkusDirectory.resolve("test-application-model.dat");
        return Optional.of(launcherFactory.create(status.java().orElseThrow(), workerClasspath.get())
                .write(descriptor, outputPath, workspaceModuleInputs(root, config)));
    }

    private static QuarkusWorkspaceModuleInputs workspaceModuleInputs(Path projectDirectory, ProjectConfig config) {
        Path root = ProjectPaths.root(projectDirectory);
        return new QuarkusWorkspaceModuleInputs(
                root,
                outputPath(root, "Quarkus build directory", config.build().outputRoot()),
                inputRoot(root, "[build].sources", config.build().source()),
                inputRoot(root, "[resources].main", first(config.build().resourceRoots(), "src/main/resources")),
                outputPath(root, "[build.output].main", config.build().output()),
                inputRoot(root, "[test.sources].java", first(config.build().testSources(), config.build().test())),
                inputRoot(root, "[resources].test", first(config.build().testResourceRoots(), "src/test/resources")),
                outputPath(root, "[build.output].test", config.build().testOutput()));
    }

    private static String first(List<String> values, String fallback) {
        if (values == null || values.isEmpty()) {
            return fallback;
        }
        return values.getFirst();
    }

    private static Path inputRoot(Path root, String key, String configuredPath) {
        try {
            return ProjectPaths.existingRoot(root, key, configuredPath);
        } catch (ProjectPathException exception) {
            throw new QuarkusAugmentationException(exception.getMessage(), exception);
        }
    }

    private static Path outputPath(Path root, String key, String configuredPath) {
        try {
            return ProjectPaths.output(root, key, configuredPath);
        } catch (ProjectPathException exception) {
            throw new QuarkusAugmentationException(exception.getMessage(), exception);
        }
    }

    private static List<Path> currentWorkerClasspath() {
        String classpath = System.getProperty("java.class.path", "");
        List<Path> entries = Arrays.stream(classpath.split(java.io.File.pathSeparator))
                .filter(entry -> !entry.isBlank())
                .map(Path::of)
                .toList();
        if (entries.isEmpty()) {
            throw new QuarkusAugmentationException(
                    "Could not determine Zolt worker classpath for Quarkus test application model. "
                            + "Run zolt test from the packaged launcher or check java.class.path.");
        }
        return entries;
    }

    @FunctionalInterface
    interface LauncherFactory {
        QuarkusTestApplicationModelWorkerLauncher create(Path javaExecutable, List<Path> workerClasspath);
    }
}

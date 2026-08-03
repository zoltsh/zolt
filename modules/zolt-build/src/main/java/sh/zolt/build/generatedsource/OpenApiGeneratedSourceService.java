package sh.zolt.build.generatedsource;

import static sh.zolt.build.generatedsource.OpenApiGeneratedSourcePaths.outputPath;

import sh.zolt.build.BuildException;
import sh.zolt.cancel.BuildCancellation;
import sh.zolt.cancel.ProcessCancellation;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.doctor.JdkChecker;
import sh.zolt.doctor.JdkDetector;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.ProjectConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class OpenApiGeneratedSourceService {
    private final JdkChecker jdkDetector;
    private final OpenApiGeneratorCommandBuilder commandBuilder;
    private final OpenApiGeneratedSourceCache cache;
    private final ProcessRunner processRunner;

    public OpenApiGeneratedSourceService() {
        this(new JdkDetector());
    }

    public OpenApiGeneratedSourceService(JdkChecker jdkDetector) {
        this(jdkDetector, java.io.File.pathSeparator, OpenApiGeneratedSourceService::runProcess);
    }

    OpenApiGeneratedSourceService(
            JdkChecker jdkDetector,
            String pathSeparator,
            ProcessRunner processRunner) {
        this.jdkDetector = jdkDetector;
        this.commandBuilder = new OpenApiGeneratorCommandBuilder(pathSeparator);
        this.cache = new OpenApiGeneratedSourceCache();
        this.processRunner = processRunner;
    }

    public void generateMain(
            Path projectDirectory,
            ProjectConfig config,
            List<ResolvedClasspathPackage> packages) {
        generate(projectDirectory, config, packages, "main", config.build().generatedMainSources());
    }

    public void generateTest(
            Path projectDirectory,
            ProjectConfig config,
            List<ResolvedClasspathPackage> packages) {
        generate(projectDirectory, config, packages, "test", config.build().generatedTestSources());
    }

    private void generate(
            Path projectDirectory,
            ProjectConfig config,
            List<ResolvedClasspathPackage> packages,
            String scope,
            List<GeneratedSourceStep> steps) {
        List<GeneratedSourceStep> openApiSteps = steps.stream()
                .filter(step -> step.kind() == GeneratedSourceKind.OPENAPI)
                .toList();
        if (openApiSteps.isEmpty()) {
            return;
        }
        JdkStatus jdkStatus = jdkDetector.detect(config.project().java());
        if (!jdkStatus.ok()) {
            throw new BuildException("JDK check failed. " + String.join(" ", jdkStatus.problems()));
        }
        List<Path> toolClasspath = toolClasspath(packages);
        if (toolClasspath.isEmpty()) {
            throw new BuildException(
                    "OpenAPI generation requires locked tool artifacts in scope `tool-openapi`. "
                            + "Run `zolt resolve` to refresh zolt.lock, then retry `zolt build`.");
        }
        Path root = projectDirectory.toAbsolutePath().normalize();
        for (GeneratedSourceStep step : openApiSteps) {
            generateStep(root, jdkStatus.java().orElseThrow(), toolClasspath, scope, step);
        }
    }

    private void generateStep(
            Path projectRoot,
            Path javaExecutable,
            List<Path> toolClasspath,
            String scope,
            GeneratedSourceStep step) {
        OpenApiGeneratedSourceValidator.validateStep(projectRoot, scope, step);
        Path output = outputPath(projectRoot, step.output(), scope, step.id(), "output");
        OpenApiGeneratedSourceCache.GenerationCacheState cacheState = cache.state(
                projectRoot,
                output,
                toolClasspath,
                scope,
                step);
        if (cache.isCurrent(output, cacheState)) {
            return;
        }
        deleteOutput(output);
        createDirectory(output);
        List<String> command = commandBuilder.command(projectRoot, javaExecutable, toolClasspath, scope, step);
        ProcessResult result = processRunner.run(command, projectRoot);
        cache.writeLog(cacheState, result.output());
        if (result.exitCode() != 0) {
            throw new BuildException(
                    "OpenAPI generation failed for [generated."
                            + scope
                            + "."
                            + step.id()
                            + "] with exit code "
                            + result.exitCode()
                            + ". Review "
                            + cacheState.log()
                            + ", fix the input or generator options, and retry `zolt build`.\n"
                            + result.output().stripTrailing());
        }
        cache.writeFingerprint(cacheState);
    }

    private static List<Path> toolClasspath(List<ResolvedClasspathPackage> packages) {
        return packages.stream()
                .filter(dependency -> dependency.scope() == DependencyScope.TOOL_OPENAPI)
                .map(dependency -> dependency.resolvedPackage().jarPath())
                .distinct()
                .sorted()
                .toList();
    }

    private static void deleteOutput(Path output) {
        if (!Files.exists(output)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(output)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException exception) {
            throw new BuildException(
                    "Could not clean OpenAPI output "
                            + output
                            + ". Check filesystem permissions and retry `zolt build`.",
                    exception);
        }
    }

    private static void createDirectory(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException exception) {
            throw new BuildException(
                    "Could not create OpenAPI output directory "
                            + path
                            + ". Check filesystem permissions.",
                    exception);
        }
    }

    private static ProcessResult runProcess(List<String> command, Path directory) {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(directory.toFile())
                    .redirectErrorStream(true)
                    .start();
            try (BuildCancellation.Registration ignored =
                    ProcessCancellation.register(process)) {
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                int exitCode = process.waitFor();
                return new ProcessResult(exitCode, output);
            }
        } catch (IOException exception) {
            throw new BuildException(
                    "Could not run OpenAPI Generator. Check that the configured JDK can launch Java processes.",
                    exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BuildException("OpenAPI generation was interrupted. Try `zolt build` again.", exception);
        }
    }

    @FunctionalInterface
    interface ProcessRunner {
        ProcessResult run(List<String> command, Path directory);
    }

    record ProcessResult(int exitCode, String output) {
    }
}

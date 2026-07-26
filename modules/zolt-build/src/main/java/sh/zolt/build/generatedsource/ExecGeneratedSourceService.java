package sh.zolt.build.generatedsource;

import static sh.zolt.build.generatedsource.ExecGeneratedSourcePaths.outputPath;

import sh.zolt.build.BuildException;
import sh.zolt.build.generatedsource.ExecGeneratedSourceCache.ExecToolIdentity;
import sh.zolt.build.generatedsource.ExecGeneratedSourceCache.GenerationCacheState;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.doctor.JdkChecker;
import sh.zolt.doctor.JdkDetector;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.ProducesLane;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectPaths;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Runs {@code kind = "exec"} generated-source steps. Position is derived from declared IO: pre-compile
 * steps (java/test sources, resources, intermediate over source inputs) run before compilation via
 * {@link #generateMain}/{@link #generateTest}; post-compile steps ({@code tool = "project"} or an input
 * under compiled classes) run after compilation via the {@code *PostCompile} entry points. Each step
 * launches its runner (jvm/project over a classpath, process over a curated-PATH binary) in a sandboxed
 * cwd with a curated environment, content-fingerprint skipping unchanged steps ({@code cache = "none"}
 * always runs). Output routing and the consumer fence live in the build wiring, not here.
 */
public final class ExecGeneratedSourceService {
    private static final int LOG_TAIL_LINES = 20;
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(30);

    private final JdkChecker jdkDetector;
    private final ExecCommandBuilder commandBuilder;
    private final ProcessRunner processRunner;
    private final String pathSeparator;
    private final UnaryOperator<String> ambientEnv;

    public ExecGeneratedSourceService() {
        this(new JdkDetector());
    }

    public ExecGeneratedSourceService(JdkChecker jdkDetector) {
        this(jdkDetector, java.io.File.pathSeparator, ExecSubprocess::run);
    }

    ExecGeneratedSourceService(JdkChecker jdkDetector, String pathSeparator, ProcessRunner processRunner) {
        this(jdkDetector, pathSeparator, processRunner, System::getenv);
    }

    ExecGeneratedSourceService(
            JdkChecker jdkDetector,
            String pathSeparator,
            ProcessRunner processRunner,
            UnaryOperator<String> ambientEnv) {
        this.jdkDetector = jdkDetector;
        this.commandBuilder = new ExecCommandBuilder(pathSeparator);
        this.processRunner = processRunner;
        this.pathSeparator = pathSeparator;
        this.ambientEnv = ambientEnv;
    }

    public void generateMain(Path projectDirectory, ProjectConfig config, List<ResolvedClasspathPackage> packages) {
        generateMain(projectDirectory, config, packages, false);
    }

    public void generateMain(
            Path projectDirectory, ProjectConfig config, List<ResolvedClasspathPackage> packages, boolean offline) {
        run(projectDirectory, config, packages, "main", config.build().generatedMainSources(), Phase.PRE_COMPILE, offline);
    }

    public void generateMainPostCompile(
            Path projectDirectory, ProjectConfig config, List<ResolvedClasspathPackage> packages, boolean offline) {
        run(projectDirectory, config, packages, "main", config.build().generatedMainSources(), Phase.POST_COMPILE, offline);
    }

    public void generateTest(Path projectDirectory, ProjectConfig config, List<ResolvedClasspathPackage> packages) {
        generateTest(projectDirectory, config, packages, false);
    }

    public void generateTest(
            Path projectDirectory, ProjectConfig config, List<ResolvedClasspathPackage> packages, boolean offline) {
        run(projectDirectory, config, packages, "test", config.build().generatedTestSources(), Phase.PRE_COMPILE, offline);
    }

    public void generateTestPostCompile(
            Path projectDirectory, ProjectConfig config, List<ResolvedClasspathPackage> packages, boolean offline) {
        run(projectDirectory, config, packages, "test", config.build().generatedTestSources(), Phase.POST_COMPILE, offline);
    }

    private void run(
            Path projectDirectory,
            ProjectConfig config,
            List<ResolvedClasspathPackage> packages,
            String scope,
            List<GeneratedSourceStep> steps,
            Phase phase,
            boolean offline) {
        List<GeneratedSourceStep> execSteps = steps.stream()
                .filter(step -> step.kind() == GeneratedSourceKind.EXEC)
                .toList();
        if (execSteps.isEmpty()) {
            return;
        }
        Path root = ProjectPaths.root(projectDirectory);
        String outputRoot = config.build().outputRoot();
        List<GeneratedSourceStep> ordered = ExecStepScheduler.order(root, outputRoot, scope, execSteps);
        // Structural lane check across both phases up front, so a post-compile step that produces sources
        // fails with a clear cycle error before source discovery reports a confusing "root missing".
        validateLaneStructure(root, outputRoot, scope, ordered);
        List<GeneratedSourceStep> phaseSteps = ordered.stream()
                .filter(step -> phase.matches(ExecStepClassification.isPostCompile(step, root, outputRoot)))
                .toList();
        if (phaseSteps.isEmpty()) {
            return;
        }
        StepContext context = prepareContext(root, config, packages, scope, phaseSteps);
        for (GeneratedSourceStep step : phaseSteps) {
            generateStep(context, step, offline);
        }
    }

    private static void validateLaneStructure(
            Path root, String outputRoot, String scope, List<GeneratedSourceStep> steps) {
        for (GeneratedSourceStep step : steps) {
            if (!ExecStepClassification.isPostCompile(step, root, outputRoot)) {
                continue;
            }
            ProducesLane produces = step.exec().produces();
            if (produces == ProducesLane.JAVA_SOURCES || produces == ProducesLane.TEST_SOURCES) {
                throw BuildException.actionable(
                        "Exec step [generated." + scope + "." + step.id() + "] runs after compilation (project runner "
                                + "or an input under compiled classes) but produces " + produces.configValue()
                                + ", which would feed that same compile.",
                        "Post-compile steps may only produce resources, test-resources, or intermediate.");
            }
        }
    }

    private StepContext prepareContext(
            Path root,
            ProjectConfig config,
            List<ResolvedClasspathPackage> packages,
            String scope,
            List<GeneratedSourceStep> phaseSteps) {
        boolean needsJava = phaseSteps.stream().anyMatch(step -> {
            String runner = step.exec().tool().runner();
            return "jvm".equals(runner) || "project".equals(runner);
        });
        Path javaExecutable = null;
        if (needsJava) {
            JdkStatus jdkStatus = jdkDetector.detect(config.project().java());
            if (!jdkStatus.ok()) {
                throw new BuildException("JDK check failed. " + String.join(" ", jdkStatus.problems()));
            }
            javaExecutable = jdkStatus.java().orElseThrow();
        }
        Path metadataDirectory = root.resolve(config.build().outputRoot()).resolve(".zolt").resolve("exec");
        return new StepContext(
                root,
                config,
                scope,
                javaExecutable,
                packages,
                ExecStepWorkspace.projectClasspath(root, config, packages, scope),
                new ExecGeneratedSourceCache(metadataDirectory),
                metadataDirectory);
    }

    private void generateStep(StepContext context, GeneratedSourceStep step, boolean offline) {
        String scope = context.scope();
        ExecGeneratedSourceValidator.validateStep(context.root(), context.config().build().outputRoot(), scope, step);
        String subject = "[generated." + scope + "." + step.id() + "]";
        String cache = step.exec().cache();
        if ("none".equals(cache) && offline) {
            throw BuildException.actionable(
                    "Exec step " + subject + " uses cache = \"none\", which always runs and cannot be satisfied with "
                            + "`--offline`.",
                    "Commit a deterministic input (e.g. checked-in DDL) and use cache = \"content\", or run without "
                            + "--offline.");
        }
        Path cwd = ExecStepWorkspace.resolveCwd(context.root(), step, subject);
        Path output = outputPath(context.root(), step.output(), scope, step.id(), "output");
        ResolvedCommand resolved = resolveCommand(context, step, subject, cwd);
        GenerationCacheState cacheState = context.cache().state(
                context.root(),
                cwd,
                resolved.classpath(),
                resolved.identity(),
                scope,
                step,
                GeneratedSourceProducerFingerprintService.inheritEnvDigests(
                        step,
                        ambientEnv));
        boolean contentCache = "content".equals(cache);
        if (contentCache && context.cache().isCurrent(output, cacheState)) {
            return;
        }
        if (step.clean()) {
            ExecStepWorkspace.deleteOutput(output);
        }
        ExecStepWorkspace.createDirectory(output);
        Map<String, String> environment = ExecEnvironment.build(context.root(), output, scope, step, ambientEnv);
        Map<Path, Long> before =
                contentCache ? ExecUndeclaredOutputScan.snapshot(cwd, output, context.metadataDirectory()) : Map.of();
        Duration timeout = Duration.ofSeconds(step.exec().timeoutSeconds());
        ProcessResult result = processRunner.run(resolved.command(), cwd, environment, timeout);
        context.cache().writeLog(cacheState, result.output());
        if (result.timedOut()) {
            throw BuildException.actionable(
                    "Exec step " + subject + " (tool `" + step.exec().toolName() + "`) exceeded its "
                            + step.exec().timeoutSeconds() + "s timeout and was terminated. See " + cacheState.log() + ".",
                    "Raise timeoutSeconds if the tool legitimately needs longer, or fix the step so it completes.");
        }
        if (result.exitCode() != 0) {
            throw new BuildException(
                    "Exec step " + subject + " (tool `" + step.exec().toolName()
                            + "`) failed with exit code " + result.exitCode() + ". Review " + cacheState.log()
                            + ", fix the inputs or tool arguments, and retry `zolt build`.\n" + logTail(result.output()));
        }
        if (contentCache) {
            ExecUndeclaredOutputScan.verify(subject, cwd, output, context.metadataDirectory(), before);
        }
        context.cache().writeFingerprint(cacheState);
    }

    private ResolvedCommand resolveCommand(StepContext context, GeneratedSourceStep step, String subject, Path cwd) {
        List<String> args = step.exec().args();
        return switch (step.exec().tool().runner()) {
            case "jvm" -> {
                List<Path> toolClasspath =
                        ExecStepWorkspace.toolClasspath(context.packages(), step.exec().toolName());
                if (toolClasspath.isEmpty()) {
                    throw BuildException.actionable(
                            "Exec step " + subject + " uses runner jvm but zolt.lock has no tool-exec artifacts for tool `"
                                    + step.exec().toolName() + "`.",
                            "Run `zolt resolve` to refresh zolt.lock so the tool's isolated closure is locked, then "
                                    + "retry `zolt build`.");
                }
                yield new ResolvedCommand(
                        commandBuilder.jvmCommand(
                                context.javaExecutable(), toolClasspath, step.exec().tool().mainClass(), args),
                        toolClasspath,
                        ExecToolIdentity.none());
            }
            case "project" -> new ResolvedCommand(
                    commandBuilder.projectCommand(
                            context.javaExecutable(), context.projectClasspath(), step.exec().tool().mainClass(), args),
                    context.projectClasspath(),
                    ExecToolIdentity.none());
            case "process" -> {
                // Probes are near-instant version checks; the step's timeoutSeconds governs the
                // step run only, so a tight step timeout cannot race probe process startup.
                ExecProcessToolResolver.Resolved probe = ExecProcessToolResolver.resolve(
                        step.exec().tool(), subject, cwd, ambientEnv, pathSeparator, processRunner,
                        PROBE_TIMEOUT);
                yield new ResolvedCommand(
                        commandBuilder.processCommand(probe.binary(), args),
                        List.of(),
                        new ExecToolIdentity(step.exec().tool().binary(), probe.probedVersion()));
            }
            default -> throw BuildException.actionable(
                    "Exec step " + subject + " uses unsupported runner `" + step.exec().tool().runner() + "`.",
                    "Use runner = \"jvm\" or \"process\", or tool = \"project\".");
        };
    }

    private static String logTail(String output) {
        String[] lines = output.stripTrailing().split("\n", -1);
        if (lines.length <= LOG_TAIL_LINES) {
            return String.join("\n", lines);
        }
        return String.join("\n", List.of(lines).subList(lines.length - LOG_TAIL_LINES, lines.length));
    }

    private enum Phase {
        PRE_COMPILE,
        POST_COMPILE;

        boolean matches(boolean postCompile) {
            return postCompile == (this == POST_COMPILE);
        }
    }

    private record StepContext(
            Path root,
            ProjectConfig config,
            String scope,
            Path javaExecutable,
            List<ResolvedClasspathPackage> packages,
            List<Path> projectClasspath,
            ExecGeneratedSourceCache cache,
            Path metadataDirectory) {
    }

    private record ResolvedCommand(List<String> command, List<Path> classpath, ExecToolIdentity identity) {
    }

    @FunctionalInterface
    interface ProcessRunner {
        ProcessResult run(List<String> command, Path directory, Map<String, String> environment, Duration timeout);
    }

    record ProcessResult(int exitCode, String output, boolean timedOut) {
    }
}

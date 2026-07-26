package sh.zolt.build.generatedsource;

import sh.zolt.build.BuildException;
import sh.zolt.build.generatedsource.ExecGeneratedSourceCache.ExecToolIdentity;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectPaths;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

/**
 * Read-only access to the producer identities used by generated-source engines.
 *
 * <p>The service deliberately delegates to the same canonical cache fingerprint functions used by
 * OpenAPI and exec generation. It resolves exec globs, inherited environment digests, JVM/project
 * classpaths, and process-tool probes exactly as generation does, so package quality cannot approve
 * an artifact whose producer would invalidate on the next build.
 */
public final class GeneratedSourceProducerFingerprintService {
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(30);

    private final String pathSeparator;
    private final ExecGeneratedSourceService.ProcessRunner processRunner;
    private final UnaryOperator<String> ambientEnv;

    public GeneratedSourceProducerFingerprintService() {
        this(
                java.io.File.pathSeparator,
                ExecSubprocess::run,
                System::getenv);
    }

    GeneratedSourceProducerFingerprintService(
            String pathSeparator,
            ExecGeneratedSourceService.ProcessRunner processRunner,
            UnaryOperator<String> ambientEnv) {
        this.pathSeparator = pathSeparator;
        this.processRunner = processRunner;
        this.ambientEnv = ambientEnv;
    }

    public List<GeneratedSourceProducerFingerprint> fingerprints(
            Path projectDirectory,
            ProjectConfig config,
            List<ResolvedClasspathPackage> packages) {
        Path root = ProjectPaths.root(projectDirectory);
        List<GeneratedSourceProducerFingerprint> fingerprints =
                new ArrayList<>();
        add(
                fingerprints,
                root,
                config,
                packages,
                "main",
                config.build().generatedMainSources());
        add(
                fingerprints,
                root,
                config,
                packages,
                "test",
                config.build().generatedTestSources());
        fingerprints.sort(Comparator.comparing(
                fingerprint -> fingerprint.scope()
                        + "\u0000"
                        + fingerprint.stepId()
                        + "\u0000"
                        + fingerprint.kind().configValue()));
        return List.copyOf(fingerprints);
    }

    private void add(
            List<GeneratedSourceProducerFingerprint> fingerprints,
            Path root,
            ProjectConfig config,
            List<ResolvedClasspathPackage> packages,
            String scope,
            List<GeneratedSourceStep> steps) {
        for (GeneratedSourceStep step : steps) {
            fingerprints.add(new GeneratedSourceProducerFingerprint(
                    scope,
                    step.id(),
                    step.kind(),
                    fingerprint(root, config, packages, scope, step)));
        }
    }

    private String fingerprint(
            Path root,
            ProjectConfig config,
            List<ResolvedClasspathPackage> packages,
            String scope,
            GeneratedSourceStep step) {
        return switch (step.kind()) {
            case EXEC -> execFingerprint(root, config, packages, scope, step);
            case OPENAPI -> {
                OpenApiGeneratedSourceValidator.validateStep(root, scope, step);
                yield OpenApiGeneratedSourceCache.producerFingerprint(
                        root,
                        openApiToolClasspath(packages),
                        scope,
                        step);
            }
            case PROTOBUF -> declaredProducerFingerprint(
                    root,
                    scope,
                    step,
                    "zolt.protobuf-producer.v1");
            case DECLARED_ROOT -> declaredProducerFingerprint(
                    root,
                    scope,
                    step,
                    "zolt.declared-generated-root.v1");
        };
    }

    private String execFingerprint(
            Path root,
            ProjectConfig config,
            List<ResolvedClasspathPackage> packages,
            String scope,
            GeneratedSourceStep step) {
        ExecGeneratedSourceValidator.validateStep(
                root,
                config.build().outputRoot(),
                scope,
                step);
        String subject = "[generated." + scope + "." + step.id() + "]";
        Path cwd = ExecStepWorkspace.resolveCwd(root, step, subject);
        List<Path> classpath;
        ExecToolIdentity identity;
        switch (step.exec().tool().runner()) {
            case "jvm" -> {
                classpath = ExecStepWorkspace.toolClasspath(
                        packages,
                        step.exec().toolName());
                if (classpath.isEmpty()) {
                    throw BuildException.actionable(
                            "Exec step "
                                    + subject
                                    + " uses runner jvm but zolt.lock has no tool-exec artifacts for tool `"
                                    + step.exec().toolName()
                                    + "`.",
                            "Run `zolt resolve` to refresh zolt.lock so the tool's isolated closure is locked.");
                }
                identity = ExecToolIdentity.none();
            }
            case "project" -> {
                classpath = ExecStepWorkspace.projectClasspath(
                        root,
                        config,
                        packages,
                        scope);
                identity = ExecToolIdentity.none();
            }
            case "process" -> {
                ExecProcessToolResolver.Resolved resolved =
                        ExecProcessToolResolver.resolve(
                                step.exec().tool(),
                                subject,
                                cwd,
                                ambientEnv,
                                pathSeparator,
                                processRunner,
                                PROBE_TIMEOUT);
                classpath = List.of();
                identity = new ExecToolIdentity(
                        step.exec().tool().binary(),
                        resolved.probedVersion());
            }
            default -> throw BuildException.actionable(
                    "Exec step "
                            + subject
                            + " uses unsupported runner `"
                            + step.exec().tool().runner()
                            + "`.",
                    "Use runner = \"jvm\" or \"process\", or tool = \"project\".");
        }
        return ExecGeneratedSourceCache.producerFingerprint(
                root,
                cwd,
                classpath,
                identity,
                scope,
                step,
                inheritEnvDigests(step, ambientEnv));
    }

    static Map<String, String> inheritEnvDigests(
            GeneratedSourceStep step,
            UnaryOperator<String> ambientEnv) {
        Map<String, String> digests = new TreeMap<>();
        for (String name : step.exec().inheritEnv()) {
            String value = ambientEnv.apply(name);
            digests.put(
                    name,
                    value == null
                            ? "absent"
                            : "sha256:"
                                    + GeneratedSourceHashes.sha256(
                                            value.getBytes(StandardCharsets.UTF_8)));
        }
        return Map.copyOf(digests);
    }

    private static List<Path> openApiToolClasspath(
            List<ResolvedClasspathPackage> packages) {
        return packages.stream()
                .filter(dependency ->
                        dependency.scope() == DependencyScope.TOOL_OPENAPI)
                .map(dependency -> dependency.resolvedPackage().jarPath())
                .distinct()
                .sorted()
                .toList();
    }

    private static String declaredProducerFingerprint(
            Path root,
            String scope,
            GeneratedSourceStep step,
            String schema) {
        StringBuilder content = new StringBuilder();
        content.append("schema=").append(schema).append('\n');
        content.append("scope=").append(scope).append('\n');
        content.append("step=").append(step).append('\n');
        content.append("[inputs]\n");
        step.inputs().stream()
                .map(input -> ProjectPaths.input(
                        root,
                        "[generated." + scope + "." + step.id() + "].inputs",
                        input))
                .distinct()
                .sorted()
                .forEach(path -> content
                        .append(GeneratedSourceHashes.relative(root, path))
                        .append('|')
                        .append(GeneratedSourceHashes.fileHash(path))
                        .append('\n'));
        return GeneratedSourceHashes.sha256(
                content.toString().getBytes(StandardCharsets.UTF_8));
    }
}

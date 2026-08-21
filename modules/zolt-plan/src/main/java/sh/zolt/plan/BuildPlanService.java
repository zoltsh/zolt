package sh.zolt.plan;

import sh.zolt.generated.GeneratedSourceEvidence;
import sh.zolt.generated.GeneratedSourceEvidenceService;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ResourceFilteringSettings;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class BuildPlanService {
    private final GeneratedSourceEvidenceService generatedSourceEvidenceService;
    private final BuildPlanGeneratedSourceNodePlanner generatedSourceNodePlanner;
    private final BuildPlanExecStepNodePlanner execStepNodePlanner = new BuildPlanExecStepNodePlanner();
    private final SpringBootNativePlanNodePlanner nativePlanNodePlanner;

    public BuildPlanService() {
        this(
                new GeneratedSourceEvidenceService(),
                new BuildPlanGeneratedSourceNodePlanner(),
                new SpringBootNativePlanNodePlanner());
    }

    BuildPlanService(GeneratedSourceEvidenceService generatedSourceEvidenceService) {
        this(
                generatedSourceEvidenceService,
                new BuildPlanGeneratedSourceNodePlanner(),
                new SpringBootNativePlanNodePlanner());
    }

    BuildPlanService(
            GeneratedSourceEvidenceService generatedSourceEvidenceService,
            BuildPlanGeneratedSourceNodePlanner generatedSourceNodePlanner,
            SpringBootNativePlanNodePlanner nativePlanNodePlanner) {
        this.generatedSourceEvidenceService = generatedSourceEvidenceService;
        this.generatedSourceNodePlanner = generatedSourceNodePlanner;
        this.nativePlanNodePlanner = nativePlanNodePlanner;
    }

    public BuildPlan plan(Path projectRoot, ProjectConfig config, PlanTarget target, Optional<Path> reportsDir) {
        return plan(projectRoot, config, target, reportsDir, Optional.empty(), Optional.empty());
    }

    public BuildPlan plan(
            Path projectRoot,
            ProjectConfig config,
            PlanTarget target,
            Optional<Path> reportsDir,
            Optional<Path> nativeImageExecutable) {
        return plan(projectRoot, config, target, reportsDir, nativeImageExecutable, Optional.empty());
    }

    public BuildPlan plan(
            Path projectRoot,
            ProjectConfig config,
            PlanTarget target,
            Optional<Path> reportsDir,
            Optional<Path> nativeImageExecutable,
            Optional<TestRuntimePlan> testRuntime) {
        Path root = projectRoot.toAbsolutePath().normalize();
        List<PlanNode> nodes = new ArrayList<>();
        List<GeneratedSourceEvidence> generatedSources =
                generatedSourceEvidenceService.evidence(root, config.build());
        BuildPlanLockfileState lockfile = BuildPlanLockfileState.read(root.resolve("zolt.lock"));
        addLockfileNode(nodes, lockfile);
        if (lockfile.error().isPresent()) {
            return new BuildPlan(1, root, config.project().name(), target, nodes);
        }
        Set<String> lockedExecToolGroups = lockfile.execToolGroups();
        addBuildNodes(nodes, root, config, generatedSources, lockedExecToolGroups);
        if (target.includesTests()) {
            addTestNodes(nodes, root, config, reportsDir, generatedSources, lockedExecToolGroups, testRuntime);
        }
        if (target.includesCoverage()) {
            addCoverageNode(nodes, config.build());
        }
        if (target.includesPackage()) {
            addPackageNode(nodes, config);
        }
        if (target == PlanTarget.NATIVE) {
            nodes.addAll(nativePlanNodePlanner.nodes(root, config, nativeImageExecutable));
        }
        if (target.includesPublish()) {
            addPublishNode(nodes, config);
        }
        return new BuildPlan(1, root, config.project().name(), target, nodes);
    }

    private static void addLockfileNode(List<PlanNode> nodes, BuildPlanLockfileState lockfile) {
        if (lockfile.error().isPresent()) {
            nodes.add(new PlanNode(
                    "lockfile",
                    "resolve",
                    PlanNodeStatus.BLOCKED,
                    "Existing zolt.lock is not readable by this Zolt version.",
                    List.of("zolt.toml", "zolt.lock"),
                    List.of(),
                    List.of(),
                    List.of(new PlanBlocker(
                            "invalid-lockfile",
                            lockfile.error().orElseThrow(),
                            "Run `zolt resolve` to regenerate zolt.lock, then rerun `zolt plan`."))));
            return;
        }
        if (lockfile.present()) {
            nodes.add(new PlanNode(
                    "lockfile",
                    "resolve",
                    PlanNodeStatus.READY,
                    "Read existing zolt.lock without refreshing dependency metadata.",
                    List.of("zolt.toml", "zolt.lock"),
                    List.of(),
                    List.of("freshness: verify with `zolt resolve --locked` or `zolt check --check lockfile`"),
                    List.of()));
            return;
        }
        nodes.add(new PlanNode(
                "lockfile",
                "resolve",
                PlanNodeStatus.BLOCKED,
                "Dependency graph is not locked yet.",
                List.of("zolt.toml"),
                List.of("zolt.lock"),
                List.of(),
                List.of(new PlanBlocker(
                        "missing-lockfile",
                        "zolt.lock is missing; plan will not resolve or download artifacts.",
                        "Run `zolt resolve` first, then rerun `zolt plan`."))));
    }

    private void addBuildNodes(
            List<PlanNode> nodes,
            Path root,
            ProjectConfig config,
            List<GeneratedSourceEvidence> generatedSources,
            Set<String> lockedExecToolGroups) {
        BuildSettings build = config.build();
        nodes.addAll(generatedSourceNodePlanner.nodes(root, generatedSources, "main"));
        nodes.addAll(execStepNodePlanner.nodes(root, generatedSources, "main", outputRoot(build), lockedExecToolGroups));
        addResourceNode(
                nodes,
                "process-main-resources",
                "resources",
                build.resourceRoots(),
                build.output(),
                build.resourceFiltering(),
                false);
        nodes.add(new PlanNode(
                "compile-main",
                "compile",
                PlanNodeStatus.READY,
                "Compile main Java sources with Zolt-owned javac inputs.",
                mainCompileInputs(build),
                List.of(build.output()),
                List.of("sources: " + build.sourceRoots()),
                List.of()));
    }

    private void addTestNodes(
            List<PlanNode> nodes,
            Path root,
            ProjectConfig config,
            Optional<Path> reportsDir,
            List<GeneratedSourceEvidence> generatedSources,
            Set<String> lockedExecToolGroups,
            Optional<TestRuntimePlan> testRuntime) {
        BuildSettings build = config.build();
        nodes.addAll(generatedSourceNodePlanner.nodes(root, generatedSources, "test"));
        nodes.addAll(execStepNodePlanner.nodes(root, generatedSources, "test", outputRoot(build), lockedExecToolGroups));
        addResourceNode(
                nodes,
                "process-test-resources",
                "test-resources",
                build.testResourceRoots(),
                build.testOutput(),
                build.resourceFiltering(),
                true);
        List<String> testCompileInputs = new ArrayList<>();
        testCompileInputs.add(build.output());
        testCompileInputs.addAll(build.testSources());
        testCompileInputs.addAll(build.groovyTestSources());
        for (GeneratedSourceStep step : build.generatedTestSources()) {
            if (joinsCompileSources(step)) {
                testCompileInputs.add(step.output());
            }
        }
        nodes.add(new PlanNode(
                "compile-tests",
                "compile",
                PlanNodeStatus.READY,
                "Compile Java and configured Groovy test sources.",
                testCompileInputs,
                List.of(build.testOutput()),
                testCompileDetails(build),
                List.of()));
        nodes.add(BuildPlanRunTestsNode.node(build, reportsDir, testRuntime));
    }

    private static void addResourceNode(
            List<PlanNode> nodes,
            String id,
            String kind,
            List<String> roots,
            String output,
            ResourceFilteringSettings filtering,
            boolean testResources) {
        List<String> details = new ArrayList<>();
        boolean filteringEnabled = testResources ? filtering.testEnabled() : filtering.enabled();
        details.add("filtering: " + (filteringEnabled ? "enabled" : "disabled"));
        if (filteringEnabled) {
            details.add("filterIncludes: " + filtering.includes());
            details.add("missingTokens: " + filtering.missing().configValue());
            details.add("tokens: " + filtering.tokens().keySet());
        }
        nodes.add(new PlanNode(
                id,
                kind,
                roots.isEmpty() ? PlanNodeStatus.SKIPPED : PlanNodeStatus.READY,
                testResources ? "Copy configured test resources." : "Copy configured main resources.",
                roots,
                List.of(output),
                details,
                List.of()));
    }

    private static void addCoverageNode(List<PlanNode> nodes, BuildSettings build) {
        nodes.add(new PlanNode(
                "coverage",
                "coverage",
                PlanNodeStatus.PLANNED,
                "Coverage runs only through the explicit zolt coverage command, not hidden work after tests.",
                List.of(build.testOutput(), "zolt.lock"),
                List.of(outputRoot(build) + "/coverage"),
                List.of("command: zolt coverage"),
                List.of()));
    }

    private static void addPackageNode(List<PlanNode> nodes, ProjectConfig config) {
        PackageMode mode = config.packageSettings().mode();
        List<PlanBlocker> blockers = new ArrayList<>();
        if ((mode == PackageMode.SPRING_BOOT || mode == PackageMode.SPRING_BOOT_WAR)
                && config.project().main().isEmpty()) {
            blockers.add(new PlanBlocker(
                    "missing-main-class",
                    "Spring Boot package modes require [project].main.",
                    "Add [project].main to zolt.toml."));
        }
        nodes.add(new PlanNode(
                "assemble-package",
                "package",
                blockers.isEmpty() ? PlanNodeStatus.READY : PlanNodeStatus.BLOCKED,
                "Assemble the configured Zolt package artifact.",
                List.of(config.build().output(), "zolt.lock"),
                packageOutputs(config),
                List.of("mode: " + mode.configValue()),
                blockers));
    }

    private static void addPublishNode(List<PlanNode> nodes, ProjectConfig config) {
        List<String> inputs = new ArrayList<>(packageOutputs(config));
        inputs.add("zolt.lock");
        nodes.add(new PlanNode(
                "publish-dry-run",
                "publish",
                PlanNodeStatus.PLANNED,
                "Publication dry run is planned as explicit Zolt behavior.",
                inputs,
                List.of(outputRoot(config.build()) + "/publish"),
                List.of("mode: dry-run"),
                List.of()));
    }

    private static boolean joinsCompileSources(GeneratedSourceStep step) {
        return step.kind() != GeneratedSourceKind.EXEC || step.exec().produces() != sh.zolt.project.ProducesLane.RESOURCES;
    }

    private static List<String> mainCompileInputs(BuildSettings build) {
        List<String> inputs = new ArrayList<>();
        inputs.addAll(build.sourceRoots());
        for (GeneratedSourceStep step : build.generatedMainSources()) {
            if (joinsCompileSources(step)) {
                inputs.add(step.output());
            }
        }
        return List.copyOf(inputs);
    }

    private static List<String> testCompileDetails(BuildSettings build) {
        List<String> details = new ArrayList<>();
        if (!build.testSources().isEmpty()) {
            details.add("javaTestRoots: " + build.testSources());
        }
        if (!build.groovyTestSources().isEmpty()) {
            details.add("groovyTestRoots: " + build.groovyTestSources());
        }
        return List.copyOf(details);
    }

    private static List<String> packageOutputs(ProjectConfig config) {
        String extension = switch (config.packageSettings().mode()) {
            case WAR, SPRING_BOOT_WAR -> ".war";
            case BOM -> ".pom";
            default -> ".jar";
        };
        String base = outputRoot(config.build()) + "/" + config.project().name() + "-" + config.project().version();
        List<String> outputs = new ArrayList<>();
        outputs.add(base + extension);
        if (config.packageSettings().sources()) {
            outputs.add(base + "-sources.jar");
        }
        if (config.packageSettings().javadoc()) {
            outputs.add(base + "-javadoc.jar");
        }
        if (config.packageSettings().tests()) {
            outputs.add(base + "-tests.jar");
        }
        return List.copyOf(outputs);
    }

    private static String outputRoot(BuildSettings build) {
        String outputRoot = build.outputRoot();
        return outputRoot == null || outputRoot.isBlank() ? "target" : outputRoot;
    }
}

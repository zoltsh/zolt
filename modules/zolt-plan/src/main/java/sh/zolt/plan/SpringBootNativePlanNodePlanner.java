package sh.zolt.plan;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.project.NativeSettings;
import sh.zolt.project.ProjectConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class SpringBootNativePlanNodePlanner {
    private final SpringBootNativePlanStateFactory stateFactory;

    SpringBootNativePlanNodePlanner() {
        this(new SpringBootNativePlanStateFactory());
    }

    SpringBootNativePlanNodePlanner(SpringBootNativePlanStateFactory stateFactory) {
        this.stateFactory = stateFactory;
    }

    List<PlanNode> nodes(Path root, ProjectConfig config, Optional<Path> nativeImageExecutable) {
        SpringBootNativePlanState state = stateFactory.state(root, config, nativeImageExecutable);
        List<PlanNode> nodes = new ArrayList<>();
        nodes.add(nativeIntent(config, state));
        nodes.add(supportBoundary(config));
        nodes.add(springAotTooling(config, state));
        nodes.add(springAotOutput(config, state));
        nodes.add(nativePackageInput(config, state));
        nodes.add(nativeImageExecutable(state));
        nodes.add(nativeImageInvocation(config, state));
        return List.copyOf(nodes);
    }

    private static PlanNode nativeIntent(ProjectConfig config, SpringBootNativePlanState state) {
        boolean springBootProject = SpringBootNativeProjectDetector.springBootProject(config);
        boolean nativeEnabled = config.frameworkSettings().springBoot().nativeEnabled();
        List<String> details = new ArrayList<>();
        details.add("framework.springBoot.native: " + (nativeEnabled ? "enabled" : "disabled"));
        details.add("springBootProject: " + springBootProject);
        details.add("springBootVersion: " + state.springBootVersion().orElse("unknown"));
        details.add("java: " + config.project().java());
        details.add("supportedBaseline: Spring Boot 3.3 on Java 21");
        List<PlanBlocker> blockers = new ArrayList<>();
        if (springBootProject && !nativeEnabled) {
            blockers.add(new PlanBlocker(
                    "spring-boot-native-disabled",
                    "Spring Boot native images require `[framework.springBoot.native] enabled = true`.",
                    "Set `[package].mode = \"spring-boot\"`, run `zolt resolve`, then use `zolt package` or `zolt run` for JVM apps, or enable the typed Spring Boot native path."));
        }
        if (nativeEnabled && !"21".equals(config.project().java())) {
            blockers.add(new PlanBlocker(
                    "unsupported-java-baseline",
                    "Spring Boot native support is currently proven for Java 21 projects.",
                    "Set [project].java to 21 or keep this project on the JVM Spring Boot path until another baseline is planned and proven."));
        }
        state.springBootVersion().ifPresent(version -> {
            if (nativeEnabled && !version.startsWith("3.3.")) {
                blockers.add(new PlanBlocker(
                        "unsupported-spring-boot-baseline",
                        "Spring Boot native support is currently proven for Spring Boot 3.3 fixtures.",
                        "Use Spring Boot 3.3 for the current native path or add executable smoke evidence for this baseline before claiming support."));
            }
        });
        return new PlanNode(
                "spring-boot-native-intent",
                "native-intent",
                blockers.isEmpty() ? PlanNodeStatus.READY : PlanNodeStatus.BLOCKED,
                "Inspect Spring Boot native intent and proven baseline without running AOT or Native Image.",
                List.of("zolt.toml"),
                List.of(),
                details,
                blockers);
    }

    private static PlanNode supportBoundary(ProjectConfig config) {
        List<String> details = List.of(
                "configuredPackageMode: " + config.packageSettings().mode().configValue(),
                "nativePackageMode: thin jar input for Native Image");
        List<PlanBlocker> blockers = new ArrayList<>();
        if (SpringBootNativeProjectDetector.micronautProject(config)) {
            blockers.add(new PlanBlocker(
                    "unsupported-micronaut-native",
                    "Micronaut native images are not supported by Zolt yet.",
                    "Use `zolt build`, `zolt test`, or `zolt package --mode thin` for the current beta path."));
        }
        if (SpringBootNativeProjectDetector.quarkusProject(config)) {
            blockers.add(new PlanBlocker(
                    "unsupported-quarkus-native",
                    "Quarkus native images are not supported by Zolt yet.",
                    "Use `zolt package --mode quarkus` or `zolt run` for the current beta path."));
        }
        return new PlanNode(
                "native-support-boundary",
                "native-support",
                blockers.isEmpty() ? PlanNodeStatus.READY : PlanNodeStatus.BLOCKED,
                "Check package and framework native support boundaries before planning Native Image.",
                List.of("zolt.toml"),
                List.of(),
                details,
                blockers);
    }

    private static PlanNode springAotTooling(ProjectConfig config, SpringBootNativePlanState state) {
        if (!config.frameworkSettings().springBoot().nativeEnabled()) {
            return new PlanNode(
                    "spring-aot-tooling",
                    "native-tooling",
                    PlanNodeStatus.SKIPPED,
                    "Spring Boot AOT tooling is only required when `[framework.springBoot.native] enabled = true`.",
                    List.of("zolt.lock"),
                    List.of(),
                    List.of("framework.springBoot.native: disabled"),
                    List.of());
        }
        List<PlanBlocker> blockers = new ArrayList<>();
        int toolingArtifacts = 0;
        if (!Files.isRegularFile(state.lockfilePath())) {
            blockers.add(new PlanBlocker(
                    "missing-lockfile",
                    "zolt.lock is missing; native plan will not resolve or download Spring AOT tooling.",
                    "Run `zolt resolve` first, then rerun `zolt plan --target native`."));
        } else if (state.lockfileError().isPresent()) {
            blockers.add(new PlanBlocker(
                    "invalid-lockfile",
                    "zolt.lock could not be parsed for Spring AOT tooling: " + state.lockfileError().orElseThrow(),
                    "Run `zolt resolve` to regenerate zolt.lock, then rerun `zolt plan --target native`."));
        } else {
            toolingArtifacts = (int) state.lockfile().orElseThrow().packages().stream()
                    .filter(lockPackage -> lockPackage.scope() == DependencyScope.TOOL_SPRING_AOT)
                    .count();
            if (toolingArtifacts == 0) {
                blockers.add(new PlanBlocker(
                        "missing-spring-aot-tooling",
                        "zolt.lock does not contain any `tool-spring-aot` artifacts.",
                        "Run `zolt resolve` without --offline to seed Spring Boot AOT tooling, then rerun the native plan."));
            }
        }
        return new PlanNode(
                "spring-aot-tooling",
                "native-tooling",
                blockers.isEmpty() ? PlanNodeStatus.READY : PlanNodeStatus.BLOCKED,
                "Read locked Spring Boot AOT tool artifacts without resolving or downloading dependencies.",
                List.of("zolt.lock"),
                List.of(),
                List.of("tool-spring-aot artifacts: " + toolingArtifacts),
                blockers);
    }

    private static PlanNode springAotOutput(ProjectConfig config, SpringBootNativePlanState state) {
        if (!config.frameworkSettings().springBoot().nativeEnabled()) {
            return new PlanNode(
                    "spring-aot-output",
                    "native-aot",
                    PlanNodeStatus.SKIPPED,
                    "Spring Boot AOT output is only required when `[framework.springBoot.native] enabled = true`.",
                    List.of("zolt.toml"),
                    List.of(),
                    List.of("framework.springBoot.native: disabled"),
                    List.of());
        }
        List<PlanBlocker> blockers = new ArrayList<>();
        List<String> details = new ArrayList<>();
        details.add("outputRoot: " + PlanPathDisplay.displayPath(state.projectRoot(), state.aotRoot()));
        details.add("sources: " + PlanPathDisplay.displayPath(state.projectRoot(), state.sources()));
        details.add("classes: " + PlanPathDisplay.displayPath(state.projectRoot(), state.classes()));
        details.add("resources: " + PlanPathDisplay.displayPath(state.projectRoot(), state.resources()));
        details.add("nativeMetadata: " + PlanPathDisplay.displayPath(state.projectRoot(), state.nativeMetadata()));
        details.add("generatedSources: " + state.generatedSources().size());
        details.add("generatedClasses: " + state.generatedClasses().size());
        details.add("reflectionMetadata: " + state.reflectionMetadata().size());
        details.add("reachabilityMetadata: " + state.reachabilityMetadata().size());
        details.add("freshness: " + state.aotFreshness().label());
        if (!Files.isDirectory(state.sources())
                || !Files.isDirectory(state.classes())
                || !Files.isDirectory(state.resources())
                || !Files.isDirectory(state.nativeMetadata())
                || state.generatedSources().isEmpty()
                || state.generatedClasses().isEmpty()
                || state.reflectionMetadata().isEmpty()) {
            blockers.add(new PlanBlocker(
                    "missing-spring-aot-output",
                    "Spring Boot native AOT output is incomplete under "
                            + PlanPathDisplay.displayPath(state.projectRoot(), state.aotRoot())
                            + ".",
                    "Run `zolt build` after enabling `[framework.springBoot.native] enabled = true`, then rerun the native plan."));
        } else if (state.aotFreshness().stale()) {
            blockers.add(new PlanBlocker(
                    "stale-spring-aot-output",
                    "Spring Boot native AOT output is older than the project model or compiled main classes.",
                    "Run `zolt build` to regenerate Spring AOT output, then rerun the native plan."));
        }
        return new PlanNode(
                "spring-aot-output",
                "native-aot",
                blockers.isEmpty() ? PlanNodeStatus.READY : PlanNodeStatus.BLOCKED,
                "Inspect existing Spring Boot AOT output required before Native Image.",
                List.of("zolt.toml", "compiled main classes"),
                List.of(
                        PlanPathDisplay.displayPath(state.projectRoot(), state.sources()),
                        PlanPathDisplay.displayPath(state.projectRoot(), state.classes()),
                        PlanPathDisplay.displayPath(state.projectRoot(), state.resources())),
                details,
                blockers);
    }

    private static PlanNode nativePackageInput(ProjectConfig config, SpringBootNativePlanState state) {
        List<String> details = new ArrayList<>();
        details.add("mainClass: " + config.project().main().orElse("<missing>"));
        details.add("configuredPackageMode: " + config.packageSettings().mode().configValue());
        details.add("nativePackageMode: thin");
        List<PlanBlocker> blockers = new ArrayList<>();
        if (config.project().main().isEmpty()) {
            blockers.add(new PlanBlocker(
                    "missing-main-class",
                    "Native Image requires [project].main.",
                    "Add the application main class to zolt.toml."));
        }
        return new PlanNode(
                "native-package-input",
                "native-package",
                blockers.isEmpty() ? PlanNodeStatus.READY : PlanNodeStatus.BLOCKED,
                "Plan the thin jar input Zolt will build before invoking Native Image.",
                List.of(config.build().output(), "zolt.lock"),
                List.of(PlanPathDisplay.displayPath(state.projectRoot(), state.packageJar())),
                details,
                blockers);
    }

    private static PlanNode nativeImageExecutable(SpringBootNativePlanState state) {
        List<PlanBlocker> blockers = new ArrayList<>();
        if (!state.nativeImageAvailable()) {
            blockers.add(new PlanBlocker(
                    "missing-native-image",
                    "The configured native-image executable is not available: " + state.nativeImageExecutable(),
                    "Install GraalVM Native Image, put native-image on PATH, or pass `--native-image` with an executable path."));
        }
        return new PlanNode(
                "native-image-executable",
                "native-tooling",
                blockers.isEmpty() ? PlanNodeStatus.READY : PlanNodeStatus.BLOCKED,
                "Check Native Image executable availability without launching it.",
                List.of(),
                List.of(),
                List.of("executable: " + state.nativeImageExecutable()),
                blockers);
    }

    private static PlanNode nativeImageInvocation(ProjectConfig config, SpringBootNativePlanState state) {
        NativeSettings nativeSettings = config.nativeSettings().withDefaultImageName(config.project().name());
        List<String> inputs = new ArrayList<>();
        inputs.add(PlanPathDisplay.displayPath(state.projectRoot(), state.packageJar()));
        inputs.add(PlanPathDisplay.displayPath(state.projectRoot(), state.classes()));
        inputs.add(PlanPathDisplay.displayPath(state.projectRoot(), state.resources()));
        inputs.add("runtime classpath: packaged scopes from zolt.lock");
        List<String> details = new ArrayList<>();
        details.add("classpathOrder: package jar, Spring Boot AOT classes, Spring Boot AOT resources, packaged runtime dependencies");
        details.add("nativeArgs: " + nativeSettings.args());
        details.add("mainClass: " + config.project().main().orElse("<missing>"));
        return new PlanNode(
                "native-image",
                "native-image",
                PlanNodeStatus.PLANNED,
                "Show the Native Image invocation shape; the plan never launches native-image.",
                inputs,
                List.of(
                        PlanPathDisplay.displayPath(state.projectRoot(), state.image()),
                        PlanPathDisplay.displayPath(state.projectRoot(), state.nativeImageLog()),
                        PlanPathDisplay.displayPath(state.projectRoot(), state.springAotEvidence())),
                details,
                List.of());
    }

}

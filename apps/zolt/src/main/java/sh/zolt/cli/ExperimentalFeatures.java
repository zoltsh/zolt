package sh.zolt.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import picocli.CommandLine.ParseResult;
import sh.zolt.cli.command.CommandFailures;
import sh.zolt.error.ActionableError;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectPaths;
import sh.zolt.workspace.discovery.ManifestProjectLoader;
import sh.zolt.workspace.discovery.ManifestWorkspaceLoader;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;
import sh.zolt.workspace.service.WorkspaceMemberSelector;
import sh.zolt.workspace.service.WorkspaceSelectionRequest;

/** Keeps preview execution behind an explicit opt-in without changing the internal model or APIs. */
final class ExperimentalFeatures {
    private static final Set<String> PROJECT_COMMANDS = Set.of(
            "resolve", "build", "run", "test", "integration-test", "coverage", "package",
            "run-package", "native", "publish", "check", "plan");
    private static final Set<String> MAIN_COMMANDS = Set.of(
            "build", "run", "package", "run-package", "native");

    private ExperimentalFeatures() {
    }

    static boolean enabled(Map<String, String> environment) {
        return "1".equals(environment.get("ZOLT_EXPERIMENTAL"));
    }

    static void check(ParseResult root, boolean enabled) {
        if (enabled || helpRequested(root) || !root.hasSubcommand()) {
            return;
        }
        ParseResult command = root.subcommand();
        String name = command.commandSpec().name();
        ParseResult leaf = command;
        while (leaf.hasSubcommand()) {
            leaf = leaf.subcommand();
        }
        try {
            if (name.equals("quarkus")) {
                reject("Quarkus augmentation and packaging");
            }
            if (name.equals("init")
                    && "kotlin".equalsIgnoreCase(String.valueOf(value(command, "--language", "java")))) {
                reject("Kotlin/JVM project creation");
            }
            if ("quarkus".equals(String.valueOf(value(leaf, "--mode", "")))) {
                reject("Quarkus packaging");
            }
            if (!PROJECT_COMMANDS.contains(name)) {
                return;
            }
            Path directory = (Path) value(leaf, "--directory", value(leaf, "--cwd", Path.of(".")));
            for (WorkspaceMember project : projects(directory, leaf, name)) {
                checkProject(project.directory(), project.config());
            }
        } catch (IllegalArgumentException | sh.zolt.error.ActionableException
                | sh.zolt.toml.ZoltConfigException | sh.zolt.workspace.WorkspaceConfigException exception) {
            throw CommandFailures.user(leaf.commandSpec(), exception);
        }
    }

    private static boolean helpRequested(ParseResult result) {
        return result.isUsageHelpRequested() || result.isVersionHelpRequested()
                || (result.hasSubcommand() && helpRequested(result.subcommand()));
    }

    private static List<WorkspaceMember> projects(Path directory, ParseResult command, String name) {
        Path root = directory.toAbsolutePath().normalize();
        ManifestWorkspaceLoader loader = new ManifestWorkspaceLoader();
        java.util.Optional<Workspace> discovered = loader.discover(root);
        if (discovered.isEmpty()) {
            if (!Files.isRegularFile(root.resolve("zolt.toml"))) {
                return List.of();
            }
            return List.of(new WorkspaceMember(".", root, new ManifestProjectLoader().load(root)));
        }
        Workspace workspace = discovered.orElseThrow();
        boolean explicitWorkspace = Boolean.TRUE.equals(value(command, "--workspace", false));
        boolean isMember = workspace.members().stream()
                .anyMatch(member -> member.directory().toAbsolutePath().normalize().equals(root));
        if (!explicitWorkspace && !isMember && !workspace.root().equals(root)
                && Files.isRegularFile(root.resolve("zolt.toml"))) {
            return List.of(new WorkspaceMember(".", root, new ManifestProjectLoader().load(root)));
        }
        if (name.equals("resolve")) {
            return workspace.members();
        }
        List<String> members = new ArrayList<>();
        addValues(members, value(command, "--member", List.of()));
        addValues(members, value(command, "--members", List.of()));
        boolean all = Boolean.TRUE.equals(value(command, "--all", false));
        if (!explicitWorkspace && members.isEmpty() && !all) {
            WorkspaceMember current = workspace.members().stream()
                    .filter(member -> member.directory().toAbsolutePath().normalize().equals(root))
                    .findFirst().orElse(null);
            if (current == null) {
                return List.of();
            }
            members.add(current.path());
        }
        WorkspaceSelectionRequest request = new WorkspaceSelectionRequest(all, members);
        WorkspaceMemberSelector selector = new WorkspaceMemberSelector();
        List<String> included = (MAIN_COMMANDS.contains(name)
                ? selector.selectMain(workspace, request)
                : selector.select(workspace, request)).includedMembers();
        return workspace.members().stream().filter(member -> included.contains(member.path())).toList();
    }

    private static void checkProject(Path directory, ProjectConfig config) {
        if (!config.compilerSettings().kotlinVersion().isBlank()
                || !config.compilerSettings().kotlinModule().isBlank()
                || !config.compilerSettings().kotlinTestModule().isBlank()
                || !config.compilerSettings().kotlinPlugins().isEmpty()
                || !config.build().kotlinTestSources().isEmpty()
                || Stream.concat(config.build().generatedMainSources().stream(),
                        config.build().generatedTestSources().stream())
                        .anyMatch(step -> step.kind() == GeneratedSourceKind.KSP
                                || step.language().equals("kotlin"))) {
            reject("Kotlin/JVM compilation, KAPT, and KSP");
        }
        if (config.packageSettings().mode() == PackageMode.QUARKUS
                || config.frameworkSettings().quarkus().enabled()) {
            reject("Quarkus augmentation and packaging");
        }
        if (config.frameworkSettings().springBoot().nativeEnabled()) {
            reject("Spring Boot AOT/native images");
        }
        Path root = directory.toAbsolutePath().normalize();
        Path mainOutput = ProjectPaths.output(root, "main output", config.build().output());
        Path testOutput = ProjectPaths.output(root, "test output", config.build().testOutput());
        Path integrationOutput = ProjectPaths.output(root, "integration output", config.build().integrationTestOutput());
        List<Path> outputs = List.of(mainOutput, testOutput);
        for (String source : config.build().sourceRoots()) {
            checkSources(directory, source, true, outputs);
        }
        for (String source : Stream.concat(config.build().testSources().stream(),
                config.build().groovyTestSources().stream()).toList()) {
            checkSources(directory, source, false, outputs);
        }
        for (String source : config.build().integrationTestSources()) {
            checkSources(directory, source, false, List.of(mainOutput, integrationOutput));
        }
    }

    private static void checkSources(
            Path directory, String configuredRoot, boolean main, List<Path> outputs) {
        Path root = directory.toAbsolutePath().normalize();
        Path source = ProjectPaths.existingRoot(root,
                main ? "[build].sources" : "[test.integration].sources", configuredRoot);
        if (!Files.isDirectory(source)) {
            return;
        }
        try (Stream<Path> files = Files.walk(source)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> outputs.stream().noneMatch(path::startsWith))
                    .filter(path -> {
                        String firstSegment = source.relativize(path).getName(0).toString();
                        return !firstSegment.equals("target") && !firstSegment.equals("build");
                    })
                    .filter(path -> ProjectPaths.isRegularFileInsideProject(root, "source root", path))
                    .map(path -> path.getFileName().toString())
                    .filter(filename -> filename.endsWith(".kt") || (main && filename.endsWith(".groovy")))
                    .findFirst()
                    .ifPresent(filename -> reject(filename.endsWith(".kt")
                            ? "Kotlin/JVM compilation" : "Groovy main compilation"));
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not inspect source root " + source + ".", exception);
        }
    }

    private static Object value(ParseResult command, String option, Object fallback) {
        var spec = command.commandSpec().findOption(option);
        if (spec == null) {
            return fallback;
        }
        Object value = spec.getValue();
        return value == null ? fallback : value;
    }

    private static void addValues(List<String> target, Object value) {
        if (value instanceof Collection<?> values) {
            values.forEach(member -> target.add(member.toString()));
        }
    }

    private static void reject(String feature) {
        throw new sh.zolt.error.ActionableException(ActionableError.of(
                feature + " is experimental and disabled by default in Zolt 1.0.",
                "Set ZOLT_EXPERIMENTAL=1 to opt in to preview features, or use the supported Java build, test, package, and native-image paths."));
    }
}

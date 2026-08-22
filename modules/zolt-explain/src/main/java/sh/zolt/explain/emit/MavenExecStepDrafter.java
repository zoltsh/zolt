package sh.zolt.explain.emit;

import sh.zolt.explain.maven.MavenExecInvocation;
import sh.zolt.explain.maven.MavenPluginInspection;
import sh.zolt.manifest.EnvironmentVariableName;
import sh.zolt.manifest.GeneratedOutputKind;
import sh.zolt.manifest.GeneratedStepSettings;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.ResourceGlob;
import sh.zolt.manifest.authored.AuthoredExecStep;
import sh.zolt.manifest.authored.AuthoredGeneratedPresets;
import sh.zolt.manifest.authored.AuthoredGeneratedSources;
import sh.zolt.manifest.authored.AuthoredGeneratedStep;
import sh.zolt.manifest.authored.AuthoredGeneratedTool;
import sh.zolt.manifest.authored.AuthoredGeneratedTools;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Drafts {@code kind = "exec"} steps from statically extracted exec-shaped Maven plugin invocations.
 *
 * <p>The draft is deliberately incomplete and says so: inputs are a {@code REPLACE_ME} placeholder and
 * the output is a Zolt-convention directory, because a static audit cannot see a tool's real input
 * closure or owned output. {@link MavenExecToolDrafter} chooses each step's tool; this class owns the
 * step itself — its identity, produced lane, output path, and environment. Every downgrade (a project
 * tool cannot regenerate compile-lane sources, a skipped unmappable or node-provisioning invocation)
 * is recorded as a review note rather than emitted as a silently wrong step.
 */
final class MavenExecStepDrafter {
    static final String INPUT_PLACEHOLDER = "REPLACE_ME";

    private MavenExecStepDrafter() {
    }

    static Optional<AuthoredGeneratedSources> draft(
            List<MavenPluginInspection> plugins, List<String> notes) {
        Map<LocalId, AuthoredGeneratedStep> mainSteps = new TreeMap<>();
        Map<LocalId, AuthoredGeneratedStep> testSteps = new TreeMap<>();
        Map<LocalId, AuthoredGeneratedTool> tools = new TreeMap<>();
        Set<String> usedIds = new LinkedHashSet<>();
        for (MavenPluginInspection plugin : plugins) {
            if (plugin.pluginManagement()) {
                continue;
            }
            for (MavenExecInvocation invocation : plugin.execInvocations()) {
                draftInvocation(plugin.coordinate(), invocation, usedIds, mainSteps, testSteps, tools, notes);
            }
        }
        if (mainSteps.isEmpty() && testSteps.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new AuthoredGeneratedSources(
                new AuthoredGeneratedTools(tools),
                AuthoredGeneratedPresets.empty(),
                mainSteps,
                testSteps));
    }

    private static void draftInvocation(
            String coordinate,
            MavenExecInvocation invocation,
            Set<String> usedIds,
            Map<LocalId, AuthoredGeneratedStep> mainSteps,
            Map<LocalId, AuthoredGeneratedStep> testSteps,
            Map<LocalId, AuthoredGeneratedTool> tools,
            List<String> notes) {
        String kind = kind(coordinate);
        if (invocation.goal().startsWith("install-node")) {
            notes.add("Maven plugin `" + coordinate + "` provisions Node via `" + invocation.goal()
                    + "`; no exec step was drafted because Zolt probes Node/npm on PATH. Provision it in CI or via asdf.");
            return;
        }
        if (!invocation.mappable()) {
            notes.add("Maven plugin `" + coordinate + "` invocation `" + describe(invocation)
                    + "` uses a shell or antrun control flow that the argv-array exec surface cannot express;"
                    + " no exec step was drafted. Keep it in [tasks] or CI.");
            return;
        }
        Optional<MavenExecToolDrafter.ToolDraft> tool =
                MavenExecToolDrafter.toolFor(kind, coordinate, invocation, notes);
        if (tool.isEmpty()) {
            return;
        }
        MavenExecToolDrafter.ToolDraft drafted = tool.orElseThrow();
        LocalId id = new LocalId(uniqueId(baseId(invocation, kind), usedIds));
        GeneratedOutputKind produces =
                producedKind(kind, invocation, drafted.projectTool(), id.value(), notes);
        boolean test = produces == GeneratedOutputKind.TEST_SOURCES
                || produces == GeneratedOutputKind.TEST_RESOURCES;
        Optional<AuthoredExecStep> step = step(id, drafted, invocation, produces, coordinate, notes);
        if (step.isEmpty()) {
            return;
        }
        drafted.declaration().ifPresent(declaration -> tools.put(drafted.tool(), declaration));
        (test ? testSteps : mainSteps).put(id, step.orElseThrow());
    }

    private static Optional<AuthoredExecStep> step(
            LocalId id,
            MavenExecToolDrafter.ToolDraft tool,
            MavenExecInvocation invocation,
            GeneratedOutputKind produces,
            String pluginCoordinate,
            List<String> notes) {
        Map<EnvironmentVariableName, String> environment = new TreeMap<>();
        invocation.environmentVariables().forEach((name, value) -> {
            try {
                environment.put(new EnvironmentVariableName(name), value);
            } catch (IllegalArgumentException exception) {
                notes.add("Maven plugin `" + pluginCoordinate + "` sets environment variable `" + name
                        + "`, which is not a portable environment-variable name; add it by hand.");
            }
        });
        Optional<ManifestRelativePath> cwd;
        try {
            cwd = invocation.workingDirectory().map(ManifestRelativePath::new);
        } catch (IllegalArgumentException exception) {
            notes.add("Maven plugin `" + pluginCoordinate + "` runs in working directory `"
                    + invocation.workingDirectory().orElse("") + "`, which is not a project-relative"
                    + " manifest path; no exec step was drafted for `" + id + "`.");
            return Optional.empty();
        }
        return Optional.of(new AuthoredExecStep(
                GeneratedStepSettings.defaultsOmitted(),
                tool.tool(),
                tool.mainClass(),
                invocation.arguments(),
                List.of(new ResourceGlob(INPUT_PLACEHOLDER)),
                new ManifestRelativePath(output(id.value(), produces)),
                produces,
                Optional.empty(),
                Optional.empty(),
                cwd,
                environment,
                Map.of(),
                List.of(),
                Optional.empty()));
    }

    private static GeneratedOutputKind producedKind(
            String kind, MavenExecInvocation invocation, boolean projectTool, String id, List<String> notes) {
        GeneratedOutputKind produces = invocation.phase()
                .flatMap(MavenExecStepDrafter::kindFromPhase)
                .orElseGet(() -> defaultKind(kind, invocation));
        if (projectTool && produces == GeneratedOutputKind.JAVA_SOURCES) {
            notes.add("Exec step `" + id + "` runs exec:java on the project classpath (tool = \"project\"),"
                    + " which Zolt schedules after compile, so it was drafted as resources rather than"
                    + " java-sources. To generate main sources, pin the tool with explicit jvm coordinates.");
            return GeneratedOutputKind.RESOURCES;
        }
        if (projectTool && produces == GeneratedOutputKind.TEST_SOURCES) {
            notes.add("Exec step `" + id + "` runs exec:java on the project classpath (tool = \"project\"),"
                    + " scheduled after compile, so it was drafted as test-resources rather than test-sources.");
            return GeneratedOutputKind.TEST_RESOURCES;
        }
        return produces;
    }

    private static GeneratedOutputKind defaultKind(String kind, MavenExecInvocation invocation) {
        if ("exec".equals(kind) && invocation.mainClass().isPresent()) {
            return GeneratedOutputKind.JAVA_SOURCES;
        }
        if (invocation.arguments().stream().anyMatch(argument -> argument.equals("install") || argument.equals("ci"))) {
            return GeneratedOutputKind.INTERMEDIATE;
        }
        return GeneratedOutputKind.RESOURCES;
    }

    private static Optional<GeneratedOutputKind> kindFromPhase(String phase) {
        return switch (phase) {
            case "generate-sources", "process-sources", "generate" ->
                    Optional.of(GeneratedOutputKind.JAVA_SOURCES);
            case "generate-test-sources", "process-test-sources" ->
                    Optional.of(GeneratedOutputKind.TEST_SOURCES);
            case "generate-test-resources", "process-test-resources" ->
                    Optional.of(GeneratedOutputKind.TEST_RESOURCES);
            case "generate-resources", "process-resources", "prepare-package", "package" ->
                    Optional.of(GeneratedOutputKind.RESOURCES);
            default -> Optional.empty();
        };
    }

    /** Output paths are relative to {@code [build.output].root}, which the draft leaves at {@code target}. */
    private static String output(String id, GeneratedOutputKind produces) {
        return switch (produces) {
            case JAVA_SOURCES, TEST_SOURCES -> "generated/sources/" + id;
            case RESOURCES, TEST_RESOURCES -> "generated/resources/" + id;
            case INTERMEDIATE -> "generated/" + id;
        };
    }

    private static String kind(String coordinate) {
        String lower = coordinate.toLowerCase(Locale.ROOT);
        if (lower.contains(":exec-maven-plugin")) {
            return "exec";
        }
        if (lower.contains(":frontend-maven-plugin")) {
            return "frontend";
        }
        return "antrun";
    }

    private static String describe(MavenExecInvocation invocation) {
        return invocation.mainClass()
                .or(invocation::executable)
                .orElse(invocation.goal());
    }

    private static String baseId(MavenExecInvocation invocation, String kind) {
        String raw = invocation.executionId();
        if (raw.isBlank()) {
            raw = invocation.goal();
        }
        if (raw.isBlank()) {
            raw = kind;
        }
        String id = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^[^a-z]+|-+$", "");
        return id.isBlank() ? "exec" : id;
    }

    private static String uniqueId(String base, Set<String> usedIds) {
        String candidate = base;
        int suffix = 2;
        while (!usedIds.add(candidate)) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }
}

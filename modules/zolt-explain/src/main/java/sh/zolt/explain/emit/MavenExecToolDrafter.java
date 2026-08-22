package sh.zolt.explain.emit;

import sh.zolt.explain.maven.MavenExecInvocation;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.DependencySelector;
import sh.zolt.manifest.GeneratedArtifactRequest;
import sh.zolt.manifest.GeneratedProcessBinary;
import sh.zolt.manifest.JavaBinaryClassName;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.authored.AuthoredGeneratedTool;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Chooses the {@code [generated.tools.<id>]} declaration behind one drafted exec step.
 *
 * <p>exec:java maps to the {@code project} pseudo-tool, or to a jvm exec tool when
 * {@code <executableDependencies>} pin coordinates; exec:exec, frontend, and antrun map to a
 * {@code process} tool that probes PATH. Every downgrade — an unreadable main class, a coordinate the
 * audit could not parse, an executable that is not a bare name — is recorded as a review note rather
 * than emitted as a silently wrong tool.
 */
final class MavenExecToolDrafter {
    private static final String TOOL_VERSION_PLACEHOLDER = "0.0.0";
    private static final LocalId PROJECT_TOOL = new LocalId("project");

    private MavenExecToolDrafter() {
    }

    /**
     * One drafted tool reference: the {@code [generated.tools.<id>]} declaration to emit (absent for
     * the {@code project} pseudo-tool, which is never declared) plus the step's own tool reference.
     */
    record ToolDraft(
            LocalId tool,
            Optional<AuthoredGeneratedTool> declaration,
            Optional<JavaBinaryClassName> mainClass,
            boolean projectTool) {
    }

    static Optional<ToolDraft> toolFor(
            String kind, String coordinate, MavenExecInvocation invocation, List<String> notes) {
        if ("exec".equals(kind) && invocation.mainClass().isPresent()) {
            return jvmOrProjectTool(coordinate, invocation, notes);
        }
        if (invocation.executable().isPresent()) {
            String executable = invocation.executable().orElseThrow();
            GeneratedProcessBinary binary = processBinary(executable, coordinate, notes);
            if (binary == null) {
                return Optional.empty();
            }
            LocalId toolName = new LocalId(binaryToolName(executable));
            AuthoredGeneratedTool tool = new AuthoredGeneratedTool.Process(
                    binary,
                    List.of(binary.value(), "--version"),
                    Optional.empty(),
                    true);
            return Optional.of(new ToolDraft(toolName, Optional.of(tool), Optional.empty(), false));
        }
        notes.add("Maven plugin `" + coordinate + "` declared an exec invocation without a main class or"
                + " executable Zolt could read statically; no exec step was drafted.");
        return Optional.empty();
    }

    /**
     * A Zolt process tool names a bare executable resolved from the curated process path, so an
     * absolute or shell-quoted Maven {@code <executable>} is reduced to its bare name and reported.
     */
    private static GeneratedProcessBinary processBinary(
            String executable, String coordinate, List<String> notes) {
        try {
            return new GeneratedProcessBinary(executable);
        } catch (IllegalArgumentException exception) {
            String bare = binaryToolName(executable);
            try {
                GeneratedProcessBinary binary = new GeneratedProcessBinary(bare);
                notes.add("Maven plugin `" + coordinate + "` runs `" + executable
                        + "`; Zolt process tools name a bare executable resolved from the curated process"
                        + " path, so the draft uses `" + bare + "`. Confirm it is on PATH in CI.");
                return binary;
            } catch (IllegalArgumentException failure) {
                notes.add("Maven plugin `" + coordinate + "` runs `" + executable
                        + "`, which is not a bare executable name; no exec step was drafted.");
                return null;
            }
        }
    }

    private static Optional<ToolDraft> jvmOrProjectTool(
            String coordinate, MavenExecInvocation invocation, List<String> notes) {
        Optional<JavaBinaryClassName> mainClass = mainClass(invocation, coordinate, notes);
        if (mainClass.isEmpty()) {
            return Optional.empty();
        }
        if (invocation.executableDependencies().isEmpty()) {
            return Optional.of(new ToolDraft(PROJECT_TOOL, Optional.empty(), mainClass, true));
        }
        List<GeneratedArtifactRequest> coordinates = new ArrayList<>();
        for (String dependency : invocation.executableDependencies()) {
            try {
                coordinates.add(new GeneratedArtifactRequest(
                        new DependencyCoordinate(dependency),
                        new DependencySelector.FixedVersion(TOOL_VERSION_PLACEHOLDER)));
            } catch (IllegalArgumentException exception) {
                notes.add("Maven plugin `" + coordinate + "` pins exec tool dependency `" + dependency
                        + "`, which is not an exact `group:artifact` coordinate; add it by hand.");
            }
        }
        if (coordinates.isEmpty()) {
            return Optional.of(new ToolDraft(PROJECT_TOOL, Optional.empty(), mainClass, true));
        }
        notes.add("Maven plugin `" + coordinate + "` pins its exec tool via <executableDependencies>; the"
                + " drafted [generated.tools] coordinates use a placeholder " + TOOL_VERSION_PLACEHOLDER
                + " version because the audit could not read one. Set the real versions before resolving.");
        LocalId toolName = new LocalId(
                binaryToolName(coordinate.substring(coordinate.indexOf(':') + 1)));
        return Optional.of(new ToolDraft(
                toolName,
                Optional.of(new AuthoredGeneratedTool.Jvm(coordinates, mainClass.orElseThrow())),
                Optional.empty(),
                false));
    }

    private static Optional<JavaBinaryClassName> mainClass(
            MavenExecInvocation invocation, String coordinate, List<String> notes) {
        String value = invocation.mainClass().orElseThrow();
        try {
            return Optional.of(new JavaBinaryClassName(value));
        } catch (IllegalArgumentException exception) {
            notes.add("Maven plugin `" + coordinate + "` runs main class `" + value
                    + "`, which is not a fully qualified Java binary name; no exec step was drafted.");
            return Optional.empty();
        }
    }

    private static String binaryToolName(String executable) {
        String base = executable;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        int dot = base.indexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        base = base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^[^a-z]+|-+$", "");
        return base.isBlank() ? "tool" : base;
    }
}

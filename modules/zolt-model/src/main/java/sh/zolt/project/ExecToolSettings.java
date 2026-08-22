package sh.zolt.project;

import java.util.List;
import java.util.Optional;

/**
 * A named exec tool declared under {@code [generated.tools.<id>]}, plus the built-in
 * {@code project} pseudo-tool. Three runners are supported:
 *
 * <ul>
 *   <li>{@code jvm}: {@code coordinates} resolve into the tool-exec scope (locked, never on app
 *       classpaths) and {@code mainClass} is launched with {@code <managed-java> -cp <locked jars>}.
 *   <li>{@code process}: a {@code binary} discovered on the curated PATH is launched directly.
 *       {@code versionCommand} is probed and its stdout enters the step fingerprint as tool identity;
 *       {@code versionExpect} is an optional semver-range guard; {@code allowUnpinnedTool} must be
 *       {@code true} to acknowledge that PATH bytes are unprovable.
 *   <li>{@code project}: the built-in pseudo-tool (referenced as {@code tool = "project"}, never
 *       declared) that launches {@code mainClass} on the member's own compiled classes plus resolved
 *       runtime classpath (Maven {@code exec:java} parity).
 * </ul>
 */
public record ExecToolSettings(
        String runner,
        List<ExecToolCoordinate> coordinates,
        String mainClass,
        String binary,
        List<String> versionCommand,
        Optional<String> versionExpect,
        boolean allowUnpinnedTool) {
    public ExecToolSettings {
        runner = runner == null ? "" : runner;
        coordinates = coordinates == null ? List.of() : List.copyOf(coordinates);
        mainClass = mainClass == null ? "" : mainClass;
        binary = binary == null ? "" : binary;
        versionCommand = versionCommand == null ? List.of() : List.copyOf(versionCommand);
        versionExpect = versionExpect == null ? Optional.empty() : versionExpect;
    }

    /** Backwards-compatible constructor for the {@code jvm} runner. */
    public ExecToolSettings(String runner, List<ExecToolCoordinate> coordinates, String mainClass) {
        this(runner, coordinates, mainClass, "", List.of(), Optional.empty(), false);
    }

    public static ExecToolSettings empty() {
        return new ExecToolSettings("", List.of(), "", "", List.of(), Optional.empty(), false);
    }

    public static ExecToolSettings process(
            String binary,
            List<String> versionCommand,
            Optional<String> versionExpect,
            boolean allowUnpinnedTool) {
        return new ExecToolSettings("process", List.of(), "", binary, versionCommand, versionExpect, allowUnpinnedTool);
    }

    public static ExecToolSettings project(String mainClass) {
        return new ExecToolSettings("project", List.of(), mainClass, "", List.of(), Optional.empty(), false);
    }
}

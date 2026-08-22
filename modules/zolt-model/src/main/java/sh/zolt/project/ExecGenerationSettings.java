package sh.zolt.project;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The per-step configuration of a {@code kind = "exec"} generated source. {@code tool} is folded in
 * from the referenced {@code [generated.tools.<id>]} table (mirroring how the shared OpenAPI
 * tool is folded into each OpenAPI step), while {@code toolName} retains the reference for identity,
 * plan output, and round-trip writing.
 *
 * <p>{@code cwd} is the project-relative working directory the process runs in (default: the project
 * root). {@code secretEnv} maps a target env name to the source env name it is read from at run time
 * (only names ever appear in config, fingerprints, plans, or logs — never the values). {@code
 * inheritEnv} is the explicit ambient-passthrough allowlist. {@code timeoutSeconds} bounds the run.
 */
public record ExecGenerationSettings(
        String toolName,
        ExecToolSettings tool,
        List<String> args,
        ProducesLane produces,
        Optional<String> into,
        Map<String, String> env,
        String cache,
        Optional<String> cwd,
        Map<String, String> secretEnv,
        List<String> inheritEnv,
        int timeoutSeconds,
        Optional<String> cacheSalt) {
    public static final int DEFAULT_TIMEOUT_SECONDS = 600;

    public ExecGenerationSettings {
        toolName = toolName == null ? "" : toolName;
        tool = tool == null ? ExecToolSettings.empty() : tool;
        args = args == null ? List.of() : List.copyOf(args);
        into = into == null ? Optional.empty() : into;
        env = sortedCopy(env);
        cache = cache == null || cache.isBlank() ? "content" : cache;
        cwd = cwd == null ? Optional.empty() : cwd;
        secretEnv = sortedCopy(secretEnv);
        inheritEnv = inheritEnv == null ? List.of() : List.copyOf(inheritEnv);
        timeoutSeconds = timeoutSeconds <= 0 ? DEFAULT_TIMEOUT_SECONDS : timeoutSeconds;
        cacheSalt = cacheSalt == null ? Optional.empty() : cacheSalt;
    }

    /** Backwards-compatible constructor for steps without cwd/secretEnv/inheritEnv/timeout overrides. */
    public ExecGenerationSettings(
            String toolName,
            ExecToolSettings tool,
            List<String> args,
            ProducesLane produces,
            Optional<String> into,
            Map<String, String> env,
            String cache) {
        this(toolName, tool, args, produces, into, env, cache,
                Optional.empty(), Map.of(), List.of(), DEFAULT_TIMEOUT_SECONDS, Optional.empty());
    }

    public static ExecGenerationSettings empty() {
        return new ExecGenerationSettings("", ExecToolSettings.empty(), List.of(), null, Optional.empty(), Map.of(), "content");
    }

    private static Map<String, String> sortedCopy(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new TreeMap<>(values));
    }
}

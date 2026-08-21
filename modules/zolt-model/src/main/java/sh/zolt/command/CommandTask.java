package sh.zolt.command;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One configured task.
 *
 * <p>{@code owner} is the workspace-relative directory of the manifest that authored the task, empty
 * for a task the discovery root itself authored. A root task resolves {@link #cwd()} from the
 * workspace root and a member task from its member root (design §15.1), so once the root and member
 * namespaces are merged the runner still needs to know which manifest a task came from.
 */
public record CommandTask(
        String name,
        Optional<String> description,
        List<String> cmd,
        Optional<String> cwd,
        Map<String, String> env,
        Optional<String> owner) {
    public CommandTask(
            String name,
            Optional<String> description,
            List<String> cmd,
            Optional<String> cwd,
            Map<String, String> env) {
        this(name, description, cmd, cwd, env, Optional.empty());
    }

    public CommandTask {
        description = description == null ? Optional.empty() : description;
        cmd = cmd == null || cmd.isEmpty() ? List.of() : List.copyOf(cmd);
        cwd = cwd == null ? Optional.empty() : cwd;
        env = env == null || env.isEmpty() ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(env));
        owner = owner == null ? Optional.empty() : owner.filter(value -> !value.isBlank());
    }
}

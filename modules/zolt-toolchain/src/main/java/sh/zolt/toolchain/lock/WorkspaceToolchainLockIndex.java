package sh.zolt.toolchain.lock;

import sh.zolt.project.toolchain.JavaToolchainRequest;
import sh.zolt.toolchain.platform.HostPlatform;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One parsed Java toolchain-lock view for a workspace command.
 */
public final class WorkspaceToolchainLockIndex {
    private final Map<Key, LockedJavaToolchain> locks;

    public WorkspaceToolchainLockIndex(String lockfileContent) {
        this(new ToolchainLockfileService().readJava(lockfileContent));
    }

    public WorkspaceToolchainLockIndex(Path lockfile) {
        this(new ToolchainLockfileService().readJava(lockfile));
    }

    WorkspaceToolchainLockIndex(List<LockedJavaToolchain> values) {
        LinkedHashMap<Key, LockedJavaToolchain> indexed = new LinkedHashMap<>();
        for (LockedJavaToolchain value : values) {
            indexed.put(
                    new Key(value.request(), value.platform()),
                    value);
        }
        this.locks = Map.copyOf(indexed);
    }

    public Optional<LockedJavaToolchain> find(
            JavaToolchainRequest request,
            HostPlatform platform) {
        return Optional.ofNullable(locks.get(new Key(request, platform)));
    }

    public int parseCount() {
        return 1;
    }

    private record Key(
            JavaToolchainRequest request,
            HostPlatform platform) {
    }
}

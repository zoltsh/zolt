package sh.zolt.toolchain.lock;

import sh.zolt.project.toolchain.JavaDistribution;
import sh.zolt.project.toolchain.JavaFeature;
import sh.zolt.project.toolchain.JavaToolchainRequest;
import sh.zolt.toolchain.platform.HostPlatform;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
                    Key.of(value.request(), value.platform()),
                    value);
        }
        this.locks = Map.copyOf(indexed);
    }

    public Optional<LockedJavaToolchain> find(
            JavaToolchainRequest request,
            HostPlatform platform) {
        return Optional.ofNullable(locks.get(Key.of(request, platform)));
    }

    public int parseCount() {
        return 1;
    }

    private record Key(
            String version,
            Optional<JavaDistribution> distribution,
            Set<JavaFeature> features,
            HostPlatform platform) {
        private Key {
            distribution = distribution == null ? Optional.empty() : distribution;
            features = Set.copyOf(features);
        }

        private static Key of(
                JavaToolchainRequest request,
                HostPlatform platform) {
            return new Key(
                    request.version(),
                    request.distribution(),
                    request.features(),
                    platform);
        }
    }
}

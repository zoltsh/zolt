package sh.zolt.update;

import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Builds {@link OutdatedScope}s by reading a project directory's {@code zolt.toml} and lock. */
public final class OutdatedScopes {
    private final ManifestProjectConfigLoader manifestLoader;
    private final ZoltLockfileReader lockfileReader;

    public OutdatedScopes() {
        this(new ManifestProjectConfigLoader(), new ZoltLockfileReader());
    }

    public OutdatedScopes(ManifestProjectConfigLoader manifestLoader, ZoltLockfileReader lockfileReader) {
        this.manifestLoader = manifestLoader;
        this.lockfileReader = lockfileReader;
    }

    public OutdatedScope fromDirectory(String label, Path directory) {
        ProjectConfig config = manifestLoader.load(directory.resolve("zolt.toml"));
        return new OutdatedScope(label, config, readLockfile(directory.resolve("zolt.lock")));
    }

    public OutdatedScope fromDirectoryWithoutLock(String label, Path directory) {
        ProjectConfig config = manifestLoader.load(directory.resolve("zolt.toml"));
        return new OutdatedScope(label, config, Optional.empty());
    }

    public Optional<ZoltLockfile> readLockfile(Path lockfilePath) {
        if (!Files.isRegularFile(lockfilePath)) {
            return Optional.empty();
        }
        return Optional.of(lockfileReader.read(lockfilePath));
    }
}

package sh.zolt.update;

import sh.zolt.error.ActionableError;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.io.IOException;
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
        return scope(label, directory, readLockfile(directory.resolve("zolt.lock")));
    }

    public OutdatedScope fromDirectoryWithoutLock(String label, Path directory) {
        return scope(label, directory, Optional.empty());
    }

    /** The authored manifest at {@code manifestPath}, parsed with the final parser. */
    public AuthoredManifest manifest(Path manifestPath) {
        return manifestLoader.document(read(manifestPath)).authored();
    }

    public Optional<ZoltLockfile> readLockfile(Path lockfilePath) {
        if (!Files.isRegularFile(lockfilePath)) {
            return Optional.empty();
        }
        return Optional.of(lockfileReader.read(lockfilePath));
    }

    private OutdatedScope scope(String label, Path directory, Optional<ZoltLockfile> lockfile) {
        String source = read(directory.resolve("zolt.toml"));
        return new OutdatedScope(
                label,
                manifestLoader.document(source).authored(),
                manifestLoader.load(source),
                lockfile);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new ZoltConfigException(ActionableError.of(
                    "Could not read zolt.toml at " + path + ".",
                    "Check that the file exists and is readable.",
                    exception));
        }
    }
}

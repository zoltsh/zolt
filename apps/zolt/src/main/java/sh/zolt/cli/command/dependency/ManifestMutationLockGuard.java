package sh.zolt.cli.command.dependency;

import java.nio.file.Path;
import java.util.function.Function;
import sh.zolt.cli.command.ProjectCommandMutationLock;

/** Manifest edits share the owning project's build lease, including standalone projects. */
final class ManifestMutationLockGuard {
    private ManifestMutationLockGuard() {}

    static <T> T withLock(Path projectRoot, Function<Path, T> action) {
        return ProjectCommandMutationLock.withLock(projectRoot, action);
    }
}

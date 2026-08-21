package sh.zolt.plan;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.lockfile.toml.ZoltLockfileReader;

/** One read of the existing lockfile shared by plan readiness and exec-tool planning. */
record BuildPlanLockfileState(
        boolean present,
        Set<String> execToolGroups,
        Optional<String> error) {
    static BuildPlanLockfileState read(Path lockfilePath) {
        if (!Files.isRegularFile(lockfilePath)) {
            return new BuildPlanLockfileState(false, Set.of(), Optional.empty());
        }
        try {
            ZoltLockfile lockfile = new ZoltLockfileReader().read(lockfilePath);
            Set<String> groups = lockfile.packages().stream()
                    .filter(lockPackage -> lockPackage.scope() == DependencyScope.TOOL_EXEC)
                    .flatMap(lockPackage -> lockPackage.toolGroups().stream())
                    .collect(Collectors.toUnmodifiableSet());
            return new BuildPlanLockfileState(true, groups, Optional.empty());
        } catch (LockfileReadException exception) {
            return new BuildPlanLockfileState(true, Set.of(), Optional.of(exception.getMessage()));
        }
    }
}

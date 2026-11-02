package sh.zolt.workspace.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;

import sh.zolt.dependency.ConflictSelectionReason;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockConflict;
import sh.zolt.lockfile.ZoltLockfile;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class WorkspaceCompilerConflictAttributionTest extends WorkspaceLockfileAggregatorTestSupport {
    private static final PackageId SHARED = new PackageId("com.example", "shared");

    @Test
    void keepsMainGroovyAndDifferentKotlinRootConflictsSeparate() throws IOException {
        String groovy = LockConflict.compilerToolGroup(DependencyScope.TOOL_GROOVY, "groovy-4.0.23");
        String kotlin22 = LockConflict.compilerToolGroup(DependencyScope.TOOL_KOTLIN, "kotlin-2.2.0");
        String kotlin2210 = LockConflict.compilerToolGroup(DependencyScope.TOOL_KOTLIN, "kotlin-2.2.10");

        ZoltLockfile aggregated = new WorkspaceLockfileAggregator().aggregate(
                workspace(List.of()),
                List.of(
                        member("apps/api", conflict("1.5.0", Optional.empty(), ConflictSelectionReason.NEWEST_VERSION)),
                        member("apps/worker", conflict("2.0.0", Optional.of(groovy), ConflictSelectionReason.NEWEST_VERSION)),
                        member("modules/core", conflict("3.0.0", Optional.of(kotlin22), ConflictSelectionReason.NEWEST_VERSION)),
                        member("modules/processor", conflict("4.0.0", Optional.of(kotlin2210), ConflictSelectionReason.NEWEST_VERSION))));

        assertEquals(
                List.of("<main>=1.5.0", groovy + "=2.0.0", kotlin22 + "=3.0.0", kotlin2210 + "=4.0.0"),
                aggregated.conflicts().stream()
                        .map(conflict -> conflict.toolGroup().orElse("<main>") + "=" + conflict.selectedVersion())
                        .toList());
    }

    @Test
    void keepsSameCompilerRootSelectionsAndReasonsSeparate() throws IOException {
        String kotlin = LockConflict.compilerToolGroup(DependencyScope.TOOL_KOTLIN, "kotlin-2.2.0");

        ZoltLockfile aggregated = new WorkspaceLockfileAggregator().aggregate(
                workspace(List.of()),
                List.of(
                        member("apps/api", conflict("2.0.0", Optional.of(kotlin), ConflictSelectionReason.NEWEST_VERSION)),
                        member("apps/worker", conflict("3.0.0", Optional.of(kotlin), ConflictSelectionReason.NEWEST_VERSION)),
                        member("modules/core", conflict("3.0.0", Optional.of(kotlin), ConflictSelectionReason.DIRECT_DEPENDENCY))));

        assertEquals(
                List.of(
                        "2.0.0:NEWEST_VERSION",
                        "3.0.0:DIRECT_DEPENDENCY",
                        "3.0.0:NEWEST_VERSION"),
                aggregated.conflicts().stream()
                        .map(conflict -> conflict.selectedVersion() + ":" + conflict.reason())
                        .sorted()
                        .toList());
    }

    private static WorkspaceMemberResolveOutput member(String member, LockConflict conflict) {
        return new WorkspaceMemberResolveOutput(
                member,
                lockfile(List.of(), List.of(conflict), List.of()),
                Set.of());
    }

    private static LockConflict conflict(
            String selected,
            Optional<String> group,
            ConflictSelectionReason reason) {
        return new LockConflict(
                SHARED,
                selected,
                List.of("1.0.0", selected),
                reason,
                group);
    }
}

package sh.zolt.workspace.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockDependencyIndex;
import sh.zolt.lockfile.LockMemberGraphIndex;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;

final class WorkspaceMemberLaneIdentityTest {
    private static final String MEMBER = "apps/api";

    @Test
    void projectedResolutionFactsMoveOnlyTheReachableMemberLaneDigest() {
        WorkspaceMemberLaneClosure baseline = closure(aCompiler("maven-central", true, List.of()));

        for (LockPackage changed : List.of(
                aCompiler("mirror", true, List.of()),
                aCompiler("maven-central", false, List.of()),
                aCompiler("maven-central", true, List.of("kotlin")))) {
            WorkspaceMemberLaneClosure after = closure(changed);

            assertNotEquals(
                    baseline.mainCompile(MEMBER).digest(),
                    after.mainCompile(MEMBER).digest());
            assertEquals(
                    baseline.mainCompile("apps/other").digest(),
                    after.mainCompile("apps/other").digest());
        }
    }

    private static WorkspaceMemberLaneClosure closure(LockPackage lockPackage) {
        List<LockPackage> packages = List.of(lockPackage);
        return new WorkspaceMemberLaneClosure(
                new ZoltLockfile(ZoltLockfile.CURRENT_VERSION, packages, List.of()),
                new LockDependencyIndex(packages),
                new LockMemberGraphIndex(List.of(), packages),
                new EmptyVisibility());
    }

    private static LockPackage aCompiler(
            String source,
            boolean direct,
            List<String> toolGroups) {
        return new LockPackage(
                new PackageId("org.jetbrains.kotlin", "kotlin-compiler-embeddable"),
                "2.2.0",
                source,
                DependencyScope.TOOL_KOTLIN,
                direct,
                Optional.of("org/jetbrains/kotlin/compiler.jar"),
                Optional.empty(),
                Optional.of("a".repeat(64)),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of(MEMBER),
                List.of(),
                List.of(),
                toolGroups);
    }

    private static final class EmptyVisibility implements WorkspaceMemberVisibility {
        @Override
        public Set<String> mainCompile(String memberPath) {
            return Set.of();
        }

        @Override
        public Set<String> mainRuntime(String memberPath) {
            return Set.of();
        }

        @Override
        public Set<String> test(String memberPath) {
            return Set.of();
        }
    }
}

package sh.zolt.workspace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import sh.zolt.doctor.JdkChecker;
import sh.zolt.doctor.JdkStatus;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class WorkspaceToolchainIndexTest {
    @Test
    void resolvesEachCheckerAndRequiredVersionOnce() {
        AtomicInteger calls = new AtomicInteger();
        JdkChecker checker = requiredVersion -> {
            calls.incrementAndGet();
            return new JdkStatus(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(requiredVersion),
                    requiredVersion);
        };
        WorkspaceToolchainIndex index = new WorkspaceToolchainIndex();

        index.checker((workspace, member) -> checker, null, null).detect("21");
        index.checker((workspace, member) -> checker, null, null).detect("21");

        assertEquals(1, calls.get());
        assertEquals(1, index.resolutions());
        assertEquals(1, index.hits());
    }

    @Test
    void sharesDetectionAcrossEquivalentManagedToolchainCheckers() {
        AtomicInteger calls = new AtomicInteger();
        WorkspaceJdkCheckerResolver resolver = new WorkspaceJdkCheckerResolver() {
            @Override
            public JdkChecker forMember(
                    Workspace workspace,
                    WorkspaceMember member) {
                return requiredVersion -> {
                    calls.incrementAndGet();
                    return status(requiredVersion);
                };
            }

            @Override
            public Object cacheKey(
                    Workspace workspace,
                    WorkspaceMember member,
                    JdkChecker checker) {
                return "managed-java-21";
            }
        };
        WorkspaceToolchainIndex index = new WorkspaceToolchainIndex();

        index.checker(resolver, null, null).detect("21");
        index.checker(resolver, null, null).detect("21");

        assertEquals(1, calls.get());
        assertEquals(1, index.resolutions());
        assertEquals(1, index.hits());
    }

    private static JdkStatus status(String requiredVersion) {
        return new JdkStatus(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(requiredVersion),
                requiredVersion);
    }
}

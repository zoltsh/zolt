package sh.zolt.workspace.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.PackageMode;
import sh.zolt.project.PackageSettings;
import sh.zolt.resolve.ResolveException;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;
import sh.zolt.workspace.service.WorkspaceProjectEdge;

final class WorkspaceExplicitSubstitutionTest extends WorkspaceLockfileAggregatorTestSupport {
    private static final PackageId CORE = new PackageId("com.acme", "core");

    @Test
    void listedWorkspaceMemberDoesNotShadowWithoutAnExplicitWorkspaceEdge() throws IOException {
        ZoltLockfile aggregated = aggregate(
                workspace(List.of()),
                "apps/api",
                externalCore());

        List<LockPackage> coreEntries = coreEntries(aggregated);
        assertEquals(1, coreEntries.size());
        assertEquals("central", coreEntries.getFirst().source());
        assertEquals("2.8.7", coreEntries.getFirst().version());
        assertTrue(aggregated.conflicts().isEmpty());
    }

    @Test
    void explicitWorkspaceSubstitutionCoexistsWithReleasedSiblingForAnotherConsumer() throws IOException {
        Workspace workspace = workspace(List.of(new WorkspaceProjectEdge(
                "apps/api",
                "modules/core",
                "compile",
                "com.acme:core")));
        LockPackage released = externalCore();
        ZoltLockfile aggregated = new WorkspaceLockfileAggregator().aggregate(
                workspace,
                List.of(
                        output("apps/api", released),
                        output("apps/worker", released)));

        List<LockPackage> coreEntries = coreEntries(aggregated);
        assertEquals(2, coreEntries.size());
        assertTrue(coreEntries.stream().anyMatch(lockPackage ->
                lockPackage.workspace().equals(Optional.of("modules/core"))
                        && lockPackage.version().equals("0.1.0")
                        && lockPackage.members().equals(List.of("apps/api"))));
        assertTrue(coreEntries.stream().anyMatch(lockPackage ->
                lockPackage.source().equals("central")
                        && lockPackage.version().equals("2.8.7")
                        && lockPackage.members().equals(List.of("apps/worker"))));
    }

    @Test
    void everyNonThinModeIsRejectedDefensivelyDuringAggregation() throws IOException {
        for (PackageMode mode : List.of(
                PackageMode.SPRING_BOOT,
                PackageMode.QUARKUS,
                PackageMode.UBER,
                PackageMode.WAR,
                PackageMode.SPRING_BOOT_WAR,
                PackageMode.BOM)) {
            Workspace base = workspace(List.of(new WorkspaceProjectEdge(
                    "apps/api",
                    "modules/core",
                    "compile",
                    "com.acme:core")));
            List<WorkspaceMember> members = base.members().stream()
                    .map(member -> member.path().equals("modules/core")
                            ? new WorkspaceMember(
                                    member.path(),
                                    member.directory(),
                                    member.config().withPackageSettings(new PackageSettings(mode)))
                            : member)
                    .toList();
            Workspace workspace = new Workspace(
                    base.root(),
                    base.configPath(),
                    base.config(),
                    members,
                    base.edges(),
                    base.buildOrder());

            ResolveException exception = assertThrows(
                    ResolveException.class,
                    () -> aggregate(workspace, "apps/api", externalCore()),
                    mode.toString());
            assertTrue(exception.getMessage().contains("`" + mode.configValue() + "`"));
            assertTrue(exception.getMessage().contains("not a reusable library artifact"));
            assertTrue(exception.getMessage().contains("package mode `thin`"));
        }
    }

    private static ZoltLockfile aggregate(
            Workspace workspace,
            String member,
            LockPackage lockPackage) {
        return new WorkspaceLockfileAggregator().aggregate(
                workspace, List.of(output(member, lockPackage)));
    }

    private static WorkspaceMemberResolveOutput output(
            String member,
            LockPackage lockPackage) {
        return new WorkspaceMemberResolveOutput(
                member,
                lockfile(List.of(lockPackage), List.of(), List.of()),
                Set.of());
    }

    private static LockPackage externalCore() {
        return externalPackage(
                CORE, "2.8.7", true, List.of(), List.of());
    }

    private static List<LockPackage> coreEntries(ZoltLockfile lockfile) {
        return lockfile.packages().stream()
                .filter(lockPackage -> lockPackage.packageId().equals(CORE))
                .toList();
    }
}

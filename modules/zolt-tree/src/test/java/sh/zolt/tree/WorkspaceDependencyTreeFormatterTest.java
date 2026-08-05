package sh.zolt.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockDependencyGraphException;
import sh.zolt.lockfile.LockPolicyEffect;
import sh.zolt.lockfile.ZoltLockfile;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class WorkspaceDependencyTreeFormatterTest extends WorkspaceTreeTestSupport {
    private final WorkspaceDependencyTreeFormatter formatter = new WorkspaceDependencyTreeFormatter();
    private final WorkspaceDependencyJsonFormatter jsonFormatter = new WorkspaceDependencyJsonFormatter();

    @Test
    void rendersOneSectionPerMemberInSortedOrder() {
        String output = formatter.format(WORKSPACE_NAME, MEMBERS, workspaceLockfile());

        assertEquals("""
                demo-workspace
                apps/api
                +- com.example:core:0.1.0
                |  \\- org.example:shared:1.0.0
                |     \\- org.example:extra:2.0.0
                +- org.example:bundle:3.0.0:zip
                \\- org.example:shared:1.0.0
                   \\- org.example:extra:2.0.0

                modules/core
                +- org.example:shared:1.0.0
                \\- org.example:shared:1.0.0
                """, output);
    }

    @Test
    void appendsWorkspacePolicyEffectsAfterTheMemberSections() {
        ZoltLockfile lockfile = new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of(sharedCompile(), extra()),
                List.of(),
                List.of(new LockPolicyEffect(
                        "global-exclusion",
                        new PackageId("commons-logging", "commons-logging"),
                        Optional.of("1.2"),
                        Optional.of("com.example:api:0.1.0"),
                        "[dependencyPolicy].exclude commons-logging:commons-logging")),
                List.of());

        String output = formatter.format(WORKSPACE_NAME, MEMBERS, lockfile);

        assertTrue(output.endsWith("""
                Policy effects
                - global-exclusion commons-logging:commons-logging:1.2 from com.example:api:0.1.0: \
                [dependencyPolicy].exclude commons-logging:commons-logging
                """), output);
    }

    @Test
    void rendersTheSameBytesRegardlessOfMemberOrder() {
        ZoltLockfile lockfile = workspaceLockfile();

        assertEquals(
                formatter.format(WORKSPACE_NAME, List.of("modules/core", "apps/api"), lockfile),
                formatter.format(WORKSPACE_NAME, List.of("apps/api", "modules/core"), lockfile));
    }

    @Test
    void refusesALockWhoseEdgeNamesNoLockedPackage() {
        ZoltLockfile lockfile = new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                List.of(external(
                        "org.example",
                        "shared",
                        "1.0.0",
                        DependencyScope.COMPILE,
                        true,
                        List.of("org.example:missing:9.9.9:jar:compile"),
                        List.of("apps/api"))),
                List.of());

        LockDependencyGraphException text = assertThrows(
                LockDependencyGraphException.class,
                () -> formatter.format(WORKSPACE_NAME, MEMBERS, lockfile));
        LockDependencyGraphException json = assertThrows(
                LockDependencyGraphException.class,
                () -> jsonFormatter.tree(WORKSPACE_NAME, MEMBERS, lockfile));

        assertTrue(text.getMessage().contains("Dangling dependency edge"), text.getMessage());
        assertTrue(text.getMessage().contains("zolt resolve --workspace"), text.getMessage());
        assertTrue(json.getMessage().contains("Dangling dependency edge"), json.getMessage());
    }

    @Test
    void rendersAMemberWithNoDirectDependenciesAsAHeadingOnly() {
        String output = formatter.format(
                WORKSPACE_NAME,
                List.of("apps/api", "modules/core", "modules/empty"),
                workspaceLockfile());

        assertTrue(output.endsWith("modules/empty\n"), output);
    }
}

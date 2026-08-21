package sh.zolt.cli.command.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.ProjectConfig;
import sh.zolt.sbom.LockSbomAssembler;
import sh.zolt.sbom.SbomComponentScope;
import sh.zolt.sbom.SbomModel;
import sh.zolt.sbom.SbomScopeSelection;
import sh.zolt.toml.ZoltTomlParser;
import sh.zolt.workspace.WorkspaceConfig;
import sh.zolt.workspace.publish.WorkspaceMemberSbomLockProjection;
import sh.zolt.workspace.resolve.WorkspaceMemberPolicyResolver;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class WorkspaceMemberSbomScopeProjectionTest {
    private static final String COORDINATE = "jakarta.servlet:jakarta.servlet-api";
    private static final String VERSION = "6.1.0";
    private static final String TOOL_VERSION = "0.1.0-TEST";

    private final WorkspaceMemberSbomLockProjection projection = new WorkspaceMemberSbomLockProjection();
    private final WorkspaceMemberPolicyResolver policyResolver = new WorkspaceMemberPolicyResolver();
    private final LockSbomAssembler assembler = new LockSbomAssembler();

    @Test
    void sameVariantRetainsEachMembersDirectScopeInItsSbom() {
        ProjectConfig core = config("core", "dependencies.provided");
        ProjectConfig worker = config("worker", "dependencies");
        Workspace workspace = workspace(core, worker);
        ZoltLockfile aggregate = new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                List.of(
                        external(DependencyScope.PROVIDED, "modules/core"),
                        external(DependencyScope.COMPILE, "apps/worker")),
                List.of());

        ZoltLockfile coreLock =
                projection.project("modules/core", core, aggregate, workspace, policyResolver);
        ZoltLockfile workerLock =
                projection.project("apps/worker", worker, aggregate, workspace, policyResolver);

        assertEquals(ZoltLockfile.CURRENT_VERSION, coreLock.version());
        assertEquals(1, coreLock.packages().size());
        assertEquals(DependencyScope.PROVIDED, coreLock.packages().getFirst().scope());
        assertEquals(List.of("modules/core"), coreLock.packages().getFirst().members());
        assertEquals(DependencyScope.COMPILE, workerLock.packages().getFirst().scope());
        assertEquals(List.of("apps/worker"), workerLock.packages().getFirst().members());

        SbomModel defaultCore = assembler.assemble(
                core, coreLock, SbomScopeSelection.requiredOnly(), Optional.empty(), TOOL_VERSION);
        SbomModel providedCore = assembler.assemble(
                core,
                coreLock,
                new SbomScopeSelection(true, false, false, false),
                Optional.empty(),
                TOOL_VERSION);
        SbomModel defaultWorker = assembler.assemble(
                worker, workerLock, SbomScopeSelection.requiredOnly(), Optional.empty(), TOOL_VERSION);

        assertTrue(defaultCore.components().isEmpty());
        assertEquals(1, providedCore.components().size());
        assertEquals(SbomComponentScope.OPTIONAL, providedCore.components().getFirst().scope());
        assertEquals(1, defaultWorker.components().size());
        assertEquals(SbomComponentScope.REQUIRED, defaultWorker.components().getFirst().scope());
    }

    private static ProjectConfig config(String name, String section) {
        return new ZoltTomlParser().parse("""
                [project]
                name = "%s"
                version = "1.0.0"
                group = "com.acme"
                java = 21

                [%s]
                "%s" = "%s"
                """.formatted(name, section, COORDINATE, VERSION));
    }

    private static Workspace workspace(ProjectConfig core, ProjectConfig worker) {
        List<String> paths = List.of("modules/core", "apps/worker");
        return new Workspace(
                Path.of("/ws"),
                Path.of("/ws/zolt-workspace.toml"),
                new WorkspaceConfig("acme", paths, List.of(), Map.of(), Map.of()),
                List.of(
                        new WorkspaceMember("modules/core", Path.of("/ws/modules/core"), core),
                        new WorkspaceMember("apps/worker", Path.of("/ws/apps/worker"), worker)));
    }

    private static LockPackage external(DependencyScope scope, String member) {
        String base = "jakarta/servlet/jakarta.servlet-api/" + VERSION
                + "/jakarta.servlet-api-" + VERSION;
        return new LockPackage(
                new PackageId("jakarta.servlet", "jakarta.servlet-api"),
                VERSION,
                "maven-central",
                scope,
                true,
                Optional.of(base + ".jar"),
                Optional.of(base + ".pom"),
                Optional.of("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of(member));
    }
}

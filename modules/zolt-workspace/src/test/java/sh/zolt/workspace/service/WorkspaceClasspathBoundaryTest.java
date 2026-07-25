package sh.zolt.workspace.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.classpath.ClasspathSet;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.workspace.WorkspaceConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspaceClasspathBoundaryTest {
    private final WorkspaceClasspathService service = new WorkspaceClasspathService();

    @TempDir
    private Path tempDir;

    @Test
    void siblingDevelopmentTestAndProvidedDependenciesDoNotCrossTheWorkspaceBoundary() throws IOException {
        Workspace workspace = workspace(
                List.of("apps/api", "modules/core"),
                List.of(new WorkspaceProjectEdge(
                        "apps/api", "modules/core", "compile", "com.acme:core")));
        List<LockPackage> packages = new ArrayList<>();
        packages.add(workspacePackage(
                "com.acme", "core", DependencyScope.COMPILE, "modules/core", "apps/api"));
        packages.add(external("sibling-compile", DependencyScope.COMPILE, "modules/core"));
        packages.add(external("sibling-runtime", DependencyScope.RUNTIME, "modules/core"));
        packages.add(external("sibling-dev", DependencyScope.DEV, "modules/core"));
        packages.add(external("sibling-test", DependencyScope.TEST, "modules/core"));
        packages.add(external("sibling-provided", DependencyScope.PROVIDED, "modules/core"));
        packages.add(external("own-dev", DependencyScope.DEV, "apps/api"));
        packages.add(external("own-test", DependencyScope.TEST, "apps/api"));
        packages.add(external("own-provided", DependencyScope.PROVIDED, "apps/api"));
        materializeJars(packages);

        ClasspathSet classpaths = service.classpathsFor(
                workspace,
                new ZoltLockfile(3, packages, List.of()),
                tempDir.resolve("cache"),
                "apps/api");

        assertTrue(contains(classpaths.runtime().entries(), "sibling-compile"));
        assertTrue(contains(classpaths.runtime().entries(), "sibling-runtime"));
        assertFalse(contains(classpaths.runtime().entries(), "sibling-dev"));
        assertFalse(contains(classpaths.test().entries(), "sibling-test"));
        assertFalse(contains(classpaths.testCompile().entries(), "sibling-provided"));
        assertTrue(contains(classpaths.runtime().entries(), "own-dev"));
        assertTrue(contains(classpaths.test().entries(), "own-test"));
        assertTrue(contains(classpaths.testCompile().entries(), "own-provided"));
    }

    @Test
    void processorMemberContributesRuntimeRequirementsButNotBuildOrTestDependencies() throws IOException {
        Workspace workspace = workspace(
                List.of("apps/api", "modules/processor"),
                List.of(new WorkspaceProjectEdge(
                        "apps/api",
                        "modules/processor",
                        "processor",
                        "com.acme:processor")));
        List<LockPackage> packages = new ArrayList<>();
        packages.add(workspacePackage(
                "com.acme", "processor", DependencyScope.PROCESSOR, "modules/processor", "apps/api"));
        packages.add(external("processor-compile", DependencyScope.COMPILE, "modules/processor"));
        packages.add(external("processor-runtime", DependencyScope.RUNTIME, "modules/processor"));
        packages.add(external("processor-dev", DependencyScope.DEV, "modules/processor"));
        packages.add(external("processor-test", DependencyScope.TEST, "modules/processor"));
        packages.add(external("processor-provided", DependencyScope.PROVIDED, "modules/processor"));
        packages.add(external("processor-build-tool", DependencyScope.PROCESSOR, "modules/processor"));
        materializeJars(packages);

        ClasspathSet classpaths = service.classpathsFor(
                workspace,
                new ZoltLockfile(3, packages, List.of()),
                tempDir.resolve("cache"),
                "apps/api");

        assertTrue(classpaths.processor().entries()
                .contains(tempDir.resolve("modules/processor/target/classes").normalize()));
        assertTrue(contains(classpaths.processor().entries(), "processor-compile"));
        assertTrue(contains(classpaths.processor().entries(), "processor-runtime"));
        assertFalse(contains(classpaths.processor().entries(), "processor-dev"));
        assertFalse(contains(classpaths.processor().entries(), "processor-test"));
        assertFalse(contains(classpaths.processor().entries(), "processor-provided"));
        assertFalse(contains(classpaths.processor().entries(), "processor-build-tool"));
    }

    private LockPackage external(
            String artifact,
            DependencyScope scope,
            String member) {
        String repositoryPath =
                "org/example/" + artifact + "/1.0.0/" + artifact + "-1.0.0.jar";
        return new LockPackage(
                new PackageId("org.example", artifact),
                "1.0.0",
                "maven-central",
                scope,
                true,
                Optional.of(repositoryPath),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of(member),
                List.of(),
                List.of(),
                List.of());
    }

    private static LockPackage workspacePackage(
            String group,
            String artifact,
            DependencyScope scope,
            String workspace,
            String member) {
        return new LockPackage(
                new PackageId(group, artifact),
                "0.1.0",
                "workspace",
                scope,
                true,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(workspace),
                Optional.of("target/classes"),
                List.of(),
                List.of(member),
                List.of(),
                List.of(),
                List.of());
    }

    private void materializeJars(List<LockPackage> packages) throws IOException {
        for (LockPackage lockPackage : packages) {
            if (lockPackage.jar().isEmpty()) {
                continue;
            }
            Path jar = tempDir.resolve("cache").resolve(lockPackage.jar().orElseThrow());
            Files.createDirectories(jar.getParent());
            Files.writeString(jar, "");
        }
    }

    private Workspace workspace(
            List<String> members,
            List<WorkspaceProjectEdge> edges) throws IOException {
        Files.writeString(tempDir.resolve("zolt-workspace.toml"), "");
        for (String member : members) {
            Files.createDirectories(tempDir.resolve(member));
        }
        return new Workspace(
                tempDir,
                tempDir.resolve("zolt-workspace.toml"),
                new WorkspaceConfig("acme-platform", members, List.of(), Map.of(), Map.of()),
                members.stream()
                        .map(member -> new WorkspaceMember(member, tempDir.resolve(member), null))
                        .toList(),
                edges,
                members);
    }

    private static boolean contains(List<Path> paths, String artifact) {
        return paths.stream()
                .map(path -> path.getFileName().toString())
                .anyMatch(fileName -> fileName.equals(artifact + "-1.0.0.jar"));
    }
}
